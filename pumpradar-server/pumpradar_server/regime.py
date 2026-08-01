from __future__ import annotations

import json
import logging
import time
import uuid
from dataclasses import dataclass
from typing import Any, Optional

from .config import Settings
from .futures import FuturesMarketState
from .models import Candidate, EvaluatedCandidate
from .storage import Storage
from .v453_research import ShortResearchManager

LOG = logging.getLogger(__name__)

SHORT_CHANNEL = "REV_MC5_SHORT_600_2X"
ONSET_CHANNEL = "ONSET_LONG_SHADOW"
DUMP_CHANNEL = "DUMP_EXHAUSTION_LONG_SHADOW"

OPENED = "OPENED"
RETRY = "RETRY"
SKIPPED = "SKIPPED"
REGIME_OUTCOME_HORIZONS = (
    5, 15, 30, 60, 90, 120, 150, 180, 240, 300, 600, 900, 1200, 1800, 3600
)
REGIME_OUTCOME_MAX_AGE_SECONDS = 75 * 60
INVALID_V450_ALGORITHM = "4.5.0-server"
INVALID_V450_STRATEGY = "REGIME-MC5-SHORT2X+ONSET+DUMP-LONG-2026-07"

REGIME_SCHEMA = """
CREATE TABLE IF NOT EXISTS regime_signals (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  channel TEXT NOT NULL,
  side TEXT NOT NULL,
  source_market TEXT NOT NULL,
  symbol TEXT NOT NULL,
  episode_id TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  disposition TEXT NOT NULL,
  reason TEXT,
  trigger_json TEXT NOT NULL,
  algorithm_version TEXT NOT NULL,
  strategy_version TEXT NOT NULL,
  config_hash TEXT NOT NULL,
  UNIQUE(run_id, channel, episode_id)
);
CREATE INDEX IF NOT EXISTS idx_regime_signals_time
  ON regime_signals(created_at_ms);
CREATE INDEX IF NOT EXISTS idx_regime_signals_channel
  ON regime_signals(channel, disposition, created_at_ms);

CREATE TABLE IF NOT EXISTS regime_slots (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  signal_id TEXT NOT NULL REFERENCES regime_signals(id),
  channel TEXT NOT NULL,
  side TEXT NOT NULL,
  symbol TEXT NOT NULL,
  opened_at_ms INTEGER NOT NULL,
  margin_usdt REAL NOT NULL,
  leverage REAL NOT NULL,
  notional_usdt REAL NOT NULL,
  quantity REAL NOT NULL,
  entry_vwap REAL NOT NULL,
  entry_fee_usdt REAL NOT NULL,
  entry_mark_price REAL,
  funding_rate REAL NOT NULL DEFAULT 0,
  next_funding_time_ms INTEGER NOT NULL DEFAULT 0,
  stop_price_percent REAL NOT NULL,
  hold_seconds INTEGER NOT NULL,
  status TEXT NOT NULL,
  closed_at_ms INTEGER,
  exit_reason TEXT,
  exit_vwap REAL,
  exit_fee_usdt REAL,
  funding_pnl_usdt REAL NOT NULL DEFAULT 0,
  gross_pnl_usdt REAL,
  net_pnl_usdt REAL,
  gross_return_margin_percent REAL,
  net_return_margin_percent REAL,
  directional_price_return_percent REAL,
  max_directional_return_percent REAL NOT NULL DEFAULT 0,
  min_directional_return_percent REAL NOT NULL DEFAULT 0,
  stop_triggered_at_ms INTEGER,
  stop_trigger_price REAL,
  stop_trigger_return_percent REAL,
  stop_market_overshoot_points REAL,
  stop_execution_impact_points REAL,
  stop_total_slippage_points REAL,
  stop_depth_age_ms INTEGER,
  last_updated_at_ms INTEGER NOT NULL,
  algorithm_version TEXT NOT NULL,
  strategy_version TEXT NOT NULL,
  config_hash TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_regime_slots_open
  ON regime_slots(status, channel, opened_at_ms);
CREATE INDEX IF NOT EXISTS idx_regime_slots_symbol
  ON regime_slots(channel, symbol, opened_at_ms);

CREATE TABLE IF NOT EXISTS regime_snapshots (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  signal_id TEXT NOT NULL UNIQUE REFERENCES regime_signals(id) ON DELETE CASCADE,
  channel TEXT NOT NULL,
  side TEXT NOT NULL,
  source_market TEXT NOT NULL,
  execution_market TEXT NOT NULL DEFAULT 'FUTURES',
  symbol TEXT NOT NULL,
  episode_id TEXT NOT NULL,
  snapshot_time_ms INTEGER NOT NULL,
  metrics_json TEXT NOT NULL,
  entry_status TEXT NOT NULL,
  entry_reference_price REAL,
  entry_best_bid REAL,
  entry_best_ask REAL,
  entry_depth_age_ms INTEGER,
  entry_vwap REAL,
  entry_quantity REAL,
  entry_notional_usdt REAL,
  entry_slippage_percent REAL,
  leverage REAL NOT NULL,
  margin_usdt REAL NOT NULL,
  stop_price_percent REAL NOT NULL,
  hold_seconds INTEGER NOT NULL,
  algorithm_version TEXT NOT NULL,
  strategy_version TEXT NOT NULL,
  config_hash TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_regime_snapshots_time
  ON regime_snapshots(snapshot_time_ms);
CREATE INDEX IF NOT EXISTS idx_regime_snapshots_channel
  ON regime_snapshots(channel, snapshot_time_ms);

CREATE TABLE IF NOT EXISTS regime_snapshot_outcomes (
  snapshot_id TEXT PRIMARY KEY REFERENCES regime_snapshots(id) ON DELETE CASCADE,
  signal_id TEXT NOT NULL,
  channel TEXT NOT NULL,
  side TEXT NOT NULL,
  symbol TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  entry_status TEXT NOT NULL,
  entry_vwap REAL,
  quantity REAL,
  position_usdt REAL NOT NULL,
  leverage REAL NOT NULL,
  margin_usdt REAL NOT NULL,
  fee_rate REAL NOT NULL,
  stop_price_percent REAL NOT NULL,
  hold_seconds INTEGER NOT NULL,
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
  return_600s REAL, mfe_600s REAL, mae_600s REAL,
  return_900s REAL, mfe_900s REAL, mae_900s REAL,
  return_1200s REAL, mfe_1200s REAL, mae_1200s REAL,
  return_1800s REAL, mfe_1800s REAL, mae_1800s REAL,
  return_3600s REAL, mfe_3600s REAL, mae_3600s REAL,
  first_barrier TEXT,
  first_barrier_at_ms INTEGER,
  completed INTEGER NOT NULL DEFAULT 0,
  completion_reason TEXT
);
CREATE INDEX IF NOT EXISTS idx_regime_outcomes_pending
  ON regime_snapshot_outcomes(completed, created_at_ms);
CREATE INDEX IF NOT EXISTS idx_regime_outcomes_channel
  ON regime_snapshot_outcomes(channel, completed, created_at_ms);
"""

REGIME_SLOT_AUDIT_COLUMNS = {
    "stop_triggered_at_ms": "INTEGER",
    "stop_trigger_price": "REAL",
    "stop_trigger_return_percent": "REAL",
    "stop_market_overshoot_points": "REAL",
    "stop_execution_impact_points": "REAL",
    "stop_total_slippage_points": "REAL",
    "stop_depth_age_ms": "INTEGER",
}

REGIME_SNAPSHOT_COLUMNS = {
    "execution_market": "TEXT NOT NULL DEFAULT 'FUTURES'",
    "entry_reference_price": "REAL",
    "entry_best_bid": "REAL",
    "entry_best_ask": "REAL",
    "entry_depth_age_ms": "INTEGER",
    "leverage": "REAL NOT NULL DEFAULT 1",
    "margin_usdt": "REAL NOT NULL DEFAULT 20",
}

REGIME_OUTCOME_COLUMNS = {
    "entry_status": "TEXT NOT NULL DEFAULT 'EXECUTABLE'",
    "leverage": "REAL NOT NULL DEFAULT 1",
    "margin_usdt": "REAL NOT NULL DEFAULT 20",
    "return_600s": "REAL",
    "mfe_600s": "REAL",
    "mae_600s": "REAL",
    "return_900s": "REAL",
    "mfe_900s": "REAL",
    "mae_900s": "REAL",
    "return_1200s": "REAL",
    "mfe_1200s": "REAL",
    "mae_1200s": "REAL",
    "return_1800s": "REAL",
    "mfe_1800s": "REAL",
    "mae_1800s": "REAL",
    "return_3600s": "REAL",
    "mfe_3600s": "REAL",
    "mae_3600s": "REAL",
}


