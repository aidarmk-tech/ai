#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import os
import signal
import sqlite3
import statistics
import time
import uuid
from collections import defaultdict, deque
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Deque, Optional

R4_MODE = "FIVE_MODEL_SHADOW_R4"
COMMON_ROUND_TRIP_COST_PCT = 0.14
MAKER_COUNTERFACTUAL_COST_PCT = 0.04
POLL_SECONDS = 1.0
GAP_MIN_MS = 60_000  # G7: per-symbol data-gap threshold for recorder_gaps

EXISTING_ACTIVE = ("BTC_ALT_LAG", "STAT_ARB", "REGIME_MOMENTUM")
RETIRED = "FLOW_ABSORPTION"
HFT = "HFT_GRID"
EXTREME = "EXTREME_REVERSION"

HFT_CONFIG = {
    "mode": "TOUCH_PROXY_V1_NO_QUEUE_MODEL",
    "min_quote_volume_24h": 100_000_000,
    "max_spread_bps": 4.0,
    "depth_fresh_ms": 3_000,
    "imbalance_abs": 0.50,
    "min_half_spacing_bps": 20.0,
    "max_half_spacing_bps": 40.0,
    "vol_spacing_multiplier": 0.25,
    "target_multiple": 2.0,
    "stop_multiple": 4.0,
    "ttl_seconds": 120,
    "cooldown_seconds": 30,
    "max_open_trades": 2,
    "common_cost_pct": COMMON_ROUND_TRIP_COST_PCT,
    "maker_counterfactual_cost_pct": MAKER_COUNTERFACTUAL_COST_PCT,
}

EXTREME_CONFIG = {
    "mode": "CAUSAL_EXTREME_REVERSAL_V1",
    "min_quote_volume_24h": 5_000_000,
    "max_spread_bps": 10.0,
    "displacement_60s_pct": 0.80,
    "confirmation_15s_pct": 0.05,
    "z_abs": 2.50,
    "z_lookback_seconds": 300,
    "extreme_recent_seconds": 30,
    "displacement_end_lag_seconds": 15,
    "horizon_seconds": 300,
    "cooldown_seconds": 300,
    "max_open_trades": 2,
    "common_cost_pct": COMMON_ROUND_TRIP_COST_PCT,
}


@dataclass
class PricePoint:
    ts_ms: int
    price: float
    z: Optional[float]


@dataclass
class Quote:
    ts_ms: int
    bid: float
    ask: float
    half_spacing_bps: float
    imbalance: float
    spread_bps: float
    microprice: float
    realized_vol_bps: float


def now_ms() -> int:
    return int(time.time() * 1000)


def jdump(obj: Any) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def pct_return(entry: float, exit_price: float, side: str) -> float:
    raw = (exit_price / entry - 1.0) * 100.0
    return raw if side.upper() == "LONG" else -raw


def clip(value: float, low: float, high: float) -> float:
    return min(max(value, low), high)


def table_names(con: sqlite3.Connection) -> set[str]:
    return {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}


def is_tradelab_db(path: Path) -> bool:
    if not path.exists() or not path.is_file():
        return False
    try:
        con = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=2)
        names = table_names(con)
        con.close()
        return {"participants", "participant_specs", "participant_events", "paper_trades", "market_samples", "meta"}.issubset(names)
    except sqlite3.Error:
        return False


def detect_db(explicit: Optional[str] = None) -> Path:
    candidates: list[Path] = []
    for raw in (explicit, os.environ.get("TRADELAB_DB")):
        if raw:
            candidates.append(Path(raw))
    candidates += [
        Path("/var/lib/pumpradar/tradelab.sqlite3"),
        Path("/var/lib/pumpradar/research/tradelab.sqlite3"),
        Path("/var/lib/pumpradar/pumpradar.sqlite3"),
    ]
    seen: set[str] = set()
    for path in candidates:
        key = str(path)
        if key in seen:
            continue
        seen.add(key)
        if is_tradelab_db(path):
            return path
    raise SystemExit("TradeLab DB not found. Set TRADELAB_DB=/path/to/database.sqlite3")


def connect(path: Path) -> sqlite3.Connection:
    con = sqlite3.connect(path, timeout=10)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA busy_timeout=10000")
    con.execute("PRAGMA foreign_keys=ON")
    return con


