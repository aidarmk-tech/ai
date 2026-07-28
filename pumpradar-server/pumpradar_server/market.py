from __future__ import annotations

import asyncio
import heapq
import json
import logging
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Optional

import aiohttp

from .config import Settings
from .mathutil import EPSILON, median, return_percent, robust_z, safe_divide
from .models import BookMetrics, Candidate, FlowMetrics, PeakFeatures

LOG = logging.getLogger(__name__)


@dataclass
class SymbolInfo:
    symbol: str
    quote_asset: str
    status: str
    spot_allowed: bool


@dataclass
class PriceRow:
    samples: deque[tuple[int, float]] = field(default_factory=deque)
    price: float = 0.0
    quote_volume_24h: float = 0.0
    last_update_ms: int = 0


@dataclass
class FlowBucket:
    second: int
    buy_quote: float = 0.0
    sell_quote: float = 0.0
    count: int = 0
    tiny_count: int = 0
    top3: list[float] = field(default_factory=list)

    def add(self, quote: float, aggressive_buy: bool) -> None:
        if aggressive_buy:
            self.buy_quote += quote
        else:
            self.sell_quote += quote
        self.count += 1
        if quote < 10.0:
            self.tiny_count += 1
        if len(self.top3) < 3:
            heapq.heappush(self.top3, quote)
        elif quote > self.top3[0]:
            heapq.heapreplace(self.top3, quote)


@dataclass
class FlowRow:
    seconds: deque[FlowBucket] = field(default_factory=deque)
    volume_buckets_10s: dict[int, float] = field(default_factory=dict)
    last_agg_id: int = -1
    last_gap_at_ms: int = -1
    best_bid: float = 0.0
    best_ask: float = 0.0
    last_book_update_id: int = -1
    last_trade_at_ms: int = 0
    last_book_at_ms: int = 0


@dataclass
class DepthRow:
    update_id: int
    bids: list[tuple[float, float]]
    asks: list[tuple[float, float]]
    updated_at_ms: int


