#!/usr/bin/env python3
import importlib.util
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("r4", HERE / "tradelab_r4_sidecar.py")
r4 = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = r4
spec.loader.exec_module(r4)

SCHEMA = """
CREATE TABLE participants(participant_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'ACTIVE',starting_equity REAL NOT NULL DEFAULT 20,equity REAL NOT NULL DEFAULT 20,rank INTEGER,role TEXT,created_at_ms INTEGER NOT NULL);
CREATE TABLE participant_specs(participant_id TEXT PRIMARY KEY,spec_version TEXT NOT NULL,config_json TEXT NOT NULL,frozen_at_ms INTEGER NOT NULL,active_effect TEXT NOT NULL DEFAULT 'SHADOW_ONLY',FOREIGN KEY(participant_id) REFERENCES participants(participant_id));
CREATE TABLE participant_events(id INTEGER PRIMARY KEY AUTOINCREMENT,ts_ms INTEGER NOT NULL,participant_id TEXT NOT NULL,symbol TEXT,event_type TEXT NOT NULL,payload_json TEXT NOT NULL);
CREATE TABLE paper_trades(trade_id TEXT PRIMARY KEY,participant_id TEXT NOT NULL,symbol_a TEXT NOT NULL,symbol_b TEXT,side_a TEXT NOT NULL,side_b TEXT,hedge_ratio REAL,opened_at_ms INTEGER NOT NULL,entry_a REAL NOT NULL,entry_b REAL,exit_due_ms INTEGER NOT NULL,closed_at_ms INTEGER,exit_a REAL,gross_return_pct REAL,net_return_pct REAL,notional_usdt REAL NOT NULL,pnl_usdt REAL,status TEXT NOT NULL DEFAULT 'OPEN',signal_json TEXT NOT NULL);
CREATE TABLE market_samples(ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,last_price REAL NOT NULL,bid REAL,ask REAL,bid_qty REAL,ask_qty REAL,mark_price REAL,index_price REAL,funding_rate REAL,quote_volume_24h REAL,trade_count_24h INTEGER,PRIMARY KEY(ts_ms,symbol));
CREATE TABLE depth_samples(ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,spread_bps REAL,bid_notional_10 REAL,ask_notional_10 REAL,imbalance REAL,best_bid_qty REAL,best_ask_qty REAL,bid_replenishment REAL,ask_replenishment REAL,PRIMARY KEY(ts_ms,symbol));
CREATE TABLE market_states(id INTEGER PRIMARY KEY AUTOINCREMENT,ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,source TEXT NOT NULL,payload_json TEXT NOT NULL);
CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE recorder_gaps(id INTEGER PRIMARY KEY AUTOINCREMENT,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,duration_ms INTEGER NOT NULL,reason TEXT NOT NULL,detected_at_ms INTEGER NOT NULL);
"""


def load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