@dataclass(frozen=True)
class EntryExecution:
    entry_vwap: float
    quantity: float
    actual_notional: float
    slippage_percent: float
    best_bid: float
    best_ask: float
    depth_age_ms: int

    def reference_price(self, side: str) -> float:
        return self.best_ask if side == "LONG" else self.best_bid


@dataclass(frozen=True)
class SlotOpenResult:
    status: str
    slot_id: Optional[str] = None


@dataclass
class PendingShort:
    signal_id: str
    episode_id: str
    created_at_ms: int
    expires_at_ms: int
    spot_trigger: dict[str, Any]
    snapshot_id: Optional[str] = None


@dataclass
class OnsetTracker:
    episode_id: str
    first_seen_ms: int
    last_seen_ms: int
    confirm_ticks: int = 0
    best_score: int = 0


@dataclass
class DumpTracker:
    episode_id: str
    first_seen_ms: int
    last_seen_ms: int
    low_price: float
    low_at_ms: int
    initial_sell_impact: float
    confirm_ticks: int = 0
    worst_return_60s: float = 0.0


def directional_return(side: str, entry: float, exit_price: float) -> float:
    if entry <= 0 or exit_price <= 0:
        return 0.0
    if side == "SHORT":
        return (entry - exit_price) / entry * 100.0
    return (exit_price / entry - 1.0) * 100.0


def trade_pnl(
    side: str,
    entry: float,
    exit_price: float,
    quantity: float,
    entry_fee: float,
    exit_fee: float,
    funding_pnl: float,
    margin: float,
) -> tuple[float, float, float, float]:
    if side == "SHORT":
        gross = (entry - exit_price) * quantity
    else:
        gross = (exit_price - entry) * quantity
    net = gross - entry_fee - exit_fee + funding_pnl
    gross_pct = gross / margin * 100.0 if margin > 0 else 0.0
    net_pct = net / margin * 100.0 if margin > 0 else 0.0
    return gross, net, gross_pct, net_pct


