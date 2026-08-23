#!/usr/bin/env python3
from __future__ import annotations

import argparse
import importlib.util
import json
import sqlite3
import sys
import time
import uuid
from pathlib import Path
from typing import Any, Optional

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("tradelab_r4_base", HERE / "tradelab_r4_sidecar.py")
base = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = base
spec.loader.exec_module(base)

R4_OPEN = "R4_OPEN"
CLEAN_KEY = "r4_isolation_hotfix_started_at_ms"
HFT_RETIRED_KEY = "retired_at_ms_HFT_GRID"
OI = "OI_FLUSH_REVERSION"
LIQ = "LIQ_CASCADE_REVERSION"
LEGACY_ACTIVE5 = (*base.EXISTING_ACTIVE, base.HFT, base.EXTREME)
ACTIVE6 = (*base.EXISTING_ACTIVE, base.EXTREME, OI, LIQ)

OI_CONFIG = {
    "mode": "OI_FLUSH_STALL_REVERSION_V1",
    "oi_window_ms": 600_000,
    "oi_current_fresh_max_ms": 120_000,
    "oi_prior_tolerance_ms": 120_000,
    "flush_oi_drop_pct": -0.75,
    "price_move_min_pct": 0.80,
    "stall_last_60s_abs_max_pct": 0.15,
    "min_oi_value_usd": 5_000_000,
    "min_quote_volume_24h": 5_000_000,
    "max_spread_bps": 10.0,
    "horizon_seconds": 240,
    "stop_bps_past_extreme": 40.0,
    "cooldown_seconds": 900,
    "max_open_trades": 2,
    "common_cost_pct": base.COMMON_ROUND_TRIP_COST_PCT,
}

LIQ_CONFIG = {
    "mode": "LIQ_CASCADE_REVERSAL_V1",
    "window_seconds": 60,
    "same_side_notional_min_usd": 20_000,
    "side_dominance_min": 0.75,
    "price_move_min_pct": 0.80,
    "confirmation_15s_pct": 0.05,
    "min_quote_volume_24h": 5_000_000,
    "max_spread_bps": 10.0,
    "horizon_seconds": 180,
    "stop_bps_past_extreme": 40.0,
    "cooldown_seconds": 300,
    "max_open_trades": 2,
    "common_cost_pct": base.COMMON_ROUND_TRIP_COST_PCT,
}

CONFIGS = {
    base.EXTREME: base.EXTREME_CONFIG,
    OI: OI_CONFIG,
    LIQ: LIQ_CONFIG,
}


