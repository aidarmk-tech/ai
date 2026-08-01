from __future__ import annotations

import asyncio
import sqlite3
import tempfile
import time
import unittest
from dataclasses import replace
from pathlib import Path

from pumpradar_server.config import Settings
from pumpradar_server.futures import FuturesMarketState
from pumpradar_server.market import DepthRow, SymbolInfo
from pumpradar_server.models import (
    BookMetrics,
    Candidate,
    Decision,
    EvaluatedCandidate,
    FlowMetrics,
    PeakFeatures,
    RiskAssessment,
)
from pumpradar_server.regime import (
    DUMP_CHANNEL,
    ONSET_CHANNEL,
    SHORT_CHANNEL,
    RegimePaperManager,
)
from pumpradar_server.storage import Storage


def make_item(symbol: str, price: float = 100.0, *, depth_age_ms: int | None = 0) -> EvaluatedCandidate:
    candidate = Candidate(
        symbol=symbol,
        price=price,
        quote_volume_24h=10_000_000,
        return_15s=0.0,
        return_60s=0.0,
        return_5m=0.0,
        acceleration=0.0,
        relative_strength_vs_btc=0.0,
        pre_score=0.0,
        return_3m=0.0,
        return_10m=0.0,
    )
    flow = FlowMetrics(
        ready=True,
        quote_volume_30s=200_000,
        trade_count_30s=100,
        trades_per_second=3.3,
        taker_buy_ratio_30s=0.5,
        taker_buy_ratio_15s=0.5,
        taker_buy_ratio_5s=0.5,
        cvd_30s=0.0,
        cvd_15s=0.0,
        cvd_5s=0.0,
        cvd_slope=0.0,
        volume_z_30s=0.0,
        trade_gap=False,
    )
    book = BookMetrics(
        spread_bps=2.0,
        obi_10=0.0,
        obi_20=0.0,
        buy_slippage_percent=0.01,
        sell_slippage_percent=0.01,
        buy_vwap=100.01,
        sell_vwap_for_position=99.99,
        best_bid=99.99,
        best_ask=100.01,
        depth_age_ms=depth_age_ms,
    )
    risk = RiskAssessment(0, 0, 0, 0, 0, 0, False)
    decision = Decision("NO_TRADE", False, False, False, [], [], risk, "A")
    return EvaluatedCandidate(candidate, flow, book, PeakFeatures(), decision)


