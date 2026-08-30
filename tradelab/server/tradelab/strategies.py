import math
from dataclasses import dataclass
from statistics import mean
from .participants import PARTICIPANTS


CONFIG = {p.participant_id: p.config for p in PARTICIPANTS}
SAMPLE_SECONDS = 5
TARGET_TOLERANCE_MS = 7_500
MAX_SAMPLE_GAP_MS = 12_000


@dataclass(frozen=True)
class Signal:
    participant_id: str
    symbol_a: str
    side_a: str
    horizon_seconds: int
    score: float
    features: dict
    symbol_b: str | None = None
    side_b: str | None = None
    hedge_ratio: float | None = None

    @property
    def key(self) -> str:
        if self.symbol_b:
            return f"{self.symbol_a}|{self.symbol_b}"
        return self.symbol_a


def _price_near(history, target_ms: int, tolerance_ms: int = TARGET_TOLERANCE_MS) -> float | None:
    if not history:
        return None
    candidates = [(abs(ts - target_ms), price) for ts, price in history if abs(ts - target_ms) <= tolerance_ms and price > 0]
    if not candidates:
        return None
    return min(candidates, key=lambda x: x[0])[1]


def window_is_continuous(history, now_ms: int, seconds: int, sample_seconds: int = SAMPLE_SECONDS) -> bool:
    if not history or seconds <= 0:
        return False
    target = now_ms - seconds * 1000
    latest_ts = history[-1][0]
    if abs(latest_ts - now_ms) > TARGET_TOLERANCE_MS:
        return False
    if _price_near(history, target) is None:
        return False
    points = [(ts, price) for ts, price in history if target - TARGET_TOLERANCE_MS <= ts <= now_ms + TARGET_TOLERANCE_MS and price > 0]
    if len(points) < 2:
        return False
    points.sort()
    max_gap = max((b[0] - a[0] for a, b in zip(points, points[1:])), default=0)
    if max_gap > MAX_SAMPLE_GAP_MS:
        return False
    expected = seconds / max(1, sample_seconds) + 1
    return len(points) >= max(2, int(expected * 0.85))


def return_pct(history, now_ms: int, seconds: int) -> float | None:
    if not history:
        return None
    current = _price_near(history, now_ms)
    previous = _price_near(history, now_ms - seconds * 1000)
    if current is None or previous is None or previous <= 0:
        return None
    return (current / previous - 1.0) * 100.0


