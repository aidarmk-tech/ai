#!/usr/bin/env python3
"""Read-only consistency audit: forward_labels.ret_300s vs market_samples.

Labels are stored as PLAIN PERCENTAGES (verified during the R4C audit:
stored value equals (p_end/p_start - 1) * 100 computed from raw samples
at ts and first sample >= ts+300s).

Usage:
    python3 audit_forward_labels.py [--db PATH] [--tol 1e-6]

Exit codes: 0 = ok, 3 = mismatches above tolerance.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import tradelab_r4_sidecar as base  # noqa: E402  (detect_db/connect reuse)

HORIZON_MS = 300_000


def audit(con: sqlite3.Connection, tol: float = 1e-6) -> dict:
    rows = con.execute(
        "SELECT fl.market_state_id AS lid, ms.ts_ms AS t, ms.symbol AS s, fl.ret_300s AS lab "
        "FROM forward_labels fl JOIN market_states ms ON ms.id = fl.market_state_id "
        "ORDER BY lid"
    ).fetchall()
    checked = mismatched = missing = 0
    max_abs = 0.0
    worst = None
    for r in rows:
        p0 = con.execute("SELECT last_price FROM market_samples WHERE symbol=? AND ts_ms=?", (r["s"], r["t"])).fetchone()
        p1 = con.execute(
            "SELECT last_price FROM market_samples WHERE symbol=? AND ts_ms>=? ORDER BY ts_ms LIMIT 1",
            (r["s"], r["t"] + HORIZON_MS),
        ).fetchone()
        if not p0 or not p1:
            missing += 1
            continue
        expected = (float(p1[0]) / float(p0[0]) - 1.0) * 100.0
        diff = abs(expected - float(r["lab"]))
        if diff > max_abs:
            max_abs = diff
            worst = {"market_state_id": r["lid"], "symbol": r["s"], "ts_ms": r["t"], "stored": float(r["lab"]), "recomputed": round(expected, 9)}
        checked += 1
        if diff > tol:
            mismatched += 1
    return {
        "checked": checked,
        "mismatched": mismatched,
        "missing_inputs": missing,
        "max_abs_diff": round(max_abs, 9),
        "tolerance": tol,
        "worst": worst,
        "units": "ret_300s stored as percent",
    }


def main() -> None:
    ap = argparse.ArgumentParser(description="forward_labels <-> market_samples consistency audit (read-only)")
    ap.add_argument("--db")
    ap.add_argument("--tol", type=float, default=1e-6)
    args = ap.parse_args()
    db = base.detect_db(args.db)
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    con.row_factory = sqlite3.Row
    out = audit(con, args.tol)
    print(json.dumps(out, ensure_ascii=False, indent=2, sort_keys=True))
    raise SystemExit(3 if out["mismatched"] else 0)


if __name__ == "__main__":
    main()
