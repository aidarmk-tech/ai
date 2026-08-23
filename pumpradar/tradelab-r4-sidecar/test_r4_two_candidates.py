#!/usr/bin/env python3
import importlib.util
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent

base_spec = importlib.util.spec_from_file_location("tradelab_r4_base_test", HERE / "tradelab_r4_sidecar.py")
base = importlib.util.module_from_spec(base_spec)
sys.modules[base_spec.name] = base
base_spec.loader.exec_module(base)

iso_spec = importlib.util.spec_from_file_location("tradelab_r4_iso_test", HERE / "tradelab_r4_isolation.py")
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
CREATE INDEX idx_oi_symbol_ts ON open_interest_samples(symbol,ts_ms);
CREATE INDEX idx_liquidations_symbol_ts ON liquidations(symbol,ts_ms);
"""


class CandidateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "test.sqlite3"
        self.con = sqlite3.connect(self.db)
        self.con.row_factory = sqlite3.Row
        self.con.executescript(SCHEMA)
        t = 1_000_000
        for pid in [*base.EXISTING_ACTIVE, base.RETIRED]:
            self.con.execute(
                "INSERT INTO participants VALUES(?,?, 'ACTIVE',20,20,NULL,'CANDIDATE',?)",
                (pid, pid, t),
            )
            self.con.execute(
                "INSERT INTO participant_specs VALUES(?,?, '{}',?,'SHADOW_ONLY')",
                (pid, "old", t),
            )
        self.con.commit()
        base.activate_r4(self.con)
        self.clean = iso.apply_isolation(self.con)
        iso.apply_candidate_set(self.con)

    def tearDown(self):
        self.con.close()
        self.tmp.cleanup()

    def join(self, pid):
        return int(self.con.execute("SELECT value FROM meta WHERE key=?", (f"join_started_at_ms_{pid}",)).fetchone()[0])

    def market(self, ts, symbol, price, qv=50_000_000.0):
        self.con.execute(
            "INSERT INTO market_samples(ts_ms,symbol,last_price,bid,ask,quote_volume_24h) VALUES(?,?,?,?,?,?)",
            (ts, symbol, price, price * 0.9999, price * 1.0001, qv),
        )

    def test_hft_retired_new_candidates_join_idempotently_without_epoch_reset(self):
        epoch = int(self.con.execute("SELECT value FROM meta WHERE key='five_model_epoch_started_at_ms'").fetchone()[0])
        oi_join = self.join(iso.OI)
        liq_join = self.join(iso.LIQ)

        active = {r[0] for r in self.con.execute("SELECT participant_id FROM participants WHERE status='ACTIVE'")}
        self.assertEqual(active, set(iso.ACTIVE6))
        hft = self.con.execute("SELECT status,role FROM participants WHERE participant_id=?", (base.HFT,)).fetchone()
        self.assertEqual((hft["status"], hft["role"]), ("RETIRED", "ELIMINATED"))
        self.assertEqual(
            self.con.execute("SELECT active_effect FROM participant_specs WHERE participant_id=?", (base.HFT,)).fetchone()[0],
            "RETIRED_NO_SCORE",
        )
        for pid in (iso.OI, iso.LIQ):
            row = self.con.execute("SELECT starting_equity,equity,status FROM participants WHERE participant_id=?", (pid,)).fetchone()
            self.assertEqual((row["starting_equity"], row["equity"], row["status"]), (20.0, 20.0, "ACTIVE"))

        self.con.execute("UPDATE participants SET equity=21.25 WHERE participant_id=?", (iso.OI,))
        self.con.commit()
        base.activate_r4(self.con)  # temporarily resurrects legacy HFT metadata
        iso.apply_isolation(self.con)
        iso.apply_candidate_set(self.con)  # must restore retirement without resetting joins/equity
        self.assertEqual(self.join(iso.OI), oi_join)
        self.assertEqual(self.join(iso.LIQ), liq_join)
        self.assertEqual(self.con.execute("SELECT equity FROM participants WHERE participant_id=?", (iso.OI,)).fetchone()[0], 21.25)
        self.assertEqual(int(self.con.execute("SELECT value FROM meta WHERE key='five_model_epoch_started_at_ms'").fetchone()[0]), epoch)
        self.assertEqual(self.con.execute("SELECT status FROM participants WHERE participant_id=?", (base.HFT,)).fetchone()[0], "RETIRED")

    def test_hft_database_boundary_blocks_new_events_and_trades(self):
        retired = int(self.con.execute("SELECT value FROM meta WHERE key=?", (iso.HFT_RETIRED_KEY,)).fetchone()[0])
        before = self.con.execute("SELECT COUNT(*) FROM participant_events WHERE participant_id=?", (base.HFT,)).fetchone()[0]
        base.add_event(self.con, base.HFT, retired + 1, "XUSDT", "SHOULD_BE_BLOCKED", {})
        self.con.execute(
            "INSERT INTO paper_trades(trade_id,participant_id,symbol_a,side_a,opened_at_ms,entry_a,exit_due_ms,notional_usdt,status,signal_json) "
            "VALUES('blocked',?,?,?, ?,100,?,10,'R4_OPEN','{}')",
            (base.HFT, "XUSDT", "LONG", retired + 1, retired + 60_001),
        )
        self.con.commit()
        after = self.con.execute("SELECT COUNT(*) FROM participant_events WHERE participant_id=?", (base.HFT,)).fetchone()[0]
        self.assertEqual(after, before)
        self.assertIsNone(self.con.execute("SELECT 1 FROM paper_trades WHERE trade_id='blocked'").fetchone())

    def test_oi_flush_opens_long_and_stop_is_frozen(self):
        join = self.join(iso.OI)
        t = join + 700_000
        s = "OIUSDT"
        self.market(t - 600_000, s, 100.0)
        self.market(t - 60_000, s, 99.10)
        self.market(t, s, 99.15)
        self.con.execute("INSERT INTO open_interest_samples VALUES(?,?,?,?)", (t - 600_000, s, 1000.0, 10_000_000.0))
        self.con.execute("INSERT INTO open_interest_samples VALUES(?,?,?,?)", (t - 30_000, s, 992.0, 9_900_000.0))
        self.con.commit()
        sc = iso.IsolatedSidecar(self.con, self.clean)
        row = self.con.execute("SELECT * FROM market_samples WHERE ts_ms=? AND symbol=?", (t, s)).fetchone()
        sc._maybe_oi_flush(row)
        trade = self.con.execute("SELECT * FROM paper_trades WHERE participant_id=? AND status='R4_OPEN'", (iso.OI,)).fetchone()
        self.assertIsNotNone(trade)
        self.assertEqual(trade["side_a"], "LONG")
        sig = json.loads(trade["signal_json"])
        self.assertLessEqual(sig["features"]["oi_delta_10m_pct"], -0.75)
        self.assertLess(sig["features"]["stop_price"], sig["features"]["pre_entry_extreme"])
        # same observation is under cooldown, so no duplicate
        sc._maybe_oi_flush(row)
        self.assertEqual(self.con.execute("SELECT COUNT(*) FROM paper_trades WHERE participant_id=?", (iso.OI,)).fetchone()[0], 1)
        sc._close_reversion(iso.OI, s, t + 5_000, sig["features"]["stop_price"] * 0.999)
        closed = self.con.execute("SELECT status FROM paper_trades WHERE trade_id=?", (trade["trade_id"],)).fetchone()[0]
        self.assertEqual(closed, "CLOSED")
        payload = json.loads(self.con.execute("SELECT payload_json FROM participant_events WHERE participant_id=? AND event_type='PAPER_CLOSE' ORDER BY id DESC LIMIT 1", (iso.OI,)).fetchone()[0])
        self.assertEqual(payload["exit_reason"], "ADVERSE_STOP")

    def test_liquidation_sell_cascade_opens_long(self):
        join = self.join(iso.LIQ)
        t = join + 120_000
        s = "LIQUSDT"
        self.market(t - 60_000, s, 100.0)
        self.market(t - 15_000, s, 99.0)
        self.market(t, s, 99.10)
        self.con.execute("INSERT INTO liquidations(ts_ms,symbol,side,quantity,price,notional) VALUES(?,?,?,?,?,?)", (t - 20_000, s, "SELL", 250, 99.0, 25_000.0))
        self.con.execute("INSERT INTO liquidations(ts_ms,symbol,side,quantity,price,notional) VALUES(?,?,?,?,?,?)", (t - 10_000, s, "BUY", 20, 99.0, 2_000.0))
        self.con.commit()
        sc = iso.IsolatedSidecar(self.con, self.clean)
        row = self.con.execute("SELECT * FROM market_samples WHERE ts_ms=? AND symbol=?", (t, s)).fetchone()
        sc._maybe_liq_cascade(row)
        trade = self.con.execute("SELECT * FROM paper_trades WHERE participant_id=? AND status='R4_OPEN'", (iso.LIQ,)).fetchone()
        self.assertIsNotNone(trade)
        self.assertEqual(trade["side_a"], "LONG")
        sig = json.loads(trade["signal_json"])
        self.assertEqual(sig["features"]["dominant_liq_side"], "SELL")
        self.assertGreaterEqual(sig["features"]["liq_sell_notional_60s"], 20_000)
        self.assertGreaterEqual(sig["features"]["confirmation_15s_pct"], 0.05)

    def test_new_participant_cannot_emit_before_join_and_score_uses_join_boundary(self):
        join = self.join(iso.OI)
        sc = iso.IsolatedSidecar(self.con, self.clean)
        self.assertFalse(sc._emit_signal_and_open(iso.OI, join - 1, "XUSDT", "LONG", 100.0, 240, {"features": {"stop_price": 90.0}}))
        self.assertEqual(self.con.execute("SELECT COUNT(*) FROM paper_trades WHERE participant_id=?", (iso.OI,)).fetchone()[0], 0)

        self.con.execute(
            "INSERT INTO paper_trades(trade_id,participant_id,symbol_a,side_a,opened_at_ms,entry_a,exit_due_ms,closed_at_ms,exit_a,gross_return_pct,net_return_pct,notional_usdt,pnl_usdt,status,signal_json) "
            "VALUES('prejoin',?,?,?, ?,100,?, ?,101,1,0.86,10,100,'CLOSED','{}')",
            (iso.OI, "OLDUSDT", "LONG", join - 10, join + 1000, join),
        )
        self.con.execute(
            "INSERT INTO paper_trades(trade_id,participant_id,symbol_a,side_a,opened_at_ms,entry_a,exit_due_ms,closed_at_ms,exit_a,gross_return_pct,net_return_pct,notional_usdt,pnl_usdt,status,signal_json) "
            "VALUES('postjoin',?,?,?, ?,100,?, ?,101,1,0.86,10,1,'CLOSED','{}')",
            (iso.OI, "NEWUSDT", "LONG", join + 10, join + 1000, join + 500),
        )
        self.con.commit()
        card = next(x for x in iso.status(self.con)["scorecard"] if x["participant_id"] == iso.OI)
        self.assertEqual(card["closed"], 1)
        self.assertAlmostEqual(card["pnl_usdt"], 1.0)
        self.assertAlmostEqual(card["score_equity"], 21.0)


if __name__ == "__main__":
    unittest.main()
