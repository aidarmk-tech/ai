from __future__ import annotations

import json
import math
import time
from pathlib import Path
from typing import Any, Optional


SHORT_RESEARCH_SCHEMA = """
CREATE TABLE IF NOT EXISTS short_signal_research (
  signal_id TEXT PRIMARY KEY REFERENCES regime_signals(id) ON DELETE CASCADE,
  symbol TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  measured_at_ms INTEGER NOT NULL,
  continuation_pressure REAL NOT NULL,
  reversal_confirmation REAL NOT NULL,
  continuation_flags_json TEXT NOT NULL,
  reversal_flags_json TEXT NOT NULL,
  metrics_json TEXT NOT NULL,
  btc_regime_status TEXT NOT NULL,
  btc_regime_json TEXT,
  challenger_a TEXT NOT NULL,
  challenger_b TEXT NOT NULL,
  challenger_c TEXT NOT NULL,
  slot_id TEXT,
  algorithm_version TEXT NOT NULL,
  strategy_version TEXT NOT NULL,
  config_hash TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_short_research_time
  ON short_signal_research(created_at_ms);
CREATE INDEX IF NOT EXISTS idx_short_research_challengers
  ON short_signal_research(challenger_a,challenger_b,challenger_c);

CREATE TABLE IF NOT EXISTS short_exit_challengers (
  slot_id TEXT NOT NULL REFERENCES regime_slots(id) ON DELETE CASCADE,
  variant TEXT NOT NULL,
  state TEXT NOT NULL,
  opened_at_ms INTEGER NOT NULL,
  target1_at_ms INTEGER,
  target1_return_percent REAL,
  protected_floor_percent REAL NOT NULL,
  max_directional_return_percent REAL NOT NULL DEFAULT 0,
  min_directional_return_percent REAL NOT NULL DEFAULT 0,
  closed_at_ms INTEGER,
  exit_reason TEXT,
  weighted_directional_return_percent REAL,
  estimated_net_margin_percent REAL,
  last_updated_at_ms INTEGER NOT NULL,
  algorithm_version TEXT NOT NULL,
  strategy_version TEXT NOT NULL,
  config_hash TEXT NOT NULL,
  PRIMARY KEY(slot_id,variant)
);
CREATE INDEX IF NOT EXISTS idx_short_exit_open
  ON short_exit_challengers(state,variant,opened_at_ms);

CREATE TABLE IF NOT EXISTS short_failure_labels (
  slot_id TEXT PRIMARY KEY REFERENCES regime_slots(id) ON DELETE CASCADE,
  signal_id TEXT NOT NULL,
  symbol TEXT NOT NULL,
  closed_at_ms INTEGER NOT NULL,
  champion_profitable INTEGER NOT NULL,
  primary_label TEXT NOT NULL,
  labels_json TEXT NOT NULL,
  continuation_pressure REAL,
  reversal_confirmation REAL,
  max_directional_return_percent REAL,
  min_directional_return_percent REAL,
  stop_total_slippage_points REAL,
  details_json TEXT NOT NULL,
  algorithm_version TEXT NOT NULL,
  strategy_version TEXT NOT NULL,
  config_hash TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_short_failure_label
  ON short_failure_labels(primary_label,closed_at_ms);

CREATE TABLE IF NOT EXISTS exit_window_samples (
  slot_id TEXT NOT NULL REFERENCES regime_slots(id) ON DELETE CASCADE,
  run_id TEXT NOT NULL,
  symbol TEXT NOT NULL,
  channel TEXT NOT NULL,
  side TEXT NOT NULL,
  sampled_at_ms INTEGER NOT NULL,
  age_seconds REAL NOT NULL,
  age_bucket INTEGER NOT NULL,
  price REAL NOT NULL,
  directional_return_pct REAL NOT NULL,
  mfe_pct REAL NOT NULL,
  mae_pct REAL NOT NULL,
  taker_buy_ratio_5s REAL,
  taker_buy_ratio_15s REAL,
  taker_buy_ratio_30s REAL,
  cvd REAL,
  cvd_slope REAL,
  obi_10 REAL,
  obi_20 REAL,
  spread_bps REAL,
  estimated_slippage_bps REAL,
  seconds_to_1m_close REAL NOT NULL,
  seconds_to_5m_close REAL NOT NULL,
  seconds_to_15m_close REAL NOT NULL,
  data_quality_flags TEXT NOT NULL,
  PRIMARY KEY(slot_id,age_bucket)
);
CREATE INDEX IF NOT EXISTS idx_exit_window_channel_time
  ON exit_window_samples(channel,sampled_at_ms);

CREATE TABLE IF NOT EXISTS exit_policy_outcomes (
  slot_id TEXT NOT NULL REFERENCES regime_slots(id) ON DELETE CASCADE,
  policy TEXT NOT NULL,
  state TEXT NOT NULL,
  triggered_at_ms INTEGER,
  trigger_age_seconds REAL,
  trigger_price REAL,
  trigger_score REAL,
  reason_codes_json TEXT NOT NULL DEFAULT '[]',
  gross_return_pct REAL,
  net_return_pct REAL,
  champion_net_return_pct REAL,
  avoided_loss_pct REAL,
  missed_upside_pct REAL,
  last_updated_at_ms INTEGER NOT NULL,
  PRIMARY KEY(slot_id,policy)
);
CREATE INDEX IF NOT EXISTS idx_exit_policy_state
  ON exit_policy_outcomes(policy,state,last_updated_at_ms);

CREATE TABLE IF NOT EXISTS dump_health_diagnostics (
  measured_at_ms INTEGER PRIMARY KEY,
  run_id TEXT NOT NULL,
  last10_pnl REAL,
  last20_pnl REAL,
  last10_pf REAL,
  last20_pf REAL,
  stop_rate REAL NOT NULL,
  target1_giveback_rate REAL NOT NULL,
  regime_state TEXT NOT NULL,
  details_json TEXT NOT NULL
);
"""


