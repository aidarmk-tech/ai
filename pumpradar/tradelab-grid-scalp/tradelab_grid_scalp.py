#!/usr/bin/env python3
"""TradeLab GRID_SCALP_ADAPTIVE_V1 - standalone shadow grid scalp sidecar.

Regime-gated grid scalper: qualifies volatile non-trending symbols every
REQUALIFY_S from market_samples, runs long/short ladder grids on them.
Writes paper_trades / participant_events with statuses G_OPEN/G_CLOSED.
"""
import bisect
import json
import math
import sqlite3
import sys
import time
import uuid
from collections import defaultdict, deque

DB = "/var/lib/tradelab/tradelab.sqlite3"
PID = "GRID_SCALP_ADAPTIVE"
SPEC_VERSION = "GS1-20260824"
MODE = "REGIME_GATED_GRID_V1"
STATUS_OPEN, STATUS_CLOSED = "G_OPEN", "G_CLOSED"

CFG = {
    "mode": MODE,
    "step_bps": 25,
    "common_cost_pct": 0.14,
    "requalify_interval_seconds": 900,
    "qualify_path_60m_min_bps": 120,
    "qualify_efficiency_ratio_60m_max": 0.06,
    "min_quote_volume_24h": 5_000_000,
    "max_spread_bps": 8,
    "liq_cascade_blackout_5m_notional": 250_000,
    "max_qualified_symbols": 10,
    "horizon_seconds": 1800,
    "cooldown_seconds": 60,
    "max_open_trades": 3,
}
NOTIONAL = 10.0


class GridState:
    __slots__ = ("last_lvl", "long_entry", "short_entry")

    def __init__(self):
        self.last_lvl = None
        self.long_entry = None   # (entry_px, opened_ms)
        self.short_entry = None


def log(msg):
    print(time.strftime("[%H:%M:%S]"), msg, flush=True)


def jdump(obj):
    return json.dumps(obj, sort_keys=True)


