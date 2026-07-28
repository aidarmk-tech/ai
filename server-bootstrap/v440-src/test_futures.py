from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from pumpradar_server.config import Settings
from pumpradar_server.futures import FuturesMarketState, FuturesPaperStore
from pumpradar_server.models import (
    BookMetrics,
    Candidate,
    Decision,
    EvaluatedCandidate,
    FlowMetrics,
    PeakFeatures,
    RiskAssessment,
)
from pumpradar_server.storage import Storage


class FuturesPaperTests(unittest.TestCase):
    def test_mark_stream_and_store_slot(self) -> None:
        state = FuturesMarketState()
        state.on_mark_price(
            "RIFUSDT",
            {"p": "0.101", "r": "0.0001", "T": 123456789},
            1000,
        )
        mark = state.mark_info("RIFUSDT")
        self.assertAlmostEqual(mark.mark_price, 0.101)
        self.assertAlmostEqual(mark.funding_rate, 0.0001)

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            settings = Settings(data_dir=root, db_path=root / "test.sqlite3")
            storage = Storage(settings)
            storage.start_run("test")
            store = FuturesPaperStore(settings, storage)
            item = EvaluatedCandidate(
                Candidate(
                    "RIFUSDT",
                    0.101,
                    5_000_000,
                    0.5,
                    1.0,
                    5.2,
                    0.2,
                    0.5,
                    100.0,
                    return_3m=3.1,
                    return_10m=7.2,
                ),
                FlowMetrics(
                    ready=True,
                    quote_volume_30s=50_000,
                    taker_buy_ratio_30s=0.8,
                    taker_buy_ratio_15s=0.85,
                    taker_buy_ratio_5s=0.9,
                    cvd_30s=20_000,
                    cvd_slope=10_000,
                ),
                BookMetrics(
                    spread_bps=5,
                    buy_slippage_percent=0.01,
                    sell_slippage_percent=0.01,
                    buy_vwap=0.101,
                    sell_vwap_for_position=0.1009,
                    best_bid=0.1009,
                    best_ask=0.101,
                ),
                PeakFeatures(),
                Decision(
                    "NO_TRADE",
                    False,
                    False,
                    False,
                    [],
                    [],
                    RiskAssessment(0, 0, 0, 100, 0, 0, False, []),
                    "C",
                ),
            )
            signal_id = store.insert_signal("MC7", item, "episode", 2000, mark)
            slot_id = store.create_slot(
                signal_id,
                "MC7",
                "RIFUSDT",
                "episode",
                2000,
                0.101,
                0.101,
                mark,
            )
            self.assertEqual(store.open_slot()["id"], slot_id)
            self.assertEqual(len(store.policies(slot_id)), 3)
            status = store.status()
            self.assertEqual(status["futures_paper_slots"], 1)
            storage.conn.close()


if __name__ == "__main__":
    unittest.main()
