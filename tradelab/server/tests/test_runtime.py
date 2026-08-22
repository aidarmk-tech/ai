from types import SimpleNamespace

from tradelab.db import connect, initialize
from tradelab.market_runtime import StableMarketRecorder
from tradelab.participants import seed
from tradelab.strategies import Signal


def runtime_settings(tmp_path):
    return SimpleNamespace(
        db_path=tmp_path / "tradelab.sqlite3",
        market_enabled=True,
        universe_size=40,
        microstructure_size=12,
        market_sample_seconds=5,
        raw_retention_hours=72,
        subscription_refresh_seconds=300,
        oi_interval_seconds=60,
        max_sample_gap_seconds=12,
        paper_exit_grace_seconds=15,
        label_grace_seconds=10,
        paper_fee_bps_per_side=5.0,
        paper_slippage_bps_per_side=2.0,
        paper_max_open=2,
        paper_starting_notional_usdt=10.0,
    )


def test_empty_ticker_cache_does_not_collapse_bootstrap_universe():
    r = StableMarketRecorder(SimpleNamespace(universe_size=40, microstructure_size=12))
    before = list(r.universe)
    r._refresh_universe_once()
    assert r.universe == before
    assert len(r.universe) == 6
    assert r.universe_generation == 0
    assert r._health_pending["universe"][0] == "WARMING"


def test_full_ticker_cache_transitions_once_to_top40():
    r = StableMarketRecorder(SimpleNamespace(universe_size=40, microstructure_size=12))
    for i in range(45):
        r.tickers[f"T{i:02d}USDT"] = {"q": str(1_000_000 + i), "st": 1}
    r.tickers["BTCUSDT"] = {"q": "999999999", "st": 1}
    r.tickers["ETHUSDT"] = {"q": "888888888", "st": 1}

    r._refresh_universe_once()
    assert len(r.universe) == 40
    assert len(r.micro) == 12
    assert "BTCUSDT" in r.universe
    assert "ETHUSDT" in r.universe
    assert r.universe_generation == 1
    assert r._health_pending["universe"][0] == "OK"

    unchanged = list(r.universe)
    r._refresh_universe_once()
    assert r.universe == unchanged
    assert r.universe_generation == 1


def test_rank_reorder_does_not_force_websocket_generation_change():
    r = StableMarketRecorder(SimpleNamespace(universe_size=40, microstructure_size=12))
    for i in range(45):
        r.tickers[f"T{i:02d}USDT"] = {"q": str(1_000_000 + i * 1000), "st": 1}
    r.tickers["BTCUSDT"] = {"q": "999999999", "st": 1}
    r.tickers["ETHUSDT"] = {"q": "888888888", "st": 1}
    r._refresh_universe_once()
    generation = r.universe_generation
    before = list(r.universe)

    # Change only order well inside the already subscribed top-40 set.
    r.tickers["T44USDT"]["q"] = "2000000"
    r.tickers["T43USDT"]["q"] = "3000000"
    r._refresh_universe_once()
    assert set(r.universe) == set(before)
    assert r.universe != before
    assert r.universe_generation == generation

    # Crossing the membership boundary must still force a resubscribe.
    r.tickers["T00USDT"]["q"] = "777777777"
    r._refresh_universe_once()
    assert r.universe_generation == generation + 1


def test_paper_entry_uses_exact_persisted_market_sample(tmp_path):
    cfg = runtime_settings(tmp_path)
    initialize(cfg.db_path)
    seed(cfg.db_path)
    r = StableMarketRecorder(cfg)
    ts = 1_000_000
    with connect(cfg.db_path) as conn:
        conn.execute(
            "INSERT INTO market_samples(ts_ms,symbol,last_price) VALUES(?,?,?)",
            (ts, "BTCUSDT", 100.0),
        )
    # A different live midpoint must not leak into replay accounting.
    r.books["BTCUSDT"] = {"b": "99", "a": "103", "E": ts}
    r.tickers["BTCUSDT"] = {"c": "102", "E": ts}
    signal = Signal("FLOW_ABSORPTION", "BTCUSDT", "LONG", 60, 1.0, {})
    r._open_paper(signal, ts, "{}")
    with connect(cfg.db_path) as conn:
        trade = conn.execute("SELECT entry_a,status FROM paper_trades").fetchone()
    assert trade["status"] == "OPEN"
    assert trade["entry_a"] == 100.0


def test_pair_exit_requires_one_common_recorded_tick(tmp_path):
    cfg = runtime_settings(tmp_path)
    initialize(cfg.db_path)
    seed(cfg.db_path)
    r = StableMarketRecorder(cfg)
    ts = 1_000_000
    with connect(cfg.db_path) as conn:
        conn.executemany(
            "INSERT INTO market_samples(ts_ms,symbol,last_price) VALUES(?,?,?)",
            [(ts, "BTCUSDT", 100.0), (ts, "ETHUSDT", 50.0)],
        )
    signal = Signal(
        "STAT_ARB",
        "BTCUSDT",
        "LONG",
        60,
        2.5,
        {},
        symbol_b="ETHUSDT",
        side_b="SHORT",
        hedge_ratio=1.0,
    )
    r._open_paper(signal, ts, "{}")
    due = ts + 60_000
    with connect(cfg.db_path) as conn:
        # Separate valid-looking leg samples are insufficient.
        conn.executemany(
            "INSERT INTO market_samples(ts_ms,symbol,last_price) VALUES(?,?,?)",
            [(due, "BTCUSDT", 101.0), (due + 5_000, "ETHUSDT", 49.0)],
        )
    r._close_due_paper(due + cfg.paper_exit_grace_seconds * 1000 + 1)
    with connect(cfg.db_path) as conn:
        trade = conn.execute("SELECT status FROM paper_trades").fetchone()
    assert trade["status"] == "INVALID_GAP"


def test_stale_depth_baseline_produces_zero_replenishment(tmp_path):
    cfg = runtime_settings(tmp_path)
    initialize(cfg.db_path)
    seed(cfg.db_path)
    r = StableMarketRecorder(cfg)
    r.micro = ["BTCUSDT"]
    r.depth_previous["BTCUSDT"] = (1_000_000, 1000.0, 1000.0)
    r.depth_latest["BTCUSDT"] = (1_010_000, 2000.0, 500.0, 0.6, 1.0, 1.0, 1.0)
    r._sample_depth(1_010_000)
    with connect(cfg.db_path) as conn:
        row = conn.execute(
            "SELECT bid_replenishment,ask_replenishment FROM depth_samples WHERE symbol='BTCUSDT'"
        ).fetchone()
    assert row["bid_replenishment"] == 0.0
    assert row["ask_replenishment"] == 0.0