def required_schema_ok(con: sqlite3.Connection) -> None:
    needed = {"participants", "participant_specs", "participant_events", "paper_trades", "market_samples", "depth_samples", "market_states", "meta"}
    missing = needed - table_names(con)
    if missing:
        raise RuntimeError(f"missing required tables: {sorted(missing)}")


def upsert_meta(con: sqlite3.Connection, key: str, value: str) -> None:
    con.execute("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, value))


def add_event(con: sqlite3.Connection, participant: str, ts_ms: int, symbol: Optional[str], event_type: str, payload: dict[str, Any]) -> None:
    con.execute(
        "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
        (ts_ms, participant, symbol, event_type, jdump(payload)),
    )


def activate_r4(con: sqlite3.Connection) -> int:
    required_schema_ok(con)
    ts = now_ms()
    row = con.execute("SELECT value FROM meta WHERE key='five_model_epoch_started_at_ms'").fetchone()
    if row:
        epoch_ts = int(row[0])
    else:
        epoch_ts = ts
        prior = {r["participant_id"]: {"status": r["status"], "equity": r["equity"], "role": r["role"]}
                 for r in con.execute("SELECT participant_id,status,equity,role FROM participants")}
        upsert_meta(con, "pre_r4_participants_json", jdump(prior))
        upsert_meta(con, "five_model_epoch_id", f"R4-{epoch_ts}-{uuid.uuid4().hex[:8]}")
        upsert_meta(con, "five_model_epoch_started_at_ms", str(epoch_ts))
        upsert_meta(con, "five_model_epoch_mode", R4_MODE)
        upsert_meta(con, "five_model_score_rule", "score paper trades opened_at_ms >= five_model_epoch_started_at_ms; ignore retired participant")

    con.execute("CREATE INDEX IF NOT EXISTS idx_market_samples_symbol_ts ON market_samples(symbol,ts_ms)")
    con.execute("CREATE INDEX IF NOT EXISTS idx_depth_samples_symbol_ts ON depth_samples(symbol,ts_ms)")
    con.execute("CREATE INDEX IF NOT EXISTS idx_paper_trades_participant_status ON paper_trades(participant_id,status,opened_at_ms)")
    con.execute("CREATE INDEX IF NOT EXISTS idx_participant_events_participant_ts ON participant_events(participant_id,ts_ms)")

    con.execute("UPDATE participants SET status='RETIRED', role='ELIMINATED' WHERE participant_id=?", (RETIRED,))
    con.execute("UPDATE participant_specs SET active_effect='RETIRED_NO_SCORE' WHERE participant_id=?", (RETIRED,))

    definitions = [
        (HFT, "HFT Grid / GLFT Proxy", "E1-20260823", HFT_CONFIG),
        (EXTREME, "Extreme Reversion", "F1-20260823", EXTREME_CONFIG),
    ]
    for pid, name, version, cfg in definitions:
        existed = con.execute("SELECT 1 FROM participants WHERE participant_id=?", (pid,)).fetchone() is not None
        con.execute(
            "INSERT INTO participants(participant_id,display_name,status,starting_equity,equity,rank,role,created_at_ms) "
            "VALUES(?,?, 'ACTIVE',20.0,20.0,NULL,'CANDIDATE',?) "
            "ON CONFLICT(participant_id) DO UPDATE SET display_name=excluded.display_name,status='ACTIVE',role='CANDIDATE'",
            (pid, name, epoch_ts),
        )
        con.execute(
            "INSERT INTO participant_specs(participant_id,spec_version,config_json,frozen_at_ms,active_effect) VALUES(?,?,?,?, 'SHADOW_ONLY') "
            "ON CONFLICT(participant_id) DO UPDATE SET spec_version=excluded.spec_version,config_json=excluded.config_json,active_effect='SHADOW_ONLY'",
            (pid, version, jdump(cfg), epoch_ts),
        )
        if not existed:
            add_event(con, pid, epoch_ts, None, "R4_ACTIVATED", {"mode": R4_MODE, "spec_version": version, "config": cfg})

    for pid in EXISTING_ACTIVE:
        con.execute("UPDATE participants SET status='ACTIVE', role='CANDIDATE' WHERE participant_id=?", (pid,))
        con.execute("UPDATE participant_specs SET active_effect='SHADOW_ONLY' WHERE participant_id=?", (pid,))

    upsert_meta(con, "five_model_active_ids", jdump([*EXISTING_ACTIVE, HFT, EXTREME]))
    con.commit()
    return epoch_ts


class Sidecar:
    def __init__(self, con: sqlite3.Connection, epoch_ts: int):
        self.con = con
        self.epoch_ts = epoch_ts
        self.stop = False
        self.history: dict[str, Deque[PricePoint]] = defaultdict(lambda: deque(maxlen=100))
        self.hft_quotes: dict[str, Quote] = {}
        self.cooldown_until: dict[tuple[str, str], int] = {}
        latest = con.execute("SELECT COALESCE(MAX(ts_ms),0) FROM market_samples").fetchone()[0]
        self.last_seen_ts = max(int(latest or 0), epoch_ts - 1)
        self.last_seen_symbol = ""  # G1: composite keyset cursor (ts_ms, symbol)
        self.last_sample_ts: dict[str, int] = {}  # G7: previous ts per symbol for gap detection
        self._seed_history(self.last_seen_ts)
        self._restore_cooldowns()

    def _restore_cooldowns(self) -> None:
        for pid, seconds in ((HFT, int(HFT_CONFIG["cooldown_seconds"])), (EXTREME, int(EXTREME_CONFIG["cooldown_seconds"]))):
            rows = self.con.execute(
                "SELECT symbol,MAX(ts_ms) AS ts FROM participant_events WHERE participant_id=? AND event_type='SIGNAL' AND symbol IS NOT NULL GROUP BY symbol",
                (pid,),
            ).fetchall()
            for row in rows:
                self.cooldown_until[(pid, row["symbol"])] = int(row["ts"]) + seconds * 1000

    def _seed_history(self, cutoff: int) -> None:
        if cutoff <= 0:
            return
        rows = self.con.execute(
            "SELECT ts_ms,symbol,last_price FROM market_samples WHERE ts_ms>=? AND ts_ms<=? ORDER BY ts_ms,symbol",
            (cutoff - 360_000, cutoff),
        ).fetchall()
        for r in rows:
            self._append_history(r["symbol"], int(r["ts_ms"]), float(r["last_price"]))
            self.last_sample_ts[r["symbol"]] = int(r["ts_ms"])

    def _append_history(self, symbol: str, ts: int, price: float) -> None:
        dq = self.history[symbol]
        past = [p.price for p in dq if ts - 300_000 <= p.ts_ms < ts]
        z: Optional[float] = None
        if len(past) >= 24:
            mean = statistics.fmean(past)
            sd = statistics.pstdev(past)
            if sd > 0:
                z = (price - mean) / sd
        dq.append(PricePoint(ts, price, z))
        while dq and dq[0].ts_ms < ts - 360_000:
            dq.popleft()

    def _price_at_or_before(self, symbol: str, target_ts: int, max_age_ms: int = 10_000) -> Optional[float]:
        for p in reversed(self.history[symbol]):
            if p.ts_ms <= target_ts:
                return p.price if target_ts - p.ts_ms <= max_age_ms else None
        return None

    def _latest_depth(self, symbol: str, ts: int) -> Optional[sqlite3.Row]:
        return self.con.execute(
            "SELECT * FROM depth_samples WHERE symbol=? AND ts_ms<=? AND ts_ms>=? ORDER BY ts_ms DESC LIMIT 1",
            (symbol, ts, ts - int(HFT_CONFIG["depth_fresh_ms"])),
        ).fetchone()

    def _open_count(self, participant: str) -> int:
        return int(self.con.execute("SELECT COUNT(*) FROM paper_trades WHERE participant_id=? AND status='OPEN'", (participant,)).fetchone()[0])

    def _symbol_open(self, participant: str, symbol: str) -> bool:
        return self.con.execute(
            "SELECT 1 FROM paper_trades WHERE participant_id=? AND symbol_a=? AND status='OPEN' LIMIT 1",
            (participant, symbol),
        ).fetchone() is not None

    def _equity(self, participant: str) -> float:
        row = self.con.execute("SELECT equity FROM participants WHERE participant_id=?", (participant,)).fetchone()
        return float(row[0]) if row else 20.0

    def _record_gap(self, symbol: str, prev_ts: int, ts: int) -> None:
        # G7: per-symbol gap detection; never break trading on audit logging failures.
        try:
            self.con.execute(
                "INSERT OR IGNORE INTO recorder_gaps(start_ms,end_ms,duration_ms,reason,detected_at_ms) VALUES(?,?,?,?,?)",
                (prev_ts, ts, ts - prev_ts, f"symbol_gap:{symbol}", now_ms()),
            )
            self.con.commit()
        except sqlite3.Error:
            pass

    def _emit_signal_and_open(self, participant: str, ts: int, symbol: str, side: str, price: float, horizon_s: int, payload: dict[str, Any]) -> bool:
        payload = dict(payload)
        payload.update({
            "side_a": side, "symbol_a": symbol, "symbol_b": None, "side_b": None,
            "hedge_ratio": None, "horizon_seconds": horizon_s, "spec_mode": R4_MODE,
        })
        add_event(self.con, participant, ts, symbol, "SIGNAL", payload)
        self.con.execute("INSERT INTO market_states(ts_ms,symbol,source,payload_json) VALUES(?,?,?,?)", (ts, symbol, participant, jdump(payload)))

        max_open = int((HFT_CONFIG if participant == HFT else EXTREME_CONFIG)["max_open_trades"])
        if self._open_count(participant) >= max_open or self._symbol_open(participant, symbol):
            add_event(self.con, participant, ts, symbol, "PAPER_SKIPPED_CAPACITY", payload)
            self.con.commit()
            return False

        trade_id = uuid.uuid4().hex
        notional = max(0.0, self._equity(participant) / max_open)
        self.con.execute(
            "INSERT INTO paper_trades(trade_id,participant_id,symbol_a,symbol_b,side_a,side_b,hedge_ratio,opened_at_ms,entry_a,entry_b,exit_due_ms,notional_usdt,status,signal_json) "
            "VALUES(?,?,?,NULL,?,NULL,NULL,?,?,NULL,?,?, 'OPEN',?)",
            (trade_id, participant, symbol, side, ts, price, ts + horizon_s * 1000, notional, jdump(payload)),
        )
        add_event(self.con, participant, ts, symbol, "PAPER_OPEN", {**payload, "trade_id": trade_id, "entry_a": price, "notional_usdt": notional})
        self.con.commit()
        return True

    def _close_trade(self, trade: sqlite3.Row, ts: int, exit_price: float, reason: str) -> bool:
        # G2/G3: idempotent close - credit equity and emit PAPER_CLOSE only when this call
        # is the single successful transition OPEN->CLOSED for the trade row.
        gross = pct_return(float(trade["entry_a"]), exit_price, trade["side_a"])
        net = gross - COMMON_ROUND_TRIP_COST_PCT
        pnl = float(trade["notional_usdt"]) * net / 100.0
        maker_cf = gross - MAKER_COUNTERFACTUAL_COST_PCT if trade["participant_id"] == HFT else None
        cur = self.con.execute(
            "UPDATE paper_trades SET closed_at_ms=?,exit_a=?,gross_return_pct=?,net_return_pct=?,pnl_usdt=?,status='CLOSED' "
            "WHERE trade_id=? AND status='OPEN'",
            (ts, exit_price, gross, net, pnl, trade["trade_id"]),
        )
        if cur.rowcount != 1:
            self.con.rollback()
            return False
        self.con.execute("UPDATE participants SET equity=equity+? WHERE participant_id=?", (pnl, trade["participant_id"]))
        payload: dict[str, Any] = {
            "trade_id": trade["trade_id"], "exit_reason": reason, "gross_return_pct": gross,
            "net_return_pct": net, "pnl_usdt": pnl, "exit_sample_ms": ts,
            "common_round_trip_cost_pct": COMMON_ROUND_TRIP_COST_PCT,
        }
        if maker_cf is not None:
            payload["maker_counterfactual_net_return_pct"] = maker_cf
            payload["maker_counterfactual_cost_pct"] = MAKER_COUNTERFACTUAL_COST_PCT
        add_event(self.con, trade["participant_id"], ts, trade["symbol_a"], "PAPER_CLOSE", payload)
        self.con.commit()
        return True

    def _close_due_extreme(self, symbol: str, ts: int, price: float) -> None:
        rows = self.con.execute(
            "SELECT * FROM paper_trades WHERE participant_id=? AND symbol_a=? AND status='OPEN' AND exit_due_ms<=? ORDER BY exit_due_ms",
            (EXTREME, symbol, ts),
        ).fetchall()
        for trade in rows:
            self._close_trade(trade, ts, price, "FIXED_300S")

    def _close_hft(self, symbol: str, ts: int, price: float) -> None:
        rows = self.con.execute("SELECT * FROM paper_trades WHERE participant_id=? AND symbol_a=? AND status='OPEN'", (HFT, symbol)).fetchall()
        for trade in rows:
            sig = json.loads(trade["signal_json"])
            target_bps = float(sig["features"]["target_bps"])
            stop_bps = float(sig["features"]["stop_bps"])
            entry = float(trade["entry_a"])
            side = trade["side_a"]
            direction = 1.0 if side == "LONG" else -1.0
            target = entry * (1 + direction * target_bps / 10_000.0)
            stop = entry * (1 - direction * stop_bps / 10_000.0)
            reason = None
            exit_price = price
            if side == "LONG":
                if price >= target: reason, exit_price = "GRID_TARGET", target
                elif price <= stop: reason = "GRID_STOP"
            else:
                if price <= target: reason, exit_price = "GRID_TARGET", target
                elif price >= stop: reason = "GRID_STOP"
            if reason is None and ts >= int(trade["exit_due_ms"]): reason = "GRID_TTL"
            if reason:
                self._close_trade(trade, ts, exit_price, reason)
                self.cooldown_until[(HFT, symbol)] = ts + int(HFT_CONFIG["cooldown_seconds"]) * 1000

    def _extreme_features(self, symbol: str, ts: int, price: float) -> Optional[dict[str, float]]:
        p15 = self._price_at_or_before(symbol, ts - 15_000)
        p75 = self._price_at_or_before(symbol, ts - 75_000)
        if p15 is None or p75 is None or p15 <= 0 or p75 <= 0: return None
        ret15 = (price / p15 - 1) * 100.0
        prev60 = (p15 / p75 - 1) * 100.0
        recent_z = [p.z for p in self.history[symbol] if p.z is not None and ts - 30_000 <= p.ts_ms <= ts]
        if not recent_z: return None
        return {"ret_15s_pct": ret15, "previous_60s_return_ending_15s_ago_pct": prev60,
                "recent_min_z": min(recent_z), "recent_max_z": max(recent_z)}

    def _maybe_extreme(self, r: sqlite3.Row) -> None:
        ts, symbol, price = int(r["ts_ms"]), r["symbol"], float(r["last_price"])
        if (r["quote_volume_24h"] or 0) < EXTREME_CONFIG["min_quote_volume_24h"]: return
        spread = None
        if r["bid"] and r["ask"] and r["bid"] > 0 and r["ask"] >= r["bid"]:
            spread = (float(r["ask"]) - float(r["bid"])) / ((float(r["ask"]) + float(r["bid"])) / 2) * 10_000
            if spread > EXTREME_CONFIG["max_spread_bps"]: return
        f = self._extreme_features(symbol, ts, price)
        if not f: return
        side: Optional[str] = None
        d, c, z = float(EXTREME_CONFIG["displacement_60s_pct"]), float(EXTREME_CONFIG["confirmation_15s_pct"]), float(EXTREME_CONFIG["z_abs"])
        if f["previous_60s_return_ending_15s_ago_pct"] <= -d and f["recent_min_z"] <= -z and f["ret_15s_pct"] >= c: side = "LONG"
        elif f["previous_60s_return_ending_15s_ago_pct"] >= d and f["recent_max_z"] >= z and f["ret_15s_pct"] <= -c: side = "SHORT"
        if not side or ts < self.cooldown_until.get((EXTREME, symbol), 0): return
        payload = {"features": {**f, "spread_bps": spread, "quote_volume_24h": r["quote_volume_24h"]},
                   "score": abs(f["previous_60s_return_ending_15s_ago_pct"]) + abs(f["ret_15s_pct"])}
        self._emit_signal_and_open(EXTREME, ts, symbol, side, price, int(EXTREME_CONFIG["horizon_seconds"]), payload)
        self.cooldown_until[(EXTREME, symbol)] = ts + int(EXTREME_CONFIG["cooldown_seconds"]) * 1000

    def _realized_vol_bps(self, symbol: str, ts: int) -> Optional[float]:
        pts = [p for p in self.history[symbol] if ts - 60_000 <= p.ts_ms <= ts]
        if len(pts) < 6: return None
        rets = [math.log(pts[i].price / pts[i-1].price) for i in range(1, len(pts)) if pts[i-1].price > 0]
        if len(rets) < 5: return None
        return statistics.pstdev(rets) * 10_000.0 * math.sqrt(max(1, len(rets)))

    def _build_hft_quote(self, r: sqlite3.Row, depth: sqlite3.Row) -> Optional[Quote]:
        ts, price = int(r["ts_ms"]), float(r["last_price"])
        spread = float(depth["spread_bps"] or 999)
        if spread > HFT_CONFIG["max_spread_bps"]: return None
        vol = self._realized_vol_bps(r["symbol"], ts)
        if vol is None: return None
        bid, ask = float(r["bid"] or price), float(r["ask"] or price)
        if bid <= 0 or ask < bid: return None
        bq, aq = float(r["bid_qty"] or depth["best_bid_qty"] or 0), float(r["ask_qty"] or depth["best_ask_qty"] or 0)
        mid = (bid + ask) / 2.0
        micro = (ask * bq + bid * aq) / (bq + aq) if bq + aq > 0 else mid
        imbalance = float(depth["imbalance"] or 0)
        center = (0.5 * mid + 0.5 * micro) * (1.0 + clip(imbalance, -1.0, 1.0) / 10_000.0)
        half = clip(max(float(HFT_CONFIG["min_half_spacing_bps"]), spread * 2.0, vol * float(HFT_CONFIG["vol_spacing_multiplier"])),
                    float(HFT_CONFIG["min_half_spacing_bps"]), float(HFT_CONFIG["max_half_spacing_bps"]))
        return Quote(ts, center * (1 - half / 10_000), center * (1 + half / 10_000), half, imbalance, spread, micro, vol)

    def _maybe_hft(self, r: sqlite3.Row) -> None:
        ts, symbol, price = int(r["ts_ms"]), r["symbol"], float(r["last_price"])
        if (r["quote_volume_24h"] or 0) < HFT_CONFIG["min_quote_volume_24h"]:
            self.hft_quotes.pop(symbol, None); return
        depth = self._latest_depth(symbol, ts)
        if not depth:
            self.hft_quotes.pop(symbol, None); return
        previous = self.hft_quotes.get(symbol)
        if previous and ts > previous.ts_ms and ts >= self.cooldown_until.get((HFT, symbol), 0):
            side: Optional[str] = None; entry: Optional[float] = None
            thr = float(HFT_CONFIG["imbalance_abs"])
            if previous.imbalance >= thr and price <= previous.bid: side, entry = "LONG", previous.bid
            elif previous.imbalance <= -thr and price >= previous.ask: side, entry = "SHORT", previous.ask
            if side and entry is not None:
                target_bps = max(18.0, previous.half_spacing_bps * float(HFT_CONFIG["target_multiple"]))
                stop_bps = max(40.0, previous.half_spacing_bps * float(HFT_CONFIG["stop_multiple"]))
                payload = {"features": {"fill_model": HFT_CONFIG["mode"], "half_spacing_bps": previous.half_spacing_bps,
                    "target_bps": target_bps, "stop_bps": stop_bps, "book_imbalance": previous.imbalance,
                    "spread_bps": previous.spread_bps, "microprice": previous.microprice,
                    "realized_vol_bps": previous.realized_vol_bps, "virtual_quote_bid": previous.bid,
                    "virtual_quote_ask": previous.ask, "touch_price": price}, "score": abs(previous.imbalance),
                    "warning": "Queue position is not observable in current recorder; this is a research proxy, not a queue-accurate HFT fill."}
                self._emit_signal_and_open(HFT, ts, symbol, side, entry, int(HFT_CONFIG["ttl_seconds"]), payload)
                self.cooldown_until[(HFT, symbol)] = ts + int(HFT_CONFIG["cooldown_seconds"]) * 1000
        q = self._build_hft_quote(r, depth)
        if q: self.hft_quotes[symbol] = q
        else: self.hft_quotes.pop(symbol, None)

    def process_row(self, r: sqlite3.Row) -> None:
        ts, symbol, price = int(r["ts_ms"]), r["symbol"], float(r["last_price"])
        prev_ts = self.last_sample_ts.get(symbol)
        if prev_ts is not None and ts - prev_ts >= GAP_MIN_MS:
            self._record_gap(symbol, prev_ts, ts)
        self.last_sample_ts[symbol] = ts
        self._append_history(symbol, ts, price)
        self._close_due_extreme(symbol, ts, price)
        self._close_hft(symbol, ts, price)
        self._maybe_extreme(r)
        self._maybe_hft(r)

    def step(self) -> int:
        # G1: composite keyset cursor (ts_ms,symbol) - never skips rows sharing the
        # boundary ts_ms at a page break, never re-processes a row.
        rows = self.con.execute(
            "SELECT * FROM market_samples WHERE (ts_ms>?) OR (ts_ms=? AND symbol>?) "
            "ORDER BY ts_ms,symbol LIMIT 5000",
            (self.last_seen_ts, self.last_seen_ts, self.last_seen_symbol),
        ).fetchall()
        if not rows: return 0
        for r in rows:
            self.process_row(r)
        last = rows[-1]
        self.last_seen_ts = int(last["ts_ms"])
        self.last_seen_symbol = str(last["symbol"])
        return len(rows)

    def run(self) -> None:
        def handle(*_: Any) -> None: self.stop = True
        signal.signal(signal.SIGTERM, handle); signal.signal(signal.SIGINT, handle)
        while not self.stop:
            if self.step() == 0: time.sleep(POLL_SECONDS)


def status(con: sqlite3.Connection) -> dict[str, Any]:
    epoch_row = con.execute("SELECT value FROM meta WHERE key='five_model_epoch_started_at_ms'").fetchone()
    epoch = int(epoch_row[0]) if epoch_row else 0
    participants = [dict(r) for r in con.execute(
        "SELECT participant_id,display_name,status,starting_equity,equity,role FROM participants WHERE participant_id IN (?,?,?,?,?,?) ORDER BY participant_id",
        (*EXISTING_ACTIVE, HFT, EXTREME, RETIRED))]
    scores = []
    for pid in (*EXISTING_ACTIVE, HFT, EXTREME):
        r = con.execute("SELECT COUNT(*) n,COALESCE(SUM(pnl_usdt),0) pnl,COALESCE(AVG(net_return_pct),0) avg_net FROM paper_trades WHERE participant_id=? AND opened_at_ms>=? AND status='CLOSED'", (pid, epoch)).fetchone()
        o = con.execute("SELECT COUNT(*) FROM paper_trades WHERE participant_id=? AND opened_at_ms>=? AND status='OPEN'", (pid, epoch)).fetchone()[0]
        scores.append({"participant_id": pid, "closed_r4": int(r["n"]), "open": int(o), "pnl_usdt_r4": float(r["pnl"]), "avg_net_pct_r4": float(r["avg_net"])})
    return {"mode": R4_MODE, "epoch_started_at_ms": epoch, "active_ids": [*EXISTING_ACTIVE, HFT, EXTREME], "retired_id": RETIRED, "participants": participants, "scorecard": scores}


def main() -> None:
    ap = argparse.ArgumentParser(description="TradeLab R4 five-model SHADOW sidecar")
    ap.add_argument("--db"); ap.add_argument("--detect-db", action="store_true"); ap.add_argument("--activate-r4", action="store_true"); ap.add_argument("--status", action="store_true"); ap.add_argument("--run", action="store_true")
    args = ap.parse_args(); db = detect_db(args.db)
    if args.detect_db: print(db); return
    con = connect(db)
    epoch = activate_r4(con) if (args.activate_r4 or args.run) else int((con.execute("SELECT value FROM meta WHERE key='five_model_epoch_started_at_ms'").fetchone() or [0])[0])
    if args.activate_r4 and not args.run: print(jdump(status(con))); return
    if args.status: print(json.dumps(status(con), ensure_ascii=False, indent=2, sort_keys=True)); return
    if args.run:
        # G4: the base sidecar writes legacy status='OPEN' trades; refuse when the
        # isolation hotfix epoch is active for this DB to prevent double-ownership.
        hotfix = con.execute("SELECT value FROM meta WHERE key='r4_isolation_hotfix_started_at_ms'").fetchone()
        if hotfix:
            raise SystemExit(
                "r4 isolation hotfix is active for this DB; base sidecar writes legacy status='OPEN' trades. "
                "Run tradelab_r4_isolation.py --run instead (paper_owner=R4_SIDECAR, status=R4_OPEN)."
            )
        Sidecar(con, epoch).run(); return
    ap.error("choose --activate-r4, --status, --run, or --detect-db")

if __name__ == "__main__": main()
