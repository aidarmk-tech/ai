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
        paper_fee_bps_per_side=5.0,
        paper_slippage_bps_per_side=2.0,
        paper_max_open=2,
        paper_starting_notional_usdt=10.0,
    )


def test_paper_trade_roundtrip_updates_equity(tmp_path):
    cfg = settings(tmp_path)
    initialize(cfg.db_path)
    seed(cfg.db_path)
    r = MarketRecorder(cfg)
    r.tickers["TESTUSDT"] = {"c": "100"}
    signal = Signal("REGIME_MOMENTUM", "TESTUSDT", "LONG", 60, 1.0, {"test": True})
    r._open_paper(signal, 1_000_000, "{}")

    with connect(cfg.db_path) as conn:
        trade = conn.execute("SELECT * FROM paper_trades").fetchone()
        assert trade is not None
        assert trade["status"] == "OPEN"
        due = trade["exit_due_ms"]
        before = conn.execute("SELECT equity FROM participants WHERE participant_id='REGIME_MOMENTUM'").fetchone()[0]

    r.tickers["TESTUSDT"] = {"c": "101"}
    r._close_due_paper(due)
    r._flush_health()

    with connect(cfg.db_path) as conn:
        trade = conn.execute("SELECT * FROM paper_trades").fetchone()
        after = conn.execute("SELECT equity FROM participants WHERE participant_id='REGIME_MOMENTUM'").fetchone()[0]
        health = conn.execute("SELECT status FROM recorder_health WHERE component='paper_book'").fetchone()
    assert trade["status"] == "CLOSED"
    assert 0.8 < trade["net_return_pct"] < 0.9
    assert after > before
    assert health[0] == "OK"


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