def _number(value: Any, default: float = 0.0) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return number if math.isfinite(number) else default


class ShortResearchManager:
    """Observation-only v4.5.3 SHORT diagnostics.

    This component never blocks or opens the champion slot. It records three
    entry challengers and two exit challengers alongside the unchanged
    REV_MC5_SHORT_600_2X paper strategy.
    """

    EXIT_VARIANTS = ("TARGET1_PROTECTED", "TARGET1_PARTIAL_50")
    FIXED_EXIT_AGES = (540, 550, 560, 570, 580, 590, 600, 840, 850, 860, 870, 880, 890, 900)
    CANDLE_EXIT_OFFSETS = (50, 30, 20)

    def __init__(self, settings, conn, lock) -> None:
        self.settings = settings
        self.conn = conn
        self.lock = lock
        with self.lock:
            self.conn.executescript(SHORT_RESEARCH_SCHEMA)
            self.conn.commit()

    @staticmethod
    def score(item) -> tuple[float, float, list[str], list[str], dict[str, Any]]:
        candidate, flow, book = item.candidate, item.flow, item.book
        r15 = _number(candidate.return_15s)
        r60 = _number(candidate.return_60s)
        r5m = _number(candidate.return_5m)
        acceleration = _number(candidate.acceleration)
        relative = _number(candidate.relative_strength_vs_btc)
        tbr30 = _number(flow.taker_buy_ratio_30s, 0.5)
        tbr15 = _number(flow.taker_buy_ratio_15s, 0.5)
        tbr5 = _number(flow.taker_buy_ratio_5s, 0.5)
        cvd30 = _number(flow.cvd_30s)
        cvd5 = _number(flow.cvd_5s)
        cvd_slope = _number(flow.cvd_slope)
        quote30 = max(1.0, _number(flow.quote_volume_30s, 1.0))
        volume_z = _number(flow.volume_z_30s)
        obi10 = _number(book.obi_10)
        spread = _number(book.spread_bps, 999.0)
        slippage = _number(book.sell_slippage_percent, 999.0)
        cvd_share = cvd30 / quote30

        continuation = 0.0
        continuation_flags: list[str] = []

        def add_cont(points: float, flag: str, condition: bool) -> None:
            nonlocal continuation
            if condition:
                continuation += points
                continuation_flags.append(flag)

        add_cont(24, "FUTURES_RETURN_60S_GE_2_5", r60 >= 2.5)
        add_cont(16, "FUTURES_RETURN_15S_GE_1_0", r15 >= 1.0)
        add_cont(18, "RELATIVE_STRENGTH_GE_2_5", relative >= 2.5)
        add_cont(14, "TAKER_BUY_30S_GE_0_60", tbr30 >= 0.60)
        add_cont(10, "TAKER_BUY_5S_GE_0_65", tbr5 >= 0.65)
        add_cont(12, "CVD_SHARE_GE_0_20", cvd_share >= 0.20)
        add_cont(10, "VOLUME_Z_GE_5", volume_z >= 5.0)
        add_cont(8, "VOLUME_Z_GE_20", volume_z >= 20.0)
        add_cont(8, "ACCELERATION_POSITIVE", acceleration >= 0.20)
        add_cont(6, "FIVE_MINUTE_EXTENSION_GE_6", r5m >= 6.0)
        continuation = min(100.0, continuation)

        reversal = 0.0
        reversal_flags: list[str] = []

        def add_rev(points: float, flag: str, condition: bool) -> None:
            nonlocal reversal
            if condition:
                reversal += points
                reversal_flags.append(flag)

        add_rev(18, "ACCELERATION_NON_POSITIVE", acceleration <= 0.0)
        add_rev(16, "SHORT_TERM_RETURN_DECELERATING", r60 > 0 and r15 <= r60 * 0.35)
        add_rev(16, "TAKER_BUY_DECELERATING", tbr5 < tbr15 < tbr30)
        add_rev(12, "TAKER_BUY_5S_NOT_DOMINANT", tbr5 <= 0.50)
        add_rev(14, "CVD_SLOPE_NON_POSITIVE", cvd_slope <= 0.0)
        add_rev(8, "CVD_5S_WEAKER_THAN_30S", cvd5 < cvd30 / 6.0)
        add_rev(8, "ASK_SIDE_BOOK", obi10 <= 0.0)
        add_rev(4, "SPREAD_EXECUTABLE", spread <= 15.0)
        add_rev(4, "SLIPPAGE_EXECUTABLE", slippage <= 0.10)
        reversal = min(100.0, reversal)

        metrics = {
            "return_15s": r15,
            "return_60s": r60,
            "return_5m": r5m,
            "acceleration": acceleration,
            "relative_strength_vs_btc": relative,
            "taker_buy_ratio_30s": tbr30,
            "taker_buy_ratio_15s": tbr15,
            "taker_buy_ratio_5s": tbr5,
            "cvd_30s": cvd30,
            "cvd_5s": cvd5,
            "cvd_slope": cvd_slope,
            "cvd_share_30s": cvd_share,
            "quote_volume_30s": quote30,
            "volume_z_30s": volume_z,
            "obi_10": obi10,
            "spread_bps": spread,
            "sell_slippage_percent": slippage,
            "trade_gap": bool(flow.trade_gap),
        }
        return continuation, reversal, continuation_flags, reversal_flags, metrics

    def _load_btc_regime(self, now_ms: int) -> tuple[str, Optional[dict[str, Any]]]:
        path = Path(self.settings.btc_regime_path)
        if not path.is_file():
            return "MISSING", None
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            return "INVALID", None
        as_of_ms = int(payload.get("as_of_ms", 0) or 0)
        if as_of_ms <= 0 or as_of_ms >= now_ms:
            return "NOT_LAGGED", payload
        age_hours = (now_ms - as_of_ms) / 3_600_000.0
        if age_hours > self.settings.btc_regime_max_age_hours:
            return "STALE", payload
        risk = str(payload.get("short_squeeze_risk", "UNKNOWN")).upper()
        return ("READY" if risk in {"LOW", "MEDIUM", "HIGH"} else "UNKNOWN"), payload

    def record_signal(self, signal_id: str, item, now_ms: int) -> None:
        continuation, reversal, cont_flags, rev_flags, metrics = self.score(item)
        regime_status, regime = self._load_btc_regime(now_ms)
        challenger_a = (
            "PASS" if continuation < self.settings.short_continuation_veto_score else "VETO_CONTINUATION"
        )
        challenger_b = (
            "PASS"
            if challenger_a == "PASS" and reversal >= self.settings.short_reversal_confirm_score
            else ("VETO_REVERSAL_UNCONFIRMED" if challenger_a == "PASS" else challenger_a)
        )
        if challenger_b != "PASS":
            challenger_c = challenger_b
        elif regime_status != "READY":
            challenger_c = f"WAIT_BTC_REGIME_{regime_status}"
        elif str((regime or {}).get("short_squeeze_risk", "UNKNOWN")).upper() == "HIGH":
            challenger_c = "VETO_BTC_SQUEEZE_REGIME"
        else:
            challenger_c = "PASS"
        with self.lock:
            signal = self.conn.execute(
                "SELECT symbol,created_at_ms FROM regime_signals WHERE id=?",
                (signal_id,),
            ).fetchone()
            if signal is None:
                return
            self.conn.execute(
                """INSERT INTO short_signal_research(
                   signal_id,symbol,created_at_ms,measured_at_ms,
                   continuation_pressure,reversal_confirmation,
                   continuation_flags_json,reversal_flags_json,metrics_json,
                   btc_regime_status,btc_regime_json,challenger_a,challenger_b,
                   challenger_c,algorithm_version,strategy_version,config_hash
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(signal_id) DO UPDATE SET
                     measured_at_ms=excluded.measured_at_ms,
                     continuation_pressure=excluded.continuation_pressure,
                     reversal_confirmation=excluded.reversal_confirmation,
                     continuation_flags_json=excluded.continuation_flags_json,
                     reversal_flags_json=excluded.reversal_flags_json,
                     metrics_json=excluded.metrics_json,
                     btc_regime_status=excluded.btc_regime_status,
                     btc_regime_json=excluded.btc_regime_json,
                     challenger_a=excluded.challenger_a,
                     challenger_b=excluded.challenger_b,
                     challenger_c=excluded.challenger_c""",
                (
                    signal_id,
                    str(signal["symbol"]),
                    int(signal["created_at_ms"]),
                    now_ms,
                    continuation,
                    reversal,
                    json.dumps(cont_flags, separators=(",", ":")),
                    json.dumps(rev_flags, separators=(",", ":")),
                    json.dumps(metrics, sort_keys=True, separators=(",", ":")),
                    regime_status,
                    json.dumps(regime, sort_keys=True, separators=(",", ":")) if regime else None,
                    challenger_a,
                    challenger_b,
                    challenger_c,
                    self.settings.algorithm_version,
                    self.settings.strategy_version,
                    self.settings.config_hash(),
                ),
            )
            self.conn.commit()

    def link_slot(self, signal_id: str, slot_id: str, opened_at_ms: int) -> None:
        with self.lock:
            self.conn.execute(
                "UPDATE short_signal_research SET slot_id=? WHERE signal_id=?",
                (slot_id, signal_id),
            )
            for variant in self.EXIT_VARIANTS:
                self.conn.execute(
                    """INSERT OR IGNORE INTO short_exit_challengers(
                       slot_id,variant,state,opened_at_ms,protected_floor_percent,
                       last_updated_at_ms,algorithm_version,strategy_version,config_hash
                       ) VALUES(?,?,'OPEN',?,?,?,?,?,?)""",
                    (
                        slot_id,
                        variant,
                        opened_at_ms,
                        self.settings.short_protected_floor_percent,
                        opened_at_ms,
                        self.settings.algorithm_version,
                        self.settings.strategy_version,
                        self.settings.config_hash(),
                    ),
                )
            self.conn.commit()

    def _estimate_net_margin(self, slot, directional_return: float) -> float:
        margin = max(1e-12, float(slot["margin_usdt"]))
        notional = float(slot["notional_usdt"])
        leverage = notional / margin
        entry_fee = float(slot["entry_fee_usdt"] or 0.0)
        estimated_exit_fee = notional * self.settings.futures_fee_rate
        fee_margin_pct = (entry_fee + estimated_exit_fee) / margin * 100.0
        return directional_return * leverage - fee_margin_pct

    @staticmethod
    def _seconds_to_close(now_ms: int, period_seconds: int) -> float:
        elapsed = (now_ms / 1000.0) % period_seconds
        remaining = period_seconds - elapsed
        return 0.0 if abs(remaining - period_seconds) < 1e-9 else remaining

    @staticmethod
    def _in_exit_window(age_seconds: float) -> bool:
        return 480.0 <= age_seconds <= 660.0 or 780.0 <= age_seconds <= 960.0

    def _ensure_policy(self, slot_id: str, policy: str, now_ms: int) -> None:
        self.conn.execute(
            """INSERT OR IGNORE INTO exit_policy_outcomes(
               slot_id,policy,state,last_updated_at_ms
               ) VALUES(?,?,'OPEN',?)""",
            (slot_id, policy, now_ms),
        )

    def _close_policy(
        self,
        slot,
        policy: str,
        now_ms: int,
        age_seconds: float,
        price: float,
        directional_return: float,
        score: Optional[float] = None,
        reasons: Optional[list[str]] = None,
    ) -> None:
        self._ensure_policy(str(slot["id"]), policy, now_ms)
        self.conn.execute(
            """UPDATE exit_policy_outcomes SET state='CLOSED',triggered_at_ms=?,
               trigger_age_seconds=?,trigger_price=?,trigger_score=?,reason_codes_json=?,
               gross_return_pct=?,net_return_pct=?,last_updated_at_ms=?
               WHERE slot_id=? AND policy=? AND state='OPEN'""",
            (
                now_ms,
                age_seconds,
                price,
                score,
                json.dumps(reasons or [], separators=(",", ":")),
                directional_return,
                self._estimate_net_margin(slot, directional_return),
                now_ms,
                slot["id"],
                policy,
            ),
        )

    def _adaptive_score(
        self, slot, current_return: float, maximum: float, features: dict[str, Any]
    ) -> tuple[float, list[str]]:
        side = str(slot["side"])
        reasons: list[str] = []
        score = 0.0
        giveback = max(0.0, maximum - current_return)
        if maximum >= 1.0 and giveback >= max(0.50, maximum * 0.35):
            score += self.settings.adaptive_giveback_weight
            reasons.append("MFE_GIVEBACK")
        cvd_slope = _number(features.get("cvd_slope"))
        if (side == "SHORT" and cvd_slope > 0) or (side == "LONG" and cvd_slope < 0):
            score += self.settings.adaptive_cvd_weight
            reasons.append("CVD_AGAINST_POSITION")
        obi = _number(features.get("obi_10"))
        if (side == "SHORT" and obi > 0.10) or (side == "LONG" and obi < -0.10):
            score += self.settings.adaptive_obi_weight
            reasons.append("OBI_AGAINST_POSITION")
        taker = _number(features.get("taker_buy_ratio_5s"), 0.5)
        if (side == "SHORT" and taker >= 0.60) or (side == "LONG" and taker <= 0.40):
            score += self.settings.adaptive_taker_weight
            reasons.append("TAKER_FLOW_AGAINST_POSITION")
        momentum = _number(features.get("short_momentum"))
        if momentum < 0:
            score += self.settings.adaptive_momentum_weight
            reasons.append("MOMENTUM_DETERIORATION")
        return min(100.0, score), reasons

    def tick_slot(
        self,
        slot,
        current_return: float,
        now_ms: int,
        *,
        price: Optional[float] = None,
        maximum: Optional[float] = None,
        minimum: Optional[float] = None,
        features: Optional[dict[str, Any]] = None,
    ) -> None:
        channel = str(slot["channel"])
        if channel not in {"REV_MC5_SHORT_600_2X", "DUMP_EXHAUSTION_LONG_SHADOW"}:
            return
        age_seconds = (now_ms - int(slot["opened_at_ms"])) / 1000.0
        maximum = max(float(slot["max_directional_return_percent"] or 0.0), current_return) if maximum is None else maximum
        minimum = min(float(slot["min_directional_return_percent"] or 0.0), current_return) if minimum is None else minimum
        features = features or {}
        with self.lock:
            if self._in_exit_window(age_seconds):
                sample_seconds = max(1, int(self.settings.exit_window_sample_seconds))
                bucket = int(age_seconds // sample_seconds) * sample_seconds
                seconds_1m = self._seconds_to_close(now_ms, 60)
                seconds_5m = self._seconds_to_close(now_ms, 300)
                seconds_15m = self._seconds_to_close(now_ms, 900)
                self.conn.execute(
                    """INSERT OR IGNORE INTO exit_window_samples(
                       slot_id,run_id,symbol,channel,side,sampled_at_ms,age_seconds,
                       age_bucket,price,directional_return_pct,mfe_pct,mae_pct,
                       taker_buy_ratio_5s,taker_buy_ratio_15s,taker_buy_ratio_30s,
                       cvd,cvd_slope,obi_10,obi_20,spread_bps,estimated_slippage_bps,
                       seconds_to_1m_close,seconds_to_5m_close,seconds_to_15m_close,
                       data_quality_flags
                       ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (
                        slot["id"], slot["run_id"], slot["symbol"], channel, slot["side"],
                        now_ms, age_seconds, bucket, float(price or 0.0), current_return,
                        maximum, minimum, features.get("taker_buy_ratio_5s"),
                        features.get("taker_buy_ratio_15s"), features.get("taker_buy_ratio_30s"),
                        features.get("cvd"), features.get("cvd_slope"), features.get("obi_10"),
                        features.get("obi_20"), features.get("spread_bps"),
                        features.get("estimated_slippage_bps"), seconds_1m, seconds_5m,
                        seconds_15m, json.dumps(features.get("data_quality_flags", []), separators=(",", ":")),
                    ),
                )
                for target in self.FIXED_EXIT_AGES:
                    if bucket == target:
                        self._close_policy(slot, f"EXIT_{target}", now_ms, age_seconds, float(price or 0.0), current_return)
                for period, remaining in ((5, seconds_5m), (15, seconds_15m)):
                    for offset in self.CANDLE_EXIT_OFFSETS:
                        if 0.0 <= remaining - offset < sample_seconds:
                            self._close_policy(
                                slot, f"CANDLE_{period}M_CLOSE_MINUS_{offset}", now_ms,
                                age_seconds, float(price or 0.0), current_return,
                                reasons=["UTC_CANDLE_ALIGNED"],
                            )

            target_reached = maximum >= self.settings.short_target1_percent
            self._ensure_policy(str(slot["id"]), "ADAPTIVE_TARGET1_PROTECTION", now_ms)
            if target_reached:
                adaptive_score, adaptive_reasons = self._adaptive_score(slot, current_return, maximum, features)
                if adaptive_score >= self.settings.adaptive_protection_score:
                    self._close_policy(
                        slot, "ADAPTIVE_TARGET1_PROTECTION", now_ms, age_seconds,
                        float(price or 0.0), current_return, adaptive_score, adaptive_reasons,
                    )

            if channel != "REV_MC5_SHORT_600_2X":
                self.conn.commit()
                return
            rows = list(
                self.conn.execute(
                    "SELECT * FROM short_exit_challengers WHERE slot_id=? AND state='OPEN'",
                    (slot["id"],),
                )
            )
            for row in rows:
                maximum = max(float(row["max_directional_return_percent"] or 0.0), current_return)
                minimum = min(float(row["min_directional_return_percent"] or 0.0), current_return)
                target1_at = row["target1_at_ms"]
                target1_return = row["target1_return_percent"]
                if target1_at is None and current_return >= self.settings.short_target1_percent:
                    target1_at = now_ms
                    target1_return = current_return
                updates: dict[str, Any] = {
                    "target1_at_ms": target1_at,
                    "target1_return_percent": target1_return,
                    "max_directional_return_percent": maximum,
                    "min_directional_return_percent": minimum,
                    "last_updated_at_ms": now_ms,
                }
                if target1_at is not None and current_return <= float(row["protected_floor_percent"]):
                    weighted = current_return
                    if str(row["variant"]) == "TARGET1_PARTIAL_50":
                        weighted = 0.5 * float(target1_return or 0.0) + 0.5 * current_return
                    updates.update(
                        {
                            "state": "CLOSED",
                            "closed_at_ms": now_ms,
                            "exit_reason": "PROTECTED_FLOOR",
                            "weighted_directional_return_percent": weighted,
                            "estimated_net_margin_percent": self._estimate_net_margin(slot, weighted),
                        }
                    )
                assignments = ",".join(f"{key}=?" for key in updates)
                self.conn.execute(
                    f"UPDATE short_exit_challengers SET {assignments} WHERE slot_id=? AND variant=?",
                    (*updates.values(), slot["id"], row["variant"]),
                )
            if rows:
                self.conn.commit()

    def close_slot(
        self,
        slot,
        current_return: float,
        reason: str,
        now_ms: int,
        net_margin_percent: float,
        stop_info: Optional[dict[str, Any]],
        maximum: float,
        minimum: float,
    ) -> None:
        if str(slot["channel"]) not in {"REV_MC5_SHORT_600_2X", "DUMP_EXHAUSTION_LONG_SHADOW"}:
            return
        with self.lock:
            policies = list(
                self.conn.execute(
                    "SELECT * FROM exit_policy_outcomes WHERE slot_id=?",
                    (slot["id"],),
                )
            )
            for policy in policies:
                if str(policy["state"]) == "OPEN":
                    self.conn.execute(
                        """UPDATE exit_policy_outcomes SET state='CLOSED_CHAMPION',
                           champion_net_return_pct=?,last_updated_at_ms=?
                           WHERE slot_id=? AND policy=?""",
                        (net_margin_percent, now_ms, slot["id"], policy["policy"]),
                    )
                else:
                    challenger_net = float(policy["net_return_pct"] or 0.0)
                    self.conn.execute(
                        """UPDATE exit_policy_outcomes SET champion_net_return_pct=?,
                           avoided_loss_pct=?,missed_upside_pct=?,last_updated_at_ms=?
                           WHERE slot_id=? AND policy=?""",
                        (
                            net_margin_percent,
                            max(0.0, challenger_net - net_margin_percent),
                            max(0.0, net_margin_percent - challenger_net),
                            now_ms,
                            slot["id"],
                            policy["policy"],
                        ),
                    )
            if str(slot["channel"]) != "REV_MC5_SHORT_600_2X":
                self.conn.commit()
                return
            rows = list(
                self.conn.execute(
                    "SELECT * FROM short_exit_challengers WHERE slot_id=? AND state='OPEN'",
                    (slot["id"],),
                )
            )
            for row in rows:
                weighted = current_return
                if str(row["variant"]) == "TARGET1_PARTIAL_50" and row["target1_at_ms"] is not None:
                    weighted = 0.5 * float(row["target1_return_percent"] or 0.0) + 0.5 * current_return
                self.conn.execute(
                    """UPDATE short_exit_challengers SET state='CLOSED',closed_at_ms=?,
                       exit_reason=?,weighted_directional_return_percent=?,
                       estimated_net_margin_percent=?,
                       max_directional_return_percent=MAX(max_directional_return_percent,?),
                       min_directional_return_percent=MIN(min_directional_return_percent,?),
                       last_updated_at_ms=? WHERE slot_id=? AND variant=?""",
                    (
                        now_ms,
                        f"CHAMPION_{reason}",
                        weighted,
                        self._estimate_net_margin(slot, weighted),
                        maximum,
                        minimum,
                        now_ms,
                        slot["id"],
                        row["variant"],
                    ),
                )

            research = self.conn.execute(
                """SELECT continuation_pressure,reversal_confirmation
                   FROM short_signal_research WHERE signal_id=?""",
                (slot["signal_id"],),
            ).fetchone()
            continuation = float(research["continuation_pressure"]) if research else None
            reversal = float(research["reversal_confirmation"]) if research else None
            slippage = float((stop_info or {}).get("total_slippage") or 0.0)
            profitable = net_margin_percent > 0.0
            labels: list[str] = []
            if profitable:
                labels.append("WIN")
            else:
                if maximum >= self.settings.short_target1_percent:
                    labels.append("PROFIT_PROTECTION_FAILURE")
                if reason == "PRICE_STOP" and continuation is not None and continuation >= self.settings.short_continuation_veto_score:
                    labels.append("CONTINUATION_SQUEEZE")
                if reason == "PRICE_STOP" and maximum < 0.75:
                    labels.append("SIGNAL_FAILURE")
                if reason == "PRICE_STOP" and slippage >= self.settings.short_execution_failure_slippage_points:
                    labels.append("EXECUTION_FAILURE")
                if not labels:
                    labels.append("STOP_PATH_FAILURE" if reason == "PRICE_STOP" else "SIGNAL_FAILURE")
            priority = (
                "EXECUTION_FAILURE",
                "CONTINUATION_SQUEEZE",
                "PROFIT_PROTECTION_FAILURE",
                "STOP_PATH_FAILURE",
                "SIGNAL_FAILURE",
                "WIN",
            )
            primary = next(label for label in priority if label in labels)
            details = {
                "exit_reason": reason,
                "champion_net_margin_percent": net_margin_percent,
                "current_directional_return_percent": current_return,
                "max_directional_return_percent": maximum,
                "min_directional_return_percent": minimum,
                "stop": stop_info or {},
            }
            self.conn.execute(
                """INSERT OR REPLACE INTO short_failure_labels(
                   slot_id,signal_id,symbol,closed_at_ms,champion_profitable,
                   primary_label,labels_json,continuation_pressure,
                   reversal_confirmation,max_directional_return_percent,
                   min_directional_return_percent,stop_total_slippage_points,
                   details_json,algorithm_version,strategy_version,config_hash
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    slot["id"],
                    slot["signal_id"],
                    slot["symbol"],
                    now_ms,
                    int(profitable),
                    primary,
                    json.dumps(labels, separators=(",", ":")),
                    continuation,
                    reversal,
                    maximum,
                    minimum,
                    slippage,
                    json.dumps(details, sort_keys=True, separators=(",", ":")),
                    self.settings.algorithm_version,
                    self.settings.strategy_version,
                    self.settings.config_hash(),
                ),
            )
            self.conn.commit()

    @staticmethod
    def _profit_factor(values: list[float]) -> Optional[float]:
        wins = sum(value for value in values if value > 0)
        losses = -sum(value for value in values if value < 0)
        if losses <= 0:
            return None if wins <= 0 else 999.0
        return wins / losses

    def dump_health(self) -> dict[str, Any]:
        with self.lock:
            rows = list(
                self.conn.execute(
                    """SELECT id,net_pnl_usdt,exit_reason,max_directional_return_percent,
                              net_return_margin_percent
                       FROM regime_slots
                       WHERE channel='DUMP_EXHAUSTION_LONG_SHADOW' AND status='CLOSED'
                       ORDER BY closed_at_ms DESC LIMIT 20"""
                )
            )
        values = [float(row["net_pnl_usdt"] or 0.0) for row in rows]
        last10, last20 = values[:10], values[:20]
        stops = sum(str(row["exit_reason"]) == "PRICE_STOP" for row in rows)
        reached = [row for row in rows if float(row["max_directional_return_percent"] or 0.0) >= self.settings.short_target1_percent]
        givebacks = sum(float(row["net_return_margin_percent"] or 0.0) <= 0.0 for row in reached)
        pf20 = self._profit_factor(last20)
        state = "HEALTHY"
        if len(last20) >= 10 and (sum(last20) < 0 or (pf20 is not None and pf20 < 1.0)):
            state = "DEGRADED"
        elif len(last10) >= 5 and (sum(last10) <= 0 or (self._profit_factor(last10) or 0) < 1.2):
            state = "CAUTION"
        result = {
            "state": state,
            "last10_pnl": sum(last10),
            "last20_pnl": sum(last20),
            "last10_pf": self._profit_factor(last10),
            "last20_pf": pf20,
            "stop_rate": stops / len(rows) if rows else 0.0,
            "target1_giveback_rate": givebacks / len(reached) if reached else 0.0,
            "sample_size": len(rows),
        }
        measured_at_ms = int(time.time() * 1000) // 60_000 * 60_000
        with self.lock:
            run = self.conn.execute(
                "SELECT id FROM experiment_runs ORDER BY started_at_ms DESC LIMIT 1"
            ).fetchone()
            if run is not None:
                self.conn.execute(
                    """INSERT OR REPLACE INTO dump_health_diagnostics(
                       measured_at_ms,run_id,last10_pnl,last20_pnl,last10_pf,last20_pf,
                       stop_rate,target1_giveback_rate,regime_state,details_json
                       ) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    (
                        measured_at_ms,
                        run[0],
                        result["last10_pnl"],
                        result["last20_pnl"],
                        result["last10_pf"],
                        result["last20_pf"],
                        result["stop_rate"],
                        result["target1_giveback_rate"],
                        result["state"],
                        json.dumps({"sample_size": len(rows)}, separators=(",", ":")),
                    ),
                )
                self.conn.commit()
        return result

    def status(self) -> dict[str, Any]:
        btc_status, btc_payload = self._load_btc_regime(int(time.time() * 1000))
        version = (
            self.settings.algorithm_version,
            self.settings.strategy_version,
            self.settings.config_hash(),
        )
        with self.lock:
            signal = self.conn.execute(
                """SELECT COUNT(*) n,
                          SUM(challenger_a='PASS') a_pass,
                          SUM(challenger_b='PASS') b_pass,
                          SUM(challenger_c='PASS') c_pass,
                          AVG(continuation_pressure) avg_cont,
                          AVG(reversal_confirmation) avg_rev
                   FROM short_signal_research
                   WHERE algorithm_version=? AND strategy_version=? AND config_hash=?""",
                version,
            ).fetchone()
            exits = {
                str(row["variant"]): {
                    "n": int(row["n"] or 0),
                    "closed": int(row["closed"] or 0),
                    "avg_estimated_net_margin": (
                        float(row["avg_net"]) if row["avg_net"] is not None else None
                    ),
                }
                for row in self.conn.execute(
                    """SELECT variant,COUNT(*) n,SUM(state='CLOSED') closed,
                              AVG(CASE WHEN state='CLOSED' THEN estimated_net_margin_percent END) avg_net
                       FROM short_exit_challengers
                       WHERE algorithm_version=? AND strategy_version=? AND config_hash=?
                       GROUP BY variant""",
                    version,
                )
            }
            failures = {
                str(row["primary_label"]): int(row["n"])
                for row in self.conn.execute(
                    """SELECT primary_label,COUNT(*) n FROM short_failure_labels
                       WHERE algorithm_version=? AND strategy_version=? AND config_hash=?
                       GROUP BY primary_label""",
                    version,
                )
            }
        return {
            "short_research": {
                "mode": "SHADOW_ONLY",
                "signals": int(signal["n"] or 0),
                "challenger_a_pass": int(signal["a_pass"] or 0),
                "challenger_b_pass": int(signal["b_pass"] or 0),
                "challenger_c_pass": int(signal["c_pass"] or 0),
                "avg_continuation_pressure": (
                    float(signal["avg_cont"]) if signal["avg_cont"] is not None else None
                ),
                "avg_reversal_confirmation": (
                    float(signal["avg_rev"]) if signal["avg_rev"] is not None else None
                ),
                "exit_challengers": exits,
                "failure_labels": failures,
                "btc_regime": {
                    "status": btc_status,
                    "as_of_ms": (btc_payload or {}).get("as_of_ms"),
                    "short_squeeze_risk": (btc_payload or {}).get("short_squeeze_risk"),
                    "metrics_count": (btc_payload or {}).get("metrics_count"),
                    "source": (btc_payload or {}).get("source"),
                },
            },
            "exit_window_research": {
                "mode": "SHADOW_ONLY",
                "samples": self.conn.execute("SELECT COUNT(*) FROM exit_window_samples").fetchone()[0],
                "closed_policies": self.conn.execute("SELECT COUNT(*) FROM exit_policy_outcomes WHERE state='CLOSED'").fetchone()[0],
            },
            "dump_health": self.dump_health(),
        }