class R4Tests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "test.sqlite3"
        self.con = sqlite3.connect(self.db)
        self.con.row_factory = sqlite3.Row
        self.con.executescript(SCHEMA)
        t = 1_000_000
        for pid in [*r4.EXISTING_ACTIVE, r4.RETIRED]:
            self.con.execute("INSERT INTO participants VALUES(?,?, 'ACTIVE',20,20,NULL,'CANDIDATE',?)", (pid, pid, t))
            self.con.execute("INSERT INTO participant_specs VALUES(?,?, '{}',?,'SHADOW_ONLY')", (pid, "old", t))
        self.con.commit()

    def tearDown(self):
        self.con.close()
        self.tmp.cleanup()

    def _insert_sample(self, ts: int, symbol: str, price: float) -> None:
        self.con.execute("INSERT INTO market_samples(ts_ms,symbol,last_price) VALUES(?,?,?)", (ts, symbol, price))

    def test_activation_has_five_active_and_retires_flow(self):
        r4.activate_r4(self.con)
        active = {x[0] for x in self.con.execute("SELECT participant_id FROM participants WHERE status='ACTIVE'")}
        self.assertEqual(active, set((*r4.EXISTING_ACTIVE, r4.HFT, r4.EXTREME)))
        self.assertEqual(self.con.execute("SELECT status FROM participants WHERE participant_id=?", (r4.RETIRED,)).fetchone()[0], "RETIRED")
        self.assertEqual(self.con.execute("SELECT active_effect FROM participant_specs WHERE participant_id=?", (r4.HFT,)).fetchone()[0], "SHADOW_ONLY")

    def test_return_math(self):
        self.assertAlmostEqual(r4.pct_return(100, 101, "LONG"), 1.0)
        self.assertAlmostEqual(r4.pct_return(100, 99, "SHORT"), 1.0)

    def test_extreme_features_detect_displacement_and_rebound(self):
        epoch = r4.activate_r4(self.con)
        sidecar = r4.Sidecar(self.con, epoch)
        sym, ts = "XUSDT", 10_000_000
        for i in range(61):
            sidecar._append_history(sym, ts - 330_000 + i * 5_000, 100.0)
        for t, p in [(ts-75_000,100.0),(ts-60_000,99.6),(ts-45_000,99.2),(ts-30_000,98.9),(ts-15_000,99.0),(ts,99.08)]:
            sidecar._append_history(sym, t, p)
        f = sidecar._extreme_features(sym, ts, 99.08)
        self.assertIsNotNone(f)
        self.assertLessEqual(f["previous_60s_return_ending_15s_ago_pct"], -0.8)
        self.assertGreaterEqual(f["ret_15s_pct"], 0.05)
        self.assertLess(f["recent_min_z"], -2.0)

    def test_no_real_order_api(self):
        text = (HERE / "tradelab_r4_sidecar.py").read_text()
        for forbidden in ["apiKey", "secretKey", "/fapi/v1/order", "create_order", "place_order"]:
            self.assertNotIn(forbidden, text)

    # ---- regression tests for infra fixes (audit follow-up) ----

    def test_keyset_pagination_never_skips_boundary_group(self):
        """G1: rows sharing one ts_ms must survive a LIMIT page break.
        Two ts groups of 3000 symbols each: legacy watermark cursor skipped
        the tail of the second group when the 5000-row page ended mid-group."""
        epoch = r4.activate_r4(self.con)
        sc = r4.Sidecar(self.con, epoch)
        base_ts = 20_000_000
        rows = []
        for i in range(3000):
            rows.append((base_ts, f"S{i:04d}USDT", 100.0))
            rows.append((base_ts + 5_000, f"S{i:04d}USDT", 100.0))
        self.con.executemany("INSERT INTO market_samples(ts_ms,symbol,last_price) VALUES(?,?,?)", rows)
        self.con.commit()
        total = 0
        for _ in range(10):
            n = sc.step()
            if n == 0:
                break
            total += n
        self.assertEqual(total, 6000)
        # and stepping again yields nothing (no reprocessing)
        self.assertEqual(sc.step(), 0)

    def test_close_is_idempotent_and_credits_equity_once(self):
        """G2/G3: second close attempt must be a no-op (no equity change,
        no duplicate PAPER_CLOSE event)."""
        epoch = r4.activate_r4(self.con)
        sc = r4.Sidecar(self.con, epoch)
        sym, ts = "YUSDT", 50_000_000
        self._insert_sample(ts, sym, 100.0)
        self.con.commit()
        self.assertTrue(sc._emit_signal_and_open(r4.EXTREME, ts, sym, "LONG", 100.0, 300, {"features": {}}))
        eq_open = sc._equity(r4.EXTREME)
        trade = self.con.execute("SELECT * FROM paper_trades WHERE status='OPEN' LIMIT 1").fetchone()
        self.assertIsNotNone(trade)
        self.assertTrue(sc._close_trade(trade, ts + 300_000, 101.0, "FIXED_300S"))
        eq_closed = sc._equity(r4.EXTREME)
        self.assertFalse(sc._close_trade(trade, ts + 300_000, 101.0, "FIXED_300S"))
        self.assertEqual(sc._equity(r4.EXTREME), eq_closed)
        self.assertNotEqual(eq_closed, eq_open)
        closes = self.con.execute("SELECT COUNT(*) FROM participant_events WHERE event_type='PAPER_CLOSE'").fetchone()[0]
        self.assertEqual(closes, 1)
        statuses = {r[0] for r in self.con.execute("SELECT DISTINCT status FROM paper_trades")}
        self.assertEqual(statuses, {"CLOSED"})

    def test_gap_detector_writes_recorder_gaps(self):
        """G7: per-symbol interval >= GAP_MIN_MS lands in recorder_gaps."""
        epoch = r4.activate_r4(self.con)
        sc = r4.Sidecar(self.con, epoch)
        sym, t0 = "GUSDT", 60_000_000
        self._insert_sample(t0, sym, 10.0)
        self._insert_sample(t0 + 120_000, sym, 10.0)
        self._insert_sample(t0 + 125_000, sym, 10.0)
        self.con.commit()
        for r in self.con.execute("SELECT * FROM market_samples WHERE symbol=? ORDER BY ts_ms", (sym,)):
            sc.process_row(r)
        gaps = self.con.execute("SELECT start_ms,end_ms,duration_ms,reason FROM recorder_gaps").fetchall()
        self.assertEqual(len(gaps), 1)
        self.assertEqual(gaps[0]["start_ms"], t0)
        self.assertEqual(gaps[0]["end_ms"], t0 + 120_000)
        self.assertTrue(gaps[0]["reason"].startswith("symbol_gap:"))

    def test_base_run_refuses_when_hotfix_active(self):
        """G4: legacy entrypoint must refuse to run over an isolated DB."""
        r4.activate_r4(self.con)
        r4.upsert_meta(self.con, "r4_isolation_hotfix_started_at_ms", "12345")
        self.con.commit()
        saved_detect, saved_argv = r4.detect_db, sys.argv
        r4.detect_db = lambda explicit=None: self.db
        sys.argv = ["tradelab_r4_sidecar.py", "--run"]
        try:
            with self.assertRaises(SystemExit) as cm:
                r4.main()
            self.assertIn("hotfix", str(cm.exception))
        finally:
            r4.detect_db = saved_detect
            sys.argv = saved_argv


