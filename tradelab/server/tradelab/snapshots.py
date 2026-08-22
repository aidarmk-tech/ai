import gzip
import hashlib
import shutil
import sqlite3
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from .db import connect


RAW_TABLES = (
    "market_samples",
    "flow_samples",
    "depth_samples",
    "open_interest_samples",
    "liquidations",
)
ANALYSIS_PREFIX = "tradelab-analysis-"


@dataclass(frozen=True)
class Snapshot:
    snapshot_id: str
    created_at_ms: int
    filename: str
    bytes: int
    sha256: str

    def as_dict(self) -> dict:
        return {
            "snapshot_id": self.snapshot_id,
            "created_at_ms": self.created_at_ms,
            "filename": self.filename,
            "bytes": self.bytes,
            "sha256": self.sha256,
        }


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _compact_analysis_copy(path: Path, created_ms: int, raw_hours: int) -> None:
    """Trim only high-volume raw tables in the copied DB.

    Cumulative participants, paper trades, signals/events, strategy specs,
    market_states and forward_labels remain intact. The live recorder DB is never
    modified here and continues to keep its longer raw retention window.
    """
    hours = max(1, int(raw_hours))
    cutoff = created_ms - hours * 3600_000
    with sqlite3.connect(path, timeout=30) as conn:
        conn.execute("PRAGMA busy_timeout=30000")
        # The source DB runs in WAL mode. A backup may preserve that mode on the
        # disposable copy. Switch the copy to DELETE so pruning/meta/VACUUM land
        # in the main .sqlite3 file that will actually be gzipped (not a -wal sidecar).
        conn.execute("PRAGMA journal_mode=DELETE")
        existing = {
            row[0]
            for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
        }
        for table in RAW_TABLES:
            if table in existing:
                conn.execute(f"DELETE FROM {table} WHERE ts_ms < ?", (cutoff,))
        if "meta" in existing:
            conn.executemany(
                "INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",
                [
                    ("snapshot_kind", "analysis"),
                    ("snapshot_raw_hours", str(hours)),
                    ("snapshot_cutoff_ms", str(cutoff)),
                    ("snapshot_created_at_ms", str(created_ms)),
                ],
            )
        conn.commit()
        conn.execute("VACUUM")
        conn.commit()
        check = conn.execute("PRAGMA quick_check").fetchone()[0]
        if check != "ok":
            raise RuntimeError(f"analysis snapshot quick_check failed: {check}")


def create_snapshot(
    db_path: Path,
    snapshot_dir: Path,
    keep: int = 15,
    raw_hours: int = 6,
) -> Snapshot:
    snapshot_dir.mkdir(parents=True, exist_ok=True)
    created = int(time.time() * 1000)
    sid = uuid.uuid4().hex[:12]
    base = f"{ANALYSIS_PREFIX}{created}-{sid}.sqlite3"
    raw = snapshot_dir / base
    gz = snapshot_dir / f"{base}.gz"

    source = sqlite3.connect(db_path, timeout=30)
    target = sqlite3.connect(raw)
    try:
        source.execute("PRAGMA busy_timeout=30000")
        source.backup(target)
    finally:
        target.close()
        source.close()

    _compact_analysis_copy(raw, created, raw_hours)

    with raw.open("rb") as src, gzip.open(gz, "wb", compresslevel=6) as dst:
        shutil.copyfileobj(src, dst, length=1024 * 1024)
    raw.unlink(missing_ok=True)

    snap = Snapshot(sid, created, gz.name, gz.stat().st_size, _sha256(gz))
    with connect(db_path) as conn:
        conn.execute(
            "INSERT INTO snapshots(snapshot_id, created_at_ms, filename, bytes, sha256) VALUES (?, ?, ?, ?, ?)",
            (snap.snapshot_id, snap.created_at_ms, snap.filename, snap.bytes, snap.sha256),
        )

    rows = sorted(snapshot_dir.glob("tradelab-*.sqlite3.gz"), key=lambda p: p.stat().st_mtime, reverse=True)
    for old in rows[max(1, keep):]:
        old.unlink(missing_ok=True)

    existing = {p.name for p in snapshot_dir.glob("tradelab-*.sqlite3.gz")}
    with connect(db_path) as conn:
        stale = conn.execute("SELECT snapshot_id, filename FROM snapshots").fetchall()
        conn.executemany(
            "DELETE FROM snapshots WHERE snapshot_id = ?",
            [(r["snapshot_id"],) for r in stale if r["filename"] not in existing],
        )
    return snap


def latest_snapshot(db_path: Path) -> Snapshot | None:
    with connect(db_path) as conn:
        row = conn.execute(
            """SELECT snapshot_id, created_at_ms, filename, bytes, sha256 FROM snapshots
               WHERE filename LIKE ? ORDER BY created_at_ms DESC LIMIT 1""",
            (ANALYSIS_PREFIX + "%",),
        ).fetchone()
    return Snapshot(**dict(row)) if row else None


def list_snapshots(db_path: Path, after_ms: int = 0) -> list[Snapshot]:
    with connect(db_path) as conn:
        rows = conn.execute(
            """SELECT snapshot_id, created_at_ms, filename, bytes, sha256
               FROM snapshots WHERE created_at_ms > ? AND filename LIKE ?
               ORDER BY created_at_ms ASC LIMIT 15""",
            (max(0, after_ms), ANALYSIS_PREFIX + "%"),
        ).fetchall()
    return [Snapshot(**dict(row)) for row in rows]


def get_snapshot(db_path: Path, snapshot_id: str) -> Snapshot | None:
    with connect(db_path) as conn:
        row = conn.execute(
            "SELECT snapshot_id, created_at_ms, filename, bytes, sha256 FROM snapshots WHERE snapshot_id = ?",
            (snapshot_id,),
        ).fetchone()
    return Snapshot(**dict(row)) if row else None
