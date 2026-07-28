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
from unittest.mock import patch


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

        with patch("pumpradar_server.strategy.impulse_score", return_value=64):
            accepted = decide(flow)
        self.assertTrue(accepted.strict_passed)
        self.assertEqual("V436_STRICT_TRADE3", accepted.reasons[0])

        with patch("pumpradar_server.strategy.impulse_score", return_value=63):
            boundary = decide(flow)
        self.assertFalse(boundary.strict_passed)
        self.assertTrue(boundary.shadow_passed)
        self.assertIn("IMPULSE_LE_63", boundary.blockers)
        self.assertIn("V435_COMPAT_SHADOW", boundary.reasons)

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

    def test_v439_adds_quality_and_exit_challengers(self) -> None:
        self.assertEqual("4.4.0-server", self.settings.algorithm_version)
        self.assertEqual("C_WEAKENING", self.settings.primary_policy)
        self.assertEqual(60, self.settings.warm_pool_size)
        self.assertEqual(20, self.settings.deep_candidates)
        self.assertEqual(25, self.settings.depth_candidates)
        self.assertEqual(0.875, self.settings.min_taker_buy_ratio_30s)
        self.assertEqual(0.90, self.settings.min_trade3_taker_buy_ratio_15s)
        self.assertEqual(0.75, self.settings.min_trade3_taker_buy_ratio_5s)
        self.assertEqual(63, self.settings.min_trade3_impulse_score)
        self.assertEqual(0, self.settings.max_trade3_exhaustion_risk)
        self.assertEqual(0, self.settings.max_trade3_artificial_risk)
        self.assertEqual(3.0, self.settings.max_return_5m)
        self.assertEqual(0.30, self.settings.protected_stop_percent)
        self.assertEqual(0.50, self.settings.protected_peak_fraction)
        self.assertEqual(
            0.05,
            self.settings.max_shadow_return_15s_excess_over_60s,
        )
        self.assertEqual(3, self.settings.min_shadow_strict_streak)
        self.assertEqual(3.0, self.settings.momentum_mc3_return_3m)
        self.assertEqual(5.0, self.settings.momentum_mc5_return_5m)
        self.assertEqual(7.0, self.settings.momentum_mc7_return_10m)
        self.assertEqual(2.0, self.settings.momentum_stop_percent)
        self.assertEqual(1.5, self.settings.momentum_trail_activation_percent)
        self.assertEqual(1.0, self.settings.momentum_trail_drawdown_percent)
        self.assertEqual(1_200, self.settings.momentum_horizon_seconds)
        self.assertEqual(120, self.settings.momentum_hold_seconds)
        self.assertEqual("MC_HOLD_120", self.settings.momentum_primary_policy)
        self.assertEqual(300, self.settings.trade3_target1_hold_seconds)
        self.assertEqual(500, self.settings.stop_watch_interval_ms)

    def test_episode_tracker_measures_persistence_and_three_tick_streak(self) -> None:
        from pumpradar_server.episodes import EpisodeTracker
        from pumpradar_server.models import (
            BookMetrics,
            Candidate,
            EvaluatedCandidate,
            FlowMetrics,
            PeakFeatures,
        )
        from pumpradar_server.strategy import assess

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
            buy_vwap=100.1,
            best_ask=100.0,
            depth_age_ms=0,
            depth_update_id=1,
        )

        def item(r15: float, r60: float) -> EvaluatedCandidate:
            candidate = Candidate(
                "BTCUSDT",
                100.0,
                10_000_000.0,
                r15,
                r60,
                2.0,
                0.5,
                1.0,
                1.0,
            )
            with patch(
                "pumpradar_server.strategy.impulse_score",
                return_value=64,
            ):
                decision = assess(
                    candidate,
                    flow,
                    book,
                    PeakFeatures(),
                    self.settings,
                    100,
                    0.0,
                    0.5,
                    False,
                )
            self.assertTrue(decision.strict_passed)
            return EvaluatedCandidate(
                candidate,
                flow,
                book,
                PeakFeatures(),
                decision,
            )

        tracker = EpisodeTracker(self.settings)
        first = tracker.observe(item(1.0, 1.0), 1_000)
        second = tracker.observe(item(1.0, 1.0), 2_000)
        third = tracker.observe(item(1.0, 1.0), 3_000)
        self.assertEqual(first.episode_id, third.episode_id)
        self.assertEqual((1, 2, 3), (
            first.strict_streak,
            second.strict_streak,
            third.strict_streak,
        ))
        self.assertFalse(first.experimental_shadow_passed)
        self.assertTrue(third.experimental_shadow_passed)

        fading = tracker.observe(item(1.50, 1.00), 4_000)
        self.assertFalse(fading.momentum_persistent)
        self.assertFalse(fading.experimental_shadow_passed)
        self.assertIn(
            "MOMENTUM_NOT_PERSISTENT",
            fading.experimental_blockers,
        )

        restarted = tracker.observe(item(1.0, 1.0), 1_204_001)
        self.assertNotEqual(first.episode_id, restarted.episode_id)
        self.assertEqual(1, restarted.strict_streak)

    def test_snapshot_audit_links_episode_and_tracks_counterfactual_outcome(self) -> None:
        from pumpradar_server.episodes import EpisodeTracker
        from pumpradar_server.models import (
            BookMetrics,
            Candidate,
            EvaluatedCandidate,
            FlowMetrics,
            PeakFeatures,
        )
        from pumpradar_server.storage import Storage
        from pumpradar_server.strategy import assess

        candidate = Candidate(
            "BTCUSDT",
            100.0,
            10_000_000.0,
            1.0,
            1.0,
            2.0,
            0.5,
            1.0,
            1.0,
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
            buy_vwap=100.1,
            best_ask=100.0,
            depth_age_ms=0,
            depth_update_id=1,
        )
        peak = PeakFeatures(
            local_high_price=101.0,
            peak_at_ms=900,
            distance_from_local_high_pct=0.2,
            seconds_since_local_high=1,
            pullback_from_high_pct=0.3,
            failed_high_attempts=1,
            breakout_level_held=True,
        )
        with patch(
            "pumpradar_server.strategy.impulse_score",
            return_value=64,
        ):
            decision = assess(
                candidate,
                flow,
                book,
                PeakFeatures(),
                self.settings,
                100,
                0.0,
                0.5,
                False,
            )
        item = EvaluatedCandidate(candidate, flow, book, peak, decision)
        telemetry = EpisodeTracker(self.settings).observe(item, 1_000)

        storage = Storage(self.settings)
        storage.start_run("episode-audit")
        snapshot_id = storage.insert_snapshot(
            item,
            "TRIGGERED",
            telemetry.episode_id,
            1_000,
            telemetry,
        )
        slot_id = storage.create_slot(
            snapshot_id,
            candidate.symbol,
            telemetry.episode_id,
            1_000,
            100.0,
            100.1,
            telemetry.episode_id,
        )
        snapshot = storage.conn.execute(
            """SELECT event_id,episode_id,peak_at_ms,local_high_price,
                      distance_from_local_high_pct
               FROM snapshots WHERE id=?""",
            (snapshot_id,),
        ).fetchone()
        slot = storage.conn.execute(
            "SELECT event_id,episode_id FROM paper_slots WHERE id=?",
            (slot_id,),
        ).fetchone()
        self.assertEqual(telemetry.episode_id, snapshot["event_id"])
        self.assertEqual(telemetry.episode_id, snapshot["episode_id"])
        self.assertEqual(tuple(snapshot[:2]), tuple(slot))
        self.assertEqual(900, snapshot["peak_at_ms"])
        self.assertEqual(101.0, snapshot["local_high_price"])
        self.assertEqual(0.2, snapshot["distance_from_local_high_pct"])
        self.assertEqual({"BTCUSDT"}, storage.pending_snapshot_symbols())

        storage.record_snapshot_observation(snapshot_id, 0.50, 6_000)
        storage.record_snapshot_observation(snapshot_id, -0.80, 16_000)
        storage.record_snapshot_observation(snapshot_id, 1.20, 301_000)
        storage.record_snapshot_observation(snapshot_id, 1.10, 1_201_000)
        outcome = storage.conn.execute(
            """SELECT fee_rate,return_5s,mfe_5s,mae_5s,return_15s,
                      first_barrier,return_300s,mfe_300s,mae_300s,
                      return_1200s,mfe_1200s,mae_1200s,
                      completed,completion_reason
               FROM snapshot_outcomes WHERE snapshot_id=?""",
            (snapshot_id,),
        ).fetchone()
        self.assertEqual(self.settings.fee_rate, outcome["fee_rate"])
        self.assertEqual(0.50, outcome["return_5s"])
        self.assertEqual(0.50, outcome["mfe_5s"])
        self.assertEqual(0.0, outcome["mae_5s"])
        self.assertEqual(-0.80, outcome["return_15s"])
        self.assertEqual("STOP_0_75", outcome["first_barrier"])
        self.assertEqual(1.20, outcome["return_300s"])
        self.assertEqual(1.20, outcome["mfe_300s"])
        self.assertEqual(-0.80, outcome["mae_300s"])
        self.assertEqual(1.10, outcome["return_1200s"])
        self.assertEqual(1.20, outcome["mfe_1200s"])
        self.assertEqual(-0.80, outcome["mae_1200s"])
        self.assertEqual(1, outcome["completed"])
        self.assertEqual("HORIZON_1200S", outcome["completion_reason"])
        self.assertEqual(set(), storage.pending_snapshot_symbols())

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
            "D_TARGET1_HOLD_300": 3.0,
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
        self.assertEqual(5, manifest["schema_version"])
        self.assertEqual(1, manifest["row_counts"]["experiment_runs"])
        self.assertEqual(0, manifest["row_counts"]["snapshot_outcomes"])

        restored = self.root / "restored.sqlite3"
        with gzip.open(output / "pumpradar.sqlite3.gz", "rb") as source:
            restored.write_bytes(source.read())
        connection = sqlite3.connect(restored)
        self.assertEqual("ok", connection.execute("PRAGMA integrity_check").fetchone()[0])
        self.assertEqual([], list(connection.execute("PRAGMA foreign_key_check")))
        for name, metadata in manifest["files"].items():
            self.assertTrue((output / name).is_file())
            self.assertEqual(64, len(metadata["sha256"]))


    def test_momentum_arms_accept_confirmed_continuation(self) -> None:
        from pumpradar_server.models import BookMetrics, Candidate, FlowMetrics
        from pumpradar_server.strategy import momentum_continuation_arms

        candidate = Candidate(
            symbol="MCUSDT",
            price=105.0,
            quote_volume_24h=50_000_000.0,
            return_15s=0.10,
            return_60s=0.30,
            return_5m=5.20,
            acceleration=-0.05,
            relative_strength_vs_btc=4.80,
            pre_score=90.0,
            price_age_ms=10,
            return_3m=3.20,
            return_10m=7.20,
        )
        flow = FlowMetrics(
            trade_gap=False,
            trade_age_ms=10,
            book_ticker_age_ms=10,
        )
        book = BookMetrics(
            spread_bps=12.0,
            buy_slippage_percent=0.05,
            sell_slippage_percent=0.05,
            buy_vwap=105.01,
            sell_vwap_for_position=104.99,
            best_bid=104.99,
            best_ask=105.00,
            depth_age_ms=10,
            depth_update_id=1,
        )
        arms, blockers = momentum_continuation_arms(
            candidate, flow, book, self.settings, 10
        )
        self.assertEqual((), blockers)
        self.assertEqual({"MC3": True, "MC5": True, "MC7": True}, arms)

    def test_momentum_challenger_trails_and_keeps_fixed_control(self) -> None:
        from pumpradar_server.models import (
            BookMetrics,
            Candidate,
            Decision,
            EvaluatedCandidate,
            FlowMetrics,
            PeakFeatures,
            RiskAssessment,
        )
        from pumpradar_server.paper import MomentumPaperManager
        from pumpradar_server.storage import Storage

        class FixedMarket:
            sell_price = 100.0

            def executable_sell_price(self, *args, **kwargs):
                return self.sell_price

        async def notify(_message):
            return None

        storage = Storage(self.settings)
        storage.start_run("mc5")
        market = FixedMarket()
        manager = MomentumPaperManager(
            self.settings, market, storage, notify
        )
        candidate = Candidate(
            symbol="MCUSDT",
            price=105.0,
            quote_volume_24h=50_000_000.0,
            return_15s=0.2,
            return_60s=0.5,
            return_5m=5.2,
            acceleration=0.0,
            relative_strength_vs_btc=4.8,
            pre_score=90.0,
            return_3m=3.1,
            return_10m=7.1,
        )
        flow = FlowMetrics()
        book = BookMetrics(best_ask=100.0, buy_vwap=100.0)
        risk = RiskAssessment(80, 50, 50, 90, 0, 0, False, [])
        decision = Decision(
            "STRONG_BUT_LATE", False, False, False, [], [], risk, "B"
        )
        item = EvaluatedCandidate(
            candidate, flow, book, PeakFeatures(), decision
        )
        asyncio.run(manager.consider(item, None, 1_000_000, "episode-mc5"))
        slot = storage.momentum_primary_open_slot()
        self.assertIsNotNone(slot)
        self.assertEqual(3, len(storage.momentum_policies_for_slot(slot["id"])))

        market.sell_price = 101.6
        asyncio.run(manager.tick(1_001_000))
        trail = next(
            row for row in storage.momentum_policies_for_slot(slot["id"])
            if row["policy"] == "MC_TRAIL_1P0"
        )
        self.assertIsNotNone(trail["activated_at_ms"])

        market.sell_price = 102.5
        asyncio.run(manager.tick(1_002_000))
        market.sell_price = 101.4
        asyncio.run(manager.tick(1_003_000))
        trail = next(
            row for row in storage.momentum_policies_for_slot(slot["id"])
            if row["policy"] == "MC_TRAIL_1P0"
        )
        fixed = next(
            row for row in storage.momentum_policies_for_slot(slot["id"])
            if row["policy"] == "MC_FIXED_TP4"
        )
        self.assertEqual(("CLOSED", "MC_TRAIL_EXIT_1"), (
            trail["state"], trail["exit_reason"]
        ))
        self.assertEqual("OPEN", fixed["state"])
        self.assertIsNotNone(storage.momentum_primary_open_slot())

        market.sell_price = 104.1
        asyncio.run(manager.tick(1_004_000))
        fixed = next(
            row for row in storage.momentum_policies_for_slot(slot["id"])
            if row["policy"] == "MC_FIXED_TP4"
        )
        self.assertEqual(("CLOSED", "MC_TARGET_4"), (
            fixed["state"], fixed["exit_reason"]
        ))
        self.assertGreater(float(fixed["net_return_percent"]), 0.0)


    def test_trade3_quality_gate_defers_thin_stale_and_vetoes_bad_book(self) -> None:
        from pumpradar_server.models import BookMetrics, FlowMetrics
        from pumpradar_server.strategy import trade3_entry_quality

        flow = FlowMetrics(
            quote_volume_30s=8_000.0,
            cvd_30s=5_000.0,
            cvd_slope=4_000.0,
            trade_age_ms=900,
            book_ticker_age_ms=250,
        )
        book = BookMetrics(obi_10=-0.05, spread_bps=16.0)
        self.assertEqual(
            (True, True),
            trade3_entry_quality(flow, book, self.settings),
        )

        fresh = FlowMetrics(
            quote_volume_30s=25_000.0,
            cvd_30s=20_000.0,
            cvd_slope=15_000.0,
            trade_age_ms=100,
            book_ticker_age_ms=30,
        )
        self.assertEqual(
            (False, False),
            trade3_entry_quality(fresh, BookMetrics(obi_10=0.03, spread_bps=10.0), self.settings),
        )

    def test_target1_hold_policy_ignores_target3_and_exits_at_300s(self) -> None:
        from pumpradar_server.paper import PaperManager
        from pumpradar_server.storage import Storage

        class FixedMarket:
            sell_price = 103.5

            def executable_sell_price(self, *args, **kwargs):
                return self.sell_price

        async def notify(_message):
            return None

        storage = Storage(self.settings)
        storage.start_run("target1-hold")
        opened_at = 1_000_000
        slot_id = storage.create_slot(None, "BTCUSDT", "event", opened_at, 100.0, 100.0)
        policy = next(
            row for row in storage.policies_for_slot(slot_id)
            if row["policy"] == "D_TARGET1_HOLD_300"
        )
        manager = PaperManager(self.settings, FixedMarket(), storage, notify)
        slot = storage.conn.execute("SELECT * FROM paper_slots WHERE id=?", (slot_id,)).fetchone()
        asyncio.run(manager._update_policy(slot, policy, 103.5, 3.5, None, opened_at + 10_000))
        policy = storage.conn.execute("SELECT * FROM policy_runs WHERE id=?", (policy["id"],)).fetchone()
        self.assertEqual("OPEN", policy["state"])
        self.assertIsNotNone(policy["activated_at_ms"])

        asyncio.run(manager._update_policy(slot, policy, 102.0, 2.0, None, opened_at + 300_000))
        policy = storage.conn.execute("SELECT * FROM policy_runs WHERE id=?", (policy["id"],)).fetchone()
        self.assertEqual(("CLOSED", "TARGET1_HOLD_300"), (policy["state"], policy["exit_reason"]))

    def test_momentum_hold_120_is_primary_and_closes_at_120s(self) -> None:
        from pumpradar_server.models import BookMetrics, Candidate, Decision, EvaluatedCandidate, FlowMetrics, PeakFeatures, RiskAssessment
        from pumpradar_server.paper import MomentumPaperManager
        from pumpradar_server.storage import Storage

        class FixedMarket:
            sell_price = 101.0

            def executable_sell_price(self, *args, **kwargs):
                return self.sell_price

        async def notify(_message):
            return None

        storage = Storage(self.settings)
        storage.start_run("mc-hold")
        manager = MomentumPaperManager(self.settings, FixedMarket(), storage, notify)
        item = EvaluatedCandidate(
            Candidate(
                symbol="MCUSDT",
                price=105.0,
                quote_volume_24h=50_000_000.0,
                return_15s=0.2,
                return_60s=0.5,
                return_5m=5.2,
                acceleration=0.0,
                relative_strength_vs_btc=4.8,
                pre_score=90.0,
            ),
            FlowMetrics(),
            BookMetrics(best_ask=100.0, buy_vwap=100.0),
            PeakFeatures(),
            Decision("STRONG_BUT_LATE", False, False, False, [], [], RiskAssessment(80, 50, 50, 90, 0, 0, False, []), "B"),
        )
        asyncio.run(manager.consider(item, None, 1_000_000, "episode"))
        asyncio.run(manager.tick(1_119_000))
        self.assertIsNotNone(storage.momentum_primary_open_slot())
        asyncio.run(manager.tick(1_120_000))
        self.assertIsNone(storage.momentum_primary_open_slot())
        hold = next(row for row in storage.momentum_policies_for_slot(
            storage.conn.execute("SELECT id FROM momentum_slots LIMIT 1").fetchone()[0]
        ) if row["policy"] == "MC_HOLD_120")
        self.assertEqual(("CLOSED", "MC_HOLD_120_EXIT"), (hold["state"], hold["exit_reason"]))

    def test_forward_outcomes_capture_new_horizons(self) -> None:
        from pumpradar_server.models import BookMetrics, Candidate, Decision, EvaluatedCandidate, FlowMetrics, PeakFeatures, RiskAssessment
        from pumpradar_server.storage import Storage

        storage = Storage(self.settings)
        storage.start_run("outcome-horizons")
        item = EvaluatedCandidate(
            Candidate(
                symbol="BTCUSDT",
                price=100.0,
                quote_volume_24h=50_000_000.0,
                return_15s=0.8,
                return_60s=1.0,
                return_5m=2.0,
                acceleration=0.1,
                relative_strength_vs_btc=1.0,
                pre_score=80.0,
            ),
            FlowMetrics(),
            BookMetrics(buy_vwap=100.0),
            PeakFeatures(),
            Decision("LONG_CONTINUATION", True, False, True, [], [], RiskAssessment(80, 10, 0, 90, 0, 0, False, []), "A"),
        )
        snapshot_id = storage.insert_snapshot(item, "TRIGGERED", "episode", 1_000_000, None)
        for seconds, value in ((90, 0.9), (150, 1.5), (180, 1.8), (240, 2.4)):
            storage.record_snapshot_observation(snapshot_id, value, 1_000_000 + seconds * 1000)
        row = storage.conn.execute(
            "SELECT return_90s,return_150s,return_180s,return_240s FROM snapshot_outcomes WHERE snapshot_id=?",
            (snapshot_id,),
        ).fetchone()
        self.assertEqual((0.9, 1.5, 1.8, 2.4), tuple(row))


if __name__ == "__main__":
    unittest.main()
