#!/usr/bin/env python3
import contextlib
import importlib.util
import io
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent

base_spec = importlib.util.spec_from_file_location("r4_restart_base", HERE / "tradelab_r4_sidecar.py")
base = importlib.util.module_from_spec(base_spec)
sys.modules[base_spec.name] = base
base_spec.loader.exec_module(base)

iso_spec = importlib.util.spec_from_file_location("r4_restart_iso", HERE / "tradelab_r4_isolation.py")
iso = importlib.util.module_from_spec(iso_spec)
sys.modules[iso_spec.name] = iso
iso_spec.loader.exec_module(iso)

SCHEMA = """
CREATE TABLE participants(participant_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'ACTIVE',starting_equity REAL NOT NULL DEFAULT 20,equity REAL NOT NULL DEFAULT 20,rank INTEGER,role TEXT,created_at_ms INTEGER NOT NULL);
CREATE TABLE participant_specs(participant_id TEXT PRIMARY KEY,spec_version TEXT NOT NULL,config_json TEXT NOT NULL,frozen_at_ms INTEGER NOT NULL,active_effect TEXT NOT NULL DEFAULT 'SHADOW_ONLY');
CREATE TABLE participant_events(id INTEGER PRIMARY KEY AUTOINCREMENT,ts_ms INTEGER NOT NULL,participant_id TEXT NOT NULL,symbol TEXT,event_type TEXT NOT NULL,payload_json TEXT NOT NULL);
CREATE TABLE paper_trades(trade_id TEXT PRIMARY KEY,participant_id TEXT NOT NULL,symbol_a TEXT NOT NULL,symbol_b TEXT,side_a TEXT NOT NULL,side_b TEXT,hedge_ratio REAL,opened_at_ms INTEGER NOT NULL,entry_a REAL NOT NULL,entry_b REAL,exit_due_ms INTEGER NOT NULL,closed_at_ms INTEGER,exit_a REAL,exit_b REAL,gross_return_pct REAL,net_return_pct REAL,notional_usdt REAL NOT NULL,pnl_usdt REAL,status TEXT NOT NULL DEFAULT 'OPEN',signal_json TEXT NOT NULL);
CREATE TABLE market_samples(ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,last_price REAL NOT NULL,bid REAL,ask REAL,bid_qty REAL,ask_qty REAL,mark_price REAL,index_price REAL,funding_rate REAL,quote_volume_24h REAL,trade_count_24h INTEGER,PRIMARY KEY(ts_ms,symbol));
CREATE TABLE depth_samples(ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,spread_bps REAL,bid_notional_10 REAL,ask_notional_10 REAL,imbalance REAL,best_bid_qty REAL,best_ask_qty REAL,bid_replenishment REAL,ask_replenishment REAL,PRIMARY KEY(ts_ms,symbol));
CREATE TABLE market_states(id INTEGER PRIMARY KEY AUTOINCREMENT,ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,source TEXT NOT NULL,payload_json TEXT NOT NULL);
CREATE TABLE open_interest_samples(ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,open_interest REAL NOT NULL,open_interest_value REAL,PRIMARY KEY(ts_ms,symbol));
CREATE TABLE liquidations(id INTEGER PRIMARY KEY AUTOINCREMENT,ts_ms INTEGER NOT NULL,symbol TEXT NOT NULL,side TEXT NOT NULL,quantity REAL,price REAL,notional REAL);
CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
"""


class RetirementRestartTest(unittest.TestCase):
    def test_clean_restart_never_calls_base_activation_or_resurrects_hft(self):
        with tempfile.TemporaryDirectory() as td:
            db = Path(td) / "test.sqlite3"
            con = sqlite3.connect(db)
            con.row_factory = sqlite3.Row
            con.executescript(SCHEMA)
            for pid in [*base.EXISTING_ACTIVE, base.RETIRED]:
                con.execute("INSERT INTO participants VALUES(?,?, 'ACTIVE',20,20,NULL,'CANDIDATE',1)", (pid, pid))
                con.execute("INSERT INTO participant_specs VALUES(?,?, '{}',1,'SHADOW_ONLY')", (pid, "old"))
            con.commit()
            base.activate_r4(con)
            iso.apply_isolation(con)
            iso.apply_candidate_set(con)
            self.assertEqual(con.execute("SELECT status FROM participants WHERE participant_id=?", (base.HFT,)).fetchone()[0], "RETIRED")
            epoch_before = con.execute("SELECT value FROM meta WHERE key='five_model_epoch_started_at_ms'").fetchone()[0]
            con.close()

            old_detect = iso.base.detect_db
            old_activate = iso.base.activate_r4
            old_argv = sys.argv
            iso.base.detect_db = lambda explicit=None: db
            iso.base.activate_r4 = lambda _con: (_ for _ in ()).throw(AssertionError("base.activate_r4 must not run after clean isolation"))
            sys.argv = ["tradelab_r4_isolation.py", "--status"]
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    iso.main()
            finally:
                iso.base.detect_db = old_detect
                iso.base.activate_r4 = old_activate
                sys.argv = old_argv

            check = sqlite3.connect(db)
            hft = check.execute("SELECT status,role FROM participants WHERE participant_id=?", (base.HFT,)).fetchone()
            self.assertEqual(hft, ("RETIRED", "ELIMINATED"))
            self.assertEqual(check.execute("SELECT active_effect FROM participant_specs WHERE participant_id=?", (base.HFT,)).fetchone()[0], "RETIRED_NO_SCORE")
            self.assertEqual(check.execute("SELECT value FROM meta WHERE key='five_model_epoch_started_at_ms'").fetchone()[0], epoch_before)
            check.close()


if __name__ == "__main__":
    unittest.main()
