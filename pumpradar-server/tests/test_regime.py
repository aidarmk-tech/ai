from __future__ import annotations

import unittest

from pumpradar_server.regime import directional_return, trade_pnl


class RegimeMathTest(unittest.TestCase):
    def test_short_directional_return(self) -> None:
        self.assertAlmostEqual(2.0, directional_return("SHORT", 100.0, 98.0))
        self.assertAlmostEqual(-2.0, directional_return("SHORT", 100.0, 102.0))

    def test_two_x_margin_return_accounts_for_fees(self) -> None:
        gross, net, gross_pct, net_pct = trade_pnl(
            "SHORT",
            entry=100.0,
            exit_price=98.0,
            quantity=0.4,
            entry_fee=0.02,
            exit_fee=0.0196,
            funding_pnl=0.0,
            margin=20.0,
        )
        self.assertAlmostEqual(0.8, gross)
        self.assertAlmostEqual(4.0, gross_pct)
        self.assertAlmostEqual(0.7604, net)
        self.assertAlmostEqual(3.802, net_pct)


if __name__ == "__main__":
    unittest.main()