class RegimeV451Tests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.settings = replace(
            Settings(),
            data_dir=root,
            db_path=root / "pumpradar.sqlite3",
            regime_slots_per_channel=4,
            regime_repeat_symbol_minutes=0,
            max_feed_age_ms=10_000,
            regime_max_spread_bps=30.0,
            regime_max_slippage_percent=0.35,
        )
        self.storage = Storage(self.settings)
        self.storage.start_run("unit-test")
        self.state = FuturesMarketState()
        self.messages: list[str] = []

        async def notify(message: str) -> None:
            self.messages.append(message)

        self.manager = RegimePaperManager(self.settings, self.state, self.storage, notify)

    def tearDown(self) -> None:
        self.storage.conn.close()
        self.temp.cleanup()

    def add_symbol(self, symbol: str, now_ms: int, bid: float = 99.99, ask: float = 100.01) -> None:
        self.state.universe[symbol] = SymbolInfo(symbol, "USDT", "TRADING", False)
        self.state.depth[symbol] = DepthRow(
            1,
            [(bid, 1000.0), (bid - 0.10, 1000.0)],
            [(ask, 1000.0), (ask + 0.10, 1000.0)],
            now_ms,
        )

    def test_none_depth_age_is_safe(self) -> None:
        self.assertFalse(self.manager._safe_gate(make_item("AAAUSDT", depth_age_ms=None)))

    def test_short_without_futures_symbol_still_has_completed_outcome(self) -> None:
        now = int(time.time() * 1000)
        self.manager.signal_short_from_spot(
            make_item("MISSINGUSDT"),
            "missing-episode",
            now,
        )
        signal = self.storage.conn.execute(
            "SELECT disposition,reason FROM regime_signals WHERE channel=?",
            (SHORT_CHANNEL,),
        ).fetchone()
        self.assertEqual(("SKIPPED", "FUTURES_SYMBOL_UNAVAILABLE"), tuple(signal))
        self.assertEqual(
            1,
            self.storage.conn.execute(
                "SELECT COUNT(*) FROM regime_snapshots"
            ).fetchone()[0],
        )
        outcome = self.storage.conn.execute(
            "SELECT entry_status,completed,completion_reason FROM regime_snapshot_outcomes"
        ).fetchone()
        self.assertEqual(
            ("NO_EXECUTABLE_ENTRY", 1, "NO_EXECUTABLE_ENTRY"),
            tuple(outcome),
        )

    def test_superseded_short_preserves_snapshot_coverage(self) -> None:
        now = int(time.time() * 1000)
        self.state.universe["AAAUSDT"] = SymbolInfo("AAAUSDT", "USDT", "TRADING", False)
        self.manager.signal_short_from_spot(
            make_item("AAAUSDT"),
            "spot-episode-1",
            now,
        )
        self.manager.signal_short_from_spot(
            make_item("AAAUSDT"),
            "spot-episode-2",
            now + 1,
        )
        old = self.storage.conn.execute(
            "SELECT disposition,reason FROM regime_signals WHERE episode_id='spot-episode-1'"
        ).fetchone()
        self.assertEqual(("EXPIRED", "SUPERSEDED_BY_NEW_SIGNAL"), tuple(old))
        self.assertEqual(
            1,
            self.storage.conn.execute(
                "SELECT COUNT(*) FROM regime_snapshots"
            ).fetchone()[0],
        )
        self.assertEqual(
            1,
            self.storage.conn.execute(
                "SELECT COUNT(*) FROM regime_snapshot_outcomes WHERE completed=1"
            ).fetchone()[0],
        )

    def test_short2x_creates_slot_snapshot_and_outcome(self) -> None:
        now = int(time.time() * 1000)
        self.add_symbol("AAAUSDT", now)
        spot = make_item("AAAUSDT")
        self.manager.signal_short_from_spot(spot, "spot-episode-1", now)
        self.assertEqual(1, len(self.manager.pending_shorts))
        asyncio.run(self.manager.observe_futures(make_item("AAAUSDT"), now + 1, "fut-1"))
        self.assertEqual(0, len(self.manager.pending_shorts))
        signal = self.storage.conn.execute(
            "SELECT disposition FROM regime_signals WHERE channel=?",
            (SHORT_CHANNEL,),
        ).fetchone()
        self.assertEqual("OPENED", signal["disposition"])
        self.assertEqual(
            1,
            self.storage.conn.execute(
                "SELECT COUNT(*) FROM regime_slots WHERE channel=? AND status='OPEN'",
                (SHORT_CHANNEL,),
            ).fetchone()[0],
        )
        self.assertEqual(1, self.storage.conn.execute("SELECT COUNT(*) FROM regime_snapshots").fetchone()[0])
        outcome = self.storage.conn.execute("SELECT * FROM regime_snapshot_outcomes").fetchone()
        self.assertEqual("SHORT", outcome["side"])
        self.assertEqual(2.0, outcome["leverage"])
        self.assertEqual(0, outcome["completed"])

    def test_four_slots_but_fifth_signal_still_has_outcome(self) -> None:
        now = int(time.time() * 1000)
        for index in range(5):
            symbol = f"T{index}USDT"
            self.add_symbol(symbol, now + index)
            item = make_item(symbol)
            signal_id = self.manager._insert_signal(
                ONSET_CHANNEL,
                "LONG",
                "FUTURES",
                symbol,
                f"episode-{index}",
                now + index,
                {"test": index},
            )
            _, execution = self.manager._record_regime_snapshot(
                signal_id,
                ONSET_CHANNEL,
                "LONG",
                item,
                now + index,
                1.0,
                1.5,
                300,
                allow_no_entry=True,
            )
            self.manager._open_slot(
                signal_id,
                ONSET_CHANNEL,
                "LONG",
                item,
                now + index,
                1.0,
                1.5,
                300,
                execution,
            )
        self.assertEqual(
            4,
            self.storage.conn.execute(
                "SELECT COUNT(*) FROM regime_slots WHERE channel=? AND status='OPEN'",
                (ONSET_CHANNEL,),
            ).fetchone()[0],
        )
        self.assertEqual(5, self.storage.conn.execute("SELECT COUNT(*) FROM regime_snapshots").fetchone()[0])
        self.assertEqual(5, self.storage.conn.execute("SELECT COUNT(*) FROM regime_snapshot_outcomes").fetchone()[0])
        fifth = self.storage.conn.execute(
            "SELECT disposition,reason FROM regime_signals WHERE episode_id='episode-4'"
        ).fetchone()
        self.assertEqual(("SKIPPED", "CHANNEL_SLOT_BUSY"), tuple(fifth))

    def test_side_aware_outcome_is_recorded(self) -> None:
        now = int(time.time() * 1000)
        self.add_symbol("AAAUSDT", now, 99.99, 100.01)
        item = make_item("AAAUSDT")
        signal_id = self.manager._insert_signal(
            SHORT_CHANNEL, "SHORT", "SPOT", "AAAUSDT", "e", now, {"x": 1}
        )
        self.manager._record_regime_snapshot(
            signal_id,
            SHORT_CHANNEL,
            "SHORT",
            item,
            now,
            2.0,
            2.0,
            600,
            allow_no_entry=True,
        )
        self.state.depth["AAAUSDT"] = DepthRow(
            2,
            [(98.99, 1000.0)],
            [(99.01, 1000.0)],
            now + 5_100,
        )
        self.manager._update_regime_outcomes(now + 5_100)
        row = self.storage.conn.execute(
            "SELECT return_5s,mfe_percent,mae_percent FROM regime_snapshot_outcomes"
        ).fetchone()
        self.assertIsNotNone(row["return_5s"])
        self.assertGreater(row["return_5s"], 0.9)
        self.assertGreater(row["mfe_percent"], 0.9)
        self.assertEqual(0.0, row["mae_percent"])

    def test_stop_slippage_components_are_persisted(self) -> None:
        now = int(time.time() * 1000)
        self.add_symbol("STOPUSDT", now, 99.99, 100.01)
        item = make_item("STOPUSDT")
        signal_id = self.manager._insert_signal(
            ONSET_CHANNEL, "LONG", "FUTURES", "STOPUSDT", "stop-e", now, {"x": 1}
        )
        _, execution = self.manager._record_regime_snapshot(
            signal_id,
            ONSET_CHANNEL,
            "LONG",
            item,
            now,
            1.0,
            1.5,
            300,
            allow_no_entry=True,
        )
        result = self.manager._open_slot(
            signal_id,
            ONSET_CHANNEL,
            "LONG",
            item,
            now,
            1.0,
            1.5,
            300,
            execution,
        )
        self.assertEqual("OPENED", result.status)
        # Top bid crosses the 1.5% stop; deeper bid causes additional VWAP impact.
        self.state.depth["STOPUSDT"] = DepthRow(
            3,
            [(98.40, 0.05), (98.00, 1000.0)],
            [(98.42, 1000.0)],
            now + 500,
        )
        asyncio.run(self.manager.tick(now + 500))
        row = self.storage.conn.execute(
            """SELECT status,exit_reason,stop_market_overshoot_points,
                      stop_execution_impact_points,stop_total_slippage_points
               FROM regime_slots WHERE id=?""",
            (result.slot_id,),
        ).fetchone()
        self.assertEqual("CLOSED", row["status"])
        self.assertEqual("PRICE_STOP", row["exit_reason"])
        self.assertGreater(row["stop_market_overshoot_points"], 0.0)
        self.assertGreater(row["stop_execution_impact_points"], 0.0)
        self.assertAlmostEqual(
            row["stop_total_slippage_points"],
            row["stop_market_overshoot_points"] + row["stop_execution_impact_points"],
            places=8,
        )

    def test_status_reports_snapshot_coverage(self) -> None:
        status = self.manager.status()
        self.assertEqual(4, status["regime_slots_per_channel"])
        self.assertIn("regime_snapshot_coverage", status)
        self.assertIn("median_total_points", status["regime_stop_slippage"])

    def test_v452_onset_is_shadow_only_and_long_horizons_exist(self) -> None:
        now = int(time.time() * 1000)
        self.add_symbol("ONSETUSDT", now)
        base = make_item("ONSETUSDT")
        candidate = replace(
            base.candidate,
            return_15s=0.60,
            return_60s=1.00,
            return_5m=1.50,
            acceleration=0.40,
            relative_strength_vs_btc=0.40,
        )
        flow = replace(
            base.flow,
            volume_z_30s=3.0,
            taker_buy_ratio_5s=0.80,
        )
        item = replace(base, candidate=candidate, flow=flow)
        for offset in range(self.settings.onset_confirm_ticks):
            asyncio.run(
                self.manager.observe_futures(
                    item,
                    now + offset * 500,
                    "onset-episode",
                )
            )
        signal = self.storage.conn.execute(
            """SELECT disposition,reason FROM regime_signals
               WHERE channel=?""",
            (ONSET_CHANNEL,),
        ).fetchone()
        self.assertEqual(
            ("SHADOW_ONLY", "REAL_ENTRY_DISABLED_V452"),
            tuple(signal),
        )
        self.assertEqual(
            0,
            self.storage.conn.execute(
                "SELECT COUNT(*) FROM regime_slots WHERE channel=?",
                (ONSET_CHANNEL,),
            ).fetchone()[0],
        )
        outcome_columns = {
            row[1]
            for row in self.storage.conn.execute(
                "PRAGMA table_info(regime_snapshot_outcomes)"
            )
        }
        for horizon in (900, 1800, 3600):
            self.assertIn(f"return_{horizon}s", outcome_columns)
            self.assertIn(f"mfe_{horizon}s", outcome_columns)
            self.assertIn(f"mae_{horizon}s", outcome_columns)

    def test_v452_preserves_open_v451_regime_slots_during_restart(self) -> None:
        now = int(time.time() * 1000)
        self.add_symbol("KEEPUSDT", now)
        item = make_item("KEEPUSDT")
        signal_id = self.manager._insert_signal(
            DUMP_CHANNEL,
            "LONG",
            "FUTURES",
            "KEEPUSDT",
            "keep-v451",
            now,
            {"source": "v451"},
        )
        _, execution = self.manager._record_regime_snapshot(
            signal_id,
            DUMP_CHANNEL,
            "LONG",
            item,
            now,
            1.0,
            1.5,
            600,
            allow_no_entry=True,
        )
        result = self.manager._open_slot(
            signal_id,
            DUMP_CHANNEL,
            "LONG",
            item,
            now,
            1.0,
            1.5,
            600,
            execution,
        )
        self.assertEqual("OPENED", result.status)
        for table in ("regime_signals", "regime_slots", "regime_snapshots"):
            self.storage.conn.execute(
                f"""UPDATE {table}
                    SET algorithm_version='4.5.1-server',
                        strategy_version='v451',
                        config_hash='v451'"""
            )
        self.storage.conn.commit()
        self.manager.recover_incompatible(now + 1000)
        slot = self.storage.conn.execute(
            "SELECT status,exit_reason FROM regime_slots WHERE id=?",
            (result.slot_id,),
        ).fetchone()
        self.assertEqual(("OPEN", None), tuple(slot))
        outcome = self.storage.conn.execute(
            "SELECT completed,completion_reason FROM regime_snapshot_outcomes"
        ).fetchone()
        self.assertEqual((0, None), tuple(outcome))


if __name__ == "__main__":
    unittest.main()
