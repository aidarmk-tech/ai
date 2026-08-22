import gzip
import hashlib
import sqlite3
import time
from tradelab.db import initialize
from tradelab.participants import seed, list_participants
from tradelab.snapshots import create_snapshot, get_snapshot, list_snapshots


def test_snapshot_is_valid_and_hashed(tmp_path):
    db = tmp_path / "tradelab.sqlite3"
    out = tmp_path / "snapshots"
    initialize(db)
    seed(db)
    snap = create_snapshot(db, out, keep=3)
    gz = out / snap.filename
    assert gz.exists()
    assert hashlib.sha256(gz.read_bytes()).hexdigest() == snap.sha256
    raw = tmp_path / "restored.sqlite3"
    raw.write_bytes(gzip.decompress(gz.read_bytes()))
    with sqlite3.connect(raw) as conn:
        assert conn.execute("PRAGMA quick_check").fetchone()[0] == "ok"
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for required in (
            "market_samples", "flow_samples", "depth_samples", "open_interest_samples",
            "liquidations", "paper_trades", "participant_specs", "recorder_health",
        ):
            assert required in tables
    participants = list_participants(db)
    assert len(participants) == 4
    assert all(p["active_effect"] == "SHADOW_ONLY" for p in participants)
    assert all(p["role"] == "CANDIDATE" for p in participants)


def test_snapshot_catchup_and_retention(tmp_path):
    db = tmp_path / "tradelab.sqlite3"
    out = tmp_path / "snapshots"
    initialize(db)
    seed(db)

    first = create_snapshot(db, out, keep=2)
    time.sleep(0.002)
    second = create_snapshot(db, out, keep=2)
    time.sleep(0.002)
    third = create_snapshot(db, out, keep=2)

    retained = list_snapshots(db, 0)
    assert [s.snapshot_id for s in retained] == [second.snapshot_id, third.snapshot_id]
    assert get_snapshot(db, first.snapshot_id) is None
    assert get_snapshot(db, second.snapshot_id) is not None
    assert [s.snapshot_id for s in list_snapshots(db, second.created_at_ms)] == [third.snapshot_id]
