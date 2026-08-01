from __future__ import annotations

import asyncio
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
from pumpradar_server.regime import SHORT_CHANNEL, RegimePaperManager
from pumpradar_server.storage import Storage
from pumpradar_server.v453_research import ShortResearchManager


def item(
    symbol: str = "AAAUSDT",
    *,
    r15: float = 0.2,
    r60: float = 1.0,
    r5m: float = 5.0,
    acceleration: float = -0.1,
    relative: float = 0.5,
    tbr30: float = 0.52,
    tbr15: float = 0.48,
    tbr5: float = 0.42,
    cvd30: float = 5_000,
    cvd5: float = -1_000,
    cvd_slope: float = -2_000,
    volume_z: float = 2.0,
    obi10: float = -0.1,
) -> EvaluatedCandidate:
    candidate = Candidate(
        symbol=symbol,
        price=100.0,
        quote_volume_24h=10_000_000,
        return_15s=r15,
        return_60s=r60,
        return_5m=r5m,
        acceleration=acceleration,
        relative_strength_vs_btc=relative,
        pre_score=0.0,
        return_3m=3.0,
        return_10m=7.0,
    )
    flow = FlowMetrics(
        ready=True,
        quote_volume_30s=200_000,
        trade_count_30s=100,
        trades_per_second=3.3,
        taker_buy_ratio_30s=tbr30,
        taker_buy_ratio_15s=tbr15,
        taker_buy_ratio_5s=tbr5,
        cvd_30s=cvd30,
        cvd_15s=cvd30 / 2,
        cvd_5s=cvd5,
        cvd_slope=cvd_slope,
        volume_z_30s=volume_z,
        trade_gap=False,
    )
    book = BookMetrics(
        spread_bps=2.0,
        obi_10=obi10,
        obi_20=obi10,
        buy_slippage_percent=0.01,
        sell_slippage_percent=0.01,
        buy_vwap=100.01,
        sell_vwap_for_position=99.99,
        best_bid=99.99,
        best_ask=100.01,
        depth_age_ms=0,
    )
    risk = RiskAssessment(0, 0, 0, 0, 0, 0, False)
    decision = Decision("NO_TRADE", False, False, False, [], [], risk, "A")
    return EvaluatedCandidate(candidate, flow, book, PeakFeatures(), decision)


class V453ResearchTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.settings = replace(
            Settings(),
            data_dir=root,
            db_path=root / "db.sqlite3",
            btc_regime_path=root / "btc-regime.json",
            regime_repeat_symbol_minutes=0,
            regime_slots_per_channel=4,
            regime_max_slippage_percent=0.35,
            max_feed_age_ms=10_000,
        )
        self.storage = Storage(self.settings)
        self.storage.start_run("test")
        self.state = FuturesMarketState()
        self.messages: list[str] = []

        async def notify(message: str) -> None:
            self.messages.append(message)

        self.manager = RegimePaperManager(self.settings, self.state, self.storage, notify)

    def tearDown(self) -> None:
        self.storage.conn.close()
        self.temp.cleanup()

    def add_depth(self, symbol: str, now_ms: int) -> None:
        self.state.universe[symbol] = SymbolInfo(symbol, "USDT", "TRADING", False)
        self.state.depth[symbol] = DepthRow(
            1,
            [(99.99, 1000.0), (99.90, 1000.0)],
            [(100.01, 1000.0), (100.10, 1000.0)],
            now_ms,
        )

    def test_continuation_pressure_separates_active_squeeze(self) -> None:
        hot = item(
            r15=1.4,
            r60=3.4,
            r5m=8.0,
            acceleration=0.5,
            relative=3.0,
            tbr30=0.68,
            tbr15=0.69,
            tbr5=0.72,
            cvd30=150_000,
            cvd5=40_000,
            cvd_slope=50_000,
            volume_z=30.0,
            obi10=0.2,
        )
        calm = item()
        hot_cont, hot_rev, *_ = ShortResearchManager.score(hot)
        calm_cont, calm_rev, *_ = ShortResearchManager.score(calm)
        self.assertGreaterEqual(hot_cont, 60)
        self.assertLess(hot_rev, 55)
        self.assertLess(calm_cont, 60)
        self.assertGreaterEqual(calm_rev, 55)

    def test_short_champion_unchanged_but_challengers_recorded(self) -> None:
        now = int(time.time() * 1000)
        self.add_depth("AAAUSDT", now)
        self.manager.signal_short_from_spot(item(), "spot-e", now)
        asyncio.run(self.manager.observe_futures(item(), now + 1, "future-e"))
        slot = self.storage.conn.execute(
            "SELECT * FROM regime_slots WHERE channel=?", (SHORT_CHANNEL,)
        ).fetchone()
        self.assertEqual("OPEN", slot["status"])
        research = self.storage.conn.execute(
            "SELECT * FROM short_signal_research WHERE signal_id=?", (slot["signal_id"],)
        ).fetchone()
        self.assertIsNotNone(research)
        self.assertEqual("PASS", research["challenger_a"])
        self.assertEqual("PASS", research["challenger_b"])
        self.assertTrue(str(research["challenger_c"]).startswith("WAIT_BTC_REGIME"))
        self.assertEqual(
            2,
            self.storage.conn.execute(
                "SELECT COUNT(*) FROM short_exit_challengers WHERE slot_id=?", (slot["id"],)
            ).fetchone()[0],
        )

    def test_target1_protection_and_failure_label(self) -> None:
        now = int(time.time() * 1000)
        self.add_depth("AAAUSDT", now)
        self.manager.signal_short_from_spot(item(), "spot-e", now)
        asyncio.run(self.manager.observe_futures(item(), now + 1, "future-e"))
        slot = self.storage.conn.execute("SELECT * FROM regime_slots").fetchone()
        self.manager.short_research.tick_slot(slot, 1.20, now + 2_000)
        self.manager.short_research.tick_slot(slot, 0.20, now + 3_000)
        protected = self.storage.conn.execute(
            "SELECT * FROM short_exit_challengers WHERE slot_id=? AND variant='TARGET1_PROTECTED'",
            (slot["id"],),
        ).fetchone()
        self.assertEqual("CLOSED", protected["state"])
        self.assertEqual("PROTECTED_FLOOR", protected["exit_reason"])
        self.manager.short_research.close_slot(
            slot,
            -2.1,
            "PRICE_STOP",
            now + 4_000,
            -4.4,
            {"total_slippage": 0.1},
            1.2,
            -2.1,
        )
        label = self.storage.conn.execute(
            "SELECT primary_label,labels_json FROM short_failure_labels WHERE slot_id=?",
            (slot["id"],),
        ).fetchone()
        self.assertEqual("PROFIT_PROTECTION_FAILURE", label["primary_label"])
        self.assertIn("PROFIT_PROTECTION_FAILURE", label["labels_json"])


    def test_export_contains_v453_research_tables(self) -> None:
        output = self.storage.export_all()
        manifest = __import__("json").loads((output / "manifest.json").read_text())
        for table in (
            "short_signal_research",
            "short_exit_challengers",
            "short_failure_labels",
        ):
            self.assertIn(table, manifest["row_counts"])
            self.assertTrue((output / f"{table}.csv.gz").is_file())

    def test_no_real_order_paths_added(self) -> None:
        root = Path(__file__).resolve().parents[1] / "pumpradar_server"
        text = "\n".join(path.read_text() for path in root.glob("*.py"))
        for forbidden in ("/fapi/v1/order", "/api/v3/order", "API_SECRET", "create_order"):
            self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main()