def upsert_meta(con: sqlite3.Connection, key: str, value: str) -> None:
    con.execute("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, value))


def meta_int(con: sqlite3.Connection, key: str, default: int = 0) -> int:
    row = con.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
    return int(row[0]) if row else default


def apply_isolation(con: sqlite3.Connection) -> int:
    row = con.execute("SELECT value FROM meta WHERE key=?", (CLEAN_KEY,)).fetchone()
    if row:
        clean_ts = int(row[0])
    else:
        clean_ts = int(time.time() * 1000)
        before = {r["participant_id"]: float(r["equity"]) for r in con.execute(
            "SELECT participant_id,equity FROM participants WHERE participant_id IN (?,?,?,?,?)", LEGACY_ACTIVE5
        )}
        upsert_meta(con, "pre_r4_isolation_equity_json", json.dumps(before, sort_keys=True, separators=(",", ":")))
        upsert_meta(con, CLEAN_KEY, str(clean_ts))
        upsert_meta(con, "five_model_epoch_id", f"R4C-{clean_ts}-{uuid.uuid4().hex[:8]}")
        upsert_meta(con, "five_model_epoch_started_at_ms", str(clean_ts))
        upsert_meta(con, "five_model_epoch_mode", "FIVE_MODEL_SHADOW_R4_ISOLATED")
        upsert_meta(con, "five_model_score_rule", "score only trades opened_at_ms >= participant score-start; R4 sidecar positions use status=R4_OPEN")

        for r in con.execute(
            "SELECT trade_id,participant_id,symbol_a FROM paper_trades WHERE participant_id IN (?,?) AND status='OPEN'",
            (base.HFT, base.EXTREME),
        ).fetchall():
            con.execute(
                "UPDATE paper_trades SET status='R4_CANCELLED_HOTFIX',closed_at_ms=? WHERE trade_id=? AND status='OPEN'",
                (clean_ts, r["trade_id"]),
            )
            base.add_event(con, r["participant_id"], clean_ts, r["symbol_a"], "R4_HOTFIX_CANCEL", {"trade_id": r["trade_id"], "reason": "paper_book isolation"})

        con.execute(
            "UPDATE participants SET starting_equity=20.0,equity=20.0,status='ACTIVE',role='CANDIDATE' WHERE participant_id IN (?,?,?,?,?)",
            LEGACY_ACTIVE5,
        )
        con.execute("UPDATE participants SET status='RETIRED',role='ELIMINATED' WHERE participant_id=?", (base.RETIRED,))
        con.execute("UPDATE participant_specs SET active_effect='RETIRED_NO_SCORE' WHERE participant_id=?", (base.RETIRED,))

    con.executescript("""
    CREATE TRIGGER IF NOT EXISTS r4_block_flow_events
    BEFORE INSERT ON participant_events
    WHEN NEW.participant_id='FLOW_ABSORPTION'
      AND NEW.ts_ms >= CAST(COALESCE((SELECT value FROM meta WHERE key='r4_isolation_hotfix_started_at_ms'),'0') AS INTEGER)
    BEGIN SELECT RAISE(IGNORE); END;

    CREATE TRIGGER IF NOT EXISTS r4_block_flow_states
    BEFORE INSERT ON market_states
    WHEN NEW.source='FLOW_ABSORPTION'
      AND NEW.ts_ms >= CAST(COALESCE((SELECT value FROM meta WHERE key='r4_isolation_hotfix_started_at_ms'),'0') AS INTEGER)
    BEGIN SELECT RAISE(IGNORE); END;

    CREATE TRIGGER IF NOT EXISTS r4_block_flow_trades
    BEFORE INSERT ON paper_trades
    WHEN NEW.participant_id='FLOW_ABSORPTION'
      AND NEW.opened_at_ms >= CAST(COALESCE((SELECT value FROM meta WHERE key='r4_isolation_hotfix_started_at_ms'),'0') AS INTEGER)
    BEGIN SELECT RAISE(IGNORE); END;

    CREATE TRIGGER IF NOT EXISTS r4_freeze_flow_equity
    BEFORE UPDATE OF equity ON participants
    WHEN OLD.participant_id='FLOW_ABSORPTION' AND OLD.status='RETIRED'
    BEGIN SELECT RAISE(IGNORE); END;
    """)
    con.commit()
    return clean_ts


def apply_candidate_set(con: sqlite3.Connection) -> None:
    tables = base.table_names(con)
    missing = {"open_interest_samples", "liquidations"} - tables
    if missing:
        raise RuntimeError(f"R4 +2 candidates require tables: {sorted(missing)}")

    now = int(time.time() * 1000)
    retired_at = meta_int(con, HFT_RETIRED_KEY)
    if not retired_at:
        retired_at = now
        upsert_meta(con, HFT_RETIRED_KEY, str(retired_at))
        for r in con.execute(
            "SELECT trade_id,symbol_a FROM paper_trades WHERE participant_id=? AND status=?",
            (base.HFT, R4_OPEN),
        ).fetchall():
            con.execute(
                "UPDATE paper_trades SET status='R4_CANCELLED_RETIRE',closed_at_ms=? WHERE trade_id=? AND status=?",
                (retired_at, r["trade_id"], R4_OPEN),
            )
            base.add_event(con, base.HFT, retired_at, r["symbol_a"], "R4_RETIRE_CANCEL", {
                "trade_id": r["trade_id"], "reason": "HFT_GRID retired; queue-unaware execution rejected"
            })
        base.add_event(con, base.HFT, retired_at, None, "R4_RETIRED", {
            "reason": "TOUCH_PROXY_V1 execution/adverse-selection failure", "replacement_slots": [OI, LIQ]
        })

    con.execute("UPDATE participants SET status='RETIRED',role='ELIMINATED' WHERE participant_id=?", (base.HFT,))
    con.execute("UPDATE participant_specs SET active_effect='RETIRED_NO_SCORE' WHERE participant_id=?", (base.HFT,))

    definitions = [
        (OI, "OI Flush Reversion", "OI1-20260823", OI_CONFIG),
        (LIQ, "Liquidation Cascade Reversion", "LQ1-20260823", LIQ_CONFIG),
    ]
    for pid, display_name, version, cfg in definitions:
        join_key = f"join_started_at_ms_{pid}"
        join_ts = meta_int(con, join_key)
        existed = con.execute("SELECT 1 FROM participants WHERE participant_id=?", (pid,)).fetchone() is not None
        if not join_ts:
            join_ts = now
            upsert_meta(con, join_key, str(join_ts))
        if not existed:
            con.execute(
                "INSERT INTO participants(participant_id,display_name,status,starting_equity,equity,rank,role,created_at_ms) "
                "VALUES(?,?, 'ACTIVE',20.0,20.0,NULL,'CANDIDATE',?)",
                (pid, display_name, join_ts),
            )
            con.execute(
                "INSERT INTO participant_specs(participant_id,spec_version,config_json,frozen_at_ms,active_effect) VALUES(?,?,?,?, 'SHADOW_ONLY')",
                (pid, version, base.jdump(cfg), join_ts),
            )
            base.add_event(con, pid, join_ts, None, "R4_JOIN", {
                "join_started_at_ms": join_ts, "starting_equity": 20.0, "spec_version": version, "config": cfg
            })
        else:
            con.execute("UPDATE participants SET display_name=?,status='ACTIVE',role='CANDIDATE' WHERE participant_id=?", (display_name, pid))
            con.execute("UPDATE participant_specs SET active_effect='SHADOW_ONLY' WHERE participant_id=?", (pid,))

    upsert_meta(con, "r4_active_ids_v2", base.jdump(list(ACTIVE6)))
    upsert_meta(con, "five_model_active_ids", base.jdump(list(ACTIVE6)))
    upsert_meta(con, "r4_candidate_set_version", "R4C_PLUS2_NO_HFT_V1")

    con.executescript("""
    CREATE TRIGGER IF NOT EXISTS r4_block_hft_events
    BEFORE INSERT ON participant_events
    WHEN NEW.participant_id='HFT_GRID'
      AND NEW.ts_ms >= CAST(COALESCE((SELECT value FROM meta WHERE key='retired_at_ms_HFT_GRID'),'0') AS INTEGER)
    BEGIN SELECT RAISE(IGNORE); END;

    CREATE TRIGGER IF NOT EXISTS r4_block_hft_states
    BEFORE INSERT ON market_states
    WHEN NEW.source='HFT_GRID'
      AND NEW.ts_ms >= CAST(COALESCE((SELECT value FROM meta WHERE key='retired_at_ms_HFT_GRID'),'0') AS INTEGER)
    BEGIN SELECT RAISE(IGNORE); END;

    CREATE TRIGGER IF NOT EXISTS r4_block_hft_trades
    BEFORE INSERT ON paper_trades
    WHEN NEW.participant_id='HFT_GRID'
      AND NEW.opened_at_ms >= CAST(COALESCE((SELECT value FROM meta WHERE key='retired_at_ms_HFT_GRID'),'0') AS INTEGER)
    BEGIN SELECT RAISE(IGNORE); END;

    CREATE TRIGGER IF NOT EXISTS r4_freeze_hft_equity
    BEFORE UPDATE OF equity ON participants
    WHEN OLD.participant_id='HFT_GRID' AND OLD.status='RETIRED'
    BEGIN SELECT RAISE(IGNORE); END;
    """)
    con.commit()


class IsolatedSidecar(base.Sidecar):
    def _participant_config(self, participant: str) -> dict[str, Any]:
        return CONFIGS[participant]

    def _score_start(self, participant: str) -> int:
        if participant in (OI, LIQ):
            return meta_int(self.con, f"join_started_at_ms_{participant}")
        return self.epoch_ts

    def _restore_cooldowns(self) -> None:
        for pid, cfg in CONFIGS.items():
            seconds = int(cfg["cooldown_seconds"])
            rows = self.con.execute(
                "SELECT symbol,MAX(ts_ms) AS ts FROM participant_events "
                "WHERE participant_id=? AND event_type='SIGNAL' AND symbol IS NOT NULL AND ts_ms>=? GROUP BY symbol",
                (pid, self._score_start(pid)),
            ).fetchall()
            for row in rows:
                self.cooldown_until[(pid, row["symbol"])] = int(row["ts"]) + seconds * 1000

    def _open_count(self, participant: str) -> int:
        return int(self.con.execute(
            "SELECT COUNT(*) FROM paper_trades WHERE participant_id=? AND status=?",
            (participant, R4_OPEN),
        ).fetchone()[0])

    def _symbol_open(self, participant: str, symbol: str) -> bool:
        return self.con.execute(
            "SELECT 1 FROM paper_trades WHERE participant_id=? AND symbol_a=? AND status=? LIMIT 1",
            (participant, symbol, R4_OPEN),
        ).fetchone() is not None

    def _emit_signal_and_open(self, participant: str, ts: int, symbol: str, side: str, price: float, horizon_s: int, payload: dict[str, Any]) -> bool:
        if participant not in CONFIGS or ts < self._score_start(participant):
            return False
        payload = dict(payload)
        payload.update({
            "side_a": side, "symbol_a": symbol, "symbol_b": None, "side_b": None,
            "hedge_ratio": None, "horizon_seconds": horizon_s,
            "spec_mode": "R4C_PLUS2_NO_HFT_V1", "paper_owner": "R4_SIDECAR",
        })
        base.add_event(self.con, participant, ts, symbol, "SIGNAL", payload)
        self.con.execute("INSERT INTO market_states(ts_ms,symbol,source,payload_json) VALUES(?,?,?,?)", (ts, symbol, participant, base.jdump(payload)))

        max_open = int(self._participant_config(participant)["max_open_trades"])
        if self._open_count(participant) >= max_open or self._symbol_open(participant, symbol):
            base.add_event(self.con, participant, ts, symbol, "PAPER_SKIPPED_CAPACITY", payload)
            self.con.commit()
            return False

        trade_id = uuid.uuid4().hex
        notional = max(0.0, self._equity(participant) / max_open)
        self.con.execute(
            "INSERT INTO paper_trades(trade_id,participant_id,symbol_a,symbol_b,side_a,side_b,hedge_ratio,opened_at_ms,entry_a,entry_b,exit_due_ms,notional_usdt,status,signal_json) "
            "VALUES(?,?,?,NULL,?,NULL,NULL,?,?,NULL,?,?,?,?)",
            (trade_id, participant, symbol, side, ts, price, ts + horizon_s * 1000, notional, R4_OPEN, base.jdump(payload)),
        )
        base.add_event(self.con, participant, ts, symbol, "PAPER_OPEN", {**payload, "trade_id": trade_id, "entry_a": price, "notional_usdt": notional})
        self.con.commit()
        return True

    def _close_trade(self, trade: sqlite3.Row, ts: int, exit_price: float, reason: str) -> bool:
        gross = base.pct_return(float(trade["entry_a"]), exit_price, trade["side_a"])
        net = gross - base.COMMON_ROUND_TRIP_COST_PCT
        pnl = float(trade["notional_usdt"]) * net / 100.0
        cur = self.con.execute(
            "UPDATE paper_trades SET closed_at_ms=?,exit_a=?,gross_return_pct=?,net_return_pct=?,pnl_usdt=?,status='CLOSED' "
            "WHERE trade_id=? AND status=?",
            (ts, exit_price, gross, net, pnl, trade["trade_id"], R4_OPEN),
        )
        if cur.rowcount != 1:
            self.con.rollback()
            return False
        self.con.execute("UPDATE participants SET equity=equity+? WHERE participant_id=?", (pnl, trade["participant_id"]))
        base.add_event(self.con, trade["participant_id"], ts, trade["symbol_a"], "PAPER_CLOSE", {
            "trade_id": trade["trade_id"], "exit_reason": reason, "gross_return_pct": gross,
            "net_return_pct": net, "pnl_usdt": pnl, "exit_sample_ms": ts,
            "common_round_trip_cost_pct": base.COMMON_ROUND_TRIP_COST_PCT, "paper_owner": "R4_SIDECAR",
        })
        self.con.commit()
        return True

    def _close_due_extreme(self, symbol: str, ts: int, price: float) -> None:
        rows = self.con.execute(
            "SELECT * FROM paper_trades WHERE participant_id=? AND symbol_a=? AND status=? AND exit_due_ms<=? ORDER BY exit_due_ms",
            (base.EXTREME, symbol, R4_OPEN, ts),
        ).fetchall()
        for trade in rows:
            self._close_trade(trade, ts, price, "FIXED_300S")

    def _close_reversion(self, participant: str, symbol: str, ts: int, price: float) -> None:
        rows = self.con.execute(
            "SELECT * FROM paper_trades WHERE participant_id=? AND symbol_a=? AND status=? ORDER BY opened_at_ms",
            (participant, symbol, R4_OPEN),
        ).fetchall()
        for trade in rows:
            sig = json.loads(trade["signal_json"])
            stop_price = float(sig["features"]["stop_price"])
            stopped = (trade["side_a"] == "LONG" and price <= stop_price) or (trade["side_a"] == "SHORT" and price >= stop_price)
            if stopped:
                self._close_trade(trade, ts, price, "ADVERSE_STOP")
            elif ts >= int(trade["exit_due_ms"]):
                self._close_trade(trade, ts, price, "FIXED_HORIZON")

    def _market_at_or_before(self, symbol: str, target_ts: int, max_age_ms: int = 10_000) -> Optional[sqlite3.Row]:
        return self.con.execute(
            "SELECT * FROM market_samples WHERE symbol=? AND ts_ms<=? AND ts_ms>=? ORDER BY ts_ms DESC LIMIT 1",
            (symbol, target_ts, target_ts - max_age_ms),
        ).fetchone()

    def _window_extreme(self, symbol: str, start_ts: int, end_ts: int, side: str) -> Optional[float]:
        col = "MIN(last_price)" if side == "LONG" else "MAX(last_price)"
        row = self.con.execute(
            f"SELECT {col} FROM market_samples WHERE symbol=? AND ts_ms>=? AND ts_ms<=?",
            (symbol, start_ts, end_ts),
        ).fetchone()
        return float(row[0]) if row and row[0] is not None else None

    @staticmethod
    def _spread_bps(r: sqlite3.Row) -> Optional[float]:
        bid, ask = r["bid"], r["ask"]
        if bid is None or ask is None or float(bid) <= 0 or float(ask) <= 0:
            return None
        mid = (float(bid) + float(ask)) / 2.0
        return (float(ask) - float(bid)) / mid * 10_000.0 if mid > 0 else None

    def _market_gate(self, r: sqlite3.Row, cfg: dict[str, Any]) -> Optional[float]:
        spread = self._spread_bps(r)
        if spread is None or spread > float(cfg["max_spread_bps"]):
            return None
        if r["quote_volume_24h"] is None or float(r["quote_volume_24h"]) < float(cfg["min_quote_volume_24h"]):
            return None
        return spread

    def _maybe_oi_flush(self, r: sqlite3.Row) -> None:
        ts, symbol, price = int(r["ts_ms"]), r["symbol"], float(r["last_price"])
        if ts < self._score_start(OI) or ts < self.cooldown_until.get((OI, symbol), 0):
            return
        spread = self._market_gate(r, OI_CONFIG)
        if spread is None:
            return

        current_oi = self.con.execute(
            "SELECT * FROM open_interest_samples WHERE symbol=? AND ts_ms<=? AND ts_ms>=? ORDER BY ts_ms DESC LIMIT 1",
            (symbol, ts, ts - int(OI_CONFIG["oi_current_fresh_max_ms"])),
        ).fetchone()
        if current_oi is None or float(current_oi["open_interest"] or 0) <= 0:
            return
        if current_oi["open_interest_value"] is None or float(current_oi["open_interest_value"]) < float(OI_CONFIG["min_oi_value_usd"]):
            return

        target = ts - int(OI_CONFIG["oi_window_ms"])
        tol = int(OI_CONFIG["oi_prior_tolerance_ms"])
        prior_oi = self.con.execute(
            "SELECT * FROM open_interest_samples WHERE symbol=? AND ts_ms<=? AND ts_ms>=? ORDER BY ts_ms DESC LIMIT 1",
            (symbol, target, target - tol),
        ).fetchone()
        if prior_oi is None or float(prior_oi["open_interest"] or 0) <= 0:
            return

        oi_delta = (float(current_oi["open_interest"]) / float(prior_oi["open_interest"]) - 1.0) * 100.0
        if oi_delta > float(OI_CONFIG["flush_oi_drop_pct"]):
            return

        p0 = self._market_at_or_before(symbol, target)
        p60 = self._market_at_or_before(symbol, ts - 60_000)
        if p0 is None or p60 is None:
            return
        displacement = (price / float(p0["last_price"]) - 1.0) * 100.0
        stall60 = (price / float(p60["last_price"]) - 1.0) * 100.0
        if abs(displacement) < float(OI_CONFIG["price_move_min_pct"]):
            return
        if abs(stall60) > float(OI_CONFIG["stall_last_60s_abs_max_pct"]):
            return

        side = "SHORT" if displacement > 0 else "LONG"
        extreme = self._window_extreme(symbol, target, ts, side)
        if extreme is None:
            return
        stop_bps = float(OI_CONFIG["stop_bps_past_extreme"])
        stop_price = extreme * (1.0 - stop_bps / 10_000.0) if side == "LONG" else extreme * (1.0 + stop_bps / 10_000.0)
        payload = {"features": {
            "oi_delta_10m_pct": oi_delta,
            "oi_current_age_ms": ts - int(current_oi["ts_ms"]),
            "oi_prior_error_ms": target - int(prior_oi["ts_ms"]),
            "price_displacement_10m_pct": displacement,
            "stall_60s_pct": stall60,
            "spread_bps": spread,
            "quote_volume_24h": r["quote_volume_24h"],
            "pre_entry_extreme": extreme,
            "stop_price": stop_price,
        }, "score": abs(oi_delta) + abs(displacement)}
        self._emit_signal_and_open(OI, ts, symbol, side, price, int(OI_CONFIG["horizon_seconds"]), payload)
        self.cooldown_until[(OI, symbol)] = ts + int(OI_CONFIG["cooldown_seconds"]) * 1000

    def _maybe_liq_cascade(self, r: sqlite3.Row) -> None:
        ts, symbol, price = int(r["ts_ms"]), r["symbol"], float(r["last_price"])
        if ts < self._score_start(LIQ) or ts < self.cooldown_until.get((LIQ, symbol), 0):
            return
        spread = self._market_gate(r, LIQ_CONFIG)
        if spread is None:
            return

        start = ts - int(LIQ_CONFIG["window_seconds"]) * 1000
        agg = self.con.execute(
            "SELECT COALESCE(SUM(CASE WHEN side='BUY' THEN notional ELSE 0 END),0) AS buy_n, "
            "COALESCE(SUM(CASE WHEN side='SELL' THEN notional ELSE 0 END),0) AS sell_n "
            "FROM liquidations WHERE symbol=? AND ts_ms>? AND ts_ms<=?",
            (symbol, start, ts),
        ).fetchone()
        buy_n, sell_n = float(agg["buy_n"]), float(agg["sell_n"])
        total = buy_n + sell_n
        major = max(buy_n, sell_n)
        if total <= 0 or major < float(LIQ_CONFIG["same_side_notional_min_usd"]):
            return
        dominance = major / total
        if dominance < float(LIQ_CONFIG["side_dominance_min"]):
            return

        p60 = self._market_at_or_before(symbol, ts - 60_000)
        p15 = self._market_at_or_before(symbol, ts - 15_000)
        if p60 is None or p15 is None:
            return
        ret60 = (price / float(p60["last_price"]) - 1.0) * 100.0
        ret15 = (price / float(p15["last_price"]) - 1.0) * 100.0
        move = float(LIQ_CONFIG["price_move_min_pct"])
        confirm = float(LIQ_CONFIG["confirmation_15s_pct"])
        dominant_side = "BUY" if buy_n > sell_n else "SELL"
        if dominant_side == "SELL":
            if ret60 > -move or ret15 < confirm:
                return
            side = "LONG"
        else:
            if ret60 < move or ret15 > -confirm:
                return
            side = "SHORT"

        extreme = self._window_extreme(symbol, start, ts, side)
        if extreme is None:
            return
        stop_bps = float(LIQ_CONFIG["stop_bps_past_extreme"])
        stop_price = extreme * (1.0 - stop_bps / 10_000.0) if side == "LONG" else extreme * (1.0 + stop_bps / 10_000.0)
        payload = {"features": {
            "liq_buy_notional_60s": buy_n,
            "liq_sell_notional_60s": sell_n,
            "dominant_liq_side": dominant_side,
            "dominance": dominance,
            "price_return_60s_pct": ret60,
            "confirmation_15s_pct": ret15,
            "spread_bps": spread,
            "quote_volume_24h": r["quote_volume_24h"],
            "pre_entry_extreme": extreme,
            "stop_price": stop_price,
        }, "score": major / 20_000.0 + abs(ret60)}
        self._emit_signal_and_open(LIQ, ts, symbol, side, price, int(LIQ_CONFIG["horizon_seconds"]), payload)
        self.cooldown_until[(LIQ, symbol)] = ts + int(LIQ_CONFIG["cooldown_seconds"]) * 1000

    def process_row(self, r: sqlite3.Row) -> None:
        symbol, ts, price = r["symbol"], int(r["ts_ms"]), float(r["last_price"])
        self._close_due_extreme(symbol, ts, price)
        self._close_reversion(OI, symbol, ts, price)
        self._close_reversion(LIQ, symbol, ts, price)
        self._append_history(symbol, ts, price)
        self._maybe_extreme(r)
        self._maybe_oi_flush(r)
        self._maybe_liq_cascade(r)


def score_start(con: sqlite3.Connection, pid: str, epoch: int) -> int:
    if pid in (OI, LIQ):
        return meta_int(con, f"join_started_at_ms_{pid}", epoch)
    return epoch


def status(con: sqlite3.Connection) -> dict[str, Any]:
    epoch = meta_int(con, "five_model_epoch_started_at_ms")
    out = {
        "mode": "R4C_PLUS2_NO_HFT_V1",
        "epoch_started_at_ms": epoch,
        "active_ids": list(ACTIVE6),
        "retired_ids": [base.RETIRED, base.HFT],
        "scorecard": [],
    }
    for pid in ACTIVE6:
        start = score_start(con, pid, epoch)
        r = con.execute(
            "SELECT COUNT(*) n,COALESCE(SUM(pnl_usdt),0) pnl,COALESCE(AVG(net_return_pct),0) avg_net "
            "FROM paper_trades WHERE participant_id=? AND opened_at_ms>=? AND status='CLOSED'",
            (pid, start),
        ).fetchone()
        o = con.execute(
            "SELECT COUNT(*) FROM paper_trades WHERE participant_id=? AND opened_at_ms>=? AND status IN ('OPEN','R4_OPEN')",
            (pid, start),
        ).fetchone()[0]
        eq = con.execute("SELECT equity FROM participants WHERE participant_id=?", (pid,)).fetchone()
        pnl = float(r["pnl"])
        out["scorecard"].append({
            "participant_id": pid,
            "score_from_ms": start,
            "physical_equity": float(eq[0]) if eq else 20.0,
            "score_equity": 20.0 + pnl,
            "closed": int(r["n"]),
            "open": int(o),
            "pnl_usdt": pnl,
            "avg_net_pct": float(r["avg_net"]),
        })
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--status", action="store_true")
    ap.add_argument("--run", action="store_true")
    args = ap.parse_args()
    db = base.detect_db(args.db)
    con = base.connect(db)
    # Once the clean R4 isolation exists, never call base.activate_r4() again:
    # it intentionally re-activates the legacy HFT slot.  A retired HFT must
    # remain retired across service restarts, including the tiny interval
    # before candidate-set triggers are re-asserted.
    if meta_int(con, CLEAN_KEY):
        base.required_schema_ok(con)
    else:
        base.activate_r4(con)
    clean = apply_isolation(con)
    apply_candidate_set(con)
    if args.apply and not args.run:
        print(json.dumps(status(con), ensure_ascii=False, indent=2, sort_keys=True))
        return
    if args.status:
        print(json.dumps(status(con), ensure_ascii=False, indent=2, sort_keys=True))
        return
    if args.run:
        IsolatedSidecar(con, clean).run()
        return
    ap.error("choose --apply, --status, or --run")


if __name__ == "__main__":
    main()
