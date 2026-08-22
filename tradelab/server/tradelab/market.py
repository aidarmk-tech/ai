import asyncio
import json
import time
import uuid
from collections import defaultdict, deque

import httpx
from websockets.asyncio.client import connect as ws_connect

from .db import connect
from .strategies import Signal, StrategyEngine


MARKET_WS = "wss://fstream.binance.com/market/stream"
PUBLIC_WS = "wss://fstream.binance.com/public/stream"
OPEN_INTEREST_URL = "https://fapi.binance.com/fapi/v1/openInterest"
BOOTSTRAP_SYMBOLS = ["BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "DOGEUSDT"]


def now_ms() -> int:
    return int(time.time() * 1000)


def _f(value, default=0.0):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


class MarketRecorder:
    def __init__(self, settings):
        self.settings = settings
        self.tickers: dict[str, dict] = {}
        self.books: dict[str, dict] = {}
        self.marks: dict[str, dict] = {}
        self.depth_latest: dict[str, tuple] = {}
        self.depth_previous: dict[str, tuple[float, float]] = {}
        self.flow_buckets: dict[tuple[str, int], list] = {}
        self.history = defaultdict(lambda: deque(maxlen=900))
        self.flow_history = defaultdict(lambda: deque(maxlen=360))
        self.depth_history = defaultdict(lambda: deque(maxlen=360))
        self.universe = list(BOOTSTRAP_SYMBOLS)
        self.micro = list(BOOTSTRAP_SYMBOLS[: self.settings.microstructure_size])
        self.engine = StrategyEngine()
        self.last_market_event_ms = 0
        self.last_public_event_ms = 0
        self.last_sample_ms = 0
        self.started_at_ms = now_ms()
        self._cleanup_last_ms = 0
        self._health_pending: dict[str, tuple[str, int | None, str, int]] = {}

    async def run(self, stop: asyncio.Event) -> None:
        if not self.settings.market_enabled:
            self._health("market_recorder", "DISABLED", "TRADELAB_MARKET_ENABLED=false")
            self._flush_health()
            await stop.wait()
            return
        self._health("market_recorder", "STARTING", "public Binance USD-M data; shadow only")
        tasks = [
            asyncio.create_task(self._health_loop(stop)),
            asyncio.create_task(self._market_stream_loop(stop)),
            asyncio.create_task(self._public_stream_loop(stop)),
            asyncio.create_task(self._sample_loop(stop)),
            asyncio.create_task(self._universe_loop(stop)),
            asyncio.create_task(self._oi_loop(stop)),
            asyncio.create_task(self._paper_close_loop(stop)),
            asyncio.create_task(self._label_loop(stop)),
        ]
        try:
            await stop.wait()
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            self._health("market_recorder", "STOPPED", "shutdown")
            self._flush_health()

    def status(self) -> dict:
        return {
            "enabled": self.settings.market_enabled,
            "started_at_ms": self.started_at_ms,
            "last_market_event_ms": self.last_market_event_ms,
            "last_public_event_ms": self.last_public_event_ms,
            "last_sample_ms": self.last_sample_ms,
            "universe": self.universe,
            "microstructure_universe": self.micro,
            "universe_size": len(self.universe),
            "microstructure_size": len(self.micro),
            "live_trading": False,
            "participant_mode": "SHADOW_ONLY_FIXED_HORIZON",
        }

    def _health(self, component: str, status: str, detail: str = "", event_ms: int | None = None) -> None:
        self._health_pending[component] = (status, event_ms, detail[:1000], now_ms())

    def _flush_health(self) -> None:
        if not self._health_pending:
            return
        pending = list(self._health_pending.items())
        self._health_pending.clear()
        rows = [(component, status, event_ms, detail, updated) for component, (status, event_ms, detail, updated) in pending]
        try:
            with connect(self.settings.db_path) as conn:
                conn.executemany(
                    """INSERT INTO recorder_health(component,status,last_event_ms,detail,updated_at_ms)
                       VALUES(?,?,?,?,?)
                       ON CONFLICT(component) DO UPDATE SET status=excluded.status,
                       last_event_ms=excluded.last_event_ms,detail=excluded.detail,updated_at_ms=excluded.updated_at_ms""",
                    rows,
                )
        except Exception:
            for component, (status, event_ms, detail, updated) in pending:
                self._health_pending[component] = (status, event_ms, detail, updated)

    async def _health_loop(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            self._flush_health()
            try:
                await asyncio.wait_for(stop.wait(), timeout=1)
            except TimeoutError:
                pass

    @staticmethod
    def _payload(message):
        if isinstance(message, dict) and "data" in message:
            return message["data"]
        return message

    async def _market_stream_loop(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            symbols = list(dict.fromkeys(["BTCUSDT", "ETHUSDT"] + self.universe))
            streams = ["!ticker@arr", "!markPrice@arr@1s", "!forceOrder@arr"]
            streams += [f"{s.lower()}@aggTrade" for s in symbols]
            try:
                async with ws_connect(MARKET_WS, ping_interval=20, ping_timeout=20, max_size=8 * 1024 * 1024) as ws:
                    await ws.send(json.dumps({"method": "SUBSCRIBE", "params": streams, "id": uuid.uuid4().hex}))
                    self._health("binance_market_ws", "CONNECTED", f"{len(streams)} streams")
                    deadline = time.monotonic() + self.settings.subscription_refresh_seconds
                    while not stop.is_set() and time.monotonic() < deadline:
                        raw = await asyncio.wait_for(ws.recv(), timeout=35)
                        data = self._payload(json.loads(raw))
                        self.last_market_event_ms = now_ms()
                        self._handle_market(data)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self._health("binance_market_ws", "DEGRADED", repr(exc), self.last_market_event_ms or None)
                await asyncio.sleep(3)

    def _handle_market(self, data) -> None:
        if isinstance(data, list):
            for item in data:
                self._handle_market(item)
            return
        if not isinstance(data, dict) or "result" in data:
            return
        if data.get("st") not in (None, 1):
            return
        event = data.get("e")
        symbol = data.get("s")
        if event == "24hrTicker" and symbol and symbol.endswith("USDT"):
            self.tickers[symbol] = data
            return
        if event == "markPriceUpdate" and symbol and symbol.endswith("USDT"):
            self.marks[symbol] = data
            return
        if event == "aggTrade" and symbol in set(self.universe):
            price, qty = _f(data.get("p")), _f(data.get("q"))
            trade_ts = int(data.get("T") or data.get("E") or now_ms())
            bucket = trade_ts // 1000 * 1000
            key = (symbol, bucket)
            row = self.flow_buckets.setdefault(key, [0.0, 0.0, 0.0, 0.0, 0])
            notional = price * qty
            if bool(data.get("m")):
                row[1] += notional
                row[3] += qty
            else:
                row[0] += notional
                row[2] += qty
            row[4] += 1
            return
        if event == "forceOrder":
            order = data.get("o") or {}
            symbol = order.get("s")
            if not symbol or not symbol.endswith("USDT"):
                return
            qty = _f(order.get("z") or order.get("q"))
            price = _f(order.get("ap") or order.get("p"))
            ts = int(order.get("T") or data.get("E") or now_ms())
            with connect(self.settings.db_path) as conn:
                conn.execute(
                    "INSERT INTO liquidations(ts_ms,symbol,side,quantity,price,notional) VALUES(?,?,?,?,?,?)",
                    (ts, symbol, order.get("S", "UNKNOWN"), qty, price, qty * price),
                )

    async def _public_stream_loop(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            symbols = list(dict.fromkeys(self.micro))
            streams = ["!bookTicker"] + [f"{s.lower()}@depth10@500ms" for s in symbols]
            try:
                async with ws_connect(PUBLIC_WS, ping_interval=20, ping_timeout=20, max_size=8 * 1024 * 1024) as ws:
                    await ws.send(json.dumps({"method": "SUBSCRIBE", "params": streams, "id": uuid.uuid4().hex}))
                    self._health("binance_public_ws", "CONNECTED", f"{len(streams)} streams")
                    deadline = time.monotonic() + self.settings.subscription_refresh_seconds
                    while not stop.is_set() and time.monotonic() < deadline:
                        raw = await asyncio.wait_for(ws.recv(), timeout=35)
                        data = self._payload(json.loads(raw))
                        self.last_public_event_ms = now_ms()
                        self._handle_public(data)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self._health("binance_public_ws", "DEGRADED", repr(exc), self.last_public_event_ms or None)
                await asyncio.sleep(3)

    def _handle_public(self, data) -> None:
        if isinstance(data, list):
            for item in data:
                self._handle_public(item)
            return
        if not isinstance(data, dict) or "result" in data or data.get("st") not in (None, 1):
            return
        event = data.get("e")
        symbol = data.get("s")
        if not symbol or not symbol.endswith("USDT"):
            return
        if event == "bookTicker":
            self.books[symbol] = data
            return
        if event != "depthUpdate" or symbol not in set(self.micro):
            return
        bids = data.get("b") or []
        asks = data.get("a") or []
        if not bids or not asks:
            return
        bid_notional = sum(_f(x[0]) * _f(x[1]) for x in bids[:10] if len(x) >= 2)
        ask_notional = sum(_f(x[0]) * _f(x[1]) for x in asks[:10] if len(x) >= 2)
        best_bid, best_ask = _f(bids[0][0]), _f(asks[0][0])
        mid = (best_bid + best_ask) / 2 if best_bid and best_ask else 0
        spread_bps = ((best_ask - best_bid) / mid * 10000) if mid else None
        total = bid_notional + ask_notional
        imbalance = (bid_notional - ask_notional) / total if total else 0.0
        self.depth_latest[symbol] = (
            bid_notional, ask_notional, imbalance, spread_bps,
            _f(bids[0][1]), _f(asks[0][1]),
        )

    async def _universe_loop(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            try:
                ranked = []
                for symbol, ticker in self.tickers.items():
                    if not symbol.endswith("USDT") or ticker.get("st") not in (None, 1):
                        continue
                    ranked.append((_f(ticker.get("q")), symbol))
                ranked.sort(reverse=True)
                selected = [s for _, s in ranked[: self.settings.universe_size]]
                for required in ("BTCUSDT", "ETHUSDT"):
                    if required not in selected:
                        selected.insert(0, required)
                if selected:
                    self.universe = list(dict.fromkeys(selected))[: self.settings.universe_size]
                    self.micro = self.universe[: self.settings.microstructure_size]
                    self._health("universe", "OK", f"universe={len(self.universe)} micro={len(self.micro)}", now_ms())
            except Exception as exc:
                self._health("universe", "DEGRADED", repr(exc))
            try:
                await asyncio.wait_for(stop.wait(), timeout=60)
            except TimeoutError:
                pass

    async def _sample_loop(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            tick = now_ms() // 1000 * 1000
            try:
                self._flush_flow(tick)
                self._sample_depth(tick)
                if (tick // 1000) % self.settings.market_sample_seconds == 0:
                    self._sample_market(tick)
                    self._evaluate_strategies(tick)
                    self.last_sample_ms = tick
                    self._health("market_sampler", "OK", f"universe={len(self.universe)}", tick)
                if tick - self._cleanup_last_ms >= 3600_000:
                    self._cleanup_raw(tick)
                    self._cleanup_last_ms = tick
            except Exception as exc:
                self._health("market_sampler", "DEGRADED", repr(exc), self.last_sample_ms or None)
            try:
                await asyncio.wait_for(stop.wait(), timeout=1)
            except TimeoutError:
                pass

    def _flush_flow(self, current_second_ms: int) -> None:
        ready = [(k, v) for k, v in self.flow_buckets.items() if k[1] < current_second_ms]
        if not ready:
            return
        rows = []
        for (symbol, ts), values in ready:
            buy_notional, sell_notional, buy_qty, sell_qty, count = values
            rows.append((ts, symbol, buy_notional, sell_notional, buy_qty, sell_qty, count))
            self.flow_history[symbol].append((ts, buy_notional, sell_notional))
            self.flow_buckets.pop((symbol, ts), None)
        with connect(self.settings.db_path) as conn:
            conn.executemany(
                """INSERT OR REPLACE INTO flow_samples
                   (ts_ms,symbol,buy_notional,sell_notional,buy_qty,sell_qty,agg_trades)
                   VALUES(?,?,?,?,?,?,?)""", rows,
            )

    def _sample_depth(self, ts: int) -> None:
        rows = []
        for symbol in self.micro:
            depth = self.depth_latest.get(symbol)
            if depth is None:
                continue
            bid_n, ask_n, imbalance, spread, best_bid_qty, best_ask_qty = depth
            prev_bid, prev_ask = self.depth_previous.get(symbol, (bid_n, ask_n))
            bid_rep = (bid_n - prev_bid) / prev_bid if prev_bid > 0 else 0.0
            ask_rep = (ask_n - prev_ask) / prev_ask if prev_ask > 0 else 0.0
            bid_rep, ask_rep = max(-5.0, min(5.0, bid_rep)), max(-5.0, min(5.0, ask_rep))
            self.depth_previous[symbol] = (bid_n, ask_n)
            rows.append((ts, symbol, spread, bid_n, ask_n, imbalance, best_bid_qty, best_ask_qty, bid_rep, ask_rep))
            self.depth_history[symbol].append((ts, imbalance, bid_rep, ask_rep, spread or 0.0))
        if rows:
            with connect(self.settings.db_path) as conn:
                conn.executemany(
                    """INSERT OR REPLACE INTO depth_samples
                       (ts_ms,symbol,spread_bps,bid_notional_10,ask_notional_10,imbalance,
                        best_bid_qty,best_ask_qty,bid_replenishment,ask_replenishment)
                       VALUES(?,?,?,?,?,?,?,?,?,?)""", rows,
                )

    def _sample_market(self, ts: int) -> None:
        rows = []
        for symbol in self.universe:
            ticker = self.tickers.get(symbol)
            if not ticker:
                continue
            last = _f(ticker.get("c"))
            if last <= 0:
                continue
            book = self.books.get(symbol, {})
            mark = self.marks.get(symbol, {})
            row = (
                ts, symbol, last, _f(book.get("b"), None), _f(book.get("a"), None),
                _f(book.get("B"), None), _f(book.get("A"), None), _f(mark.get("p"), None),
                _f(mark.get("i"), None), _f(mark.get("r"), None), _f(ticker.get("q"), None),
                int(ticker.get("n") or 0),
            )
            rows.append(row)
            self.history[symbol].append((ts, last))
        if rows:
            with connect(self.settings.db_path) as conn:
                conn.executemany(
                    """INSERT OR REPLACE INTO market_samples
                       (ts_ms,symbol,last_price,bid,ask,bid_qty,ask_qty,mark_price,index_price,
                        funding_rate,quote_volume_24h,trade_count_24h)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""", rows,
                )

    def _evaluate_strategies(self, ts: int) -> None:
        signals = self.engine.evaluate(
            ts, self.universe, self.micro, self.history, self.flow_history,
            self.depth_history, self.tickers,
        )
        for signal in signals:
            self._record_signal(signal, ts)

    def _record_signal(self, signal: Signal, ts: int) -> None:
        payload = {
            "spec_mode": "FROZEN_SHADOW",
            "score": signal.score,
            "horizon_seconds": signal.horizon_seconds,
            "features": signal.features,
            "symbol_a": signal.symbol_a,
            "symbol_b": signal.symbol_b,
            "side_a": signal.side_a,
            "side_b": signal.side_b,
            "hedge_ratio": signal.hedge_ratio,
        }
        text = json.dumps(payload, separators=(",", ":"), sort_keys=True)
        with connect(self.settings.db_path) as conn:
            conn.execute(
                "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
                (ts, signal.participant_id, signal.key, "SIGNAL", text),
            )
            if signal.symbol_b is None:
                conn.execute(
                    "INSERT INTO market_states(ts_ms,symbol,source,payload_json) VALUES(?,?,?,?)",
                    (ts, signal.symbol_a, signal.participant_id, text),
                )
        self._open_paper(signal, ts, text)

    def _latest_price(self, symbol: str) -> float | None:
        book = self.books.get(symbol)
        if book:
            bid, ask = _f(book.get("b")), _f(book.get("a"))
            if bid > 0 and ask > 0:
                return (bid + ask) / 2
        ticker = self.tickers.get(symbol)
        price = _f(ticker.get("c")) if ticker else 0
        return price if price > 0 else None

    def _open_paper(self, signal: Signal, ts: int, signal_json: str) -> None:
        a = self._latest_price(signal.symbol_a)
        b = self._latest_price(signal.symbol_b) if signal.symbol_b else None
        if a is None or (signal.symbol_b and b is None):
            return
        with connect(self.settings.db_path) as conn:
            open_count = conn.execute(
                "SELECT COUNT(*) FROM paper_trades WHERE participant_id=? AND status='OPEN'",
                (signal.participant_id,),
            ).fetchone()[0]
            duplicate = conn.execute(
                """SELECT COUNT(*) FROM paper_trades WHERE participant_id=? AND status='OPEN'
                   AND symbol_a=? AND COALESCE(symbol_b,'')=COALESCE(?, '')""",
                (signal.participant_id, signal.symbol_a, signal.symbol_b),
            ).fetchone()[0]
            if open_count >= self.settings.paper_max_open or duplicate:
                conn.execute(
                    "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
                    (ts, signal.participant_id, signal.key, "PAPER_SKIPPED_CAPACITY", signal_json),
                )
                return
            equity = conn.execute("SELECT equity FROM participants WHERE participant_id=?", (signal.participant_id,)).fetchone()[0]
            notional = max(1.0, min(self.settings.paper_starting_notional_usdt, equity / max(1, self.settings.paper_max_open)))
            conn.execute(
                """INSERT INTO paper_trades
                   (trade_id,participant_id,symbol_a,symbol_b,side_a,side_b,hedge_ratio,opened_at_ms,
                    entry_a,entry_b,exit_due_ms,notional_usdt,status,signal_json)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?, 'OPEN', ?)""",
                (uuid.uuid4().hex, signal.participant_id, signal.symbol_a, signal.symbol_b,
                 signal.side_a, signal.side_b, signal.hedge_ratio, ts, a, b,
                 ts + signal.horizon_seconds * 1000, notional, signal_json),
            )
            conn.execute(
                "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
                (ts, signal.participant_id, signal.key, "PAPER_OPEN", signal_json),
            )

    async def _paper_close_loop(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            try:
                self._close_due_paper(now_ms())
            except Exception as exc:
                self._health("paper_book", "DEGRADED", repr(exc))
            try:
                await asyncio.wait_for(stop.wait(), timeout=1)
            except TimeoutError:
                pass

    @staticmethod
    def _directional_return(side: str, entry: float, exit_price: float) -> float:
        raw = exit_price / entry - 1.0
        return raw if side == "LONG" else -raw

    def _close_due_paper(self, ts: int) -> None:
        closed = 0
        with connect(self.settings.db_path) as conn:
            trades = conn.execute(
                "SELECT * FROM paper_trades WHERE status='OPEN' AND exit_due_ms<=? ORDER BY exit_due_ms LIMIT 100",
                (ts,),
            ).fetchall()
            for trade in trades:
                exit_a = self._latest_price(trade["symbol_a"])
                exit_b = self._latest_price(trade["symbol_b"]) if trade["symbol_b"] else None
                if exit_a is None or (trade["symbol_b"] and exit_b is None):
                    continue
                ra = self._directional_return(trade["side_a"], trade["entry_a"], exit_a)
                if trade["symbol_b"]:
                    rb = self._directional_return(trade["side_b"], trade["entry_b"], exit_b)
                    beta = abs(trade["hedge_ratio"] or 1.0)
                    wa, wb = 1.0 / (1.0 + beta), beta / (1.0 + beta)
                    gross = wa * ra + wb * rb
                else:
                    gross = ra
                roundtrip_cost = 2 * (self.settings.paper_fee_bps_per_side + self.settings.paper_slippage_bps_per_side) / 10000.0
                net = gross - roundtrip_cost
                pnl = trade["notional_usdt"] * net
                conn.execute(
                    """UPDATE paper_trades SET status='CLOSED',closed_at_ms=?,exit_a=?,exit_b=?,
                       gross_return_pct=?,net_return_pct=?,pnl_usdt=? WHERE trade_id=?""",
                    (ts, exit_a, exit_b, gross * 100, net * 100, pnl, trade["trade_id"]),
                )
                conn.execute("UPDATE participants SET equity=equity+? WHERE participant_id=?", (pnl, trade["participant_id"]))
                display = trade["symbol_a"] if not trade["symbol_b"] else f"{trade['symbol_a']}|{trade['symbol_b']}"
                conn.execute(
                    "INSERT INTO participant_events(ts_ms,participant_id,symbol,event_type,payload_json) VALUES(?,?,?,?,?)",
                    (ts, trade["participant_id"], display, "PAPER_CLOSE",
                     json.dumps({"trade_id": trade["trade_id"], "gross_return_pct": gross * 100, "net_return_pct": net * 100, "pnl_usdt": pnl})),
                )
                closed += 1
            if closed:
                rows = conn.execute("SELECT participant_id,equity FROM participants ORDER BY equity DESC").fetchall()
                for rank, row in enumerate(rows, 1):
                    conn.execute("UPDATE participants SET rank=? WHERE participant_id=?", (rank, row["participant_id"]))
        if closed:
            self._health("paper_book", "OK", f"closed={closed}", ts)

    async def _oi_loop(self, stop: asyncio.Event) -> None:
        async with httpx.AsyncClient(timeout=10.0, headers={"User-Agent": "TradeLab/0.2"}) as client:
            while not stop.is_set():
                sem = asyncio.Semaphore(4)

                async def fetch(symbol):
                    async with sem:
                        try:
                            r = await client.get(OPEN_INTEREST_URL, params={"symbol": symbol})
                            r.raise_for_status()
                            data = r.json()
                            oi = _f(data.get("openInterest"))
                            price = self._latest_price(symbol) or 0.0
                            return (int(data.get("time") or now_ms()), symbol, oi, oi * price)
                        except Exception:
                            return None

                results = await asyncio.gather(*(fetch(s) for s in self.micro), return_exceptions=False)
                rows = [x for x in results if x]
                if rows:
                    with connect(self.settings.db_path) as conn:
                        conn.executemany(
                            "INSERT OR REPLACE INTO open_interest_samples(ts_ms,symbol,open_interest,open_interest_value) VALUES(?,?,?,?)",
                            rows,
                        )
                    self._health("open_interest", "OK", f"symbols={len(rows)}", max(r[0] for r in rows))
                try:
                    await asyncio.wait_for(stop.wait(), timeout=self.settings.oi_interval_seconds)
                except TimeoutError:
                    pass

    async def _label_loop(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            try:
                processed = self._label_ready_states(now_ms())
                if processed:
                    self._health("forward_labels", "OK", f"processed={processed}", now_ms())
            except Exception as exc:
                self._health("forward_labels", "DEGRADED", repr(exc))
            try:
                await asyncio.wait_for(stop.wait(), timeout=10)
            except TimeoutError:
                pass

    def _label_ready_states(self, ts: int) -> int:
        processed = 0
        with connect(self.settings.db_path) as conn:
            states = conn.execute(
                """SELECT m.id,m.ts_ms,m.symbol FROM market_states m
                   LEFT JOIN forward_labels f ON f.market_state_id=m.id
                   WHERE f.market_state_id IS NULL AND m.ts_ms<=? ORDER BY m.ts_ms LIMIT 100""",
                (ts - 300_000,),
            ).fetchall()
            for state in states:
                points = conn.execute(
                    "SELECT ts_ms,last_price FROM market_samples WHERE symbol=? AND ts_ms BETWEEN ? AND ? ORDER BY ts_ms",
                    (state["symbol"], state["ts_ms"] - 5_000, state["ts_ms"] + 305_000),
                ).fetchall()
                if not points:
                    continue
                base = min(points, key=lambda x: abs(x["ts_ms"] - state["ts_ms"]))["last_price"]
                future = [(p["ts_ms"], p["last_price"]) for p in points if p["ts_ms"] >= state["ts_ms"]]
                if base <= 0 or not future:
                    continue

                def r_at(sec):
                    target = state["ts_ms"] + sec * 1000
                    p = min(future, key=lambda x: abs(x[0] - target))[1]
                    return (p / base - 1.0) * 100

                def extrema(sec):
                    end = state["ts_ms"] + sec * 1000
                    vals = [(p / base - 1.0) * 100 for t, p in future if t <= end]
                    return (max(vals), min(vals)) if vals else (None, None)

                m30, a30 = extrema(30)
                m60, a60 = extrema(60)
                m300, a300 = extrema(300)
                conn.execute(
                    """INSERT OR REPLACE INTO forward_labels
                       (market_state_id,ret_5s,ret_15s,ret_30s,ret_60s,ret_120s,ret_300s,
                        mfe_30s,mae_30s,mfe_60s,mae_60s,mfe_300s,mae_300s)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (state["id"], r_at(5), r_at(15), r_at(30), r_at(60), r_at(120), r_at(300),
                     m30, a30, m60, a60, m300, a300),
                )
                processed += 1
        return processed

    def _cleanup_raw(self, ts: int) -> None:
        cutoff = ts - self.settings.raw_retention_hours * 3600_000
        with connect(self.settings.db_path) as conn:
            for table in ("market_samples", "flow_samples", "depth_samples", "open_interest_samples", "liquidations"):
                conn.execute(f"DELETE FROM {table} WHERE ts_ms<?", (cutoff,))
        self._health("retention", "OK", f"raw_hours={self.settings.raw_retention_hours}", ts)


def participant_stats(db_path) -> list[dict]:
    with connect(db_path) as conn:
        rows = conn.execute(
            """SELECT p.participant_id,p.display_name,p.equity,p.rank,p.role,
                      COUNT(t.trade_id) AS trades,
                      SUM(CASE WHEN t.status='CLOSED' THEN 1 ELSE 0 END) AS closed_trades,
                      SUM(CASE WHEN t.status='OPEN' THEN 1 ELSE 0 END) AS open_trades,
                      COALESCE(SUM(CASE WHEN t.status='CLOSED' THEN t.pnl_usdt END),0) AS net_pnl_usdt,
                      AVG(CASE WHEN t.status='CLOSED' THEN t.net_return_pct END) AS mean_net_return_pct,
                      SUM(CASE WHEN t.status='CLOSED' AND t.net_return_pct>0 THEN 1 ELSE 0 END) AS winners
               FROM participants p LEFT JOIN paper_trades t USING(participant_id)
               GROUP BY p.participant_id ORDER BY COALESCE(p.rank,999),p.participant_id"""
        ).fetchall()
    return [dict(r) for r in rows]


def recorder_health(db_path) -> list[dict]:
    with connect(db_path) as conn:
        rows = conn.execute("SELECT * FROM recorder_health ORDER BY component").fetchall()
    return [dict(r) for r in rows]
