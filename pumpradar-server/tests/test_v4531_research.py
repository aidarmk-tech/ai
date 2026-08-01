from __future__ import annotations

import sqlite3
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace

from pumpradar_server.main import Service
from pumpradar_server.v453_research import ShortResearchManager


BASE_SCHEMA = """
CREATE TABLE regime_signals(id TEXT PRIMARY KEY);
CREATE TABLE regime_slots(
  id TEXT PRIMARY KEY, run_id TEXT, signal_id TEXT, channel TEXT, side TEXT,
  symbol TEXT, opened_at_ms INTEGER, margin_usdt REAL, notional_usdt REAL,
  entry_fee_usdt REAL, max_directional_return_percent REAL,
  min_directional_return_percent REAL, status TEXT, closed_at_ms INTEGER,
  exit_reason TEXT, net_pnl_usdt REAL, net_return_margin_percent REAL
);
"""


def settings(root: Path):
    return SimpleNamespace(
        btc_regime_path=root / "btc.json",
        btc_regime_max_age_hours=72.0,
        algorithm_version="4.5.3.1-server",
        strategy_version="test",
        config_hash=lambda: "hash",
        short_protected_floor_percent=0.25,
        short_target1_percent=1.0,
        short_continuation_veto_score=60.0,
        short_reversal_confirm_score=55.0,
        short_execution_failure_slippage_points=0.5,
        futures_fee_rate=0.0005,
        exit_window_sample_seconds=5,
        adaptive_protection_score=55.0,
        adaptive_giveback_weight=30.0,
        adaptive_cvd_weight=20.0,
        adaptive_obi_weight=15.0,
        adaptive_taker_weight=20.0,
        adaptive_momentum_weight=15.0,
    )


class V4531ResearchTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(BASE_SCHEMA)
        self.conn.execute("INSERT INTO regime_signals(id) VALUES('sig')")
        self.conn.execute(
            """INSERT INTO regime_slots VALUES(
               'slot','run','sig','REV_MC5_SHORT_600_2X','SHORT','TESTUSDT',
               0,20,40,0.02,0,0,'OPEN',NULL,NULL,NULL,NULL)"""
        )
        self.manager = ShortResearchManager(settings(self.root), self.conn, threading.RLock())
        self.slot = self.conn.execute("SELECT * FROM regime_slots WHERE id='slot'").fetchone()

    def tearDown(self) -> None:
        self.conn.close()
        self.temp.cleanup()

    def test_migration_is_idempotent(self) -> None:
        ShortResearchManager(settings(self.root), self.conn, threading.RLock())
        tables = {
            row[0] for row in self.conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
        }
        self.assertIn("exit_window_samples", tables)
        self.assertIn("exit_policy_outcomes", tables)

    def test_window_boundaries_and_five_second_deduplication(self) -> None:
        self.manager.tick_slot(self.slot, 1.2, 479_000, price=99, maximum=1.2, minimum=0)
        self.assertEqual(0, self.conn.execute("SELECT COUNT(*) FROM exit_window_samples").fetchone()[0])
        self.manager.tick_slot(self.slot, 1.3, 540_100, price=98.7, maximum=1.3, minimum=0)
        self.manager.tick_slot(self.slot, 1.4, 544_900, price=98.6, maximum=1.4, minimum=0)
        rows = list(self.conn.execute("SELECT age_bucket FROM exit_window_samples"))
        self.assertEqual([540], [row[0] for row in rows])
        fixed = self.conn.execute(
            "SELECT state FROM exit_policy_outcomes WHERE slot_id='slot' AND policy='EXIT_540'"
        ).fetchone()
        self.assertEqual("CLOSED", fixed[0])

    def test_utc_candle_alignment(self) -> None:
        self.assertEqual(30.0, self.manager._seconds_to_close(270_000, 300))
        self.assertEqual(50.0, self.manager._seconds_to_close(250_000, 300))
        self.assertEqual(20.0, self.manager._seconds_to_close(880_000, 900))

    def test_adaptive_protection_cannot_trigger_before_target1(self) -> None:
        dangerous = {
            "cvd_slope": 10,
            "obi_10": 0.5,
            "taker_buy_ratio_5s": 0.8,
            "short_momentum": -1,
        }
        self.manager.tick_slot(
            self.slot, 0.2, 500_000, price=99.8, maximum=0.9, minimum=0, features=dangerous
        )
        state = self.conn.execute(
            "SELECT state FROM exit_policy_outcomes WHERE policy='ADAPTIVE_TARGET1_PROTECTION'"
        ).fetchone()[0]
        self.assertEqual("OPEN", state)
        self.manager.tick_slot(
            self.slot, 0.2, 505_000, price=99.8, maximum=1.5, minimum=0, features=dangerous
        )
        state = self.conn.execute(
            "SELECT state,trigger_score FROM exit_policy_outcomes WHERE policy='ADAPTIVE_TARGET1_PROTECTION'"
        ).fetchone()
        self.assertEqual("CLOSED", state[0])
        self.assertGreaterEqual(state[1], 55)

    def test_coverage_events_are_throttled(self) -> None:
        events: list[tuple[str, str, str]] = []
        service = object.__new__(Service)
        service.settings = SimpleNamespace(coverage_log_interval_seconds=60)
        service.storage = SimpleNamespace(event=lambda *args: events.append(args))
        service.last_coverage_event_ms = 0
        service.last_coverage_message = None
        self.assertTrue(service._coverage_event("same", 100_000))
        self.assertFalse(service._coverage_event("same", 120_000))
        self.assertTrue(service._coverage_event("same", 160_000))
        self.assertEqual(2, len(events))


if __name__ == "__main__":
    unittest.main()