def main():
    con = sqlite3.connect(DB, timeout=60)
    con.row_factory = sqlite3.Row
    register(con)

    cfg = CFG
    step_r = math.log(1 + cfg["step_bps"] / 10000)

    # --- bootstrap: 2h of prices ---
    t_now = con.execute("SELECT MAX(ts_ms) FROM market_samples").fetchone()[0]
    t_back = t_now - 2 * 3600_000
    prices = defaultdict(deque)
    meta_row = {}          # symbol -> latest market_samples row
    for r in con.execute(
        "SELECT * FROM market_samples WHERE ts_ms>=? ORDER BY ts_ms", (t_back,)
    ):
        prices[r["symbol"]].append((r["ts_ms"], float(r["last_price"])))
        if len(prices[r["symbol"]]) > 1500:
            prices[r["symbol"]].popleft()
        meta_row[r["symbol"]] = r
    last_ts = t_now
    log(f"bootstrap done, symbols={len(prices)}, last_ts={last_ts}")

    qualified, er_cache = {}, {}
    last_qualify = 0

    def qualify(now_ms):
        nonlocal qualified, last_qualify
        qualified, er_cache = {}, {}
        blackouts = set()
        cut = now_ms - 5 * 60_000
        for sym, tot in con.execute(
            "SELECT symbol, SUM(notional) n FROM liquidations WHERE ts_ms>=? GROUP BY symbol", (cut,)
        ):
            if (tot or 0) >= cfg["liq_cascade_blackout_5m_notional"]:
                blackouts.add(sym)
        picked = []
        cands = []
        for sym, dq in prices.items():
            if len(dq) < 200:
                continue
            hour_dq = [p for t, p in dq if t >= now_ms - 3600_000]
            if len(hour_dq) < 100:
                continue
            path = sum(abs(b / a - 1) * 10000 for a, b in zip(hour_dq, hour_dq[1:]))
            disp = abs(hour_dq[-1] / hour_dq[0] - 1) * 10000
            er = disp / path if path > 0 else 1.0
            er_cache[sym] = {"path_bps": round(path, 1), "er": round(er, 3)}
            r = meta_row.get(sym)
            if r is None:
                continue
            spread = None
            if r["bid"] and r["ask"] and r["ask"] >= r["bid"] > 0:
                spread = (float(r["ask"]) - float(r["bid"])) / ((float(r["ask"]) + float(r["bid"])) / 2) * 10_000
            ok = (
                path >= cfg["qualify_path_60m_min_bps"]
                and er <= cfg["qualify_efficiency_ratio_60m_max"]
                and (r["quote_volume_24h"] or 0) >= cfg["min_quote_volume_24h"]
                and spread is not None and spread <= cfg["max_spread_bps"]
                and sym not in blackouts
            )
            if ok:
                score = max(0.0, (path - disp) / 2.0) * (1 - cfg["common_cost_pct"] * 100 / cfg["step_bps"])
                cands.append((score, sym))
        cands.sort(reverse=True)
        qualified = {sym: True for _, sym in cands[: cfg["max_qualified_symbols"]]}
        picked = sorted(qualified)
        last_qualify = now_ms
        log(f"qualified {len(picked)}: {picked}")

    def emit_event(ts, event_type, symbol, payload):
        con.execute(
            "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) "
            "VALUES(?,?,?,?,?)",
            (ts, PID, symbol, event_type, jdump(payload)),
        )

    def open_trade(ts, symbol, side, px, reason_tag, extra):
        trade_id = uuid.uuid4().hex
        payload = {
            "features": extra, "mode": MODE, "spec_version": SPEC_VERSION,
            "side_a": side, "symbol_a": symbol, "reason": reason_tag,
            "step_bps": cfg["step_bps"],
        }
        con.execute(
            "INSERT INTO paper_trades(trade_id,participant_id,symbol_a,side_a,opened_at_ms,entry_a,"
            "exit_due_ms,notional_usdt,status,signal_json) VALUES(?,?,?,?,?,?,?,?,?,?)",
            (trade_id, PID, symbol, side, ts, px, ts + cfg["horizon_seconds"] * 1000,
             NOTIONAL, STATUS_OPEN, jdump(payload)),
        )
        emit_event(ts, "SIGNAL", symbol, payload)
        emit_event(ts, "PAPER_OPEN", symbol, {
            **payload, "entry_a": px, "trade_id": trade_id, "notional_usdt": NOTIONAL})
        return trade_id

    def close_trade(row, ts, px, reason):
        gross = (px / row["entry_a"] - 1) * 100
        if row["side_a"] == "SHORT":
            gross = -gross
        net = gross - cfg["common_cost_pct"]
        pnl = row["notional_usdt"] * net / 100
        con.execute(
            "UPDATE paper_trades SET closed_at_ms=?, exit_a=?, gross_return_pct=?, "
            "net_return_pct=?, pnl_usdt=?, status=? WHERE trade_id=?",
            (ts, px, gross, net, pnl, STATUS_CLOSED, row["trade_id"]),
        )
        emit_event(ts, "PAPER_CLOSE", row["symbol_a"], {
            "trade_id": row["trade_id"], "exit_reason": reason,
            "gross_return_pct": gross, "net_return_pct": net, "paper_owner": "GRID_SIDECAR"})

    opens_this_second = {}

    def cooldown_ok(ts, symbol):
        return ts - opens_this_second.get((PID, symbol), 0) >= cfg["cooldown_seconds"] * 1000

    # restore open positions across restarts
    states = defaultdict(GridState)
    open_rows = {}
    for row in con.execute(
        f"SELECT * FROM paper_trades WHERE participant_id=? AND status=?", (PID, STATUS_OPEN)
    ):
        open_rows[row["symbol_a"] + row["side_a"]] = row
        st = states[row["symbol_a"]]
        lvl = math.log(float(row["entry_a"])) / step_r
        st.last_lvl = lvl
        if row["side_a"] == "LONG":
            st.long_entry = (float(row["entry_a"]), int(row["opened_at_ms"]))
        else:
            st.short_entry = (float(row["entry_a"]), int(row["opened_at_ms"]))
    log(f"restored open legs: {len(open_rows)}")

    qualify(t_now)

    POLL_MS = 5000
    while True:
        time.sleep(2)
        now_ms = int(time.time() * 1000)
        fresh = con.execute(
            "SELECT * FROM market_samples WHERE ts_ms>? ORDER BY ts_ms", (last_ts,)
        ).fetchall()
        if not fresh:
            continue
        last_ts = fresh[-1]["ts_ms"]

        if now_ms - last_qualify >= cfg["requalify_interval_seconds"] * 1000:
            qualify(last_ts)

        for r in fresh:
            sym, ts, px = r["symbol"], int(r["ts_ms"]), float(r["last_price"])
            meta_row[sym] = r
            dq = prices[sym]
            dq.append((ts, px))
            if len(dq) > 1500:
                dq.popleft()

            if sym not in qualified and states[sym].long_entry is None and states[sym].short_entry is None:
                continue
            st = states[sym]

            # --- close due / target legs ---
            # NOTE: runs before any open logic; a leg opened while the symbol
            # was qualified must stay closable (TTL/target) even after the
            # symbol leaves the qualified set, else it becomes an orphan that
            # permanently consumes max_open_trades capacity.
            for key, st_field in (("LONG", "long_entry"), ("SHORT", "short_entry")):
                ent = getattr(st, st_field)
                if ent is None:
                    continue
                epx, oms = ent
                lvl_ent = math.log(epx) / step_r
                lvl_now = math.log(px) / step_r
                hit_target = (key == "LONG" and lvl_now >= lvl_ent + 1) or \
                             (key == "SHORT" and lvl_now <= lvl_ent - 1)
                hit_ttl = ts - oms >= cfg["horizon_seconds"] * 1000
                if hit_target or hit_ttl:
                    row = open_rows.pop(sym + key, None)
                    if row is None:
                        row = con.execute(
                            "SELECT * FROM paper_trades WHERE participant_id=? AND symbol_a=? AND side_a=? AND status=?",
                            (PID, sym, key, STATUS_OPEN),
                        ).fetchone()
                    if row is not None:
                        close_trade(row, ts, px, "TARGET" if hit_target else "TTL")
                        con.commit()
                    setattr(st, st_field, None)

            if sym not in qualified:
                continue

            # --- open new legs ---
            cur_open = sum(1 for v in states.values() for f in ("long_entry", "short_entry")
                           if getattr(v, f) is not None)
            if cur_open >= cfg["max_open_trades"]:
                continue
            if not cooldown_ok(ts, sym):
                continue
            spread = None
            if r["bid"] and r["ask"] and r["ask"] >= r["bid"] > 0:
                spread = (float(r["ask"]) - float(r["bid"])) / ((float(r["ask"]) + float(r["bid"])) / 2) * 10_000
            else:
                continue
            if spread > cfg["max_spread_bps"]:
                continue
            lvl = math.log(px) / step_r
            if st.last_lvl is None:
                st.last_lvl = lvl
                continue
            feats = {**(er_cache.get(sym) or {}), "spread_bps": round(spread, 2)}
            if lvl <= st.last_lvl - 1 and st.long_entry is None:
                tid = open_trade(ts, sym, "LONG", px, "GRID_BUY_DIP", feats)
                open_rows[sym + "LONG"] = con.execute(
                    "SELECT * FROM paper_trades WHERE trade_id=?", (tid,)).fetchone()
                st.long_entry = (px, ts)
                st.last_lvl = lvl
                opens_this_second[(PID, sym)] = ts
                con.commit()
            elif lvl >= st.last_lvl + 1 and st.short_entry is None:
                tid = open_trade(ts, sym, "SHORT", px, "GRID_SELL_RIP", feats)
                open_rows[sym + "SHORT"] = con.execute(
                    "SELECT * FROM paper_trades WHERE trade_id=?", (tid,)).fetchone()
                st.short_entry = (px, ts)
                st.last_lvl = lvl
                opens_this_second[(PID, sym)] = ts
                con.commit()
            else:
                st.last_lvl = lvl


def register(con):
    con.execute(
        "INSERT OR IGNORE INTO participants(participant_id,display_name,status,starting_equity,equity,rank,role,created_at_ms) "
        "VALUES(?,?, 'ACTIVE',20.0,20.0,NULL,'CANDIDATE',?)",
        (PID, "Grid Scalp Adaptive", int(time.time() * 1000)),
    )
    con.execute(
        "INSERT OR IGNORE INTO participant_specs(participant_id,spec_version,config_json,frozen_at_ms,active_effect) "
        "VALUES(?,?,?,?, 'SHADOW_ONLY')",
        (PID, SPEC_VERSION, jdump(CFG), int(time.time() * 1000)),
    )
    con.commit()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
