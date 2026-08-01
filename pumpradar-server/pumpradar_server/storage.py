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
from .episodes import EpisodeTelemetry
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
  episode_id TEXT,
  first_pre_candidate_at_ms INTEGER, impulse_started_at_ms INTEGER,
  strict_first_at_ms INTEGER, impulse_age_ms INTEGER, strict_streak INTEGER,
  momentum_persistent INTEGER, experimental_shadow_passed INTEGER,
  experimental_blockers TEXT,
  episode_start_price REAL, extension_from_episode_start_pct REAL,
  local_high_price REAL, peak_at_ms INTEGER,
  distance_from_local_high_pct REAL, seconds_since_local_high INTEGER,
  pullback_from_high_pct REAL, failed_high_attempts INTEGER,
  lower_high_detected INTEGER, breakout_level_held INTEGER,
  new_high_without_cvd_high INTEGER,
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
  config_hash TEXT NOT NULL,
  episode_id TEXT
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
CREATE TABLE IF NOT EXISTS snapshot_outcomes (
  snapshot_id TEXT PRIMARY KEY REFERENCES snapshots(id) ON DELETE CASCADE,
  episode_id TEXT,
  symbol TEXT NOT NULL,
  snapshot_type TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  entry_vwap REAL,
  entry_status TEXT NOT NULL,
  position_usdt REAL NOT NULL,
  fee_rate REAL NOT NULL,
  last_observed_at_ms INTEGER,
  mfe_percent REAL NOT NULL DEFAULT 0,
  mae_percent REAL NOT NULL DEFAULT 0,
  return_5s REAL, mfe_5s REAL, mae_5s REAL,
  return_15s REAL, mfe_15s REAL, mae_15s REAL,
  return_30s REAL, mfe_30s REAL, mae_30s REAL,
  return_60s REAL, mfe_60s REAL, mae_60s REAL,
  return_90s REAL, mfe_90s REAL, mae_90s REAL,
  return_120s REAL, mfe_120s REAL, mae_120s REAL,
  return_150s REAL, mfe_150s REAL, mae_150s REAL,
  return_180s REAL, mfe_180s REAL, mae_180s REAL,
  return_240s REAL, mfe_240s REAL, mae_240s REAL,
  return_300s REAL, mfe_300s REAL, mae_300s REAL,
  first_barrier TEXT, first_barrier_at_ms INTEGER,
  completed INTEGER NOT NULL DEFAULT 0,
  completion_reason TEXT
);
CREATE INDEX IF NOT EXISTS idx_snapshot_outcomes_pending
  ON snapshot_outcomes(completed, created_at_ms);
CREATE INDEX IF NOT EXISTS idx_snapshot_outcomes_episode
  ON snapshot_outcomes(episode_id);