def _aligned_prices(hist_a, hist_b, window_seconds: int) -> tuple[list[float], list[float]]:
    if not hist_a or not hist_b:
        return [], []
    cutoff = min(hist_a[-1][0], hist_b[-1][0]) - window_seconds * 1000
    a = {ts // 5000: price for ts, price in hist_a if ts >= cutoff and price > 0}
    b = {ts // 5000: price for ts, price in hist_b if ts >= cutoff and price > 0}
    keys = sorted(set(a).intersection(b))
    return [a[k] for k in keys], [b[k] for k in keys]


def _returns(prices: list[float]) -> list[float]:
    return [math.log(prices[i] / prices[i - 1]) for i in range(1, len(prices)) if prices[i - 1] > 0 and prices[i] > 0]


def _covariance(a: list[float], b: list[float]) -> float:
    if len(a) != len(b) or len(a) < 3:
        return 0.0
    ma, mb = mean(a), mean(b)
    return sum((x - ma) * (y - mb) for x, y in zip(a, b)) / (len(a) - 1)


def _variance(a: list[float]) -> float:
    if len(a) < 3:
        return 0.0
    ma = mean(a)
    return sum((x - ma) ** 2 for x in a) / (len(a) - 1)


def rolling_beta(hist_asset, hist_btc, window_seconds: int) -> float | None:
    asset_prices, btc_prices = _aligned_prices(hist_asset, hist_btc, window_seconds)
    if len(asset_prices) < 12:
        return None
    ar, br = _returns(asset_prices), _returns(btc_prices)
    n = min(len(ar), len(br))
    if n < 10:
        return None
    ar, br = ar[-n:], br[-n:]
    vb = _variance(br)
    if vb <= 1e-16:
        return None
    return _covariance(ar, br) / vb


def correlation(hist_a, hist_b, window_seconds: int) -> float | None:
    ap, bp = _aligned_prices(hist_a, hist_b, window_seconds)
    if len(ap) < 30:
        return None
    ar, br = _returns(ap), _returns(bp)
    n = min(len(ar), len(br))
    if n < 20:
        return None
    ar, br = ar[-n:], br[-n:]
    va, vb = _variance(ar), _variance(br)
    if va <= 1e-16 or vb <= 1e-16:
        return None
    return _covariance(ar, br) / math.sqrt(va * vb)


def spread_zscore(hist_a, hist_b, window_seconds: int) -> tuple[float, float] | None:
    ap, bp = _aligned_prices(hist_a, hist_b, window_seconds)
    if len(ap) < 60:
        return None
    la, lb = [math.log(x) for x in ap], [math.log(x) for x in bp]
    vb = _variance(lb)
    if vb <= 1e-16:
        return None
    beta = _covariance(la, lb) / vb
    intercept = mean(la) - beta * mean(lb)
    residuals = [a - intercept - beta * b for a, b in zip(la, lb)]
    mr = mean(residuals)
    vr = _variance(residuals)
    if vr <= 1e-16:
        return None
    z = (residuals[-1] - mr) / math.sqrt(vr)
    return z, beta


class StrategyEngine:
    def __init__(self):
        self.cooldowns: dict[tuple[str, str], int] = {}

    def _allowed(self, participant_id: str, key: str, now_ms: int, cooldown_seconds: int) -> bool:
        last = self.cooldowns.get((participant_id, key), 0)
        if now_ms - last < cooldown_seconds * 1000:
            return False
        self.cooldowns[(participant_id, key)] = now_ms
        return True

    def evaluate(self, now_ms: int, universe: list[str], micro: list[str], history, flows, depths, tickers) -> list[Signal]:
        signals: list[Signal] = []
        signals += self._lead_lag(now_ms, universe, history, tickers)
        signals += self._momentum(now_ms, universe, history)
        signals += self._absorption(now_ms, micro, history, flows, depths)
        signals += self._stat_arb(now_ms, universe[:14], history)
        return signals

    def _lead_lag(self, now_ms, universe, history, tickers) -> list[Signal]:
        cfg = CONFIG["BTC_ALT_LAG"]
        btc = history.get("BTCUSDT")
        required = max(cfg["warmup_seconds"], cfg["beta_window_seconds"])
        if not btc or not window_is_continuous(btc, now_ms, required):
            return []
        btc15 = return_pct(btc, now_ms, 15)
        if btc15 is None or abs(btc15) < cfg["btc_impulse_15s_pct"]:
            return []
        candidates = []
        for symbol in universe:
            if symbol == "BTCUSDT" or symbol not in history:
                continue
            if not window_is_continuous(history[symbol], now_ms, required):
                continue
            qv = float(tickers.get(symbol, {}).get("q", 0) or 0)
            if qv < cfg["min_quote_volume_24h"]:
                continue
            alt15 = return_pct(history[symbol], now_ms, 15)
            beta = rolling_beta(history[symbol], btc, cfg["beta_window_seconds"])
            if alt15 is None or beta is None or beta <= 0:
                continue
            predicted = beta * btc15
            gap = predicted - alt15
            if abs(gap) < cfg["min_lag_gap_pct"] or predicted * gap <= 0:
                continue
            side = "LONG" if gap > 0 else "SHORT"
            candidates.append((abs(gap), Signal(
                "BTC_ALT_LAG", symbol, side, cfg["horizon_seconds"], abs(gap),
                {"btc_ret15_pct": btc15, "alt_ret15_pct": alt15, "beta": beta, "predicted_pct": predicted, "lag_gap_pct": gap, "quote_volume_24h": qv},
            )))
        out = []
        for _, signal in sorted(candidates, key=lambda x: x[0], reverse=True)[:2]:
            if self._allowed(signal.participant_id, signal.key, now_ms, cfg["cooldown_seconds"]):
                out.append(signal)
        return out

    def _momentum(self, now_ms, universe, history) -> list[Signal]:
        cfg = CONFIG["REGIME_MOMENTUM"]
        btc, eth = history.get("BTCUSDT"), history.get("ETHUSDT")
        required = max(cfg["warmup_seconds"], 300)
        if not btc or not eth:
            return []
        if not window_is_continuous(btc, now_ms, required) or not window_is_continuous(eth, now_ms, required):
            return []
        btc60, btc300 = return_pct(btc, now_ms, 60), return_pct(btc, now_ms, 300)
        eth300 = return_pct(eth, now_ms, 300)
        if None in (btc60, btc300, eth300):
            return []
        direction = 0
        if btc60 >= cfg["btc_ret_60s_pct"] and btc300 >= cfg["btc_ret_300s_pct"] and eth300 > 0:
            direction = 1
        elif btc60 <= -cfg["btc_ret_60s_pct"] and btc300 <= -cfg["btc_ret_300s_pct"] and eth300 < 0:
            direction = -1
        if direction == 0:
            return []
        candidates = []
        for symbol in universe[:25]:
            h = history.get(symbol)
            if not h or not window_is_continuous(h, now_ms, required):
                continue
            r60, r15 = return_pct(h, now_ms, 60), return_pct(h, now_ms, 15)
            if r60 is None or r15 is None:
                continue
            if direction * r60 < cfg["asset_ret_60s_pct"] or direction * r15 < cfg["asset_ret_15s_pct"]:
                continue
            score = direction * r60
            side = "LONG" if direction > 0 else "SHORT"
            candidates.append((score, Signal(
                "REGIME_MOMENTUM", symbol, side, cfg["horizon_seconds"], score,
                {"btc_ret60_pct": btc60, "btc_ret300_pct": btc300, "eth_ret300_pct": eth300, "asset_ret60_pct": r60, "asset_ret15_pct": r15, "regime": "BULL" if direction > 0 else "BEAR"},
            )))
        out = []
        for _, signal in sorted(candidates, key=lambda x: x[0], reverse=True)[:2]:
            if self._allowed(signal.participant_id, signal.key, now_ms, cfg["cooldown_seconds"]):
                out.append(signal)
        return out

    def _absorption(self, now_ms, micro, history, flows, depths) -> list[Signal]:
        cfg = CONFIG["FLOW_ABSORPTION"]
        candidates = []
        cutoff = now_ms - cfg["flow_window_seconds"] * 1000
        for symbol in micro:
            h, fw, dw = history.get(symbol), flows.get(symbol), depths.get(symbol)
            if not h or not fw or not dw:
                continue
            if not window_is_continuous(h, now_ms, cfg["warmup_seconds"]):
                continue
            if now_ms - dw[-1][0] > 2_500:
                continue
            recent = [x for x in fw if x[0] >= cutoff]
            if not recent or now_ms - recent[-1][0] > 3_500:
                continue
            buy = sum(x[1] for x in recent)
            sell = sum(x[2] for x in recent)
            total = buy + sell
            if total < cfg["min_flow_notional"]:
                continue
            imbalance = (buy - sell) / total
            if abs(imbalance) < cfg["flow_imbalance_abs"]:
                continue
            price_ret = return_pct(h, now_ms, cfg["flow_window_seconds"])
            if price_ret is None or abs(price_ret) > cfg["max_price_response_abs_pct"]:
                continue
            _, book_imb, bid_rep, ask_rep, spread_bps = dw[-1]
            side = None
            replenish = 0.0
            if imbalance > 0 and ask_rep >= cfg["min_replenishment"]:
                side, replenish = "SHORT", ask_rep
            elif imbalance < 0 and bid_rep >= cfg["min_replenishment"]:
                side, replenish = "LONG", bid_rep
            if side is None:
                continue
            score = abs(imbalance) * (1 + replenish)
            candidates.append((score, Signal(
                "FLOW_ABSORPTION", symbol, side, cfg["horizon_seconds"], score,
                {"buy_notional_15s": buy, "sell_notional_15s": sell, "flow_imbalance": imbalance, "price_response_pct": price_ret, "book_imbalance": book_imb, "bid_replenishment": bid_rep, "ask_replenishment": ask_rep, "spread_bps": spread_bps},
            )))
        out = []
        for _, signal in sorted(candidates, key=lambda x: x[0], reverse=True)[:2]:
            if self._allowed(signal.participant_id, signal.key, now_ms, cfg["cooldown_seconds"]):
                out.append(signal)
        return out

    def _stat_arb(self, now_ms, universe, history) -> list[Signal]:
        cfg = CONFIG["STAT_ARB"]
        required = max(cfg["warmup_seconds"], cfg["pair_window_seconds"])
        candidates = []
        symbols = [s for s in universe if s in history and window_is_continuous(history[s], now_ms, required, cfg["sample_seconds"])]
        for i, a in enumerate(symbols):
            for b in symbols[i + 1:]:
                corr = correlation(history[a], history[b], cfg["pair_window_seconds"])
                if corr is None or corr < cfg["min_correlation"]:
                    continue
                zb = spread_zscore(history[a], history[b], cfg["pair_window_seconds"])
                if zb is None:
                    continue
                z, beta = zb
                if abs(z) < cfg["entry_z_abs"] or beta <= 0:
                    continue
                side_a, side_b = ("SHORT", "LONG") if z > 0 else ("LONG", "SHORT")
                candidates.append((abs(z), Signal(
                    "STAT_ARB", a, side_a, cfg["horizon_seconds"], abs(z),
                    {"zscore": z, "correlation": corr, "hedge_ratio": beta},
                    symbol_b=b, side_b=side_b, hedge_ratio=beta,
                )))
        if not candidates:
            return []
        for _, signal in sorted(candidates, key=lambda x: x[0], reverse=True)[:cfg["top_pairs"]]:
            if self._allowed(signal.participant_id, signal.key, now_ms, cfg["cooldown_seconds"]):
                return [signal]
        return []
