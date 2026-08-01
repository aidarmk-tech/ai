from __future__ import annotations

import asyncio
import tempfile
import unittest
from pathlib import Path

from pumpradar_server.config import Settings
from pumpradar_server.futures import (
    FuturesMarketState,
    FuturesPaperManager,
    FuturesPaperStore,
)
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


def make_item(channel: str = "MC7") -> EvaluatedCandidate:
    candidate = Candidate(
        symbol="RIFUSDT",
        price=0.101,
        quote_volume_24h=5_000_000,
        return_15s=0.5,
        return_60s=1.0,
        return_5m=5.2,
        acceleration=0.2,
        relative_strength_vs_btc=0.5,
        pre_score=100.0,
        return_3m=3.1,
        return_10m=7.2 if channel == "MC7" else 6.0,
    )
    flow = FlowMetrics(
        ready=True,
        quote_volume_30s=50_000,
        taker_buy_ratio_30s=0.8,
        taker_buy_ratio_15s=0.85,
        taker_buy_ratio_5s=0.9,
        cvd_30s=20_000,
        cvd_slope=10_000,
    )
    book = BookMetrics(
        spread_bps=5,
        buy_slippage_percent=0.01,
        sell_slippage_percent=0.01,
        buy_vwap=0.101,
        sell_vwap_for_position=0.1009,
        best_bid=0.1009,
        best_ask=0.101,
    )
    risk = RiskAssessment(80, 20, 0, 90, 0, 0, False, [])
    return EvaluatedCandidate(
        candidate,
        flow,
        book,
        PeakFeatures(),
        Decision("NO_TRADE", False, False, False, [], [], risk, "B"),
    )


class FixedMarket(FuturesMarketState):
    def __init__(self, price: float) -> None:
        super().__init__()
        self.sell_price = price

    def executable_sell_price(self, *args, **kwargs):
        return self.sell_price


class V440Tests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.settings = Settings(data_dir=root, db_path=root / "test.sqlite3")
        self.storage = Storage(self.settings)
        self.storage.start_run("test")

    def tearDown(self) -> None:
        self.storage.conn.close()
        self.temp.cleanup()

    def test_spot_mc7_slot_keeps_channel_and_hold_primary(self) -> None:
        market = FixedMarket(0.101)

        async def notify(_message):
            return None

        manager = MomentumPaperManager(
            self.settings, market, self.storage, notify
        )
        asyncio.run(manager.consider(make_item("MC7"), None, 1_000_000, "ep", "MC7"))
        slot = self.storage.momentum_primary_open_slot()
        self.assertEqual("MC7", slot["signal_channel"])
        policies = self.storage.momentum_policies_for_slot(slot["id"])
        self.assertEqual(
            {"MC_HOLD_120", "MC_TRAIL_1P0", "MC_FIXED_TP4"},
            {row["policy"] for row in policies},
        )
        market.sell_price = 0.102
        asyncio.run(manager.tick(1_120_000))
        self.assertIsNone(self.storage.momentum_primary_open_slot())

    def test_futures_mark_signal_slot_and_fee_accounting(self) -> None:
        state = FixedMarket(0.101)
        state.on_mark_price(
            "RIFUSDT", {"p": "0.101", "r": "0.0001", "T": 9_000_000}, 900
        )
        store = FuturesPaperStore(self.settings, self.storage)
        item = make_item("MC7")
        mark = state.mark_info("RIFUSDT")
        signal_id = store.insert_signal("MC7", item, "ep", 1_000_000, mark)
        slot_id = store.create_slot(signal_id, "MC7", item, "ep", 1_000_000, mark)
        slot = store.open_slot()
        self.assertEqual(slot_id, slot["id"])
        self.assertEqual("MC7", slot["signal_channel"])
        self.assertAlmostEqual(
            slot["entry_fee_usdt"],
            self.settings.position_usdt * self.settings.futures_fee_rate,
        )
        self.assertEqual(3, len(store.policies(slot_id)))

    def test_futures_hold_closes_primary_after_120_seconds(self) -> None:
        state = FixedMarket(0.102)
        store = FuturesPaperStore(self.settings, self.storage)

        async def notify(_message):
            return None

        manager = FuturesPaperManager(
            self.settings, state, store, notify
        )
        item = make_item("MC5")
        signal_id = store.insert_signal(
            "MC5", item, "ep", 1_000_000, state.mark_info("RIFUSDT")
        )
        asyncio.run(manager.consider(item, signal_id, 1_000_000, "ep", "MC5"))
        asyncio.run(manager.tick(1_119_000))
        self.assertIsNotNone(store.open_slot())
        asyncio.run(manager.tick(1_120_000))
        self.assertIsNone(store.open_slot())
        status = store.status()
        self.assertEqual(100, status["freeze_target_primary_trades"])
        self.assertEqual(1, status["freeze_closed_primary_trades"])

    def test_no_real_order_tokens_in_server_source(self) -> None:
        root = Path(__file__).resolve().parents[1] / "pumpradar_server"
        text = "\n".join(path.read_text() for path in root.glob("*.py"))
        forbidden = (
            "/fapi/v1/order",
            "/api/v3/order",
            "create_order",
            "newOrder",
            "API_SECRET",
        )
        for token in forbidden:
            self.assertNotIn(token, text)


if __name__ == "__main__":
    unittest.main()
