from tradelab.db import connect, initialize
from tradelab.participants import ensure_clean_research_epoch, seed


def test_clean_epoch_discards_preflight_once_and_resets_equity(tmp_path):
    db = tmp_path / "tradelab.sqlite3"
    initialize(db)
    seed(db)
    with connect(db) as conn:
        conn.execute("UPDATE participants SET equity=21.0, rank=1 WHERE participant_id='FLOW_ABSORPTION'")
        conn.execute(
            "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
            (1, "FLOW_ABSORPTION", "BTCUSDT", "SIGNAL", "{}"),
        )
        conn.execute(
            "INSERT INTO paper_trades(trade_id,participant_id,symbol_a,side_a,opened_at_ms,entry_a,exit_due_ms,notional_usdt,status,signal_json) VALUES(?,?,?,?,?,?,?,?,?,?)",
            ("old", "FLOW_ABSORPTION", "BTCUSDT", "LONG", 1, 100.0, 61_000, 10.0, "CLOSED", "{}"),
        )

    first = ensure_clean_research_epoch(db)
    assert first["created"] is True
    with connect(db) as conn:
        assert conn.execute("SELECT COUNT(*) FROM participant_events").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM paper_trades").fetchone()[0] == 0
        row = conn.execute("SELECT equity,rank FROM participants WHERE participant_id='FLOW_ABSORPTION'").fetchone()
        assert row["equity"] == 20.0
        assert row["rank"] is None

        # New tournament output must survive ordinary restarts.
        conn.execute(
            "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
            (2, "FLOW_ABSORPTION", "ETHUSDT", "SIGNAL", "{}"),
        )

    second = ensure_clean_research_epoch(db)
    assert second["created"] is False
    assert second["epoch_id"] == first["epoch_id"]
    with connect(db) as conn:
        assert conn.execute("SELECT COUNT(*) FROM participant_events").fetchone()[0] == 1