CREATE TABLE IF NOT EXISTS momentum_slots (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES experiment_runs(id),
  source_snapshot_id TEXT REFERENCES snapshots(id),
  signal_channel TEXT NOT NULL DEFAULT 'MC5',
  episode_id TEXT NOT NULL,
  symbol TEXT NOT NULL,
  opened_at_ms INTEGER NOT NULL,
  entry_best_ask REAL NOT NULL,
  entry_vwap REAL NOT NULL,
  position_usdt REAL NOT NULL,
  quantity REAL NOT NULL,
  entry_fee_usdt REAL NOT NULL,
  primary_status TEXT NOT NULL,
  primary_closed_at_ms INTEGER,
  primary_exit_reason TEXT,
  primary_exit_vwap REAL,
  primary_gross_return_percent REAL,
  primary_net_return_percent REAL,
  max_executable_return_percent REAL NOT NULL DEFAULT 0,
  min_executable_return_percent REAL NOT NULL DEFAULT 0,
  last_updated_at_ms INTEGER NOT NULL,
  algorithm_version TEXT NOT NULL,
  strategy_version TEXT NOT NULL,
  config_hash TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_momentum_slots_open
  ON momentum_slots(primary_status, opened_at_ms);
CREATE TABLE IF NOT EXISTS momentum_policy_runs (
  id TEXT PRIMARY KEY,
  slot_id TEXT NOT NULL REFERENCES momentum_slots(id),
  policy TEXT NOT NULL,
  state TEXT NOT NULL,
  activated_at_ms INTEGER,
  peak_return_percent REAL NOT NULL DEFAULT 0,
  closed_at_ms INTEGER,
  exit_reason TEXT,
  exit_vwap REAL,
  exit_fee_usdt REAL NOT NULL DEFAULT 0,
  gross_return_percent REAL,
  net_return_percent REAL,
  UNIQUE(slot_id, policy)
);
CREATE INDEX IF NOT EXISTS idx_momentum_policy_open
  ON momentum_policy_runs(state);
"""

SNAPSHOT_AUDIT_COLUMNS = {
    "return_3m": "REAL",
    "return_10m": "REAL",
    "price_age_ms": "INTEGER",
    "trade_age_ms": "INTEGER",
    "book_ticker_age_ms": "INTEGER",
    "depth_age_ms": "INTEGER",
    "depth_update_id": "INTEGER",
    "best_bid": "REAL",
    "best_ask": "REAL",
    "entry_buy_vwap": "REAL",
    "executable_sell_vwap": "REAL",
    "episode_id": "TEXT",
    "first_pre_candidate_at_ms": "INTEGER",
    "impulse_started_at_ms": "INTEGER",
    "strict_first_at_ms": "INTEGER",
    "impulse_age_ms": "INTEGER",
    "strict_streak": "INTEGER",
    "momentum_persistent": "INTEGER",
    "experimental_shadow_passed": "INTEGER",
    "experimental_blockers": "TEXT",
    "episode_start_price": "REAL",
    "extension_from_episode_start_pct": "REAL",
    "local_high_price": "REAL",
    "peak_at_ms": "INTEGER",
    "distance_from_local_high_pct": "REAL",
    "seconds_since_local_high": "INTEGER",
    "pullback_from_high_pct": "REAL",
    "failed_high_attempts": "INTEGER",
    "lower_high_detected": "INTEGER",
    "breakout_level_held": "INTEGER",
    "new_high_without_cvd_high": "INTEGER",
}

PAPER_SLOT_AUDIT_COLUMNS = {
    "episode_id": "TEXT",
}

MOMENTUM_SLOT_AUDIT_COLUMNS = {
    "signal_channel": "TEXT NOT NULL DEFAULT 'MC5'",
}

SNAPSHOT_OUTCOME_AUDIT_COLUMNS = {
    "fee_rate": "REAL NOT NULL DEFAULT 0.001",
    "return_90s": "REAL",
    "mfe_90s": "REAL",
    "mae_90s": "REAL",
    "return_150s": "REAL",
    "mfe_150s": "REAL",
    "mae_150s": "REAL",
    "return_180s": "REAL",
    "mfe_180s": "REAL",
    "mae_180s": "REAL",
    "return_240s": "REAL",
    "mfe_240s": "REAL",
    "mae_240s": "REAL",
    "return_600s": "REAL",
    "mfe_600s": "REAL",
    "mae_600s": "REAL",
    "return_1200s": "REAL",
    "mfe_1200s": "REAL",
    "mae_1200s": "REAL",
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
    "episode_id", "first_pre_candidate_at_ms", "impulse_started_at_ms",
    "strict_first_at_ms", "impulse_age_ms", "strict_streak",
    "momentum_persistent", "experimental_shadow_passed",
    "experimental_blockers", "episode_start_price",
    "extension_from_episode_start_pct", "local_high_price", "peak_at_ms",
    "distance_from_local_high_pct", "seconds_since_local_high",
    "pullback_from_high_pct", "failed_high_attempts",
    "lower_high_detected", "breakout_level_held",
    "new_high_without_cvd_high",
    "return_3m", "return_10m",
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
            existing_slot_columns = {
                row["name"] for row in self.conn.execute("PRAGMA table_info(paper_slots)")
            }
            for name, data_type in PAPER_SLOT_AUDIT_COLUMNS.items():
                if name not in existing_slot_columns:
                    self.conn.execute(
                        f"ALTER TABLE paper_slots ADD COLUMN {name} {data_type}"
                    )
            existing_momentum_columns = {
                row["name"] for row in self.conn.execute("PRAGMA table_info(momentum_slots)")
            }
            for name, data_type in MOMENTUM_SLOT_AUDIT_COLUMNS.items():
                if name not in existing_momentum_columns:
                    self.conn.execute(f"ALTER TABLE momentum_slots ADD COLUMN {name} {data_type}")
            existing_outcome_columns = {
                row["name"]
                for row in self.conn.execute(
                    "PRAGMA table_info(snapshot_outcomes)"
                )
            }
            for name, data_type in SNAPSHOT_OUTCOME_AUDIT_COLUMNS.items():
                if name not in existing_outcome_columns:
                    self.conn.execute(
                        "ALTER TABLE snapshot_outcomes "
                        f"ADD COLUMN {name} {data_type}"
                    )
            self.conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_snapshots_episode ON snapshots(episode_id)"
            )
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
            incompatible_momentum = list(self.conn.execute(
                """SELECT id FROM momentum_slots
                   WHERE primary_status='OPEN' AND
                   (algorithm_version<>? OR strategy_version<>? OR config_hash<>?)""",
                (
                    self.settings.algorithm_version,
                    self.settings.strategy_version,
                    self.settings.config_hash(),
                ),
            ))
            for row in incompatible_momentum:
                self.conn.execute(
                    """UPDATE momentum_slots SET primary_status='CLOSED',
                       primary_closed_at_ms=?, primary_exit_reason='CONFIG_CHANGED',
                       last_updated_at_ms=? WHERE id=?""",
                    (now, now, row["id"]),
                )
                self.conn.execute(
                    """UPDATE momentum_policy_runs SET state='CLOSED',
                       closed_at_ms=?, exit_reason='CONFIG_CHANGED'
                       WHERE slot_id=? AND state='OPEN'""",
                    (now, row["id"]),
                )
            if incompatible_momentum:
                self.conn.execute(
                    "INSERT INTO service_events(timestamp_ms,severity,subsystem,message) VALUES(?,?,?,?)",
                    (
                        now,
                        "WARNING",
                        "recovery",
                        f"Closed {len(incompatible_momentum)} momentum slot(s) after config/version change",
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

    def insert_snapshot(
        self,
        item: EvaluatedCandidate,
        snapshot_type: str,
        event_id: Optional[str],
        now_ms: int,
        telemetry: Optional[EpisodeTelemetry] = None,
    ) -> str:
        snapshot_id = str(uuid.uuid4())
        c, f, b, p, d = (
            item.candidate,
            item.flow,
            item.book,
            item.peak,
            item.decision,
        )
        episode_id = telemetry.episode_id if telemetry else None
        stable_event_id = event_id or episode_id
        values = (
            snapshot_id, self.run_id, now_ms, snapshot_type, stable_event_id, c.symbol, d.label, d.liquidity_tier,
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
            episode_id,
            telemetry.first_pre_candidate_at_ms if telemetry else None,
            telemetry.impulse_started_at_ms if telemetry else None,
            telemetry.strict_first_at_ms if telemetry else None,
            telemetry.impulse_age_ms if telemetry else None,
            telemetry.strict_streak if telemetry else None,
            int(telemetry.momentum_persistent) if telemetry else None,
            int(telemetry.experimental_shadow_passed) if telemetry else None,
            (
                json.dumps(telemetry.experimental_blockers, ensure_ascii=False)
                if telemetry
                else None
            ),
            telemetry.episode_start_price if telemetry else None,
            telemetry.extension_from_episode_start_pct if telemetry else None,
            p.local_high_price,
            p.peak_at_ms,
            p.distance_from_local_high_pct,
            p.seconds_since_local_high,
            p.pullback_from_high_pct,
            p.failed_high_attempts,
            int(p.lower_high_detected),
            int(p.breakout_level_held),
            int(p.new_high_without_cvd_high),
            c.return_3m,
            c.return_10m,
        )
        placeholders = ",".join("?" for _ in values)
        columns = ",".join(SNAPSHOT_COLUMNS)
        with self.lock:
            cursor = self.conn.execute(
                f"INSERT OR IGNORE INTO snapshots ({columns}) VALUES ({placeholders})",
                values,
            )
            if cursor.rowcount == 0:
                existing = self.conn.execute(
                    """SELECT id FROM snapshots
                       WHERE run_id=? AND snapshot_time_ms=?
                         AND symbol=? AND snapshot_type=?""",
                    (self.run_id, now_ms, c.symbol, snapshot_type),
                ).fetchone()
                if existing:
                    snapshot_id = str(existing["id"])
            elif snapshot_type in {"TRIGGERED", "SHADOW", "NEAR_MISS", "MC3_SHADOW", "MC5_CHALLENGER", "MC7_SHADOW"}:
                entry_status = (
                    "EXECUTABLE"
                    if b.buy_vwap is not None
                    else "NO_EXECUTABLE_ENTRY"
                )
                self.conn.execute(
                    """INSERT OR IGNORE INTO snapshot_outcomes(
                         snapshot_id,episode_id,symbol,snapshot_type,created_at_ms,
                         entry_vwap,entry_status,position_usdt,fee_rate,completed,
                         completion_reason
                       ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                    (
                        snapshot_id,
                        episode_id,
                        c.symbol,
                        snapshot_type,
                        now_ms,
                        b.buy_vwap,
                        entry_status,
                        self.settings.position_usdt,
                        self.settings.fee_rate,
                        int(b.buy_vwap is None),
                        (
                            "NO_EXECUTABLE_ENTRY"
                            if b.buy_vwap is None
                            else None
                        ),
                    ),
                )
            self.conn.commit()
        return snapshot_id

    def create_slot(
        self,
        source_snapshot_id: str,
        symbol: str,
        event_id: str,
        now_ms: int,
        best_ask: float,
        entry_vwap: float,
        episode_id: Optional[str] = None,
    ) -> str:
        slot_id = str(uuid.uuid4())
        quantity = self.settings.position_usdt / entry_vwap
        entry_fee = self.settings.position_usdt * self.settings.fee_rate
        with self.lock:
            self.conn.execute(
                """INSERT INTO paper_slots(
                  id,run_id,source_snapshot_id,event_id,symbol,opened_at_ms,entry_best_ask,entry_vwap,
                  position_usdt,quantity,entry_fee_usdt,baseline_status,last_updated_at_ms,
                  algorithm_version,strategy_version,config_hash,episode_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?,?,?,?)""",
                (slot_id, self.run_id, source_snapshot_id, event_id, symbol, now_ms, best_ask, entry_vwap,
                 self.settings.position_usdt, quantity, entry_fee, now_ms, self.settings.algorithm_version,
                 self.settings.strategy_version, self.settings.config_hash(),
                 episode_id or event_id),
            )
            for policy in ("A_PARTIAL_20", "B_FULL_PROTECTED", "C_WEAKENING", "D_TARGET1_HOLD_300"):
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

    def pending_snapshot_outcomes(self) -> list[sqlite3.Row]:
        with self.lock:
            return list(
                self.conn.execute(
                    """SELECT * FROM snapshot_outcomes
                       WHERE completed=0
                       ORDER BY created_at_ms"""
                )
            )

    def pending_snapshot_symbols(self) -> set[str]:
        with self.lock:
            return {
                str(row["symbol"])
                for row in self.conn.execute(
                    "SELECT DISTINCT symbol FROM snapshot_outcomes WHERE completed=0"
                )
            }

    def record_snapshot_observation(
        self,
        snapshot_id: str,
        current_return: float,
        now_ms: int,
    ) -> None:
        self.record_snapshot_observations(
            [(snapshot_id, current_return)],
            now_ms,
        )

    def record_snapshot_observations(
        self,
        observations: list[tuple[str, float]],
        now_ms: int,
    ) -> None:
        horizons = (5, 15, 30, 60, 90, 120, 150, 180, 240, 300, 600, 1200)
        with self.lock:
            for snapshot_id, current_return in observations:
                row = self.conn.execute(
                    "SELECT * FROM snapshot_outcomes WHERE snapshot_id=?",
                    (snapshot_id,),
                ).fetchone()
                if row is None or row["completed"]:
                    continue
                mfe = max(float(row["mfe_percent"]), current_return)
                mae = min(float(row["mae_percent"]), current_return)
                age_ms = max(0, now_ms - int(row["created_at_ms"]))
                updates: dict[str, Any] = {
                    "last_observed_at_ms": now_ms,
                    "mfe_percent": mfe,
                    "mae_percent": mae,
                }
                for seconds in horizons:
                    return_column = f"return_{seconds}s"
                    if (
                        age_ms >= seconds * 1_000
                        and row[return_column] is None
                    ):
                        updates[return_column] = current_return
                        updates[f"mfe_{seconds}s"] = mfe
                        updates[f"mae_{seconds}s"] = mae
                if row["first_barrier"] is None:
                    if current_return >= 1.0:
                        updates["first_barrier"] = "TARGET_1"
                        updates["first_barrier_at_ms"] = now_ms
                    elif current_return <= -self.settings.initial_stop_percent:
                        updates["first_barrier"] = "STOP_0_75"
                        updates["first_barrier_at_ms"] = now_ms
                if age_ms >= 1_200_000 and (
                    row["return_1200s"] is not None
                    or "return_1200s" in updates
                ):
                    updates["completed"] = 1
                    updates["completion_reason"] = "HORIZON_1200S"
                assignments = ",".join(f"{name}=?" for name in updates)
                self.conn.execute(
                    f"""UPDATE snapshot_outcomes
                        SET {assignments}
                        WHERE snapshot_id=?""",
                    (*updates.values(), snapshot_id),
                )
            if observations:
                self.conn.commit()

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

    def create_momentum_slot(
        self,
        source_snapshot_id: str,
        symbol: str,
        episode_id: str,
        now_ms: int,
        best_ask: float,
        entry_vwap: float,
        signal_channel: str = "MC5",
    ) -> str:
        slot_id = str(uuid.uuid4())
        quantity = self.settings.position_usdt / entry_vwap
        entry_fee = self.settings.position_usdt * self.settings.fee_rate
        with self.lock:
            self.conn.execute(
                """INSERT INTO momentum_slots(
                  id,run_id,source_snapshot_id,signal_channel,episode_id,symbol,opened_at_ms,
                  entry_best_ask,entry_vwap,position_usdt,quantity,entry_fee_usdt,
                  primary_status,last_updated_at_ms,algorithm_version,
                  strategy_version,config_hash
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?,?,?)""",
                (
                    slot_id,
                    self.run_id,
                    source_snapshot_id,
                    signal_channel,
                    episode_id,
                    symbol,
                    now_ms,
                    best_ask,
                    entry_vwap,
                    self.settings.position_usdt,
                    quantity,
                    entry_fee,
                    now_ms,
                    self.settings.algorithm_version,
                    self.settings.strategy_version,
                    self.settings.config_hash(),
                ),
            )
            for policy in ("MC_HOLD_120", "MC_TRAIL_1P0", "MC_FIXED_TP4"):
                self.conn.execute(
                    "INSERT INTO momentum_policy_runs(id,slot_id,policy,state) VALUES(?,?,?,'OPEN')",
                    (str(uuid.uuid4()), slot_id, policy),
                )
            self.conn.commit()
        return slot_id

    def momentum_policy_slots(self) -> list[sqlite3.Row]:
        with self.lock:
            return list(
                self.conn.execute(
                    """SELECT * FROM momentum_slots
                       WHERE id IN (
                         SELECT DISTINCT slot_id FROM momentum_policy_runs
                         WHERE state='OPEN'
                       )
                       ORDER BY opened_at_ms"""
                )
            )

    def momentum_primary_open_slot(self) -> Optional[sqlite3.Row]:
        with self.lock:
            return self.conn.execute(
                """SELECT * FROM momentum_slots
                   WHERE primary_status='OPEN'
                   ORDER BY opened_at_ms LIMIT 1"""
            ).fetchone()

    def pending_momentum_symbols(self) -> set[str]:
        with self.lock:
            return {
                str(row["symbol"])
                for row in self.conn.execute(
                    """SELECT DISTINCT ms.symbol
                       FROM momentum_slots ms
                       JOIN momentum_policy_runs mp ON mp.slot_id=ms.id
                       WHERE mp.state='OPEN'"""
                )
            }

    def last_momentum_slot_for_symbol(self, symbol: str) -> Optional[sqlite3.Row]:
        with self.lock:
            return self.conn.execute(
                """SELECT * FROM momentum_slots
                   WHERE symbol=? ORDER BY opened_at_ms DESC LIMIT 1""",
                (symbol,),
            ).fetchone()

    def momentum_policies_for_slot(self, slot_id: str) -> list[sqlite3.Row]:
        with self.lock:
            return list(
                self.conn.execute(
                    """SELECT * FROM momentum_policy_runs
                       WHERE slot_id=? ORDER BY policy""",
                    (slot_id,),
                )
            )

    def update_momentum_slot_extremes(
        self, slot_id: str, current_return: float, now_ms: int
    ) -> None:
        with self.lock:
            self.conn.execute(
                """UPDATE momentum_slots SET
                   max_executable_return_percent=MAX(max_executable_return_percent,?),
                   min_executable_return_percent=MIN(min_executable_return_percent,?),
                   last_updated_at_ms=? WHERE id=?""",
                (current_return, current_return, now_ms, slot_id),
            )
            self.conn.commit()

    def update_momentum_policy(self, policy_id: str, **fields: Any) -> None:
        if not fields:
            return
        assignments = ",".join(f"{name}=?" for name in fields)
        with self.lock:
            self.conn.execute(
                f"UPDATE momentum_policy_runs SET {assignments} WHERE id=?",
                (*fields.values(), policy_id),
            )
            self.conn.commit()

    def close_momentum_primary(
        self,
        slot_id: str,
        now_ms: int,
        reason: str,
        exit_vwap: float,
        gross: float,
        net: float,
    ) -> None:
        with self.lock:
            self.conn.execute(
                """UPDATE momentum_slots SET primary_status='CLOSED',
                   primary_closed_at_ms=?, primary_exit_reason=?,
                   primary_exit_vwap=?, primary_gross_return_percent=?,
                   primary_net_return_percent=?, last_updated_at_ms=?
                   WHERE id=?""",
                (now_ms, reason, exit_vwap, gross, net, now_ms, slot_id),
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
            shadow_audit = self.conn.execute(
                """SELECT
                     COUNT(DISTINCT episode_id) AS episodes,
                     COALESCE(SUM(momentum_persistent=1), 0)
                       AS momentum_persistent_ticks,
                     COALESCE(SUM(experimental_shadow_passed=1), 0)
                       AS experimental_pass_ticks
                   FROM snapshots
                   WHERE run_id=? AND episode_id IS NOT NULL""",
                (self.run_id,),
            ).fetchone()
            outcome_counts = self.conn.execute(
                """SELECT
                     COALESCE(SUM(so.completed=0), 0) AS pending,
                     COALESCE(SUM(so.completed=1), 0) AS completed
                   FROM snapshot_outcomes so
                   JOIN snapshots s ON s.id=so.snapshot_id
                   WHERE s.run_id=?""",
                (self.run_id,),
            ).fetchone()
            open_slot = self.baseline_open_slot()
            momentum_slots = self.conn.execute(
                "SELECT COUNT(*) n FROM momentum_slots WHERE run_id=?",
                (self.run_id,),
            ).fetchone()
            open_momentum = self.momentum_primary_open_slot()
            momentum_closed = self.conn.execute(
                """SELECT policy, COUNT(*) AS n,
                          COALESCE(AVG(net_return_percent), 0) AS avg_net
                   FROM momentum_policy_runs
                   WHERE state='CLOSED' GROUP BY policy"""
            ).fetchall()
            return {
                "run_id": self.run_id,
                "snapshots": row["n"] if row else 0,
                "slots": slots["n"] if slots else 0,
                "active_slot": dict(open_slot) if open_slot else None,
                "active_slot_run_id": open_slot["run_id"] if open_slot else None,
                "momentum_slots": int(momentum_slots["n"] if momentum_slots else 0),
                "active_momentum_slot": dict(open_momentum) if open_momentum else None,
                "momentum_challenger": {
                    "primary_policy": self.settings.momentum_primary_policy,
                    "mc3_return_3m": self.settings.momentum_mc3_return_3m,
                    "mc5_return_5m": self.settings.momentum_mc5_return_5m,
                    "mc7_return_10m": self.settings.momentum_mc7_return_10m,
                    "stop_percent": self.settings.momentum_stop_percent,
                    "trail_activation_percent": self.settings.momentum_trail_activation_percent,
                    "trail_drawdown_percent": self.settings.momentum_trail_drawdown_percent,
                    "horizon_seconds": self.settings.momentum_horizon_seconds,
                    "closed_policies": {
                        str(row["policy"]): {
                            "count": int(row["n"]),
                            "avg_net_percent": round(float(row["avg_net"]), 6),
                        }
                        for row in momentum_closed
                    },
                },
                "algorithm_version": self.settings.algorithm_version,
                "strategy_version": self.settings.strategy_version,
                "primary_policy": self.settings.primary_policy,
                "config_hash": self.settings.config_hash(),
                "daily_pnl": self.daily_pnl(),
                "shadow_audit": {
                    "episodes": int(shadow_audit["episodes"] or 0),
                    "momentum_persistent_ticks": int(
                        shadow_audit["momentum_persistent_ticks"] or 0
                    ),
                    "experimental_pass_ticks": int(
                        shadow_audit["experimental_pass_ticks"] or 0
                    ),
                    "outcomes_pending": int(outcome_counts["pending"] or 0),
                    "outcomes_completed": int(outcome_counts["completed"] or 0),
                },
            }

    def daily_pnl(self, now_ms: Optional[int] = None) -> dict[str, Any]:
        """Report each counterfactual policy separately for the local day."""
        now_ms = int(time.time() * 1000) if now_ms is None else now_ms
        offset_ms = self.settings.report_timezone_offset_minutes * 60_000
        day_ms = 24 * 60 * 60 * 1000
        day_start_ms = ((now_ms + offset_ms) // day_ms) * day_ms - offset_ms
        day_end_ms = day_start_ms + day_ms
        policies = {
            name: {"closed_slots": 0, "net_pnl_usdt": 0.0}
            for name in ("A_PARTIAL_20", "B_FULL_PROTECTED", "C_WEAKENING")
        }
        with self.lock:
            rows = self.conn.execute(
                """SELECT pr.policy, COUNT(*) AS closed_slots,
                          COALESCE(SUM(ps.position_usdt * pr.net_return_percent / 100.0), 0)
                          AS net_pnl_usdt
                   FROM policy_runs pr
                   JOIN paper_slots ps ON ps.id=pr.slot_id
                   WHERE pr.state='CLOSED' AND pr.closed_at_ms>=? AND pr.closed_at_ms<?
                   GROUP BY pr.policy""",
                (day_start_ms, day_end_ms),
            )
            for row in rows:
                policies[row["policy"]] = {
                    "closed_slots": int(row["closed_slots"]),
                    "net_pnl_usdt": round(float(row["net_pnl_usdt"]), 8),
                }
        offset = self.settings.report_timezone_offset_minutes
        sign = "+" if offset >= 0 else "-"
        absolute = abs(offset)
        return {
            "timezone": f"UTC{sign}{absolute // 60:02d}:{absolute % 60:02d}",
            "day_start_ms": day_start_ms,
            "day_end_ms": day_end_ms,
            "primary_policy": self.settings.primary_policy,
            "policies": policies,
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
            "momentum_slots",
            "momentum_policy_runs",
            "futures_signals",
            "futures_momentum_slots",
            "futures_momentum_policy_runs",
            "snapshot_outcomes",
            "regime_signals",
            "regime_slots",
            "regime_snapshots",
            "regime_snapshot_outcomes",
            "short_signal_research",
            "short_exit_challengers",
            "short_failure_labels",
            "exit_window_samples",
            "exit_policy_outcomes",
            "dump_health_diagnostics",
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
            available_tables = {
                str(row[0])
                for row in snapshot.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                )
            }
            for table in tables:
                if table not in available_tables:
                    continue
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
            "schema_version": 8,
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
        try:
            temporary_link.symlink_to(output_dir.name)
            os.replace(temporary_link, latest)
        except OSError:
            # Windows validation environments may not grant symlink rights.
            # The export itself remains complete; Linux deployments retain the
            # atomic `latest` symlink behavior above.
            temporary_link.unlink(missing_ok=True)
        self._prune_exports(latest.parent, output_dir)
        return output_dir
