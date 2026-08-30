from types import SimpleNamespace

from tradelab.db import connect, initialize
from tradelab.market import MarketRecorder
from tradelab.participants import seed
from tradelab.strategies import Signal


def settings(tmp_path):
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


def test_paper_trade_closes_on_due_sample_not_current_price(tmp_path):
    cfg = settings(tmp_path)
    initialize(cfg.db_path)
    seed(cfg.db_path)
    r = MarketRecorder(cfg)
    r.tickers["TESTUSDT"] = {"c": "100"}
    signal = Signal("REGIME_MOMENTUM", "TESTUSDT", "LONG", 60, 1.0, {"test": True})
    r._open_paper(signal, 1_000_000, "{}")

    with connect(cfg.db_path) as conn:
        trade = conn.execute("SELECT * FROM paper_trades").fetchone()
        due = trade["exit_due_ms"]
        before = conn.execute("SELECT equity FROM participants WHERE participant_id='REGIME_MOMENTUM'").fetchone()[0]
        conn.execute(
            "INSERT INTO market_samples(ts_ms,symbol,last_price) VALUES(?,?,?)",
            (due + 5_000, "TESTUSDT", 101.0),
        )

    # Deliberately make the live ticker very different. It must not be used.
    r.tickers["TESTUSDT"] = {"c": "150"}
    r._close_due_paper(due + 6_000)
    r._flush_health()

    with connect(cfg.db_path) as conn:
        trade = conn.execute("SELECT * FROM paper_trades").fetchone()
        after = conn.execute("SELECT equity FROM participants WHERE participant_id='REGIME_MOMENTUM'").fetchone()[0]
        health = conn.execute("SELECT status FROM recorder_health WHERE component='paper_book'").fetchone()
    assert trade["status"] == "CLOSED"
    assert trade["exit_a"] == 101.0
    assert trade["closed_at_ms"] == due + 5_000
    assert 0.8 < trade["net_return_pct"] < 0.9
    assert after > before
    assert health[0] == "OK"


def test_paper_trade_becomes_invalid_when_horizon_has_gap(tmp_path):
    cfg = settings(tmp_path)
    initialize(cfg.db_path)
    seed(cfg.db_path)
    r = MarketRecorder(cfg)
    r.tickers["TESTUSDT"] = {"c": "100"}
    signal = Signal("FLOW_ABSORPTION", "TESTUSDT", "LONG", 60, 1.0, {})
    r._open_paper(signal, 1_000_000, "{}")
    with connect(cfg.db_path) as conn:
        due = conn.execute("SELECT exit_due_ms FROM paper_trades").fetchone()[0]
        before = conn.execute("SELECT equity FROM participants WHERE participant_id='FLOW_ABSORPTION'").fetchone()[0]

    r._close_due_paper(due + cfg.paper_exit_grace_seconds * 1000 + 1)
    with connect(cfg.db_path) as conn:
        trade = conn.execute("SELECT * FROM paper_trades").fetchone()
        after = conn.execute("SELECT equity FROM participants WHERE participant_id='FLOW_ABSORPTION'").fetchone()[0]
        event = conn.execute("SELECT event_type FROM participant_events ORDER BY id DESC LIMIT 1").fetchone()[0]
    assert trade["status"] == "INVALID_GAP"
    assert trade["pnl_usdt"] is None
    assert after == before
    assert event == "PAPER_INVALID_GAP"


def test_forward_label_requires_continuous_300s_window(tmp_path):
    cfg = settings(tmp_path)
    initialize(cfg.db_path)
    seed(cfg.db_path)
    r = MarketRecorder(cfg)
    start = 1_000_000
    with connect(cfg.db_path) as conn:
        conn.execute(
            "INSERT INTO market_states(ts_ms,symbol,source,payload_json) VALUES(?,?,?,?)",
            (start, "BTCUSDT", "FLOW_ABSORPTION", "{}"),
        )
        # Data only reaches 60 seconds: the old implementation incorrectly used
        # the last available value as 120s/300s labels.
        for sec in range(0, 65, 5):
            conn.execute(
                "INSERT INTO market_samples(ts_ms,symbol,last_price) VALUES(?,?,?)",
                (start + sec * 1000, "BTCUSDT", 100 + sec / 1000),
            )
    valid, invalid = r._label_ready_states(start + 400_000)
    assert valid == 0
    assert invalid == 1
    with connect(cfg.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM forward_labels").fetchone()[0] == 0
        q = conn.execute("SELECT valid,reason FROM forward_label_quality").fetchone()
    assert q["valid"] == 0
    assert q["reason"].startswith("MISSING_")


def test_health_is_buffered_until_flush(tmp_path):
    cfg = settings(tmp_path)
    initialize(cfg.db_path)
    seed(cfg.db_path)
    r = MarketRecorder(cfg)
    r._health("test_component", "OK", "buffered", 123)
    with connect(cfg.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM recorder_health WHERE component='test_component'").fetchone()[0] == 0
    r._flush_health()
    with connect(cfg.db_path) as conn:
        row = conn.execute("SELECT status,last_event_ms FROM recorder_health WHERE component='test_component'").fetchone()
    assert row["status"] == "OK"
    assert row["last_event_ms"] == 123
