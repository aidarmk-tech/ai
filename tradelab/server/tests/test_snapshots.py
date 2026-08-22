import gzip
import hashlib
import sqlite3
import time

from tradelab.db import initialize
from tradelab.participants import list_participants, seed
from tradelab.snapshots import create_full_snapshot, create_snapshot, get_snapshot, list_snapshots


def restore(gz, raw):
    raw.write_bytes(gzip.decompress(gz.read_bytes()))
    return raw


def test_snapshot_is_valid_and_hashed(tmp_path):
    db = tmp_path / "tradelab.sqlite3"
    out = tmp_path / "snapshots"
    initialize(db)
    seed(db)
    snap = create_snapshot(db, out, keep=3)
    gz = out / snap.filename
    assert gz.exists()
    assert snap.kind == "analysis"
    assert hashlib.sha256(gz.read_bytes()).hexdigest() == snap.sha256
    raw = restore(gz, tmp_path / "restored.sqlite3")
    with sqlite3.connect(raw) as conn:
        assert conn.execute("PRAGMA quick_check").fetchone()[0] == "ok"
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for required in (
            "market_samples",
            "flow_samples",
            "depth_samples",
            "open_interest_samples",
            "liquidations",
            "paper_trades",
            "participant_specs",
            "recorder_health",
        ):
            assert required in tables
        assert conn.execute("SELECT value FROM meta WHERE key='snapshot_kind'").fetchone()[0] == "analysis"
    participants = list_participants(db)
    assert len(participants) == 4
    assert all(p["active_effect"] == "SHADOW_ONLY" for p in participants)
    assert all(p["role"] == "CANDIDATE" for p in participants)


def test_analysis_snapshot_keeps_recent_raw_and_all_research_events(tmp_path):
    db = tmp_path / "tradelab.sqlite3"
    out = tmp_path / "snapshots"
    initialize(db)
    seed(db)
    now = int(time.time() * 1000)
    old = now - 3 * 3600_000
    recent = now - 5 * 60_000

    with sqlite3.connect(db) as conn:
        conn.execute(
            "INSERT INTO market_samples(ts_ms,symbol,last_price) VALUES(?,?,?)",
            (old, "BTCUSDT", 100.0),
        )
        conn.execute(
            "INSERT INTO market_samples(ts_ms,symbol,last_price) VALUES(?,?,?)",
            (recent, "BTCUSDT", 101.0),
        )
        conn.execute(
            "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
            (old, "BTC_ALT_LAG", "BTCUSDT", "TEST", "{}"),
        )

    snap = create_snapshot(db, out, keep=3, raw_hours=1)
    raw = restore(out / snap.filename, tmp_path / "analysis.sqlite3")
    with sqlite3.connect(raw) as conn:
        samples = conn.execute("SELECT ts_ms,last_price FROM market_samples ORDER BY ts_ms").fetchall()
        assert samples == [(recent, 101.0)]
        assert conn.execute("SELECT COUNT(*) FROM participant_events WHERE event_type='TEST'").fetchone()[0] == 1
        assert conn.execute("SELECT value FROM meta WHERE key='snapshot_raw_hours'").fetchone()[0] == "1"
        assert conn.execute("PRAGMA quick_check").fetchone()[0] == "ok"

    with sqlite3.connect(db) as conn:
        assert conn.execute("SELECT COUNT(*) FROM market_samples").fetchone()[0] == 2


def test_full_snapshot_preserves_all_retained_raw_without_affecting_compact_latest(tmp_path):
    db = tmp_path / "tradelab.sqlite3"
    out = tmp_path / "snapshots"
    initialize(db)
    seed(db)
    now = int(time.time() * 1000)
    old = now - 10 * 3600_000
    recent = now - 60_000
    with sqlite3.connect(db) as conn:
        conn.executemany(
            "INSERT INTO market_samples(ts_ms,symbol,last_price) VALUES(?,?,?)",
            [(old, "BTCUSDT", 100.0), (recent, "BTCUSDT", 101.0)],
        )

    compact = create_snapshot(db, out, keep=3, raw_hours=6)
    full = create_full_snapshot(db, out, keep=1, retained_raw_hours=72)
    assert full.kind == "full"
    raw = restore(out / full.filename, tmp_path / "full.sqlite3")
    with sqlite3.connect(raw) as conn:
        assert conn.execute("SELECT COUNT(*) FROM market_samples").fetchone()[0] == 2
        assert conn.execute("SELECT value FROM meta WHERE key='snapshot_kind'").fetchone()[0] == "full"
        assert conn.execute("SELECT value FROM meta WHERE key='snapshot_raw_hours'").fetchone()[0] == "72"
        assert conn.execute("PRAGMA quick_check").fetchone()[0] == "ok"

    # Normal catch-up APIs are intentionally compact-only.
    assert [s.snapshot_id for s in list_snapshots(db, 0)] == [compact.snapshot_id]
    assert get_snapshot(db, full.snapshot_id) is not None


def test_snapshot_catchup_and_retention(tmp_path):
    db = tmp_path / "tradelab.sqlite3"
    out = tmp_path / "snapshots"
    initialize(db)
    seed(db)

    first = create_snapshot(db, out, keep=2)
    time.sleep(0.002)
    second = create_snapshot(db, out, keep=2)
    time.sleep(0.002)
    full = create_full_snapshot(db, out, keep=1)
    time.sleep(0.002)
    third = create_snapshot(db, out, keep=2)

    retained = list_snapshots(db, 0)
    assert [s.snapshot_id for s in retained] == [second.snapshot_id, third.snapshot_id]
    assert get_snapshot(db, first.snapshot_id) is None
    assert get_snapshot(db, second.snapshot_id) is not None
    assert get_snapshot(db, full.snapshot_id) is not None
    assert [s.snapshot_id for s in list_snapshots(db, second.created_at_ms)] == [third.snapshot_id]