class ForwardLabelAuditTests(unittest.TestCase):
    def setUp(self):
        self.audit = load_module("audit_fl", "audit_forward_labels.py")
        self.con = sqlite3.connect(":memory:")
        self.con.row_factory = sqlite3.Row
        self.con.executescript(
            """
            CREATE TABLE market_samples(ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,last_price REAL NOT NULL,PRIMARY KEY(ts_ms,symbol));
            CREATE TABLE market_states(id INTEGER PRIMARY KEY AUTOINCREMENT,ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,source TEXT NOT NULL,payload_json TEXT NOT NULL);
            CREATE TABLE forward_labels(market_state_id INTEGER PRIMARY KEY REFERENCES market_states(id),ret_300s REAL);
            """
        )

    def tearDown(self):
        self.con.close()

    def test_audit_flags_wrong_units_and_accepts_correct_labels(self):
        # X: price 100 -> 101 over exactly 300s => true ret = +1.0%
        self.con.execute("INSERT INTO market_samples VALUES(1000,'X',100.0)")
        self.con.execute("INSERT INTO market_samples VALUES(301000,'X',101.0)")
        self.con.execute("INSERT INTO market_states(id,ts_ms,symbol,source,payload_json) VALUES(1,1000,'X','T','{}')")
        self.con.execute("INSERT INTO market_states(id,ts_ms,symbol,source,payload_json) VALUES(2,1000,'X','T','{}')")
        self.con.execute("INSERT INTO forward_labels VALUES(1,1.0)")   # correct percent
        self.con.execute("INSERT INTO forward_labels VALUES(2,100.0)") # double-scaled garbage
        out = self.audit.audit(self.con, tol=1e-6)
        self.assertEqual(out["checked"], 2)
        self.assertEqual(out["mismatched"], 1)
        self.assertEqual(out["missing_inputs"], 0)
        self.assertGreater(out["max_abs_diff"], 90.0)

    def test_audit_counts_missing_inputs(self):
        self.con.execute("INSERT INTO market_samples VALUES(1000,'Z',50.0)")
        self.con.execute("INSERT INTO market_states(id,ts_ms,symbol,source,payload_json) VALUES(7,1000,'Z','T','{}')")
        self.con.execute("INSERT INTO forward_labels VALUES(7,0.5)")  # no future sample
        out = self.audit.audit(self.con)
        self.assertEqual(out["checked"], 0)
        self.assertEqual(out["missing_inputs"], 1)


if __name__ == "__main__":
    unittest.main()