class RegimePaperManager:
    """Three lightweight, public-data-only paper channels.

    - spot MC5 exhaustion -> 2x futures short, 600 seconds;
    - early futures acceleration -> 1x onset long shadow, 300 seconds;
    - futures dump capitulation + reclaim -> 1x long shadow, 600 seconds.

    Paper slots are capped per channel, while every emitted signal is recorded
    independently with a snapshot and side-aware futures outcomes. No exchange order
    endpoint, API key, signed request, or leverage-setting request is used.
    """

    def __init__(
        self,
        settings: Settings,
        state: FuturesMarketState,
        storage: Storage,
        notify,
    ) -> None:
        self.settings = settings
        self.state = state
        self.storage = storage
        self.notify = notify
        self.conn = storage.conn
        self.lock = storage.lock
        self.pending_shorts: dict[str, PendingShort] = {}
        self.onset_trackers: dict[str, OnsetTracker] = {}
        self.dump_trackers: dict[str, DumpTracker] = {}
        self.extremes: dict[str, tuple[float, float]] = {}
        with self.lock:
            self.conn.executescript(REGIME_SCHEMA)
            self._ensure_columns("regime_slots", REGIME_SLOT_AUDIT_COLUMNS)
            self._ensure_columns("regime_snapshots", REGIME_SNAPSHOT_COLUMNS)
            self._ensure_columns("regime_snapshot_outcomes", REGIME_OUTCOME_COLUMNS)
            self.conn.commit()
        self.short_research = ShortResearchManager(settings, self.conn, self.lock)

    def _ensure_columns(self, table: str, columns: dict[str, str]) -> None:
        existing = {
            str(row["name"])
            for row in self.conn.execute(f"PRAGMA table_info({table})")
        }
        for name, declaration in columns.items():
            if name not in existing:
                self.conn.execute(
                    f"ALTER TABLE {table} ADD COLUMN {name} {declaration}"
                )

    @staticmethod
    def pre_onset_candidate(candidate: Candidate) -> bool:
        r15 = candidate.return_15s or 0.0
        r60 = candidate.return_60s or 0.0
        r5m = candidate.return_5m
        accel = candidate.acceleration or 0.0
        return (
            (r15 >= 0.20 or r60 >= 0.45 or accel >= 0.20)
            and (r5m is None or r5m <= 3.0)
        )

    @staticmethod
    def pre_dump_candidate(candidate: Candidate) -> bool:
        return any(
            (
                (candidate.return_15s or 0.0) <= -0.30,
                (candidate.return_60s or 0.0) <= -0.75,
                (candidate.return_5m or 0.0) <= -1.80,
                (candidate.acceleration or 0.0) <= -0.25,
            )
        )

    def pending_symbols(self) -> set[str]:
        symbols = set(self.pending_shorts)
        symbols.update(self.onset_trackers)
        symbols.update(self.dump_trackers)
        version = (
            self.settings.algorithm_version,
            self.settings.strategy_version,
            self.settings.config_hash(),
        )
        with self.lock:
            symbols.update(
                str(row["symbol"])
                for row in self.conn.execute(
                    """SELECT DISTINCT symbol FROM regime_slots
                       WHERE status='OPEN'"""
                )
            )
            symbols.update(
                str(row["symbol"])
                for row in self.conn.execute(
                    """SELECT DISTINCT o.symbol
                       FROM regime_snapshot_outcomes o
                       WHERE o.completed=0"""
                )
            )
        return symbols

    def recover_incompatible(self, now_ms: int) -> None:
        version = (
            self.settings.algorithm_version,
            self.settings.strategy_version,
            self.settings.config_hash(),
        )
        with self.lock:
            self.conn.execute(
                """UPDATE regime_slots
                   SET status='INVALIDATED', closed_at_ms=?,
                       exit_reason='CONFIG_CHANGED', last_updated_at_ms=?
                   WHERE status='OPEN' AND
                     (algorithm_version<>? OR strategy_version<>? OR config_hash<>?)
                     AND NOT (
                       algorithm_version='4.5.1-server'
                       AND channel IN (
                         'REV_MC5_SHORT_600_2X',
                         'ONSET_LONG_SHADOW',
                         'DUMP_EXHAUSTION_LONG_SHADOW'
                       )
                     )""",
                (now_ms, now_ms, *version),
            )
            self.conn.execute(
                """UPDATE regime_signals
                   SET disposition='INVALIDATED', reason='CONFIG_CHANGED'
                   WHERE disposition='PENDING' AND
                     (algorithm_version<>? OR strategy_version<>? OR config_hash<>?)""",
                version,
            )
            self.conn.execute(
                """UPDATE regime_snapshot_outcomes
                   SET completed=1, completion_reason='CONFIG_CHANGED',
                       last_observed_at_ms=COALESCE(last_observed_at_ms,?)
                   WHERE completed=0 AND snapshot_id IN (
                     SELECT id FROM regime_snapshots
                     WHERE algorithm_version<>? OR strategy_version<>? OR config_hash<>?
                   )
                   AND signal_id NOT IN (
                     SELECT id FROM regime_signals
                     WHERE algorithm_version='4.5.1-server'
                       AND channel IN (
                         'REV_MC5_SHORT_600_2X',
                         'ONSET_LONG_SHADOW',
                         'DUMP_EXHAUSTION_LONG_SHADOW'
                       )
                   )""",
                (now_ms, *version),
            )
            self.conn.execute(
                """UPDATE regime_signals
                   SET disposition='EXPIRED', reason='SERVICE_RESTART_BEFORE_ENTRY'
                   WHERE disposition='PENDING' AND run_id<>?""",
                (self.storage.run_id,),
            )
            invalidated_runs = self.conn.execute(
                """UPDATE experiment_runs
                   SET status='INVALID_TECHNICAL_RUN',
                       ended_at_ms=COALESCE(ended_at_ms,?)
                   WHERE algorithm_version=? AND strategy_version=?
                     AND status<>'INVALID_TECHNICAL_RUN'""",
                (now_ms, INVALID_V450_ALGORITHM, INVALID_V450_STRATEGY),
            ).rowcount
            if invalidated_runs:
                self.conn.execute(
                    """INSERT INTO service_events(timestamp_ms,severity,subsystem,message)
                       VALUES(?,?,?,?)""",
                    (
                        now_ms,
                        "WARNING",
                        "regime_migration",
                        f"Marked {invalidated_runs} v4.5.0 run(s) INVALID_TECHNICAL_RUN",
                    ),
                )
            self.conn.commit()

    def _metrics(self, item: EvaluatedCandidate) -> dict[str, Any]:
        c, f, b = item.candidate, item.flow, item.book
        mark = self.state.mark_info(c.symbol)
        return {
            "return_15s": c.return_15s,
            "return_60s": c.return_60s,
            "return_3m": c.return_3m,
            "return_5m": c.return_5m,
            "return_10m": c.return_10m,
            "acceleration": c.acceleration,
            "relative_strength_vs_btc": c.relative_strength_vs_btc,
            "quote_volume_24h": c.quote_volume_24h,
            "quote_volume_30s": f.quote_volume_30s,
            "trade_count_30s": f.trade_count_30s,
            "trades_per_second": f.trades_per_second,
            "volume_z_30s": f.volume_z_30s,
            "taker_buy_ratio_30s": f.taker_buy_ratio_30s,
            "taker_buy_ratio_15s": f.taker_buy_ratio_15s,
            "taker_buy_ratio_5s": f.taker_buy_ratio_5s,
            "cvd_30s": f.cvd_30s,
            "cvd_15s": f.cvd_15s,
            "cvd_5s": f.cvd_5s,
            "cvd_slope": f.cvd_slope,
            "spread_bps": b.spread_bps,
            "obi_10": b.obi_10,
            "obi_20": b.obi_20,
            "buy_slippage_percent": b.buy_slippage_percent,
            "sell_slippage_percent": b.sell_slippage_percent,
            "depth_age_ms": b.depth_age_ms,
            "mark_price": mark.mark_price,
            "funding_rate": mark.funding_rate,
            "next_funding_time_ms": mark.next_funding_time_ms,
        }

    def _insert_signal(
        self,
        channel: str,
        side: str,
        source_market: str,
        symbol: str,
        episode_id: str,
        now_ms: int,
        trigger: dict[str, Any],
        disposition: str = "PENDING",
        reason: Optional[str] = None,
    ) -> str:
        with self.lock:
            existing = self.conn.execute(
                """SELECT id FROM regime_signals
                   WHERE run_id=? AND channel=? AND episode_id=?""",
                (self.storage.run_id, channel, episode_id),
            ).fetchone()
            if existing:
                return str(existing["id"])
            signal_id = str(uuid.uuid4())
            self.conn.execute(
                """INSERT INTO regime_signals(
                   id,run_id,channel,side,source_market,symbol,episode_id,
                   created_at_ms,disposition,reason,trigger_json,
                   algorithm_version,strategy_version,config_hash
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    signal_id,
                    self.storage.run_id,
                    channel,
                    side,
                    source_market,
                    symbol,
                    episode_id,
                    now_ms,
                    disposition,
                    reason,
                    json.dumps(trigger, sort_keys=True, separators=(",", ":")),
                    self.settings.algorithm_version,
                    self.settings.strategy_version,
                    self.settings.config_hash(),
                ),
            )
            self.conn.commit()
            return signal_id

    def _set_signal(self, signal_id: str, disposition: str, reason: Optional[str] = None) -> None:
        with self.lock:
            self.conn.execute(
                "UPDATE regime_signals SET disposition=?, reason=? WHERE id=?",
                (disposition, reason, signal_id),
            )
            self.conn.commit()

    def _signal_disposition(self, signal_id: str) -> Optional[str]:
        with self.lock:
            row = self.conn.execute(
                "SELECT disposition FROM regime_signals WHERE id=?",
                (signal_id,),
            ).fetchone()
        return str(row["disposition"]) if row else None

    def signal_short_from_spot(
        self,
        item: EvaluatedCandidate,
        episode_id: str,
        now_ms: int,
    ) -> None:
        symbol = item.candidate.symbol
        spot_trigger = self._metrics(item)
        if symbol not in self.state.universe:
            signal_id = self._insert_signal(
                SHORT_CHANNEL,
                "SHORT",
                "SPOT",
                symbol,
                episode_id,
                now_ms,
                {"spot_mc5": spot_trigger},
                disposition="SKIPPED",
                reason="FUTURES_SYMBOL_UNAVAILABLE",
            )
            self._insert_regime_snapshot(
                signal_id,
                SHORT_CHANNEL,
                "SHORT",
                symbol,
                now_ms,
                self.settings.regime_short_leverage,
                self.settings.regime_short_stop_percent,
                self.settings.regime_short_hold_seconds,
                None,
                None,
            )
            return
        signal_id = self._insert_signal(
            SHORT_CHANNEL,
            "SHORT",
            "SPOT",
            symbol,
            episode_id,
            now_ms,
            {"spot_mc5": spot_trigger},
        )
        previous = self.pending_shorts.get(symbol)
        if previous is not None and previous.signal_id != signal_id:
            if previous.snapshot_id is None:
                previous.snapshot_id = self._record_no_entry_snapshot_for_pending(
                    previous,
                    symbol,
                    now_ms,
                )
            self._set_signal(
                previous.signal_id,
                "EXPIRED",
                "SUPERSEDED_BY_NEW_SIGNAL",
            )
        self.pending_shorts[symbol] = PendingShort(
            signal_id=signal_id,
            episode_id=episode_id,
            created_at_ms=now_ms,
            expires_at_ms=now_ms + self.settings.regime_short_entry_wait_seconds * 1000,
            spot_trigger=spot_trigger,
        )

    def _channel_open_count(self, channel: str) -> int:
        with self.lock:
            row = self.conn.execute(
                "SELECT COUNT(*) AS n FROM regime_slots WHERE channel=? AND status='OPEN'",
                (channel,),
            ).fetchone()
        return int(row["n"] if row else 0)

    def _repeat_blocked(self, channel: str, symbol: str, now_ms: int) -> bool:
        with self.lock:
            row = self.conn.execute(
                """SELECT opened_at_ms FROM regime_slots
                   WHERE channel=? AND symbol=?
                     AND algorithm_version=? AND strategy_version=? AND config_hash=?
                   ORDER BY opened_at_ms DESC LIMIT 1""",
                (
                    channel,
                    symbol,
                    self.settings.algorithm_version,
                    self.settings.strategy_version,
                    self.settings.config_hash(),
                ),
            ).fetchone()
        return bool(
            row
            and now_ms - int(row["opened_at_ms"])
            < self.settings.regime_repeat_symbol_minutes * 60_000
        )

    def _fresh_depth(self, symbol: str, now_ms: int):
        row = self.state.depth.get(symbol)
        if not row or not row.bids or not row.asks:
            return None
        if now_ms - row.updated_at_ms > self.settings.max_feed_age_ms:
            return None
        return row

    @staticmethod
    def _buy_quote_vwap(levels: list[tuple[float, float]], quote_amount: float):
        remaining = quote_amount
        spent = 0.0
        qty = 0.0
        for price, available_qty in levels:
            level_quote = price * available_qty
            take_quote = min(remaining, level_quote)
            if take_quote <= 0:
                break
            spent += take_quote
            qty += take_quote / price
            remaining -= take_quote
            if remaining <= 1e-9:
                break
        if remaining > 1e-6 or qty <= 0:
            return None
        return spent / qty, qty, spent

    @staticmethod
    def _buy_qty_vwap(levels: list[tuple[float, float]], quantity: float):
        remaining = quantity
        cost = 0.0
        bought = 0.0
        for price, available_qty in levels:
            take = min(remaining, available_qty)
            if take <= 0:
                break
            cost += take * price
            bought += take
            remaining -= take
            if remaining <= 1e-9:
                break
        if remaining > 1e-8 or bought <= 0:
            return None
        return cost / bought, cost

    @staticmethod
    def _sell_qty_vwap(levels: list[tuple[float, float]], quantity: float):
        remaining = quantity
        received = 0.0
        sold = 0.0
        for price, available_qty in levels:
            take = min(remaining, available_qty)
            if take <= 0:
                break
            received += take * price
            sold += take
            remaining -= take
            if remaining <= 1e-9:
                break
        if remaining > 1e-8 or sold <= 0:
            return None
        return received / sold, received

    def _entry_execution(
        self,
        side: str,
        symbol: str,
        leverage: float,
        now_ms: int,
    ) -> Optional[EntryExecution]:
        depth = self._fresh_depth(symbol, now_ms)
        if depth is None:
            return None
        best_bid, best_ask = depth.bids[0][0], depth.asks[0][0]
        target_notional = self.settings.regime_margin_usdt * leverage
        if side == "LONG":
            result = self._buy_quote_vwap(depth.asks, target_notional)
            if result is None:
                return None
            entry_vwap, quantity, actual_notional = result
            slippage = (entry_vwap / best_ask - 1.0) * 100.0
        else:
            quantity = target_notional / best_bid
            result = self._sell_qty_vwap(depth.bids, quantity)
            if result is None:
                return None
            entry_vwap, actual_notional = result
            quantity = target_notional / entry_vwap
            result = self._sell_qty_vwap(depth.bids, quantity)
            if result is None:
                return None
            entry_vwap, actual_notional = result
            slippage = (1.0 - entry_vwap / best_bid) * 100.0
        return EntryExecution(
            entry_vwap=entry_vwap,
            quantity=quantity,
            actual_notional=actual_notional,
            slippage_percent=slippage,
            best_bid=best_bid,
            best_ask=best_ask,
            depth_age_ms=max(0, now_ms - int(depth.updated_at_ms)),
        )

    def _snapshot_entry_status(
        self,
        execution: Optional[EntryExecution],
    ) -> str:
        if execution is None:
            return "NO_EXECUTABLE_ENTRY"
        mid = (execution.best_bid + execution.best_ask) / 2.0
        spread_bps = (
            (execution.best_ask - execution.best_bid) / mid * 10_000
            if mid > 0
            else 999.0
        )
        if spread_bps > self.settings.regime_max_spread_bps:
            return "SPREAD_TOO_WIDE"
        if execution.slippage_percent > self.settings.regime_max_slippage_percent:
            return "SLIPPAGE_TOO_HIGH"
        return "EXECUTABLE"

    def _record_regime_snapshot(
        self,
        signal_id: str,
        channel: str,
        side: str,
        item: EvaluatedCandidate,
        now_ms: int,
        leverage: float,
        stop_pct: float,
        hold_seconds: int,
        *,
        allow_no_entry: bool,
    ) -> tuple[Optional[str], Optional[EntryExecution]]:
        with self.lock:
            existing = self.conn.execute(
                """SELECT id,entry_status,entry_reference_price,entry_best_bid,
                          entry_best_ask,entry_depth_age_ms,entry_vwap,entry_quantity,
                          entry_notional_usdt,entry_slippage_percent
                   FROM regime_snapshots WHERE signal_id=?""",
                (signal_id,),
            ).fetchone()
        if existing:
            if existing["entry_vwap"] is None:
                return str(existing["id"]), None
            reference = float(existing["entry_reference_price"] or existing["entry_vwap"])
            best_bid = float(existing["entry_best_bid"] or reference)
            best_ask = float(existing["entry_best_ask"] or reference)
            return str(existing["id"]), EntryExecution(
                entry_vwap=float(existing["entry_vwap"]),
                quantity=float(existing["entry_quantity"]),
                actual_notional=float(existing["entry_notional_usdt"]),
                slippage_percent=float(existing["entry_slippage_percent"] or 0.0),
                best_bid=best_bid,
                best_ask=best_ask,
                depth_age_ms=int(existing["entry_depth_age_ms"] or 0),
            )

        execution = self._entry_execution(
            side,
            item.candidate.symbol,
            leverage,
            now_ms,
        )
        if execution is None and not allow_no_entry:
            return None, None
        return self._insert_regime_snapshot(
            signal_id,
            channel,
            side,
            item.candidate.symbol,
            now_ms,
            leverage,
            stop_pct,
            hold_seconds,
            self._metrics(item),
            execution,
        ), execution

    def _insert_regime_snapshot(
        self,
        signal_id: str,
        channel: str,
        side: str,
        symbol: str,
        now_ms: int,
        leverage: float,
        stop_pct: float,
        hold_seconds: int,
        entry_metrics: Optional[dict[str, Any]],
        execution: Optional[EntryExecution],
    ) -> Optional[str]:
        with self.lock:
            signal = self.conn.execute(
                """SELECT episode_id,source_market,trigger_json
                   FROM regime_signals WHERE id=?""",
                (signal_id,),
            ).fetchone()
            if signal is None:
                return None
            existing = self.conn.execute(
                "SELECT id FROM regime_snapshots WHERE signal_id=?",
                (signal_id,),
            ).fetchone()
            if existing:
                return str(existing["id"])

            try:
                signal_trigger = json.loads(str(signal["trigger_json"]))
            except (TypeError, ValueError, json.JSONDecodeError):
                signal_trigger = {"raw": signal["trigger_json"]}
            metrics = {
                "signal_trigger": signal_trigger,
                "futures_entry": entry_metrics,
            }
            entry_status = self._snapshot_entry_status(execution)
            snapshot_id = str(uuid.uuid4())
            margin = self.settings.regime_margin_usdt
            position = execution.actual_notional if execution else margin * leverage
            completed = int(execution is None)
            completion_reason = "NO_EXECUTABLE_ENTRY" if execution is None else None
            self.conn.execute(
                """INSERT INTO regime_snapshots(
                   id,run_id,signal_id,channel,side,source_market,execution_market,
                   symbol,episode_id,snapshot_time_ms,metrics_json,entry_status,
                   entry_reference_price,entry_best_bid,entry_best_ask,entry_depth_age_ms,
                   entry_vwap,entry_quantity,entry_notional_usdt,entry_slippage_percent,
                   leverage,margin_usdt,stop_price_percent,hold_seconds,
                   algorithm_version,strategy_version,config_hash
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    snapshot_id,
                    self.storage.run_id,
                    signal_id,
                    channel,
                    side,
                    str(signal["source_market"]),
                    "FUTURES",
                    symbol,
                    str(signal["episode_id"]),
                    now_ms,
                    json.dumps(metrics, sort_keys=True, separators=(",", ":")),
                    entry_status,
                    execution.reference_price(side) if execution else None,
                    execution.best_bid if execution else None,
                    execution.best_ask if execution else None,
                    execution.depth_age_ms if execution else None,
                    execution.entry_vwap if execution else None,
                    execution.quantity if execution else None,
                    execution.actual_notional if execution else None,
                    execution.slippage_percent if execution else None,
                    leverage,
                    margin,
                    stop_pct,
                    hold_seconds,
                    self.settings.algorithm_version,
                    self.settings.strategy_version,
                    self.settings.config_hash(),
                ),
            )
            self.conn.execute(
                """INSERT INTO regime_snapshot_outcomes(
                   snapshot_id,signal_id,channel,side,symbol,created_at_ms,
                   entry_status,entry_vwap,quantity,position_usdt,leverage,margin_usdt,
                   fee_rate,stop_price_percent,hold_seconds,completed,completion_reason
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    snapshot_id,
                    signal_id,
                    channel,
                    side,
                    symbol,
                    now_ms,
                    entry_status,
                    execution.entry_vwap if execution else None,
                    execution.quantity if execution else None,
                    position,
                    leverage,
                    margin,
                    self.settings.futures_fee_rate,
                    stop_pct,
                    hold_seconds,
                    completed,
                    completion_reason,
                ),
            )
            self.conn.commit()
        return snapshot_id

    def _record_no_entry_snapshot_for_pending(
        self,
        pending: PendingShort,
        symbol: str,
        now_ms: int,
    ) -> Optional[str]:
        return self._insert_regime_snapshot(
            pending.signal_id,
            SHORT_CHANNEL,
            "SHORT",
            symbol,
            now_ms,
            self.settings.regime_short_leverage,
            self.settings.regime_short_stop_percent,
            self.settings.regime_short_hold_seconds,
            None,
            None,
        )

    def _open_slot(
        self,
        signal_id: str,
        channel: str,
        side: str,
        item: EvaluatedCandidate,
        now_ms: int,
        leverage: float,
        stop_pct: float,
        hold_seconds: int,
        execution: Optional[EntryExecution],
    ) -> SlotOpenResult:
        symbol = item.candidate.symbol
        if self._signal_disposition(signal_id) != "PENDING":
            return SlotOpenResult(SKIPPED)
        if self._channel_open_count(channel) >= self.settings.regime_slots_per_channel:
            self._set_signal(signal_id, "SKIPPED", "CHANNEL_SLOT_BUSY")
            return SlotOpenResult(SKIPPED)
        if self._repeat_blocked(channel, symbol, now_ms):
            self._set_signal(signal_id, "SKIPPED", "REPEAT_SYMBOL")
            return SlotOpenResult(SKIPPED)
        if item.flow.trade_gap:
            self._set_signal(signal_id, "SKIPPED", "TRADE_GAP")
            return SlotOpenResult(SKIPPED)
        if execution is None:
            return SlotOpenResult(RETRY)
        mid = (execution.best_bid + execution.best_ask) / 2.0
        spread_bps = (
            (execution.best_ask - execution.best_bid) / mid * 10_000
            if mid > 0
            else 999.0
        )
        if spread_bps > self.settings.regime_max_spread_bps:
            self._set_signal(signal_id, "SKIPPED", "SPREAD_TOO_WIDE")
            return SlotOpenResult(SKIPPED)
        if execution.slippage_percent > self.settings.regime_max_slippage_percent:
            self._set_signal(signal_id, "SKIPPED", "SLIPPAGE_TOO_HIGH")
            return SlotOpenResult(SKIPPED)

        margin = self.settings.regime_margin_usdt
        mark = self.state.mark_info(symbol)
        slot_id = str(uuid.uuid4())
        entry_fee = execution.actual_notional * self.settings.futures_fee_rate
        with self.lock:
            signal = self.conn.execute(
                "SELECT disposition FROM regime_signals WHERE id=?",
                (signal_id,),
            ).fetchone()
            if signal is None or str(signal["disposition"]) != "PENDING":
                return SlotOpenResult(SKIPPED)
            # Recheck under the write lock so the configured cap cannot be exceeded.
            open_count = int(
                self.conn.execute(
                    "SELECT COUNT(*) n FROM regime_slots WHERE channel=? AND status='OPEN'",
                    (channel,),
                ).fetchone()["n"]
            )
            if open_count >= self.settings.regime_slots_per_channel:
                self.conn.execute(
                    "UPDATE regime_signals SET disposition='SKIPPED', reason='CHANNEL_SLOT_BUSY' WHERE id=?",
                    (signal_id,),
                )
                self.conn.commit()
                return SlotOpenResult(SKIPPED)
            self.conn.execute(
                """INSERT INTO regime_slots(
                   id,run_id,signal_id,channel,side,symbol,opened_at_ms,
                   margin_usdt,leverage,notional_usdt,quantity,entry_vwap,
                   entry_fee_usdt,entry_mark_price,funding_rate,next_funding_time_ms,
                   stop_price_percent,hold_seconds,status,last_updated_at_ms,
                   algorithm_version,strategy_version,config_hash
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?,?,?)""",
                (
                    slot_id,
                    self.storage.run_id,
                    signal_id,
                    channel,
                    side,
                    symbol,
                    now_ms,
                    margin,
                    leverage,
                    execution.actual_notional,
                    execution.quantity,
                    execution.entry_vwap,
                    entry_fee,
                    mark.mark_price or None,
                    mark.funding_rate,
                    mark.next_funding_time_ms,
                    stop_pct,
                    hold_seconds,
                    now_ms,
                    self.settings.algorithm_version,
                    self.settings.strategy_version,
                    self.settings.config_hash(),
                ),
            )
            self.conn.execute(
                "UPDATE regime_signals SET disposition='OPENED', reason=NULL WHERE id=?",
                (signal_id,),
            )
            self.conn.commit()
        self.extremes[slot_id] = (0.0, 0.0)
        return SlotOpenResult(OPENED, slot_id)

    def _safe_gate(self, item: EvaluatedCandidate) -> bool:
        b, f = item.book, item.flow
        return (
            not f.trade_gap
            and b.depth_age_ms is not None
            and b.depth_age_ms <= self.settings.max_feed_age_ms
            and b.spread_bps is not None
            and b.spread_bps <= self.settings.regime_max_spread_bps
            and b.buy_slippage_percent is not None
            and b.buy_slippage_percent <= self.settings.regime_max_slippage_percent
            and b.sell_slippage_percent is not None
            and b.sell_slippage_percent <= self.settings.regime_max_slippage_percent
        )

    def _onset_score(self, item: EvaluatedCandidate) -> int:
        c, f, b = item.candidate, item.flow, item.book
        if not self._safe_gate(item):
            return 0
        t5 = f.taker_buy_ratio_5s
        t15 = f.taker_buy_ratio_15s
        t30 = f.taker_buy_ratio_30s
        checks = (
            (c.return_15s or 0.0) >= self.settings.onset_min_return_15s,
            (c.return_60s or 0.0) >= self.settings.onset_min_return_60s,
            c.return_5m is not None and c.return_5m <= self.settings.onset_max_return_5m,
            (c.acceleration or 0.0) >= self.settings.onset_min_acceleration,
            (c.relative_strength_vs_btc or 0.0) >= self.settings.onset_min_relative_strength,
            (f.volume_z_30s or 0.0) >= self.settings.onset_min_volume_z
            or f.quote_volume_30s >= self.settings.onset_min_quote_volume_30s,
            t5 is not None and t5 >= self.settings.onset_min_tbr_5s,
            t15 is not None and t30 is not None and t5 is not None
            and t5 >= t15 - 0.02 and t15 >= t30 - 0.03,
            f.cvd_5s > 0 and f.cvd_slope > 0,
            b.obi_10 is not None and b.obi_10 > -0.10,
        )
        return sum(bool(value) for value in checks)

    def _dump_scout(self, item: EvaluatedCandidate) -> tuple[bool, float]:
        c, f, b = item.candidate, item.flow, item.book
        if f.trade_gap or b.spread_bps is None or b.spread_bps > 60.0:
            return False, 0.0
        move_score = sum(
            (
                (c.return_15s or 0.0) <= self.settings.dump_max_return_15s,
                (c.return_60s or 0.0) <= self.settings.dump_max_return_60s,
                (c.return_5m or 0.0) <= self.settings.dump_max_return_5m,
                (c.acceleration or 0.0) <= self.settings.dump_max_acceleration,
                (c.relative_strength_vs_btc or 0.0) <= self.settings.dump_max_relative_strength,
            )
        )
        flow_ok = (
            f.taker_buy_ratio_30s is not None
            and f.taker_buy_ratio_30s <= self.settings.dump_max_tbr_30s
            and f.cvd_30s < 0
        )
        intensity = (
            (f.volume_z_30s or 0.0) >= self.settings.dump_min_volume_z
            or f.quote_volume_30s >= self.settings.dump_min_quote_volume_30s
        )
        sell_quote = max(
            1.0,
            f.quote_volume_30s * (1.0 - (f.taker_buy_ratio_30s or 0.5)),
        )
        impact = max(0.0, -(c.return_15s or 0.0)) / sell_quote * 1_000_000.0
        return move_score >= 3 and flow_ok and intensity, impact

    def _dump_reclaim(self, item: EvaluatedCandidate, tracker: DumpTracker, now_ms: int) -> bool:
        c, f, b = item.candidate, item.flow, item.book
        if not self._safe_gate(item) or tracker.low_price <= 0:
            return False
        bounce = (c.price / tracker.low_price - 1.0) * 100.0
        t5 = f.taker_buy_ratio_5s
        t30 = f.taker_buy_ratio_30s
        sell_quote = max(1.0, f.quote_volume_30s * (1.0 - (t30 or 0.5)))
        current_impact = max(0.0, -(c.return_15s or 0.0)) / sell_quote * 1_000_000.0
        absorbed = (
            tracker.initial_sell_impact <= 0
            or current_impact <= tracker.initial_sell_impact * 0.80
            or (t5 or 0.0) >= 0.65
        )
        return all(
            (
                bounce >= self.settings.dump_reclaim_percent,
                now_ms - tracker.low_at_ms >= self.settings.dump_low_hold_seconds * 1000,
                t5 is not None and t5 >= self.settings.dump_reclaim_tbr_5s,
                t30 is not None and t5 is not None
                and t5 - t30 >= self.settings.dump_min_tbr_reversal,
                f.cvd_5s > 0,
                f.cvd_slope > 0,
                absorbed,
            )
        )

    async def observe_futures(
        self,
        item: EvaluatedCandidate,
        now_ms: int,
        episode_id: str,
    ) -> None:
        symbol = item.candidate.symbol

        pending = self.pending_shorts.get(symbol)
        if pending is not None:
            self.short_research.record_signal(pending.signal_id, item, now_ms)
            snapshot_id, execution = self._record_regime_snapshot(
                pending.signal_id,
                SHORT_CHANNEL,
                "SHORT",
                item,
                now_ms,
                self.settings.regime_short_leverage,
                self.settings.regime_short_stop_percent,
                self.settings.regime_short_hold_seconds,
                allow_no_entry=False,
            )
            pending.snapshot_id = snapshot_id or pending.snapshot_id
            result = self._open_slot(
                pending.signal_id,
                SHORT_CHANNEL,
                "SHORT",
                item,
                now_ms,
                self.settings.regime_short_leverage,
                self.settings.regime_short_stop_percent,
                self.settings.regime_short_hold_seconds,
                execution,
            )
            if result.status in {OPENED, SKIPPED}:
                self.pending_shorts.pop(symbol, None)
            if result.status == OPENED and result.slot_id:
                row = self._open_row_by_id(result.slot_id)
                self.short_research.link_slot(
                    pending.signal_id, result.slot_id, int(row["opened_at_ms"])
                )
                await self.notify(
                    f"🔴 REV MC5 SHORT 2x paper\n{symbol}\n"
                    f"entry {float(row['entry_vwap']):.8g} · "
                    f"margin {self.settings.regime_margin_usdt:.2f} USDT · "
                    f"notional ~{self.settings.regime_margin_usdt * self.settings.regime_short_leverage:.2f} USDT\n"
                    f"stop {self.settings.regime_short_stop_percent:.1f}% price · "
                    f"hold {self.settings.regime_short_hold_seconds}s"
                )

        score = self._onset_score(item)
        tracker = self.onset_trackers.get(symbol)
        if score >= self.settings.onset_min_score:
            if tracker is None or now_ms - tracker.last_seen_ms > 2_500:
                tracker = OnsetTracker(episode_id, now_ms, now_ms)
                self.onset_trackers[symbol] = tracker
            tracker.last_seen_ms = now_ms
            tracker.confirm_ticks += 1
            tracker.best_score = max(tracker.best_score, score)
            if tracker.confirm_ticks >= self.settings.onset_confirm_ticks:
                trigger = self._metrics(item)
                trigger["onset_score"] = score
                trigger["confirm_ticks"] = tracker.confirm_ticks
                signal_id = self._insert_signal(
                    ONSET_CHANNEL,
                    "LONG",
                    "FUTURES",
                    symbol,
                    tracker.episode_id,
                    now_ms,
                    trigger,
                    disposition="SHADOW_ONLY",
                    reason="REAL_ENTRY_DISABLED_V452",
                )
                self._record_regime_snapshot(
                    signal_id,
                    ONSET_CHANNEL,
                    "LONG",
                    item,
                    now_ms,
                    1.0,
                    self.settings.onset_stop_percent,
                    self.settings.onset_hold_seconds,
                    allow_no_entry=True,
                )
                self.onset_trackers.pop(symbol, None)
                LOG.info(
                    "ONSET_LONG_SHADOW measurement only symbol=%s score=%s signal=%s",
                    symbol,
                    score,
                    signal_id,
                )
        elif tracker is not None:
            tracker.confirm_ticks = 0

        scout, initial_impact = self._dump_scout(item)
        dump = self.dump_trackers.get(symbol)
        if dump is None and scout:
            dump = DumpTracker(
                episode_id=episode_id,
                first_seen_ms=now_ms,
                last_seen_ms=now_ms,
                low_price=item.candidate.price,
                low_at_ms=now_ms,
                initial_sell_impact=initial_impact,
                worst_return_60s=item.candidate.return_60s or 0.0,
            )
            self.dump_trackers[symbol] = dump
        if dump is not None:
            dump.last_seen_ms = now_ms
            dump.worst_return_60s = min(
                dump.worst_return_60s,
                item.candidate.return_60s or 0.0,
            )
            if item.candidate.price < dump.low_price:
                dump.low_price = item.candidate.price
                dump.low_at_ms = now_ms
                dump.confirm_ticks = 0
            if self._dump_reclaim(item, dump, now_ms):
                dump.confirm_ticks += 1
            else:
                dump.confirm_ticks = 0
            if dump.confirm_ticks >= self.settings.dump_confirm_ticks:
                trigger = self._metrics(item)
                trigger.update(
                    {
                        "low_price": dump.low_price,
                        "low_at_ms": dump.low_at_ms,
                        "worst_return_60s": dump.worst_return_60s,
                        "initial_sell_impact": dump.initial_sell_impact,
                    }
                )
                signal_id = self._insert_signal(
                    DUMP_CHANNEL,
                    "LONG",
                    "FUTURES",
                    symbol,
                    dump.episode_id,
                    now_ms,
                    trigger,
                )
                _, execution = self._record_regime_snapshot(
                    signal_id,
                    DUMP_CHANNEL,
                    "LONG",
                    item,
                    now_ms,
                    1.0,
                    self.settings.dump_stop_percent,
                    self.settings.dump_hold_seconds,
                    allow_no_entry=True,
                )
                result = self._open_slot(
                    signal_id,
                    DUMP_CHANNEL,
                    "LONG",
                    item,
                    now_ms,
                    1.0,
                    self.settings.dump_stop_percent,
                    self.settings.dump_hold_seconds,
                    execution,
                )
                if result.status == RETRY:
                    self._set_signal(signal_id, "SKIPPED", "NO_EXECUTABLE_ENTRY")
                self.dump_trackers.pop(symbol, None)
                if result.status == OPENED:
                    await self.notify(
                        f"🟢 DUMP EXHAUSTION LONG shadow\n{symbol}\n"
                        f"reclaim from local low · 1x · stop {self.settings.dump_stop_percent:.1f}% · "
                        f"hold {self.settings.dump_hold_seconds}s"
                    )

    def _open_row_by_id(self, slot_id: str):
        with self.lock:
            return self.conn.execute(
                "SELECT * FROM regime_slots WHERE id=?",
                (slot_id,),
            ).fetchone()

    def _open_slots(self):
        with self.lock:
            return list(
                self.conn.execute(
                    "SELECT * FROM regime_slots WHERE status='OPEN' ORDER BY opened_at_ms"
                )
            )

    def _exit_execution(
        self,
        side: str,
        symbol: str,
        quantity: float,
        now_ms: int,
    ) -> Optional[tuple[float, float, int]]:
        depth = self._fresh_depth(symbol, now_ms)
        if depth is None:
            return None
        if side == "SHORT":
            trigger_price = depth.asks[0][0]
            result = self._buy_qty_vwap(depth.asks, quantity)
        else:
            trigger_price = depth.bids[0][0]
            result = self._sell_qty_vwap(depth.bids, quantity)
        if result is None:
            return None
        return trigger_price, result[0], max(0, now_ms - int(depth.updated_at_ms))

    def _update_regime_outcomes(self, now_ms: int) -> None:
        with self.lock:
            rows = list(
                self.conn.execute(
                    """SELECT * FROM regime_snapshot_outcomes
                       WHERE completed=0 ORDER BY created_at_ms"""
                )
            )
        statements: list[tuple[str, tuple[Any, ...]]] = []
        for row in rows:
            age_ms = max(0, now_ms - int(row["created_at_ms"]))
            entry = row["entry_vwap"]
            quantity = row["quantity"]
            if entry is None or quantity is None or float(entry) <= 0 or float(quantity) <= 0:
                statements.append(
                    (
                        """UPDATE regime_snapshot_outcomes
                           SET completed=1,completion_reason='NO_EXECUTABLE_ENTRY',
                               last_observed_at_ms=? WHERE snapshot_id=?""",
                        (now_ms, row["snapshot_id"]),
                    )
                )
                continue
            execution = self._exit_execution(
                str(row["side"]),
                str(row["symbol"]),
                float(quantity),
                now_ms,
            )
            if execution is None:
                if age_ms >= REGIME_OUTCOME_MAX_AGE_SECONDS * 1000:
                    statements.append(
                        (
                            """UPDATE regime_snapshot_outcomes
                               SET completed=1,completion_reason='MARKET_DATA_TIMEOUT',
                                   last_observed_at_ms=? WHERE snapshot_id=?""",
                            (now_ms, row["snapshot_id"]),
                        )
                    )
                continue
            _, exit_vwap, _ = execution
            current = directional_return(str(row["side"]), float(entry), exit_vwap)
            mfe = max(float(row["mfe_percent"] or 0.0), current)
            mae = min(float(row["mae_percent"] or 0.0), current)
            updates: dict[str, Any] = {
                "last_observed_at_ms": now_ms,
                "mfe_percent": mfe,
                "mae_percent": mae,
            }
            for seconds in REGIME_OUTCOME_HORIZONS:
                return_column = f"return_{seconds}s"
                if age_ms >= seconds * 1000 and row[return_column] is None:
                    updates[return_column] = current
                    updates[f"mfe_{seconds}s"] = mfe
                    updates[f"mae_{seconds}s"] = mae
            if row["first_barrier"] is None:
                if current >= 1.0:
                    updates["first_barrier"] = "TARGET_1"
                    updates["first_barrier_at_ms"] = now_ms
                elif current <= -float(row["stop_price_percent"]):
                    updates["first_barrier"] = "STOP"
                    updates["first_barrier_at_ms"] = now_ms
            if age_ms >= 3_600_000 and (
                row["return_3600s"] is not None or "return_3600s" in updates
            ):
                updates["completed"] = 1
                updates["completion_reason"] = "HORIZON_3600S"
            assignments = ",".join(f"{name}=?" for name in updates)
            statements.append(
                (
                    f"UPDATE regime_snapshot_outcomes SET {assignments} WHERE snapshot_id=?",
                    (*updates.values(), row["snapshot_id"]),
                )
            )
        if statements:
            with self.lock:
                for sql, params in statements:
                    self.conn.execute(sql, params)
                self.conn.commit()

    async def tick(self, now_ms: int) -> None:
        for symbol, pending in list(self.pending_shorts.items()):
            if now_ms >= pending.expires_at_ms:
                self.pending_shorts.pop(symbol, None)
                if pending.snapshot_id is None:
                    pending.snapshot_id = self._record_no_entry_snapshot_for_pending(
                        pending,
                        symbol,
                        now_ms,
                    )
                self._set_signal(pending.signal_id, "EXPIRED", "FUTURES_ENTRY_NOT_READY")
        for symbol, tracker in list(self.onset_trackers.items()):
            if now_ms - tracker.last_seen_ms > self.settings.onset_tracker_ttl_seconds * 1000:
                self.onset_trackers.pop(symbol, None)
        for symbol, tracker in list(self.dump_trackers.items()):
            if now_ms - tracker.first_seen_ms > self.settings.dump_tracker_ttl_seconds * 1000:
                self.dump_trackers.pop(symbol, None)

        self._update_regime_outcomes(now_ms)

        for slot in self._open_slots():
            side = str(slot["side"])
            entry = float(slot["entry_vwap"])
            execution = self._exit_execution(
                side,
                str(slot["symbol"]),
                float(slot["quantity"]),
                now_ms,
            )
            if execution is None:
                continue
            trigger_price, exit_vwap, depth_age_ms = execution
            trigger_return = directional_return(side, entry, trigger_price)
            current = directional_return(side, entry, exit_vwap)
            old_max, old_min = self.extremes.get(
                str(slot["id"]),
                (
                    float(slot["max_directional_return_percent"] or 0.0),
                    float(slot["min_directional_return_percent"] or 0.0),
                ),
            )
            live_max = max(old_max, current)
            live_min = min(old_min, current)
            self.extremes[str(slot["id"])] = (live_max, live_min)
            age_seconds = (now_ms - int(slot["opened_at_ms"])) / 1000.0
            flow = self.state.flow_metrics(str(slot["symbol"]), now_ms)
            book = self.state.book_metrics(
                str(slot["symbol"]), float(slot["notional_usdt"]), now_ms
            )
            previous_sample = self.conn.execute(
                """SELECT directional_return_pct FROM exit_window_samples
                   WHERE slot_id=? ORDER BY sampled_at_ms DESC LIMIT 1""",
                (slot["id"],),
            ).fetchone()
            features = {
                "taker_buy_ratio_5s": flow.taker_buy_ratio_5s,
                "taker_buy_ratio_15s": flow.taker_buy_ratio_15s,
                "taker_buy_ratio_30s": flow.taker_buy_ratio_30s,
                "cvd": flow.cvd_30s,
                "cvd_slope": flow.cvd_slope,
                "obi_10": book.obi_10,
                "obi_20": book.obi_20,
                "spread_bps": book.spread_bps,
                "estimated_slippage_bps": (
                    (book.sell_slippage_percent if side == "LONG" else book.buy_slippage_percent)
                    * 100.0
                    if (book.sell_slippage_percent if side == "LONG" else book.buy_slippage_percent) is not None
                    else None
                ),
                "short_momentum": current - float(previous_sample[0]) if previous_sample else 0.0,
                "data_quality_flags": [
                    flag
                    for flag, failed in (
                        ("TRADE_GAP", flow.trade_gap),
                        ("FLOW_NOT_READY", not flow.ready),
                        ("DEPTH_MISSING", book.depth_age_ms is None),
                        ("DEPTH_STALE", book.depth_age_ms is not None and book.depth_age_ms > 2_000),
                    )
                    if failed
                ],
            }
            self.short_research.tick_slot(
                slot,
                current,
                now_ms,
                price=exit_vwap,
                maximum=live_max,
                minimum=live_min,
                features=features,
            )
            with self.lock:
                self.conn.execute(
                    """UPDATE regime_slots SET directional_price_return_percent=?,
                       max_directional_return_percent=?,min_directional_return_percent=?,
                       last_updated_at_ms=? WHERE id=? AND status='OPEN'""",
                    (current, live_max, live_min, now_ms, slot["id"]),
                )
                self.conn.commit()
            reason: Optional[str] = None
            stop_info: Optional[dict[str, Any]] = None
            stop_pct = float(slot["stop_price_percent"])
            if trigger_return <= -stop_pct:
                reason = "PRICE_STOP"
                stop_info = {
                    "triggered_at_ms": now_ms,
                    "trigger_price": trigger_price,
                    "trigger_return": trigger_return,
                    "market_overshoot": max(0.0, -trigger_return - stop_pct),
                    "execution_impact": max(0.0, trigger_return - current),
                    "total_slippage": max(0.0, -current - stop_pct),
                    "depth_age_ms": depth_age_ms,
                }
            elif age_seconds >= int(slot["hold_seconds"]):
                reason = "HOLD_EXIT"
            if reason is not None:
                await self._close(
                    slot,
                    exit_vwap,
                    current,
                    reason,
                    now_ms,
                    stop_info,
                )

    async def _close(
        self,
        slot,
        exit_vwap: float,
        current_return: float,
        reason: str,
        now_ms: int,
        stop_info: Optional[dict[str, Any]] = None,
    ) -> None:
        quantity = float(slot["quantity"])
        exit_notional = quantity * exit_vwap
        exit_fee = exit_notional * self.settings.futures_fee_rate
        funding_pnl = 0.0
        funding_time = int(slot["next_funding_time_ms"] or 0)
        if funding_time > int(slot["opened_at_ms"]) and now_ms >= funding_time:
            base_funding = float(slot["notional_usdt"]) * float(slot["funding_rate"] or 0.0)
            funding_pnl = base_funding if str(slot["side"]) == "SHORT" else -base_funding
        gross, net, gross_pct, net_pct = trade_pnl(
            str(slot["side"]),
            float(slot["entry_vwap"]),
            exit_vwap,
            quantity,
            float(slot["entry_fee_usdt"]),
            exit_fee,
            funding_pnl,
            float(slot["margin_usdt"]),
        )
        high, low = self.extremes.pop(
            str(slot["id"]),
            (
                float(slot["max_directional_return_percent"] or 0.0),
                float(slot["min_directional_return_percent"] or 0.0),
            ),
        )
        stop_info = stop_info or {}
        with self.lock:
            self.conn.execute(
                """UPDATE regime_slots SET status='CLOSED', closed_at_ms=?,
                   exit_reason=?, exit_vwap=?, exit_fee_usdt=?, funding_pnl_usdt=?,
                   gross_pnl_usdt=?, net_pnl_usdt=?, gross_return_margin_percent=?,
                   net_return_margin_percent=?, directional_price_return_percent=?,
                   max_directional_return_percent=?, min_directional_return_percent=?,
                   stop_triggered_at_ms=?, stop_trigger_price=?,
                   stop_trigger_return_percent=?, stop_market_overshoot_points=?,
                   stop_execution_impact_points=?, stop_total_slippage_points=?,
                   stop_depth_age_ms=?, last_updated_at_ms=? WHERE id=?""",
                (
                    now_ms,
                    reason,
                    exit_vwap,
                    exit_fee,
                    funding_pnl,
                    gross,
                    net,
                    gross_pct,
                    net_pct,
                    current_return,
                    high,
                    low,
                    stop_info.get("triggered_at_ms"),
                    stop_info.get("trigger_price"),
                    stop_info.get("trigger_return"),
                    stop_info.get("market_overshoot"),
                    stop_info.get("execution_impact"),
                    stop_info.get("total_slippage"),
                    stop_info.get("depth_age_ms"),
                    now_ms,
                    slot["id"],
                ),
            )
            self.conn.commit()
        self.short_research.close_slot(
            slot, current_return, reason, now_ms, net_pct, stop_info, high, low
        )
        icon = "🔻" if str(slot["side"]) == "SHORT" else "🔹"
        slip_text = ""
        if reason == "PRICE_STOP" and stop_info:
            slip_text = f" · stop slip {float(stop_info['total_slippage']):.3f} pp"
        await self.notify(
            f"{icon} {slot['channel']} {slot['symbol']} {reason}\n"
            f"price {current_return:+.3f}% · margin net {net_pct:+.3f}% · "
            f"pnl {net:+.4f} USDT{slip_text}"
        )
        LOG.info(
            "Closed regime slot %s %s %s net_margin=%+.3f%% pnl=%+.4f stop_slip=%s",
            slot["id"],
            slot["channel"],
            reason,
            net_pct,
            net,
            stop_info.get("total_slippage"),
        )

    @staticmethod
    def _percentile(values: list[float], fraction: float) -> Optional[float]:
        if not values:
            return None
        ordered = sorted(values)
        if len(ordered) == 1:
            return ordered[0]
        position = (len(ordered) - 1) * fraction
        lower = int(position)
        upper = min(lower + 1, len(ordered) - 1)
        weight = position - lower
        return ordered[lower] * (1.0 - weight) + ordered[upper] * weight

    def status(self) -> dict[str, Any]:
        version_params = (
            self.settings.algorithm_version,
            self.settings.strategy_version,
            self.settings.config_hash(),
        )
        with self.lock:
            open_by_channel = {
                str(row["channel"]): int(row["n"])
                for row in self.conn.execute(
                    """SELECT channel, COUNT(*) n FROM regime_slots
                       WHERE status='OPEN'
                         AND algorithm_version=? AND strategy_version=? AND config_hash=?
                       GROUP BY channel""",
                    version_params,
                )
            }
            closed_by_channel: dict[str, Any] = {}
            for row in self.conn.execute(
                """SELECT channel, COUNT(*) n,
                          AVG(net_return_margin_percent) avg_net_margin,
                          SUM(CASE WHEN net_pnl_usdt>0 THEN 1 ELSE 0 END) wins,
                          SUM(net_pnl_usdt) pnl_usdt,
                          SUM(CASE WHEN net_pnl_usdt>0 THEN net_pnl_usdt ELSE 0 END) gross_profit,
                          -SUM(CASE WHEN net_pnl_usdt<0 THEN net_pnl_usdt ELSE 0 END) gross_loss
                   FROM regime_slots
                   WHERE status='CLOSED'
                     AND net_pnl_usdt IS NOT NULL
                     AND algorithm_version=? AND strategy_version=? AND config_hash=?
                   GROUP BY channel""",
                version_params,
            ):
                loss = float(row["gross_loss"] or 0.0)
                closed_by_channel[str(row["channel"])] = {
                    "n": int(row["n"]),
                    "avg_net_margin": float(row["avg_net_margin"] or 0.0),
                    "win_rate": float(row["wins"] or 0) / int(row["n"]),
                    "pnl_usdt": float(row["pnl_usdt"] or 0.0),
                    "profit_factor": (
                        float(row["gross_profit"] or 0.0) / loss if loss > 0 else None
                    ),
                }
            signal_counts = {
                str(row["channel"]): int(row["n"])
                for row in self.conn.execute(
                    """SELECT channel, COUNT(*) n FROM regime_signals
                       WHERE algorithm_version=? AND strategy_version=? AND config_hash=?
                       GROUP BY channel""",
                    version_params,
                )
            }
            signal_dispositions: dict[str, dict[str, int]] = {}
            for row in self.conn.execute(
                """SELECT channel,disposition,COUNT(*) n
                   FROM regime_signals
                   WHERE algorithm_version=? AND strategy_version=? AND config_hash=?
                   GROUP BY channel,disposition""",
                version_params,
            ):
                signal_dispositions.setdefault(str(row["channel"]), {})[
                    str(row["disposition"])
                ] = int(row["n"])
            outcome_by_channel: dict[str, dict[str, int]] = {}
            for row in self.conn.execute(
                """SELECT o.channel,COUNT(*) n,
                          SUM(CASE WHEN o.completed=0 THEN 1 ELSE 0 END) pending,
                          SUM(CASE WHEN o.completed=1 THEN 1 ELSE 0 END) completed,
                          SUM(CASE WHEN o.entry_vwap IS NOT NULL THEN 1 ELSE 0 END) executable,
                          SUM(CASE WHEN o.entry_vwap IS NULL THEN 1 ELSE 0 END) no_entry
                   FROM regime_snapshot_outcomes o
                   JOIN regime_snapshots s ON s.id=o.snapshot_id
                   WHERE s.algorithm_version=? AND s.strategy_version=? AND s.config_hash=?
                   GROUP BY o.channel""",
                version_params,
            ):
                outcome_by_channel[str(row["channel"])] = {
                    "n": int(row["n"] or 0),
                    "pending": int(row["pending"] or 0),
                    "completed": int(row["completed"] or 0),
                    "executable": int(row["executable"] or 0),
                    "no_entry": int(row["no_entry"] or 0),
                }
            outcome_counts = self.conn.execute(
                """SELECT COUNT(*) n,
                          SUM(CASE WHEN o.completed=0 THEN 1 ELSE 0 END) pending,
                          SUM(CASE WHEN o.completed=1 THEN 1 ELSE 0 END) completed
                   FROM regime_snapshot_outcomes o
                   JOIN regime_snapshots s ON s.id=o.snapshot_id
                   WHERE s.algorithm_version=? AND s.strategy_version=? AND s.config_hash=?""",
                version_params,
            ).fetchone()
            signal_total = int(
                self.conn.execute(
                    """SELECT COUNT(*) n FROM regime_signals
                       WHERE algorithm_version=? AND strategy_version=? AND config_hash=?""",
                    version_params,
                ).fetchone()["n"]
            )
            snapshot_total = int(
                self.conn.execute(
                    """SELECT COUNT(*) n FROM regime_snapshots
                       WHERE algorithm_version=? AND strategy_version=? AND config_hash=?""",
                    version_params,
                ).fetchone()["n"]
            )
            stop_rows = list(
                self.conn.execute(
                    """SELECT stop_total_slippage_points,
                              stop_market_overshoot_points,stop_execution_impact_points
                       FROM regime_slots
                       WHERE exit_reason='PRICE_STOP'
                         AND stop_total_slippage_points IS NOT NULL
                         AND algorithm_version=? AND strategy_version=? AND config_hash=?""",
                    version_params,
                )
            )
        totals = [float(row["stop_total_slippage_points"]) for row in stop_rows]
        market = [float(row["stop_market_overshoot_points"] or 0.0) for row in stop_rows]
        impact = [float(row["stop_execution_impact_points"] or 0.0) for row in stop_rows]
        return {
            "regime_primary_channel": SHORT_CHANNEL,
            "regime_slots_per_channel": self.settings.regime_slots_per_channel,
            "regime_open_by_channel": open_by_channel,
            "regime_closed_by_channel": closed_by_channel,
            "regime_signal_counts": signal_counts,
            "regime_signal_dispositions": signal_dispositions,
            "regime_snapshot_coverage": {
                "signals": signal_total,
                "snapshots": snapshot_total,
                "percent": (snapshot_total / signal_total * 100.0 if signal_total else 100.0),
            },
            "regime_outcomes": {
                "n": int(outcome_counts["n"] or 0),
                "pending": int(outcome_counts["pending"] or 0),
                "completed": int(outcome_counts["completed"] or 0),
            },
            "regime_outcomes_by_channel": outcome_by_channel,
            "regime_stop_slippage": {
                "n": len(totals),
                "avg_total_points": (sum(totals) / len(totals) if totals else None),
                "median_total_points": self._percentile(totals, 0.50),
                "p90_total_points": self._percentile(totals, 0.90),
                "max_total_points": max(totals) if totals else None,
                "avg_market_overshoot_points": (sum(market) / len(market) if market else None),
                "avg_execution_impact_points": (sum(impact) / len(impact) if impact else None),
            },
            "regime_pending_short": len(self.pending_shorts),
            "regime_onset_scouts": len(self.onset_trackers),
            "regime_dump_scouts": len(self.dump_trackers),
            **self.short_research.status(),
        }
