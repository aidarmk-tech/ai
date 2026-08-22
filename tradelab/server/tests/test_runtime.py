from types import SimpleNamespace

from tradelab.market_runtime import StableMarketRecorder


def test_empty_ticker_cache_does_not_collapse_bootstrap_universe():
    r = StableMarketRecorder(SimpleNamespace(universe_size=40, microstructure_size=12))
    before = list(r.universe)
    r._refresh_universe_once()
    assert r.universe == before
    assert len(r.universe) == 6
    assert r.universe_generation == 0
    assert r._health_pending["universe"][0] == "WARMING"


def test_full_ticker_cache_transitions_once_to_top40():
    r = StableMarketRecorder(SimpleNamespace(universe_size=40, microstructure_size=12))
    for i in range(45):
        r.tickers[f"T{i:02d}USDT"] = {"q": str(1_000_000 + i), "st": 1}
    r.tickers["BTCUSDT"] = {"q": "999999999", "st": 1}
    r.tickers["ETHUSDT"] = {"q": "888888888", "st": 1}

    r._refresh_universe_once()
    assert len(r.universe) == 40
    assert len(r.micro) == 12
    assert "BTCUSDT" in r.universe
    assert "ETHUSDT" in r.universe
    assert r.universe_generation == 1
    assert r._health_pending["universe"][0] == "OK"

    unchanged = list(r.universe)
    r._refresh_universe_once()
    assert r.universe == unchanged
    assert r.universe_generation == 1
