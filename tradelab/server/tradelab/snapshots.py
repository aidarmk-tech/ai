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
FULL_PREFIX = "tradelab-full-"


@dataclass(frozen=True)
class Snapshot:
    snapshot_id: str
    created_at_ms: int
    filename: str
    bytes: int
    sha256: str

    @property
    def kind(self) -> str:
        return "full" if self.filename.startswith(FULL_PREFIX) else "analysis"

    def as_dict(self) -> dict:
        return {
            "snapshot_id": self.snapshot_id,
            "created_at_ms": self.created_at_ms,
            "filename": self.filename,
            "bytes": self.bytes,
            "sha256": self.sha256,
            "kind": self.kind,
        }


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _prepare_snapshot_copy(
    path: Path,
    created_ms: int,
    *,
    kind: str,
    raw_hours: int | None,
) -> None:
    """Finalize the disposable SQLite backup before gzip.

    Analysis snapshots keep only a recent raw overlap window. Full snapshots do
    not prune any rows from the consistent backup and therefore contain every
    raw row still retained by the live server plus all cumulative research
    tables. Full exports deliberately skip VACUUM because compacting a multi-GB
    72-hour DB would double I/O for no analytical benefit.
    """
    with sqlite3.connect(path, timeout=30) as conn:
        conn.execute("PRAGMA busy_timeout=30000")
        conn.execute("PRAGMA journal_mode=DELETE")
        existing = {
            row[0]
            for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
        }

        cutoff = None
        if kind == "analysis":
            hours = max(1, int(raw_hours or 1))
            cutoff = created_ms - hours * 3600_000
            for table in RAW_TABLES:
                if table in existing:
                    conn.execute(f"DELETE FROM {table} WHERE ts_ms < ?", (cutoff,))
        else:
            hours = int(raw_hours or 0)

        if "meta" in existing:
            meta = [
                ("snapshot_kind", kind),
                ("snapshot_created_at_ms", str(created_ms)),
            ]
            if hours > 0:
                meta.append(("snapshot_raw_hours", str(hours)))
            if cutoff is not None:
                meta.append(("snapshot_cutoff_ms", str(cutoff)))
            conn.executemany("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", meta)

        conn.commit()
        if kind == "analysis":
            conn.execute("VACUUM")
            conn.commit()
        check = conn.execute("PRAGMA quick_check").fetchone()[0]
        if check != "ok":
            raise RuntimeError(f"{kind} snapshot quick_check failed: {check}")


def _create_snapshot(
    db_path: Path,
    snapshot_dir: Path,
    *,
    kind: str,
    raw_hours: int,
    keep: int,
) -> Snapshot:
    snapshot_dir.mkdir(parents=True, exist_ok=True)
    created = int(time.time() * 1000)
    sid = uuid.uuid4().hex[:12]
    prefix = FULL_PREFIX if kind == "full" else ANALYSIS_PREFIX
    base = f"{prefix}{created}-{sid}.sqlite3"
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

    try:
        _prepare_snapshot_copy(
            raw,
            created,
            kind=kind,
            raw_hours=raw_hours,
        )
        with raw.open("rb") as src, gzip.open(gz, "wb", compresslevel=6) as dst:
            shutil.copyfileobj(src, dst, length=1024 * 1024)
    finally:
        raw.unlink(missing_ok=True)

    snap = Snapshot(sid, created, gz.name, gz.stat().st_size, _sha256(gz))
    with connect(db_path) as conn:
        conn.execute(
            "INSERT INTO snapshots(snapshot_id, created_at_ms, filename, bytes, sha256) VALUES (?, ?, ?, ?, ?)",
            (snap.snapshot_id, snap.created_at_ms, snap.filename, snap.bytes, snap.sha256),
        )

    # Compact and full exports have separate retention pools. An occasional
    # full research export must never evict the 4-hour compact history used by
    # the Android catch-up worker.
    rows = sorted(snapshot_dir.glob(f"{prefix}*.sqlite3.gz"), key=lambda p: p.stat().st_mtime, reverse=True)
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


def create_snapshot(
    db_path: Path,
    snapshot_dir: Path,
    keep: int = 15,
    raw_hours: int = 6,
) -> Snapshot:
    """Create the routine compact snapshot used every four hours."""
    return _create_snapshot(
        db_path,
        snapshot_dir,
        kind="analysis",
        raw_hours=max(1, int(raw_hours)),
        keep=keep,
    )


def create_full_snapshot(
    db_path: Path,
    snapshot_dir: Path,
    keep: int = 1,
    retained_raw_hours: int = 72,
) -> Snapshot:
    """Create an on-demand full research export of the live retained DB."""
    return _create_snapshot(
        db_path,
        snapshot_dir,
        kind="full",
        raw_hours=max(1, int(retained_raw_hours)),
        keep=keep,
    )


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
