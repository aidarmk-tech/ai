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
CREATE TABLE paper_trades(trade_id TEXT PRIMARY KEY,participant_id TEXT NOT NULL,symbol_a TEXT NOT NULL,symbol_b TEXT,side_a TEXT NOT NULL,side_b TEXT,hedge_ratio REAL,opened_at_ms INTEGER NOT NULL,entry_a REAL NOT NULL,entry_b REAL,exit_due_ms INTEGER NOT NULL,closed_at_ms INTEGER,exit_a REAL,exit_b REAL,gross_return_pct REAL,net_return_pct REAL,notional_usdt REAL NOT NULL,pnl_usdt REAL,status TEXT NOT NULL DEFAULT 'OPEN',signal_json TEXT NOT NULL);
CREATE TABLE market_samples(ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,last_price REAL NOT NULL,bid REAL,ask REAL,bid_qty REAL,ask_qty REAL,mark_price REAL,index_price REAL,funding_rate REAL,quote_volume_24h REAL,trade_count_24h INTEGER,PRIMARY KEY(ts_ms,symbol));
CREATE TABLE depth_samples(ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,spread_bps REAL,bid_notional_10 REAL,ask_notional_10 REAL,imbalance REAL,best_bid_qty REAL,best_ask_qty REAL,bid_replenishment REAL,ask_replenishment REAL,PRIMARY KEY(ts_ms,symbol));
CREATE TABLE market_states(id INTEGER PRIMARY KEY AUTOINCREMENT,ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,source TEXT NOT NULL,payload_json TEXT NOT NULL);
CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
"""

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

if __name__ == "__main__":
    unittest.main()
