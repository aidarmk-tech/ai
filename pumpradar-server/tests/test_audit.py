from __future__ import annotations

import gzip
import asyncio
import json
import os
import sqlite3
import sys
import tempfile
import types
import unittest
from dataclasses import replace
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

    def test_protected_floor_covers_fees_and_tracks_half_peak(self) -> None:
        from pumpradar_server.paper import protected_floor_percent

        self.assertEqual(0.30, protected_floor_percent(0.40, self.settings))
        self.assertEqual(0.50, protected_floor_percent(1.00, self.settings))
        self.assertEqual(1.20, protected_floor_percent(2.40, self.settings))

    def test_protected_exit_uses_half_peak_floor(self) -> None:
        from pumpradar_server.paper import PaperManager
        from pumpradar_server.storage import Storage

        class FixedMarket:
            sell_price = 101.1

            def executable_sell_price(self, *args, **kwargs):
                return self.sell_price

        async def notify(_message):
            return None

        storage = Storage(self.settings)
        storage.start_run("protected-floor")
        opened_at = 1_000_000
        slot_id = storage.create_slot(
            None, "BTCUSDT", "event", opened_at, 100.0, 100.0
        )
        policy = next(
            row
            for row in storage.policies_for_slot(slot_id)
            if row["policy"] == "B_FULL_PROTECTED"
        )
        storage.update_policy(
            policy["id"],
            activated_at_ms=opened_at + 1_000,
            peak_return_percent=2.0,
        )
        market = FixedMarket()
        manager = PaperManager(self.settings, market, storage, notify)

        policy = storage.conn.execute(
            "SELECT * FROM policy_runs WHERE id=?", (policy["id"],)
        ).fetchone()
        asyncio.run(manager._update_policy(
            storage.conn.execute(
                "SELECT * FROM paper_slots WHERE id=?", (slot_id,)
            ).fetchone(),
            policy,
            market.sell_price,
            1.10,
            None,
            opened_at + 2_000,
        ))
        self.assertEqual(
            "OPEN",
            storage.conn.execute(
                "SELECT state FROM policy_runs WHERE id=?", (policy["id"],)
            ).fetchone()[0],
        )

        market.sell_price = 100.9
        policy = storage.conn.execute(
            "SELECT * FROM policy_runs WHERE id=?", (policy["id"],)
        ).fetchone()
        asyncio.run(manager._update_policy(
            storage.conn.execute(
                "SELECT * FROM paper_slots WHERE id=?", (slot_id,)
            ).fetchone(),
            policy,
            market.sell_price,
            0.90,
            None,
            opened_at + 3_000,
        ))
        closed = storage.conn.execute(
            "SELECT state, exit_reason FROM policy_runs WHERE id=?",
            (policy["id"],),
        ).fetchone()
        self.assertEqual(("CLOSED", "PROTECTED_EXIT"), tuple(closed))

    def test_v436_strict_filters_preserve_v435_entries_as_shadow(self) -> None:
        from pumpradar_server.models import Candidate, FlowMetrics, BookMetrics, PeakFeatures
        from pumpradar_server.strategy import assess

        candidate = Candidate(
            "BTCUSDT", 100.0, 10_000_000.0, 1.0, 1.5, 2.0, 0.5, 1.0, 1.0
        )
        flow = FlowMetrics(
            ready=True,
            quote_volume_30s=200_000.0,
            trade_count_30s=60,
            trades_per_second=3.0,
            taker_buy_ratio_30s=0.90,
            taker_buy_ratio_15s=0.95,
            taker_buy_ratio_5s=0.95,
            cvd_30s=100.0,
            cvd_15s=60.0,
            cvd_5s=30.0,
            cvd_slope=1.0,
            volume_z_30s=6.0,
        )
        book = BookMetrics(
            spread_bps=10.0,
            obi_10=0.6,
            buy_slippage_percent=0.05,
            sell_slippage_percent=0.05,
            depth_age_ms=0,
            depth_update_id=1,
        )
        peak = PeakFeatures()

        def decide(candidate_flow: FlowMetrics):
            return assess(
                candidate,
                candidate_flow,
                book,
                peak,
                self.settings,
                100,
                0.0,
                0.5,
                False,
            )

        accepted = decide(flow)
        self.assertTrue(accepted.strict_passed)
        self.assertEqual("V436_STRICT_TRADE3", accepted.reasons[0])

        rejected = {
            "TBR15_LOW": replace(
                flow, taker_buy_ratio_15s=0.89, taker_buy_ratio_5s=0.90
            ),
            "TBR5_LOW": replace(
                flow, taker_buy_ratio_15s=0.90, taker_buy_ratio_5s=0.74
            ),
            "ARTIFICIAL_NONZERO": replace(flow, largest_trade_share=0.45),
            "EXHAUSTION_NONZERO": replace(flow, spread_bps=61.0),
        }
        for blocker, candidate_flow in rejected.items():
            with self.subTest(blocker=blocker):
                decision = decide(candidate_flow)
                self.assertFalse(decision.strict_passed)
                self.assertTrue(decision.shadow_passed)
                self.assertEqual("TRADE3_SHADOW", decision.label)
                self.assertIn("V435_COMPAT_SHADOW", decision.reasons)
                self.assertIn(blocker, decision.blockers)

    def test_c_weakening_is_primary_without_closing_a_or_b(self) -> None:
        from pumpradar_server.models import FlowMetrics
        from pumpradar_server.paper import PaperManager
        from pumpradar_server.storage import Storage

        class FixedMarket:
            sell_price = 100.45

            def executable_sell_price(self, *args, **kwargs):
                return self.sell_price

        messages = []

        async def notify(message):
            messages.append(message)

        storage = Storage(self.settings)
        storage.start_run("primary-c")
        opened_at = 1_000_000
        slot_id = storage.create_slot(
            None, "BTCUSDT", "event", opened_at, 100.0, 100.0
        )
        policy = next(
            row
            for row in storage.policies_for_slot(slot_id)
            if row["policy"] == "C_WEAKENING"
        )
        storage.update_policy(policy["id"], peak_return_percent=0.80)
        market = FixedMarket()
        manager = PaperManager(self.settings, market, storage, notify)
        weak_flow = FlowMetrics(
            taker_buy_ratio_30s=0.90,
            taker_buy_ratio_15s=0.90,
            taker_buy_ratio_5s=0.50,
            cvd_slope=1.0,
        )

        for step, current_return in enumerate((0.45, 0.44), start=1):
            market.sell_price = 100.0 + current_return
            policy = storage.conn.execute(
                "SELECT * FROM policy_runs WHERE id=?", (policy["id"],)
            ).fetchone()
            asyncio.run(manager._update_policy(
                storage.conn.execute(
                    "SELECT * FROM paper_slots WHERE id=?", (slot_id,)
                ).fetchone(),
                policy,
                market.sell_price,
                current_return,
                weak_flow,
                opened_at + step * 1_000,
            ))

        policies = {
            row["policy"]: row["state"]
            for row in storage.policies_for_slot(slot_id)
        }
        self.assertEqual("CLOSED", policies["C_WEAKENING"])
        self.assertEqual("OPEN", policies["A_PARTIAL_20"])
        self.assertEqual("OPEN", policies["B_FULL_PROTECTED"])
        self.assertEqual(
            "OPEN",
            storage.conn.execute(
                "SELECT baseline_status FROM paper_slots WHERE id=?", (slot_id,)
            ).fetchone()[0],
        )
        self.assertTrue(
            any("C_WEAKENING primary" in message for message in messages)
        )

    def test_stale_symbol_measurement_is_rejected(self) -> None:
        from pumpradar_server.market import MarketState

        state = MarketState()
        self.assertEqual(999_999, state.measurement_age_ms("BTCUSDT", 20_000))

    def test_real_stream_restart_discards_partial_measurement_window(self) -> None:
        from pumpradar_server.market import MarketState, SymbolInfo

        state = MarketState()
        state.set_universe([SymbolInfo("BTCUSDT", "USDT", "TRADING", True)])
        state.on_mini_tickers(
            [{"s": "BTCUSDT", "c": "100", "q": "10000000"}],
            10_000,
        )
        state.on_agg_trade(
            "BTCUSDT",
            {"a": 10, "p": "100", "q": "1", "T": 10_000, "m": False},
            10_000,
        )
        state.on_book_ticker(
            "BTCUSDT",
            {"u": 10, "b": "99", "a": "100"},
            10_000,
        )
        state.on_depth(
            "BTCUSDT",
            {"lastUpdateId": 10, "bids": [["99", "1"]], "asks": [["100", "1"]]},
            10_000,
        )
        self.assertEqual(0, state.measurement_age_ms("BTCUSDT", 10_000))

        state.reset_candidate_stream_state({"BTCUSDT"})
        state.on_agg_trade(
            "BTCUSDT",
            {"a": 20, "p": "101", "q": "1", "T": 11_000, "m": False},
            11_000,
        )
        self.assertFalse(state.flow_metrics("BTCUSDT", 11_000).trade_gap)
        self.assertEqual(999_999, state.measurement_age_ms("BTCUSDT", 11_000))

    def test_subscription_changes_do_not_require_a_stream_restart(self) -> None:
        from pumpradar_server.market import BinanceFeed, MarketState

        class FakeWebSocket:
            def __init__(self) -> None:
                self.messages = []

            async def send_json(self, payload) -> None:
                self.messages.append(payload)

        state = MarketState()
        feed = BinanceFeed(self.settings, state)
        ws = FakeWebSocket()
        next_id = asyncio.run(feed._update_subscriptions(
            ws,
            {"btcusdt@aggTrade", "btcusdt@bookTicker"},
            {"ethusdt@aggTrade", "ethusdt@bookTicker"},
            7,
        ))
        self.assertEqual(["UNSUBSCRIBE", "SUBSCRIBE"], [m["method"] for m in ws.messages])
        self.assertEqual(9, next_id)
        self.assertEqual(1, state.candidate_subscription_update_count)

    def test_decision_symbols_get_immediate_depth_coverage(self) -> None:
        fake_web = types.SimpleNamespace(middleware=lambda handler: handler)
        sys.modules["aiohttp"] = types.SimpleNamespace(web=fake_web)
        from pumpradar_server.main import Service

        service = Service(self.settings)
        self.assertTrue(service._ensure_decision_coverage({"BTCUSDT"}))
        self.assertEqual({"BTCUSDT"}, service.warm_symbols)
        self.assertEqual({"BTCUSDT"}, service.depth_symbols)
        self.assertEqual(("BTCUSDT",), service.feed._warm_symbols)
        self.assertEqual(("BTCUSDT",), service.feed._depth_symbols)
        self.assertFalse(service._ensure_decision_coverage({"BTCUSDT"}))

    def test_missing_first_depth_snapshot_is_reported_as_warming(self) -> None:
        from pumpradar_server.models import Candidate, FlowMetrics, BookMetrics, PeakFeatures
        from pumpradar_server.strategy import assess

        candidate = Candidate(
            "BTCUSDT", 100.0, 10_000_000.0, 1.0, 1.5, 2.0, 0.5, 1.0, 1.0
        )
        flow = FlowMetrics(
            ready=True,
            trade_count_30s=10,
            taker_buy_ratio_30s=0.9,
            taker_buy_ratio_15s=0.9,
            taker_buy_ratio_5s=0.9,
            cvd_30s=100.0,
            cvd_15s=60.0,
            cvd_5s=30.0,
            cvd_slope=1.0,
            volume_z_30s=5.0,
        )
        decision = assess(
            candidate,
            flow,
            BookMetrics(),
            PeakFeatures(),
            self.settings,
            999_999,
            0.0,
            0.5,
            False,
        )
        self.assertIn("DEPTH_WARMING", decision.risk.veto_reasons)
        self.assertNotIn("STALE_FEED", decision.risk.veto_reasons)
        self.assertFalse(decision.strict_passed)

    def test_v436_filters_entries_and_keeps_protected_floor(self) -> None:
        self.assertEqual("4.3.6-server", self.settings.algorithm_version)
        self.assertEqual("C_WEAKENING", self.settings.primary_policy)
        self.assertEqual(60, self.settings.warm_pool_size)
        self.assertEqual(15, self.settings.deep_candidates)
        self.assertEqual(20, self.settings.depth_candidates)
        self.assertEqual(0.875, self.settings.min_taker_buy_ratio_30s)
        self.assertEqual(0.90, self.settings.min_trade3_taker_buy_ratio_15s)
        self.assertEqual(0.75, self.settings.min_trade3_taker_buy_ratio_5s)
        self.assertEqual(0, self.settings.max_trade3_exhaustion_risk)
        self.assertEqual(0, self.settings.max_trade3_artificial_risk)
        self.assertEqual(3.0, self.settings.max_return_5m)
        self.assertEqual(0.30, self.settings.protected_stop_percent)
        self.assertEqual(0.50, self.settings.protected_peak_fraction)

    def test_daily_pnl_keeps_alternative_policies_separate(self) -> None:
        from pumpradar_server.storage import Storage

        storage = Storage(self.settings)
        storage.start_run("daily")
        now_ms = 1_800_000_000_000
        slot_id = storage.create_slot(None, "BTCUSDT", "event", now_ms, 100.0, 100.0)
        net_by_policy = {
            "A_PARTIAL_20": 1.0,
            "B_FULL_PROTECTED": 2.0,
            "C_WEAKENING": -0.5,
        }
        for policy in storage.policies_for_slot(slot_id):
            storage.update_policy(
                policy["id"],
                state="CLOSED",
                closed_at_ms=now_ms,
                net_return_percent=net_by_policy[policy["policy"]],
            )
        result = storage.daily_pnl(now_ms)
        self.assertEqual("C_WEAKENING", result["primary_policy"])
        self.assertEqual(0.2, result["policies"]["A_PARTIAL_20"]["net_pnl_usdt"])
        self.assertEqual(0.4, result["policies"]["B_FULL_PROTECTED"]["net_pnl_usdt"])
        self.assertEqual(-0.1, result["policies"]["C_WEAKENING"]["net_pnl_usdt"])

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
