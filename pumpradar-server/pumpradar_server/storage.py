from __future__ import annotations

import csv
import gzip
import hashlib
import json
import logging
import os
import shutil
import sqlite3
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Iterable, Optional

from .config import Settings
from .models import EvaluatedCandidate

LOG = logging.getLogger(__name__)


SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;
PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS experiment_runs (
  id TEXT PRIMARY KEY,
  started_at_ms INTEGER NOT NULL,
  ended_at_ms INTEGER,
  algorithm_version TEXT NOT NULL,
  strategy_version TEXT NOT NULL,
  config_hash TEXT NOT NULL,
  host TEXT,
  status TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS snapshots (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES experiment_runs(id),
  snapshot_time_ms INTEGER NOT NULL,
  snapshot_type TEXT NOT NULL,
  event_id TEXT,
  symbol TEXT NOT NULL,
  opportunity_label TEXT NOT NULL,
  liquidity_tier TEXT NOT NULL,
  algorithm_version TEXT NOT NULL,
  strategy_version TEXT NOT NULL,
  config_hash TEXT NOT NULL,
  return_15s REAL, return_60s REAL, return_5m REAL, acceleration REAL,
  volume_z_30s REAL, quote_volume_30s REAL, taker_buy_ratio_30s REAL,
  taker_buy_ratio_15s REAL, taker_buy_ratio_5s REAL,
  cvd_30s REAL, cvd_15s REAL, cvd_5s REAL, cvd_slope REAL,
  spread_bps REAL, obi_10 REAL, slippage_buy_percent REAL, slippage_sell_percent REAL,
  relative_strength_vs_btc REAL, largest_trade_share REAL, top3_trade_share REAL, tiny_trade_share REAL,
  impulse INTEGER, entry_risk INTEGER, confidence INTEGER, exhaustion_risk INTEGER,
  artificial_risk INTEGER, market_wide_risk INTEGER,
  strict_quality_passed INTEGER NOT NULL, shadow_quality_passed INTEGER NOT NULL,
  technical_entry_passed INTEGER NOT NULL, hard_veto INTEGER NOT NULL,
  blockers TEXT NOT NULL, reasons TEXT NOT NULL,
  price_age_ms INTEGER, trade_age_ms INTEGER, book_ticker_age_ms INTEGER,
  depth_age_ms INTEGER, depth_update_id INTEGER,
  best_bid REAL, best_ask REAL, entry_buy_vwap REAL, executable_sell_vwap REAL,
  UNIQUE(run_id, snapshot_time_ms, symbol, snapshot_type)
);
CREATE INDEX IF NOT EXISTS idx_snapshots_time ON snapshots(snapshot_time_ms);
CREATE INDEX IF NOT EXISTS idx_snapshots_label ON snapshots(opportunity_label);
CREATE TABLE IF NOT EXISTS paper_slots (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES experiment_runs(id),
  source_snapshot_id TEXT REFERENCES snapshots(id),
  event_id TEXT NOT NULL,
  symbol TEXT NOT NULL,
  opened_at_ms INTEGER NOT NULL,
  entry_best_ask REAL NOT NULL,
  entry_vwap REAL NOT NULL,
  position_usdt REAL NOT NULL,
  quantity REAL NOT NULL,
  entry_fee_usdt REAL NOT NULL,
  baseline_status TEXT NOT NULL,
  baseline_closed_at_ms INTEGER,
  baseline_exit_reason TEXT,
  baseline_exit_vwap REAL,
  baseline_gross_return_percent REAL,
  baseline_net_return_percent REAL,
  max_executable_return_percent REAL NOT NULL DEFAULT 0,
  min_executable_return_percent REAL NOT NULL DEFAULT 0,
  last_updated_at_ms INTEGER NOT NULL,
  algorithm_version TEXT NOT NULL,
  strategy_version TEXT NOT NULL,
  config_hash TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_slots_open ON paper_slots(baseline_status, opened_at_ms);
CREATE TABLE IF NOT EXISTS policy_runs (
  id TEXT PRIMARY KEY,
  slot_id TEXT NOT NULL REFERENCES paper_slots(id),
  policy TEXT NOT NULL,
  state TEXT NOT NULL,
  activated_at_ms INTEGER,
  partial_quantity REAL NOT NULL DEFAULT 0,
  partial_exit_vwap REAL,
  partial_exit_fee_usdt REAL NOT NULL DEFAULT 0,
  peak_return_percent REAL NOT NULL DEFAULT 0,
  weakening_ticks INTEGER NOT NULL DEFAULT 0,
  closed_at_ms INTEGER,
  exit_reason TEXT,
  exit_vwap REAL,
  exit_fee_usdt REAL NOT NULL DEFAULT 0,
  gross_return_percent REAL,
  net_return_percent REAL,
  UNIQUE(slot_id, policy)
);
CREATE INDEX IF NOT EXISTS idx_policy_open ON policy_runs(state);
CREATE TABLE IF NOT EXISTS skipped_candidates (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES experiment_runs(id),
  snapshot_id TEXT REFERENCES snapshots(id),
  occurred_at_ms INTEGER NOT NULL,
  symbol TEXT NOT NULL,
  reason TEXT NOT NULL,
  active_slot_id TEXT
);
CREATE TABLE IF NOT EXISTS service_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  timestamp_ms INTEGER NOT NULL,
  severity TEXT NOT NULL,
  subsystem TEXT NOT NULL,
  message TEXT NOT NULL
);
"""

SNAPSHOT_AUDIT_COLUMNS = {
    "price_age_ms": "INTEGER",
    "trade_age_ms": "INTEGER",
    "book_ticker_age_ms": "INTEGER",
    "depth_age_ms": "INTEGER",
    "depth_update_id": "INTEGER",
    "best_bid": "REAL",
    "best_ask": "REAL",
    "entry_buy_vwap": "REAL",
    "executable_sell_vwap": "REAL",
}

SNAPSHOT_COLUMNS = (
    "id", "run_id", "snapshot_time_ms", "snapshot_type", "event_id", "symbol",
    "opportunity_label", "liquidity_tier", "algorithm_version", "strategy_version",
    "config_hash", "return_15s", "return_60s", "return_5m", "acceleration",
    "volume_z_30s", "quote_volume_30s", "taker_buy_ratio_30s",
    "taker_buy_ratio_15s", "taker_buy_ratio_5s", "cvd_30s", "cvd_15s", "cvd_5s",
    "cvd_slope", "spread_bps", "obi_10", "slippage_buy_percent",
    "slippage_sell_percent", "relative_strength_vs_btc", "largest_trade_share",
    "top3_trade_share", "tiny_trade_share", "impulse", "entry_risk", "confidence",
    "exhaustion_risk", "artificial_risk", "market_wide_risk",
    "strict_quality_passed", "shadow_quality_passed", "technical_entry_passed",
    "hard_veto", "blockers", "reasons", "price_age_ms", "trade_age_ms",
    "book_ticker_age_ms", "depth_age_ms", "depth_update_id", "best_bid", "best_ask",
    "entry_buy_vwap", "executable_sell_vwap",
)


class Storage:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        settings.data_dir.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(settings.db_path, check_same_thread=False, timeout=30)
        self.conn.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        with self.lock:
            self.conn.executescript(SCHEMA)
            existing = {
                row["name"] for row in self.conn.execute("PRAGMA table_info(snapshots)")
            }
            for name, data_type in SNAPSHOT_AUDIT_COLUMNS.items():
                if name not in existing:
                    self.conn.execute(f"ALTER TABLE snapshots ADD COLUMN {name} {data_type}")
            self.conn.commit()
        self.run_id = ""

    def start_run(self, host: str) -> str:
        run_id = str(uuid.uuid4())
        now = int(time.time() * 1000)
        with self.lock:
            self.conn.execute(
                """UPDATE experiment_runs
                   SET ended_at_ms=COALESCE(ended_at_ms, ?), status='INTERRUPTED'
                   WHERE status='RUNNING'""",
                (now,),
            )
            self.conn.execute(
                "INSERT INTO experiment_runs VALUES (?, ?, NULL, ?, ?, ?, ?, 'RUNNING')",
                (run_id, now, self.settings.algorithm_version, self.settings.strategy_version, self.settings.config_hash(), host),
            )
            incompatible = list(self.conn.execute(
                """SELECT id FROM paper_slots
                   WHERE baseline_status='OPEN' AND
                   (algorithm_version<>? OR strategy_version<>? OR config_hash<>?)""",
                (
                    self.settings.algorithm_version,
                    self.settings.strategy_version,
                    self.settings.config_hash(),
                ),
            ))
            for row in incompatible:
                self.conn.execute(
                    """UPDATE paper_slots SET baseline_status='CLOSED',
                       baseline_closed_at_ms=?, baseline_exit_reason='CONFIG_CHANGED',
                       last_updated_at_ms=? WHERE id=?""",
                    (now, now, row["id"]),
                )
                self.conn.execute(
                    """UPDATE policy_runs SET state='CLOSED', closed_at_ms=?,
                       exit_reason='CONFIG_CHANGED' WHERE slot_id=? AND state='OPEN'""",
                    (now, row["id"]),
                )
            if incompatible:
                self.conn.execute(
                    "INSERT INTO service_events(timestamp_ms,severity,subsystem,message) VALUES(?,?,?,?)",
                    (
                        now,
                        "WARNING",
                        "recovery",
                        f"Closed {len(incompatible)} open slot(s) after config/version change",
                    ),
                )
            self.conn.commit()
        self.run_id = run_id
        return run_id

    def finish_run(self) -> None:
        if not self.run_id:
            return
        with self.lock:
            self.conn.execute(
                "UPDATE experiment_runs SET ended_at_ms=?, status='STOPPED' WHERE id=?",
                (int(time.time() * 1000), self.run_id),
            )
            self.conn.commit()

    def event(self, severity: str, subsystem: str, message: str) -> None:
        with self.lock:
            self.conn.execute(
                "INSERT INTO service_events(timestamp_ms,severity,subsystem,message) VALUES(?,?,?,?)",
                (int(time.time() * 1000), severity, subsystem, message[:2000]),
            )
            self.conn.commit()

    def insert_snapshot(self, item: EvaluatedCandidate, snapshot_type: str, event_id: Optional[str], now_ms: int) -> str:
        snapshot_id = str(uuid.uuid4())
        c, f, b, d = item.candidate, item.flow, item.book, item.decision
        values = (
            snapshot_id, self.run_id, now_ms, snapshot_type, event_id, c.symbol, d.label, d.liquidity_tier,
            self.settings.algorithm_version, self.settings.strategy_version, self.settings.config_hash(),
            c.return_15s, c.return_60s, c.return_5m, c.acceleration,
            f.volume_z_30s, f.quote_volume_30s, f.taker_buy_ratio_30s, f.taker_buy_ratio_15s, f.taker_buy_ratio_5s,
            f.cvd_30s, f.cvd_15s, f.cvd_5s, f.cvd_slope,
            b.spread_bps, b.obi_10, b.buy_slippage_percent, b.sell_slippage_percent,
            c.relative_strength_vs_btc, f.largest_trade_share, f.top3_trade_share, f.tiny_trade_share,
            d.risk.impulse, d.risk.entry_risk, d.risk.confidence, d.risk.exhaustion_risk,
            d.risk.artificial_risk, d.risk.market_wide_risk,
            int(d.strict_passed), int(d.shadow_passed), int(d.technical_passed), int(d.risk.hard_veto),
            json.dumps(d.blockers, ensure_ascii=False), json.dumps(d.reasons, ensure_ascii=False),
            c.price_age_ms,
            f.trade_age_ms,
            f.book_ticker_age_ms,
            b.depth_age_ms,
            b.depth_update_id,
            b.best_bid,
            b.best_ask,
            b.buy_vwap,
            b.sell_vwap_for_position,
        )
        placeholders = ",".join("?" for _ in values)
        columns = ",".join(SNAPSHOT_COLUMNS)
        with self.lock:
            self.conn.execute(
                f"INSERT OR IGNORE INTO snapshots ({columns}) VALUES ({placeholders})",
                values,
            )
            self.conn.commit()
        return snapshot_id

    def create_slot(self, source_snapshot_id: str, symbol: str, event_id: str, now_ms: int, best_ask: float, entry_vwap: float) -> str:
        slot_id = str(uuid.uuid4())
        quantity = self.settings.position_usdt / entry_vwap
        entry_fee = self.settings.position_usdt * self.settings.fee_rate
        with self.lock:
            self.conn.execute(
                """INSERT INTO paper_slots(
                  id,run_id,source_snapshot_id,event_id,symbol,opened_at_ms,entry_best_ask,entry_vwap,
                  position_usdt,quantity,entry_fee_usdt,baseline_status,last_updated_at_ms,
                  algorithm_version,strategy_version,config_hash
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?,?,?)""",
                (slot_id, self.run_id, source_snapshot_id, event_id, symbol, now_ms, best_ask, entry_vwap,
                 self.settings.position_usdt, quantity, entry_fee, now_ms, self.settings.algorithm_version,
                 self.settings.strategy_version, self.settings.config_hash()),
            )
            for policy in ("A_PARTIAL_20", "B_FULL_PROTECTED", "C_WEAKENING"):
                self.conn.execute(
                    "INSERT INTO policy_runs(id,slot_id,policy,state) VALUES(?,?,?,'OPEN')",
                    (str(uuid.uuid4()), slot_id, policy),
                )
            self.conn.commit()
        return slot_id

    def open_slots(self) -> list[sqlite3.Row]:
        with self.lock:
            return list(self.conn.execute(
                "SELECT * FROM paper_slots WHERE id IN (SELECT DISTINCT slot_id FROM policy_runs WHERE state='OPEN') ORDER BY opened_at_ms"
            ))

    def policies_for_slot(self, slot_id: str) -> list[sqlite3.Row]:
        with self.lock:
            return list(self.conn.execute("SELECT * FROM policy_runs WHERE slot_id=? ORDER BY policy", (slot_id,)))

    def update_slot_extremes(self, slot_id: str, current_return: float, now_ms: int) -> None:
        with self.lock:
            self.conn.execute(
                """UPDATE paper_slots SET
                   max_executable_return_percent=MAX(max_executable_return_percent,?),
                   min_executable_return_percent=MIN(min_executable_return_percent,?),
                   last_updated_at_ms=? WHERE id=?""",
                (current_return, current_return, now_ms, slot_id),
            )
            self.conn.commit()

    def update_policy(self, policy_id: str, **fields: Any) -> None:
        if not fields:
            return
        assignments = ",".join(f"{name}=?" for name in fields)
        with self.lock:
            self.conn.execute(f"UPDATE policy_runs SET {assignments} WHERE id=?", (*fields.values(), policy_id))
            self.conn.commit()

    def close_baseline(self, slot_id: str, now_ms: int, reason: str, exit_vwap: float, gross: float, net: float) -> None:
        with self.lock:
            self.conn.execute(
                """UPDATE paper_slots SET baseline_status='CLOSED', baseline_closed_at_ms=?, baseline_exit_reason=?,
                   baseline_exit_vwap=?, baseline_gross_return_percent=?, baseline_net_return_percent=?, last_updated_at_ms=?
                   WHERE id=?""",
                (now_ms, reason, exit_vwap, gross, net, now_ms, slot_id),
            )
            self.conn.commit()

    def add_skipped(self, snapshot_id: str, symbol: str, reason: str, active_slot_id: Optional[str], now_ms: int) -> None:
        with self.lock:
            self.conn.execute(
                "INSERT INTO skipped_candidates VALUES(?,?,?,?,?,?,?)",
                (str(uuid.uuid4()), self.run_id, snapshot_id, now_ms, symbol, reason, active_slot_id),
            )
            self.conn.commit()

    def baseline_open_slot(self) -> Optional[sqlite3.Row]:
        with self.lock:
            return self.conn.execute(
                "SELECT * FROM paper_slots WHERE baseline_status='OPEN' ORDER BY opened_at_ms LIMIT 1"
            ).fetchone()

    def last_slot_for_symbol(self, symbol: str) -> Optional[sqlite3.Row]:
        with self.lock:
            return self.conn.execute(
                "SELECT * FROM paper_slots WHERE symbol=? ORDER BY opened_at_ms DESC LIMIT 1", (symbol,)
            ).fetchone()

    def status(self) -> dict[str, Any]:
        with self.lock:
            row = self.conn.execute("SELECT COUNT(*) n FROM snapshots WHERE run_id=?", (self.run_id,)).fetchone()
            slots = self.conn.execute("SELECT COUNT(*) n FROM paper_slots WHERE run_id=?", (self.run_id,)).fetchone()
            open_slot = self.baseline_open_slot()
            return {
                "run_id": self.run_id,
                "snapshots": row["n"] if row else 0,
                "slots": slots["n"] if slots else 0,
                "active_slot": dict(open_slot) if open_slot else None,
                "active_slot_run_id": open_slot["run_id"] if open_slot else None,
                "algorithm_version": self.settings.algorithm_version,
                "strategy_version": self.settings.strategy_version,
                "config_hash": self.settings.config_hash(),
            }

    def checkpoint(self) -> None:
        with self.lock:
            self.conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            self.conn.commit()

    @staticmethod
    def _sha256(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()

    def _prune_exports(self, exports_root: Path, current: Path) -> None:
        directories = sorted(
            (
                path
                for path in exports_root.iterdir()
                if path.is_dir() and not path.is_symlink() and path != current
            ),
            key=lambda path: path.stat().st_mtime,
        )
        all_directories = directories + [current]
        sizes = {
            path: sum(item.stat().st_size for item in path.rglob("*") if item.is_file())
            for path in all_directories
        }
        total = sum(sizes.values())
        limit = max(1, self.settings.export_max_total_mb) * 1024 * 1024
        keep = max(1, self.settings.export_keep_count)
        while directories and (len(directories) + 1 > keep or total > limit):
            victim = directories.pop(0)
            total -= sizes[victim]
            shutil.rmtree(victim)

    def export_all(self, output_dir: Optional[Path] = None) -> Path:
        now_ms = int(time.time() * 1000)
        stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime(now_ms / 1000))
        output_dir = output_dir or self.settings.data_dir / "exports" / f"{stamp}-{now_ms % 1000:03d}"
        output_dir.mkdir(parents=True, exist_ok=True)
        backup_path = output_dir / "pumpradar.sqlite3"
        with self.lock:
            self.conn.commit()
            backup_conn = sqlite3.connect(backup_path)
            self.conn.backup(backup_conn)
            backup_conn.close()

        tables = [
            "experiment_runs",
            "snapshots",
            "paper_slots",
            "policy_runs",
            "skipped_candidates",
            "service_events",
        ]
        exported_files: dict[str, dict[str, Any]] = {}
        row_counts: dict[str, int] = {}
        snapshot = sqlite3.connect(backup_path)
        snapshot.row_factory = sqlite3.Row
        try:
            integrity = snapshot.execute("PRAGMA integrity_check").fetchone()[0]
            foreign_key_errors = list(snapshot.execute("PRAGMA foreign_key_check"))
            if integrity != "ok" or foreign_key_errors:
                raise RuntimeError(
                    f"SQLite export validation failed: integrity={integrity}, "
                    f"foreign_keys={len(foreign_key_errors)}"
                )
            for table in tables:
                row_counts[table] = int(
                    snapshot.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                )
                rows = snapshot.execute(f"SELECT * FROM {table}")
                headers = [item[0] for item in rows.description]
                csv_path = output_dir / f"{table}.csv.gz"
                with gzip.open(csv_path, "wt", encoding="utf-8", newline="") as handle:
                    writer = csv.writer(handle)
                    writer.writerow(headers)
                    writer.writerows(rows)
                exported_files[csv_path.name] = {
                    "bytes": csv_path.stat().st_size,
                    "sha256": self._sha256(csv_path),
                }
        finally:
            snapshot.close()

        sqlite_gz = output_dir / "pumpradar.sqlite3.gz"
        with backup_path.open("rb") as src, gzip.open(sqlite_gz, "wb") as dst:
            shutil.copyfileobj(src, dst)
        backup_path.unlink(missing_ok=True)
        exported_files[sqlite_gz.name] = {
            "bytes": sqlite_gz.stat().st_size,
            "sha256": self._sha256(sqlite_gz),
        }
        manifest = {
            "schema_version": 2,
            "exported_at_ms": now_ms,
            "run_id": self.run_id,
            "algorithm_version": self.settings.algorithm_version,
            "strategy_version": self.settings.strategy_version,
            "config_hash": self.settings.config_hash(),
            "row_counts": row_counts,
            "files": exported_files,
        }
        manifest_path = output_dir / "manifest.json"
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        latest = self.settings.data_dir / "exports" / "latest"
        temporary_link = latest.with_name(f".latest-{uuid.uuid4().hex}")
        temporary_link.symlink_to(output_dir.name)
        os.replace(temporary_link, latest)
        self._prune_exports(latest.parent, output_dir)
        return output_dir
