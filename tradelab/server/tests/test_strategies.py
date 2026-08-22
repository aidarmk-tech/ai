from collections import defaultdict, deque

from tradelab.strategies import StrategyEngine, correlation, rolling_beta, spread_zscore


def make_history(start_ms=1_000_000, points=100, step_ms=5000, base=100.0, drift=0.0005, variable=False):
    out = deque(maxlen=900)
    price = base
    for i in range(points):
        step = drift + (((i % 7) - 3) * 0.00004 if variable else 0.0)
        price *= 1 + step
        out.append((start_ms + i * step_ms, price))
    return out


def test_beta_and_correlation_are_sane():
    btc = make_history(points=100, base=100, drift=0.0005, variable=True)
    alt = deque((ts, 50 * (p / btc[0][1]) ** 1.5) for ts, p in btc)
    beta = rolling_beta(alt, btc, 180)
    corr = correlation(alt, btc, 180)
    assert beta is not None and 1.3 < beta < 1.7
    assert corr is not None and corr > 0.99


def test_spread_zscore_detects_relative_dislocation():
    b = make_history(points=400, base=100, drift=0.0001, variable=True)
    a = deque(maxlen=900)
    for idx, (ts, p) in enumerate(b):
        relative = 1.0 + (0.0002 if idx % 20 < 10 else -0.0002)
        a.append((ts, p * relative))
    ts, last = a[-1]
    a[-1] = (ts, last * 1.02)
    result = spread_zscore(a, b, 1800)
    assert result is not None
    z, beta = result
    assert z > 2.2
    assert beta > 0


def test_absorption_emits_reversal_signal():
    engine = StrategyEngine()
    now = 2_000_000
    history = defaultdict(lambda: deque(maxlen=900))
    for i in range(20):
        ts = now - (19 - i) * 5000
        history["BTCUSDT"].append((ts, 100.0))
        history["TESTUSDT"].append((ts, 10.0 + i * 0.0001))
    flows = defaultdict(lambda: deque(maxlen=360))
    for i in range(15):
        flows["TESTUSDT"].append((now - (14 - i) * 1000, 9000.0, 1000.0))
    depths = defaultdict(lambda: deque(maxlen=360))
    depths["TESTUSDT"].append((now, -0.1, 0.0, 0.15, 2.0))
    tickers = {"TESTUSDT": {"q": "100000000"}}
    signals = engine.evaluate(now, ["BTCUSDT", "TESTUSDT"], ["TESTUSDT"], history, flows, depths, tickers)
    c = [s for s in signals if s.participant_id == "FLOW_ABSORPTION"]
    assert len(c) == 1
    assert c[0].side_a == "SHORT"
