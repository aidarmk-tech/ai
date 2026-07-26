from __future__ import annotations

import gzip
import json
import os
import sqlite3
import sys
import tempfile
import types
import unittest
from pathlib import Path


class AuditFlowTest(unittest.TestCase):
    def setUp(self) -> None:
        sys.modules.setdefault("aiohttp", types.SimpleNamespace())
        self.root = Path(tempfile.mkdtemp())
        os.environ["PUMPRADAR_DATA_DIR"] = str(self.root)
        os.environ["PUMPRADAR_DB_PATH"] = str(self.root / "audit.sqlite3")
        os.environ["PUMPRADAR_API_TOKEN"] = "test"

        from pumpradar_server.config import Settings

        self.settings = Settings(
            data_dir=self.root,
            db_path=self.root / "audit.sqlite3",
            api_token="test",
        )

    def test_slot_and_policy_state_survive_restart(self) -> None:
        from pumpradar_server.storage import Storage

        first = Storage(self.settings)
        first_run = first.start_run("first")
        slot_id = first.create_slot(
            None,
            "BTCUSDT",
            "event",
            1_000_000,
            100.0,
            100.1,
        )
        policy = first.policies_for_slot(slot_id)[0]
        first.update_policy(
            policy["id"],
            activated_at_ms=1_001_000,
            partial_quantity=0.04,
            weakening_ticks=1,
        )
        first.conn.close()

        second = Storage(self.settings)
        second.start_run("second")
        active = second.baseline_open_slot()
        self.assertEqual(slot_id, active["id"])
        restored = second.policies_for_slot(slot_id)[0]
        self.assertEqual(1_001_000, restored["activated_at_ms"])
        self.assertEqual(0.04, restored["partial_quantity"])
        self.assertEqual(1, restored["weakening_ticks"])
        previous = second.conn.execute(
            "SELECT status, ended_at_ms FROM experiment_runs WHERE id=?",
            (first_run,),
        ).fetchone()
        self.assertEqual("INTERRUPTED", previous["status"])
        self.assertIsNotNone(previous["ended_at_ms"])

    def test_incompatible_open_slot_is_closed(self) -> None:
        from pumpradar_server.storage import Storage

        first = Storage(self.settings)
        first.start_run("first")
        slot_id = first.create_slot(None, "ETHUSDT", "event", 1_000, 10.0, 10.0)
        first.conn.execute(
            "UPDATE paper_slots SET config_hash='old-config' WHERE id=?",
            (slot_id,),
        )
        first.conn.commit()
        first.conn.close()

        second = Storage(self.settings)
        second.start_run("second")
        slot = second.conn.execute(
            "SELECT baseline_status, baseline_exit_reason FROM paper_slots WHERE id=?",
            (slot_id,),
        ).fetchone()
        self.assertEqual(("CLOSED", "CONFIG_CHANGED"), tuple(slot))
        open_policies = second.conn.execute(
            "SELECT COUNT(*) FROM policy_runs WHERE slot_id=? AND state='OPEN'",
            (slot_id,),
        ).fetchone()[0]
        self.assertEqual(0, open_policies)

    def test_weakening_matches_trade3_point_35_policy(self) -> None:
        from pumpradar_server.models import FlowMetrics
        from pumpradar_server.paper import weakening_confirmed

        weak = FlowMetrics(
            taker_buy_ratio_5s=0.54,
            taker_buy_ratio_15s=0.70,
            taker_buy_ratio_30s=0.75,
            cvd_slope=1.0,
        )
        self.assertFalse(weakening_confirmed(weak, 0.34, self.settings))
        self.assertTrue(weakening_confirmed(weak, 0.35, self.settings))

    def test_stale_symbol_measurement_is_rejected(self) -> None:
        from pumpradar_server.market import MarketState

        state = MarketState()
        self.assertEqual(999_999, state.measurement_age_ms("BTCUSDT", 20_000))

    def test_export_is_self_consistent(self) -> None:
        from pumpradar_server.storage import Storage

        storage = Storage(self.settings)
        storage.start_run("export")
        output = storage.export_all()
        manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(2, manifest["schema_version"])
        self.assertEqual(1, manifest["row_counts"]["experiment_runs"])

        restored = self.root / "restored.sqlite3"
        with gzip.open(output / "pumpradar.sqlite3.gz", "rb") as source:
            restored.write_bytes(source.read())
        connection = sqlite3.connect(restored)
        self.assertEqual("ok", connection.execute("PRAGMA integrity_check").fetchone()[0])
        self.assertEqual([], list(connection.execute("PRAGMA foreign_key_check")))
        for name, metadata in manifest["files"].items():
            self.assertTrue((output / name).is_file())
            self.assertEqual(64, len(metadata["sha256"]))


if __name__ == "__main__":
    unittest.main()