class MarketState:
    PRICE_MAX_AGE_MS = 10 * 60 * 1000
    FLOW_MAX_AGE_SECONDS = 65
    VOLUME_BUCKET_MAX_AGE = 60 * 60 // 10

    def __init__(self) -> None:
        self.universe: dict[str, SymbolInfo] = {}
        self.prices: dict[str, PriceRow] = defaultdict(PriceRow)
        self.flows: dict[str, FlowRow] = defaultdict(FlowRow)
        self.depth: dict[str, DepthRow] = {}
        self.last_market_message_ms = 0
        self.last_candidate_message_ms = 0
        self.candidate_connection_count = 0
        self.candidate_subscription_update_count = 0
        self.last_candidate_connect_ms = 0
        self._peak_rows: dict[str, deque[tuple[int, float, float]]] = defaultdict(deque)

    def set_universe(self, symbols: list[SymbolInfo]) -> None:
        self.universe = {s.symbol: s for s in symbols}
        keep = set(self.universe)
        self.prices = defaultdict(PriceRow, {k: v for k, v in self.prices.items() if k in keep})

    def retain_detailed_symbols(self, symbols: set[str]) -> None:
        """Drop stale flow/depth state when symbols leave the warm pool."""
        keep = set(symbols)
        self.flows = defaultdict(FlowRow, {k: v for k, v in self.flows.items() if k in keep})
        self.depth = {k: v for k, v in self.depth.items() if k in keep}
        self._peak_rows = defaultdict(deque, {k: v for k, v in self._peak_rows.items() if k in keep})

    def reset_candidate_stream_state(self, symbols: set[str]) -> None:
        """Discard partial windows after a real candidate websocket reconnect."""
        for symbol in symbols:
            self.flows.pop(symbol, None)
            self.depth.pop(symbol, None)
            self._peak_rows.pop(symbol, None)

    def retain_depth_symbols(self, symbols: set[str]) -> None:
        """Never expose a recently unsubscribed depth book as executable."""
        keep = set(symbols)
        self.depth = {k: v for k, v in self.depth.items() if k in keep}

    def on_mini_tickers(self, data: list[dict[str, Any]], now_ms: int) -> None:
        self.last_market_message_ms = now_ms
        for item in data:
            symbol = str(item.get("s", ""))
            if symbol not in self.universe:
                continue
            try:
                price = float(item["c"])
                quote_volume = float(item.get("q", 0.0))
            except (KeyError, TypeError, ValueError):
                continue
            row = self.prices[symbol]
            row.price = price
            row.quote_volume_24h = quote_volume
            row.last_update_ms = now_ms
            if not row.samples or now_ms - row.samples[-1][0] >= 900:
                row.samples.append((now_ms, price))
                cutoff = now_ms - self.PRICE_MAX_AGE_MS
                while row.samples and row.samples[0][0] < cutoff:
                    row.samples.popleft()

    def on_agg_trade(self, symbol: str, data: dict[str, Any], now_ms: int) -> None:
        try:
            agg_id = int(data.get("a", -1))
            price = float(data["p"])
            qty = float(data["q"])
            trade_time = int(data.get("T", now_ms))
            aggressive_buy = not bool(data.get("m", False))
        except (KeyError, TypeError, ValueError):
            return
        row = self.flows[symbol]
        if agg_id > 0 and row.last_agg_id >= 0:
            if agg_id <= row.last_agg_id:
                return
            if agg_id > row.last_agg_id + 1:
                row.last_gap_at_ms = now_ms
        row.last_agg_id = max(row.last_agg_id, agg_id)
        row.last_trade_at_ms = now_ms
        second = trade_time // 1000
        if not row.seconds or row.seconds[-1].second != second:
            row.seconds.append(FlowBucket(second))
        row.seconds[-1].add(price * qty, aggressive_buy)
        cutoff = now_ms // 1000 - self.FLOW_MAX_AGE_SECONDS
        while row.seconds and row.seconds[0].second < cutoff:
            row.seconds.popleft()
        bucket10 = trade_time // 10_000
        row.volume_buckets_10s[bucket10] = row.volume_buckets_10s.get(bucket10, 0.0) + price * qty
        min_bucket = now_ms // 10_000 - self.VOLUME_BUCKET_MAX_AGE
        for key in list(row.volume_buckets_10s):
            if key < min_bucket:
                del row.volume_buckets_10s[key]
        self.last_candidate_message_ms = now_ms

    def on_book_ticker(self, symbol: str, data: dict[str, Any], now_ms: int) -> None:
        try:
            update_id = int(data.get("u", -1))
            bid = float(data["b"])
            ask = float(data["a"])
        except (KeyError, TypeError, ValueError):
            return
        row = self.flows[symbol]
        if update_id > 0 and update_id <= row.last_book_update_id:
            return
        row.last_book_update_id = update_id
        row.best_bid = bid
        row.best_ask = ask
        row.last_book_at_ms = now_ms
        self.last_candidate_message_ms = now_ms

    def on_depth(self, symbol: str, data: dict[str, Any], now_ms: int) -> None:
        try:
            update_id = int(data.get("lastUpdateId", data.get("u", -1)))
            bids = [(float(p), float(q)) for p, q in data.get("bids", data.get("b", []))]
            asks = [(float(p), float(q)) for p, q in data.get("asks", data.get("a", []))]
        except (TypeError, ValueError):
            return
        previous = self.depth.get(symbol)
        if previous and update_id > 0 and update_id <= previous.update_id:
            return
        if bids and asks:
            self.depth[symbol] = DepthRow(update_id, bids[:20], asks[:20], now_ms)
            self.last_candidate_message_ms = now_ms

    def _price_at(self, symbol: str, target_ms: int) -> Optional[float]:
        row = self.prices.get(symbol)
        if not row or not row.samples or row.samples[0][0] > target_ms:
            return None
        value = row.samples[0][1]
        for ts, price in row.samples:
            if ts <= target_ms:
                value = price
            else:
                break
        return value

    def _return(self, symbol: str, window_ms: int, now_ms: int) -> Optional[float]:
        row = self.prices.get(symbol)
        if not row or row.price <= 0:
            return None
        past = self._price_at(symbol, now_ms - window_ms)
        return None if past is None else return_percent(row.price, past)

    def _acceleration(self, symbol: str, now_ms: int) -> Optional[float]:
        p30 = self._price_at(symbol, now_ms - 30_000)
        p15 = self._price_at(symbol, now_ms - 15_000)
        p0 = self.prices.get(symbol).price if symbol in self.prices else 0.0
        if p30 is None or p15 is None or p0 <= 0:
            return None
        prev = return_percent(p15, p30)
        current = return_percent(p0, p15)
        return None if prev is None or current is None else current - prev

    def market_context(self, now_ms: int) -> tuple[float, float]:
        values = [v for symbol in self.universe if (v := self._return(symbol, 60_000, now_ms)) is not None]
        if not values:
            return 0.0, 0.5
        return median(values), sum(1 for v in values if v > 0) / len(values)

    @staticmethod
    def is_pre_candidate(candidate: Candidate) -> bool:
        return (
            (candidate.return_15s is not None and candidate.return_15s >= 0.35)
            or (candidate.return_60s is not None and candidate.return_60s >= 0.80)
            or (candidate.return_3m is not None and candidate.return_3m >= 2.50)
            or (candidate.return_5m is not None and candidate.return_5m >= 4.50)
            or (candidate.return_10m is not None and candidate.return_10m >= 6.50)
            or (candidate.acceleration is not None and candidate.acceleration >= 0.30)
        )

    def rank_universe(self, minimum_volume: float, now_ms: int) -> list[Candidate]:
        """Rank every sufficiently liquid symbol, including calm controls.

        This is deliberately broader than the trade pre-filter.  It lets the
        server warm flow history before a symbol crosses the entry boundary and
        gives us a rotating normal-market control sample without changing any
        frozen TRADE_3 thresholds.
        """
        btc_ret = self._return("BTCUSDT", 60_000, now_ms)
        out: list[Candidate] = []
        for symbol, row in self.prices.items():
            if row.price <= 0 or row.quote_volume_24h < minimum_volume:
                continue
            r15 = self._return(symbol, 15_000, now_ms)
            r60 = self._return(symbol, 60_000, now_ms)
            r3m = self._return(symbol, 180_000, now_ms)
            r5m = self._return(symbol, 300_000, now_ms)
            r10m = self._return(symbol, 600_000, now_ms)
            accel = self._acceleration(symbol, now_ms)
            rel = None if r60 is None or btc_ret is None else r60 - btc_ret
            liquidity = max(0.0, min(1.0, __import__("math").log10(max(0.1, row.quote_volume_24h / 1_000_000)) / 2))
            clamp = lambda v, d, lo, hi: max(lo, min(hi, (v or 0.0) / d))
            pre = (
                clamp(r15, 0.5, 0, 3) * 20
                + clamp(r60, 1.0, 0, 3) * 20
                + clamp(r3m, 3.0, 0, 3) * 10
                + clamp(r5m, 5.0, 0, 3) * 10
                + clamp(r10m, 7.0, 0, 3) * 5
                + clamp(accel, 0.3, 0, 3) * 15
                + clamp(rel, 0.5, -2, 3) * 15
                + liquidity * 20
            )
            out.append(Candidate(
                symbol=symbol,
                price=row.price,
                quote_volume_24h=row.quote_volume_24h,
                return_15s=r15,
                return_60s=r60,
                return_5m=r5m,
                acceleration=accel,
                relative_strength_vs_btc=rel,
                pre_score=pre,
                price_age_ms=(
                    now_ms - row.last_update_ms if row.last_update_ms else None
                ),
                return_3m=r3m,
                return_10m=r10m,
            ))
        out.sort(key=lambda c: c.pre_score, reverse=True)
        return out

    def compute_candidates(self, minimum_volume: float, limit: int, now_ms: int) -> list[Candidate]:
        return [c for c in self.rank_universe(minimum_volume, now_ms) if self.is_pre_candidate(c)][:limit]

    def flow_metrics(self, symbol: str, now_ms: int) -> FlowMetrics:
        row = self.flows.get(symbol)
        if not row:
            return FlowMetrics()
        now_s = now_ms // 1000

        def sum_window(seconds: int) -> tuple[float, float, int, int, list[float]]:
            buy = sell = 0.0
            count = tiny = 0
            top_values: list[float] = []
            cutoff = now_s - seconds
            for bucket in row.seconds:
                if bucket.second < cutoff:
                    continue
                buy += bucket.buy_quote
                sell += bucket.sell_quote
                count += bucket.count
                tiny += bucket.tiny_count
                top_values.extend(bucket.top3)
            return buy, sell, count, tiny, top_values

        b60, s60, _, _, _ = sum_window(60)
        b30, s30, c30, tiny30, top30 = sum_window(30)
        b15, s15, _, _, _ = sum_window(15)
        b5, s5, _, _, _ = sum_window(5)
        total30 = b30 + s30
        ratio = lambda b, s: b / (b + s) if b + s > EPSILON else None
        current_bucket = now_ms // 10_000
        completed = [v for k, v in sorted(row.volume_buckets_10s.items()) if k < current_bucket]
        volume_z: Optional[float] = None
        if len(completed) >= 7:
            current = completed[-1]
            history = completed[:-1]
            short_z = robust_z(current, history[-60:])
            long_z = robust_z(current, history) if len(history) > 60 else None
            volume_z = min(short_z, long_z) if long_z is not None else short_z
        top30.sort(reverse=True)
        largest = top30[0] / total30 if total30 > EPSILON and top30 else None
        top3 = sum(top30[:3]) / total30 if total30 > EPSILON and top30 else None
        tiny_share = tiny30 / c30 if c30 else None
        spread = None
        if row.best_bid > 0 and row.best_ask > 0:
            mid = (row.best_bid + row.best_ask) / 2
            spread = (row.best_ask - row.best_bid) / mid * 10_000
        return FlowMetrics(
            ready=c30 >= 3 and volume_z is not None,
            quote_volume_30s=total30,
            trade_count_30s=c30,
            trades_per_second=c30 / 30.0,
            taker_buy_ratio_30s=ratio(b30, s30),
            taker_buy_ratio_15s=ratio(b15, s15),
            taker_buy_ratio_5s=ratio(b5, s5),
            cvd_30s=b30 - s30,
            cvd_15s=b15 - s15,
            cvd_5s=b5 - s5,
            cvd_slope=(b30 - s30) - ((b60 - s60) - (b30 - s30)),
            volume_z_30s=volume_z,
            trade_gap=row.last_gap_at_ms > 0 and now_ms - row.last_gap_at_ms < 60_000,
            largest_trade_share=largest,
            top3_trade_share=top3,
            tiny_trade_share=tiny_share,
            spread_bps=spread,
            trade_age_ms=now_ms - row.last_trade_at_ms if row.last_trade_at_ms else None,
            book_ticker_age_ms=now_ms - row.last_book_at_ms if row.last_book_at_ms else None,
        )

    @staticmethod
    def _probe_buy(levels: list[tuple[float, float]], amount: float) -> tuple[Optional[float], float, float]:
        remaining = amount
        quote_spent = base = 0.0
        best = levels[0][0] if levels else 0.0
        for price, qty in levels:
            level_quote = price * qty
            take = min(remaining, level_quote)
            if take <= 0:
                break
            quote_spent += take
            base += take / price
            remaining -= take
            if remaining <= EPSILON:
                break
        if base <= 0 or best <= 0:
            return None, max(0.0, remaining), 0.0
        vwap = quote_spent / base
        slip = (vwap / best - 1) * 100
        return vwap, max(0.0, remaining), slip

    @staticmethod
    def _probe_sell(levels: list[tuple[float, float]], qty: float) -> tuple[Optional[float], float]:
        remaining = qty
        quote_received = sold = 0.0
        for price, available in levels:
            take = min(remaining, available)
            if take <= 0:
                break
            quote_received += take * price
            sold += take
            remaining -= take
            if remaining <= EPSILON:
                break
        return (quote_received / sold if sold > 0 and remaining <= EPSILON else None), max(0.0, remaining)

    def book_metrics(self, symbol: str, amount_usdt: float, now_ms: Optional[int] = None) -> BookMetrics:
        row = self.depth.get(symbol)
        if not row or not row.bids or not row.asks:
            return BookMetrics()
        depth_age_ms = None if now_ms is None else max(0, now_ms - row.updated_at_ms)
        best_bid, best_ask = row.bids[0][0], row.asks[0][0]
        mid = (best_bid + best_ask) / 2
        spread = (best_ask - best_bid) / mid * 10_000 if mid > 0 else None
        bid10 = sum(p * q for p, q in row.bids[:10])
        ask10 = sum(p * q for p, q in row.asks[:10])
        bid20 = sum(p * q for p, q in row.bids[:20])
        ask20 = sum(p * q for p, q in row.asks[:20])
        imbalance = lambda b, a: (b - a) / (b + a) if b + a > EPSILON else 0.0
        buy_vwap, shortfall, buy_slip = self._probe_buy(row.asks, amount_usdt)
        qty = amount_usdt / buy_vwap if buy_vwap else 0.0
        sell_vwap, sell_shortfall = self._probe_sell(row.bids, qty)
        sell_slip = None
        if sell_vwap is not None and best_bid > 0:
            sell_slip = (1 - sell_vwap / best_bid) * 100
        return BookMetrics(
            spread_bps=spread,
            obi_10=imbalance(bid10, ask10),
            obi_20=imbalance(bid20, ask20),
            buy_slippage_percent=None if shortfall > EPSILON else buy_slip,
            sell_slippage_percent=None if sell_shortfall > EPSILON else sell_slip,
            buy_vwap=buy_vwap if shortfall <= EPSILON else None,
            sell_vwap_for_position=sell_vwap if sell_shortfall <= EPSILON else None,
            best_bid=best_bid,
            best_ask=best_ask,
            bid_notional_top10=bid10,
            ask_notional_top10=ask10,
            depth_age_ms=depth_age_ms,
            depth_update_id=row.update_id,
        )

    def executable_sell_price(
        self,
        symbol: str,
        qty: float,
        now_ms: Optional[int] = None,
        max_age_ms: Optional[int] = None,
    ) -> Optional[float]:
        row = self.depth.get(symbol)
        if not row:
            return None
        if now_ms is not None and max_age_ms is not None and now_ms - row.updated_at_ms > max_age_ms:
            return None
        price, shortfall = self._probe_sell(row.bids, qty)
        return price if shortfall <= EPSILON else None

    def measurement_age_ms(self, symbol: str, now_ms: int) -> int:
        """Age of the oldest source required for an executable decision."""
        price = self.prices.get(symbol)
        flow = self.flows.get(symbol)
        depth = self.depth.get(symbol)
        timestamps = [
            price.last_update_ms if price else 0,
            flow.last_trade_at_ms if flow else 0,
            flow.last_book_at_ms if flow else 0,
            depth.updated_at_ms if depth else 0,
        ]
        if any(value <= 0 for value in timestamps):
            return 999_999
        return max(now_ms - value for value in timestamps)

    def update_peak(self, symbol: str, price: float, cvd: float, now_ms: int) -> PeakFeatures:
        samples = self._peak_rows[symbol]
        samples.append((now_ms, price, cvd))
        cutoff = now_ms - 300_000
        while samples and samples[0][0] < cutoff:
            samples.popleft()
        if len(samples) < 2:
            return PeakFeatures()
        max_price = max(p for _, p, _ in samples)
        high_sample = next((x for x in reversed(samples) if x[1] >= max_price - 0.0005 * max_price), samples[-1])
        max_cvd = max(v for _, _, v in samples)
        distance = (max_price - price) / max_price * 100 if max_price > 0 else 0.0
        post_high = [x for x in samples if x[0] >= high_sample[0]]
        min_after = min(p for _, p, _ in post_high)
        pullback = (max_price - min_after) / max_price * 100 if max_price > 0 else 0.0
        min_time = next((t for t, p, _ in post_high if p <= min_after + 0.0005 * max_price), high_sample[0])
        bounce_high = max((p for t, p, _ in samples if t >= min_time), default=price)
        lower_high = pullback >= 0.3 and bounce_high < max_price * (1 - 0.0005) and price < bounce_high
        before_breakout = [x for x in samples if x[0] <= high_sample[0] - 60_000]
        breakout_level = before_breakout[-1][1] if before_breakout else samples[0][1]
        breakout_held = price >= breakout_level
        band = max_price * (1 - 0.25 / 100)
        attempts = 0
        in_zone = False
        for _, p, _ in post_high:
            if not in_zone and band <= p < max_price * (1 - 0.0005):
                in_zone = True
            elif in_zone and p < band * (1 - 0.25 / 100):
                attempts += 1
                in_zone = False
        new_high_no_cvd = price >= max_price - 0.0005 * max_price and max_cvd > 0 and cvd < max_cvd * 0.98
        return PeakFeatures(
            local_high_price=max_price,
            peak_at_ms=high_sample[0],
            distance_from_local_high_pct=max(0.0, distance),
            seconds_since_local_high=max(0, (now_ms - high_sample[0]) // 1000),
            pullback_from_high_pct=max(0.0, pullback),
            failed_high_attempts=attempts,
            lower_high_detected=lower_high,
            breakout_level_held=breakout_held,
            new_high_without_cvd_high=new_high_no_cvd,
        )


class BinanceFeed:
    def __init__(self, settings: Settings, state: MarketState) -> None:
        self.settings = settings
        self.state = state
        self.session: Optional[aiohttp.ClientSession] = None
        self._warm_symbols: tuple[str, ...] = ()
        self._depth_symbols: tuple[str, ...] = ()
        self._candidate_change = asyncio.Event()
        self._stopping = asyncio.Event()

    async def start(self) -> None:
        timeout = aiohttp.ClientTimeout(total=20)
        self.session = aiohttp.ClientSession(
            timeout=timeout,
            headers={"User-Agent": f"PumpRadar/{self.settings.algorithm_version}"},
        )
        await self.refresh_universe()

    async def stop(self) -> None:
        self._stopping.set()
        self._candidate_change.set()
        if self.session:
            await self.session.close()

    async def refresh_universe(self) -> None:
        assert self.session
        async with self.session.get(f"{self.settings.rest_url}/api/v3/exchangeInfo") as response:
            response.raise_for_status()
            payload = await response.json()
        excluded_parts = ("UPUSDT", "DOWNUSDT", "BULLUSDT", "BEARUSDT")
        symbols = []
        for raw in payload.get("symbols", []):
            symbol = raw.get("symbol", "")
            if raw.get("quoteAsset") != "USDT" or raw.get("status") != "TRADING":
                continue
            if raw.get("isSpotTradingAllowed") is False or symbol.endswith(excluded_parts):
                continue
            symbols.append(SymbolInfo(symbol, "USDT", "TRADING", True))
        self.state.set_universe(symbols)
        LOG.info("Loaded %d USDT spot symbols", len(symbols))

    def set_candidate_symbols(self, warm_symbols: list[str], depth_symbols: Optional[list[str]] = None) -> None:
        warm = tuple(sorted(set(warm_symbols)))
        depth = tuple(sorted(set(depth_symbols or ()) & set(warm)))
        if warm != self._warm_symbols or depth != self._depth_symbols:
            self._warm_symbols = warm
            self._depth_symbols = depth
            self._candidate_change.set()

    @staticmethod
    def _streams(
        warm_symbols: tuple[str, ...],
        depth_symbols: tuple[str, ...],
    ) -> set[str]:
        depth = set(depth_symbols)
        streams: set[str] = set()
        for symbol in warm_symbols:
            low = symbol.lower()
            streams.update((f"{low}@aggTrade", f"{low}@bookTicker"))
            if symbol in depth:
                streams.add(f"{low}@depth20@100ms")
        return streams

    async def _update_subscriptions(
        self,
        ws: aiohttp.ClientWebSocketResponse,
        current: set[str],
        desired: set[str],
        request_id: int,
    ) -> int:
        removed = sorted(current - desired)
        added = sorted(desired - current)
        if removed:
            await ws.send_json({"method": "UNSUBSCRIBE", "params": removed, "id": request_id})
            request_id += 1
        if added:
            await ws.send_json({"method": "SUBSCRIBE", "params": added, "id": request_id})
            request_id += 1
        if removed or added:
            self.state.candidate_subscription_update_count += 1
        return request_id

    async def market_loop(self) -> None:
        assert self.session
        url = f"{self.settings.ws_url}/ws/!miniTicker@arr"
        while not self._stopping.is_set():
            try:
                async with self.session.ws_connect(url, heartbeat=30, receive_timeout=90) as ws:
                    LOG.info("Market websocket connected")
                    async for message in ws:
                        if message.type == aiohttp.WSMsgType.TEXT:
                            payload = json.loads(message.data)
                            if isinstance(payload, list):
                                self.state.on_mini_tickers(payload, int(time.time() * 1000))
                        elif message.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                            break
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                LOG.warning("Market websocket error: %s", exc)
            await asyncio.sleep(3)

    async def candidate_loop(self) -> None:
        assert self.session
        while not self._stopping.is_set():
            symbols = self._warm_symbols
            self._candidate_change.clear()
            if not symbols:
                try:
                    await asyncio.wait_for(self._candidate_change.wait(), timeout=5)
                except asyncio.TimeoutError:
                    pass
                continue
            streams = self._streams(symbols, self._depth_symbols)
            url = f"{self.settings.ws_url}/stream?streams={'/'.join(sorted(streams))}"
            try:
                async with self.session.ws_connect(url, heartbeat=30, receive_timeout=90) as ws:
                    now_ms = int(time.time() * 1000)
                    self.state.reset_candidate_stream_state(set(symbols))
                    self.state.candidate_connection_count += 1
                    self.state.last_candidate_connect_ms = now_ms
                    request_id = 1
                    LOG.info(
                        "Warm websocket connected for %d symbols (%d depth)",
                        len(symbols),
                        len(self._depth_symbols),
                    )
                    while not self._stopping.is_set():
                        if self._candidate_change.is_set():
                            self._candidate_change.clear()
                            desired = self._streams(self._warm_symbols, self._depth_symbols)
                            request_id = await self._update_subscriptions(
                                ws, streams, desired, request_id
                            )
                            streams = desired
                        try:
                            message = await asyncio.wait_for(ws.receive(), timeout=1)
                        except asyncio.TimeoutError:
                            continue
                        if message.type != aiohttp.WSMsgType.TEXT:
                            if message.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                                break
                            continue
                        payload = json.loads(message.data)
                        stream = str(payload.get("stream", ""))
                        data = payload.get("data", {})
                        symbol = stream.split("@", 1)[0].upper()
                        now_ms = int(time.time() * 1000)
                        if "@aggTrade" in stream:
                            self.state.on_agg_trade(symbol, data, now_ms)
                        elif "@bookTicker" in stream:
                            self.state.on_book_ticker(symbol, data, now_ms)
                        elif "@depth" in stream:
                            self.state.on_depth(symbol, data, now_ms)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                LOG.warning("Candidate websocket error: %s", exc)
            await asyncio.sleep(2)
