import gzip
import hashlib
import sqlite3
from tradelab.db import initialize
from tradelab.participants import seed, list_participants
from tradelab.snapshots import create_snapshot


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
    assert len(list_participants(db)) == 4
