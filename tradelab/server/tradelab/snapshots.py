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


def _relocate_ddl(sql: str, target_schema: str) -> str:
    s = sql.lstrip()
    for kw in ("CREATE UNIQUE INDEX ", "CREATE INDEX ", "CREATE TABLE ", "CREATE VIEW ", "CREATE TRIGGER "):
        if s.upper().startswith(kw):
            return f"{kw}{target_schema}.{s[len(kw):]}"
    raise RuntimeError(f"unsupported DDL in snapshot source: {s[:60]!r}")


def _materialize_snapshot_copy(
    source_path: Path,
    target_path: Path,
    created_ms: int,
    *,
    kind: str,
    raw_hours: int | None,
) -> None:
    """Build the disposable snapshot DB directly instead of copy+prune.

    Copying the whole live file and deleting old raw rows needs a rollback
    journal comparable to the DB size plus a VACUUM pass, which does not fit
    on the small VPS disk. Materializing only retained rows into a fresh file
    keeps transient usage near the final snapshot size and yields an
    already-compact result without VACUUM.
    """
    cutoff = None
    hours = 0
    if kind == "analysis":
        hours = max(1, int(raw_hours or 1))
        cutoff = created_ms - hours * 3600_000
    else:
        hours = int(raw_hours or 0)

    conn = sqlite3.connect(source_path, timeout=30)
    conn.isolation_level = None
    try:
        conn.execute("PRAGMA busy_timeout=30000")
        conn.execute("ATTACH DATABASE ? AS snap", (str(target_path),))
        try:
            conn.execute("PRAGMA snap.journal_mode=DELETE")
            objects = conn.execute(
                "SELECT type, name, sql FROM sqlite_master "
                "WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY rowid"
            ).fetchall()
            tables = [obj for obj in objects if obj[0] == "table"]
            others = [obj for obj in objects if obj[0] != "table"]

            conn.execute("BEGIN")
            try:
                for _, name, sql in tables:
                    conn.execute(_relocate_ddl(sql, "snap"))
                    has_ts = any(
                        col[1] == "ts_ms" for col in conn.execute(f'PRAGMA main.table_info("{name}")')
                    )
                    if cutoff is not None and name in RAW_TABLES and has_ts:
                        conn.execute(
                            f'INSERT INTO snap."{name}" SELECT * FROM main."{name}" WHERE ts_ms >= ?',
                            (cutoff,),
                        )
                    else:
                        conn.execute(f'INSERT INTO snap."{name}" SELECT * FROM main."{name}"')

                for _, name, sql in others:
                    conn.execute(_relocate_ddl(sql, "snap"))

                meta = [
                    ("snapshot_kind", kind),
                    ("snapshot_created_at_ms", str(created_ms)),
                ]
                if hours > 0:
                    meta.append(("snapshot_raw_hours", str(hours)))
                if cutoff is not None:
                    meta.append(("snapshot_cutoff_ms", str(cutoff)))
                conn.executemany('INSERT OR REPLACE INTO snap."meta"(key,value) VALUES(?,?)', meta)

                check = conn.execute("PRAGMA snap.quick_check").fetchone()[0]
                if check != "ok":
                    raise RuntimeError(f"{kind} snapshot quick_check failed: {check}")
                conn.execute("COMMIT")
            except BaseException:
                conn.execute("ROLLBACK")
                raise
        finally:
            conn.execute("DETACH DATABASE snap")
    finally:
        conn.close()


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

    # Refuse early instead of dying mid-build and leaking partial artifacts
    # when the disk is nearly full. Analysis copies stay near the retained raw
    # window, full exports approach the whole live DB.
    db_bytes = db_path.stat().st_size
    if kind == "full":
        required = db_bytes * 2 + 512 * 1024 * 1024
    else:
        required = db_bytes + 512 * 1024 * 1024
    free = shutil.disk_usage(snapshot_dir).free
    if free < required:
        raise RuntimeError(
            f"insufficient disk space for {kind} snapshot: need ~{required} bytes, free {free} bytes"
        )

    published = False
    try:
        _materialize_snapshot_copy(
            db_path,
            raw,
            created,
            kind=kind,
            raw_hours=raw_hours,
        )
        with raw.open("rb") as src, gzip.open(gz, "wb", compresslevel=6) as dst:
            shutil.copyfileobj(src, dst, length=1024 * 1024)

        snap = Snapshot(sid, created, gz.name, gz.stat().st_size, _sha256(gz))
        with connect(db_path) as conn:
            conn.execute(
                "INSERT INTO snapshots(snapshot_id, created_at_ms, filename, bytes, sha256) VALUES (?, ?, ?, ?, ?)",
                (snap.snapshot_id, snap.created_at_ms, snap.filename, snap.bytes, snap.sha256),
            )
        published = True
    finally:
        raw.unlink(missing_ok=True)
        if not published:
            gz.unlink(missing_ok=True)

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
            (max(0, after_ms), ANALYSIS_PREFIX + "%",),
        ).fetchall()
    return [Snapshot(**dict(row)) for row in rows]


def get_snapshot(db_path: Path, snapshot_id: str) -> Snapshot | None:
    with connect(db_path) as conn:
        row = conn.execute(
            "SELECT snapshot_id, created_at_ms, filename, bytes, sha256 FROM snapshots WHERE snapshot_id = ?",
            (snapshot_id,),
        ).fetchone()
    return Snapshot(**dict(row)) if row else None
