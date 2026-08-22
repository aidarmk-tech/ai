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
from typing import Any

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("tradelab_r4_base", HERE / "tradelab_r4_sidecar.py")
base = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = base
spec.loader.exec_module(base)

R4_OPEN = "R4_OPEN"
ACTIVE5 = (*base.EXISTING_ACTIVE, base.HFT, base.EXTREME)
CLEAN_KEY = "r4_isolation_hotfix_started_at_ms"


def upsert_meta(con: sqlite3.Connection, key: str, value: str) -> None:
    con.execute("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, value))


def apply_isolation(con: sqlite3.Connection) -> int:
    row = con.execute("SELECT value FROM meta WHERE key=?", (CLEAN_KEY,)).fetchone()
    if row:
        clean_ts = int(row[0])
    else:
        clean_ts = int(time.time() * 1000)
        before = {r["participant_id"]: float(r["equity"]) for r in con.execute(
            "SELECT participant_id,equity FROM participants WHERE participant_id IN (?,?,?,?,?)", ACTIVE5
        )}
        upsert_meta(con, "pre_r4_isolation_equity_json", json.dumps(before, sort_keys=True, separators=(",", ":")))
        upsert_meta(con, CLEAN_KEY, str(clean_ts))
        upsert_meta(con, "five_model_epoch_id", f"R4C-{clean_ts}-{uuid.uuid4().hex[:8]}")
        upsert_meta(con, "five_model_epoch_started_at_ms", str(clean_ts))
        upsert_meta(con, "five_model_epoch_mode", "FIVE_MODEL_SHADOW_R4_ISOLATED")
        upsert_meta(con, "five_model_score_rule", "score only trades opened_at_ms >= five_model_epoch_started_at_ms; R4 sidecar positions use status=R4_OPEN")

        # Cancel only contaminated sidecar positions that are still open. Historical closed rows remain for audit.
        for r in con.execute(
            "SELECT trade_id,participant_id,symbol_a FROM paper_trades WHERE participant_id IN (?,?) AND status='OPEN'",
            (base.HFT, base.EXTREME),
        ).fetchall():
            con.execute(
                "UPDATE paper_trades SET status='R4_CANCELLED_HOTFIX',closed_at_ms=? WHERE trade_id=? AND status='OPEN'",
                (clean_ts, r["trade_id"]),
            )
            base.add_event(con, r["participant_id"], clean_ts, r["symbol_a"], "R4_HOTFIX_CANCEL", {"trade_id": r["trade_id"], "reason": "paper_book isolation"})

        # Clean tournament: all five active candidates restart from the same virtual $20.
        con.execute(
            "UPDATE participants SET starting_equity=20.0,equity=20.0,status='ACTIVE',role='CANDIDATE' WHERE participant_id IN (?,?,?,?,?)",
            ACTIVE5,
        )
        con.execute("UPDATE participants SET status='RETIRED',role='ELIMINATED' WHERE participant_id=?", (base.RETIRED,))
        con.execute("UPDATE participant_specs SET active_effect='RETIRED_NO_SCORE' WHERE participant_id=?", (base.RETIRED,))

    # Block the retired Flow generator at the database boundary. The old engine may still compute it,
    # but it can no longer write new Flow events/states/trades or alter retired equity.
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


class IsolatedSidecar(base.Sidecar):
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
        payload = dict(payload)
        payload.update({
            "side_a": side, "symbol_a": symbol, "symbol_b": None, "side_b": None,
            "hedge_ratio": None, "horizon_seconds": horizon_s,
            "spec_mode": "FIVE_MODEL_SHADOW_R4_ISOLATED",
            "paper_owner": "R4_SIDECAR",
        })
        base.add_event(self.con, participant, ts, symbol, "SIGNAL", payload)
        self.con.execute("INSERT INTO market_states(ts_ms,symbol,source,payload_json) VALUES(?,?,?,?)", (ts, symbol, participant, base.jdump(payload)))

        max_open = int((base.HFT_CONFIG if participant == base.HFT else base.EXTREME_CONFIG)["max_open_trades"])
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

    def _close_trade(self, trade: sqlite3.Row, ts: int, exit_price: float, reason: str) -> None:
        gross = base.pct_return(float(trade["entry_a"]), exit_price, trade["side_a"])
        net = gross - base.COMMON_ROUND_TRIP_COST_PCT
        pnl = float(trade["notional_usdt"]) * net / 100.0
        maker_cf = gross - base.MAKER_COUNTERFACTUAL_COST_PCT if trade["participant_id"] == base.HFT else None

        cur = self.con.execute(
            "UPDATE paper_trades SET closed_at_ms=?,exit_a=?,gross_return_pct=?,net_return_pct=?,pnl_usdt=?,status='CLOSED' WHERE trade_id=? AND status=?",
            (ts, exit_price, gross, net, pnl, trade["trade_id"], R4_OPEN),
        )
        if cur.rowcount != 1:
            self.con.rollback()
            return

        self.con.execute("UPDATE participants SET equity=equity+? WHERE participant_id=?", (pnl, trade["participant_id"]))
        payload: dict[str, Any] = {
            "trade_id": trade["trade_id"], "exit_reason": reason, "gross_return_pct": gross,
            "net_return_pct": net, "pnl_usdt": pnl, "exit_sample_ms": ts,
            "common_round_trip_cost_pct": base.COMMON_ROUND_TRIP_COST_PCT,
            "paper_owner": "R4_SIDECAR",
        }
        if maker_cf is not None:
            payload["maker_counterfactual_net_return_pct"] = maker_cf
            payload["maker_counterfactual_cost_pct"] = base.MAKER_COUNTERFACTUAL_COST_PCT
        base.add_event(self.con, trade["participant_id"], ts, trade["symbol_a"], "PAPER_CLOSE", payload)
        self.con.commit()

    def _close_due_extreme(self, symbol: str, ts: int, price: float) -> None:
        rows = self.con.execute(
            "SELECT * FROM paper_trades WHERE participant_id=? AND symbol_a=? AND status=? AND exit_due_ms<=? ORDER BY exit_due_ms",
            (base.EXTREME, symbol, R4_OPEN, ts),
        ).fetchall()
        for trade in rows:
            self._close_trade(trade, ts, price, "FIXED_300S")

    def _close_hft(self, symbol: str, ts: int, price: float) -> None:
        rows = self.con.execute(
            "SELECT * FROM paper_trades WHERE participant_id=? AND symbol_a=? AND status=?",
            (base.HFT, symbol, R4_OPEN),
        ).fetchall()
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
                if price >= target:
                    reason, exit_price = "GRID_TARGET", target
                elif price <= stop:
                    reason = "GRID_STOP"
            else:
                if price <= target:
                    reason, exit_price = "GRID_TARGET", target
                elif price >= stop:
                    reason = "GRID_STOP"
            if reason is None and ts >= int(trade["exit_due_ms"]):
                reason = "GRID_TTL"
            if reason:
                self._close_trade(trade, ts, exit_price, reason)
                self.cooldown_until[(base.HFT, symbol)] = ts + int(base.HFT_CONFIG["cooldown_seconds"] * 1000)


def status(con: sqlite3.Connection) -> dict[str, Any]:
    epoch = int((con.execute("SELECT value FROM meta WHERE key='five_model_epoch_started_at_ms'").fetchone() or [0])[0])
    out = {"mode": "FIVE_MODEL_SHADOW_R4_ISOLATED", "epoch_started_at_ms": epoch, "active_ids": list(ACTIVE5), "retired_id": base.RETIRED, "scorecard": []}
    for pid in ACTIVE5:
        r = con.execute(
            "SELECT COUNT(*) n,COALESCE(SUM(pnl_usdt),0) pnl,COALESCE(AVG(net_return_pct),0) avg_net FROM paper_trades WHERE participant_id=? AND opened_at_ms>=? AND status='CLOSED'",
            (pid, epoch),
        ).fetchone()
        o = con.execute(
            "SELECT COUNT(*) FROM paper_trades WHERE participant_id=? AND opened_at_ms>=? AND status IN ('OPEN','R4_OPEN')",
            (pid, epoch),
        ).fetchone()[0]
        eq = con.execute("SELECT equity FROM participants WHERE participant_id=?", (pid,)).fetchone()[0]
        out["scorecard"].append({"participant_id": pid, "equity": float(eq), "closed": int(r["n"]), "open": int(o), "pnl_usdt": float(r["pnl"]), "avg_net_pct": float(r["avg_net"])})
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
    base.activate_r4(con)
    clean = apply_isolation(con)
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
