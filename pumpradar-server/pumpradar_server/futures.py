from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from dataclasses import dataclass
from typing import Any, Optional

import aiohttp

from .config import Settings
from .market import MarketState, SymbolInfo
from .models import EvaluatedCandidate
from .storage import Storage

LOG = logging.getLogger(__name__)


@dataclass
class MarkRow:
    mark_price: float = 0.0
    funding_rate: float = 0.0
    next_funding_time_ms: int = 0
    updated_at_ms: int = 0


class FuturesMarketState(MarketState):
    """Independent USD-M state; no spot price or flow is reused."""

    def __init__(self) -> None:
        super().__init__()
        self.marks: dict[str, MarkRow] = {}

    def set_universe(self, symbols: list[SymbolInfo]) -> None:
        super().set_universe(symbols)
        keep = set(self.universe)
        self.marks = {k: v for k, v in self.marks.items() if k in keep}

    def retain_detailed_symbols(self, symbols: set[str]) -> None:
        super().retain_detailed_symbols(symbols)
        self.marks = {k: v for k, v in self.marks.items() if k in symbols}

    def reset_candidate_stream_state(self, symbols: set[str]) -> None:
        super().reset_candidate_stream_state(symbols)
        for symbol in symbols:
            self.marks.pop(symbol, None)

    def on_mark_price(self, symbol: str, data: dict[str, Any], now_ms: int) -> None:
        try:
            mark = float(data.get("p", 0.0))
            rate = float(data.get("r", 0.0))
            next_time = int(data.get("T", 0))
        except (TypeError, ValueError):
            return
        if mark <= 0:
            return
        self.marks[symbol] = MarkRow(mark, rate, next_time, now_ms)
        self.last_candidate_message_ms = now_ms

    def mark_info(self, symbol: str) -> MarkRow:
        return self.marks.get(symbol, MarkRow())


class FuturesFeed:
    """Public Binance USD-M market data only; never submits orders."""

    def __init__(self, settings: Settings, state: FuturesMarketState) -> None:
        self.settings = settings
        self.state = state
        self.session: Optional[aiohttp.ClientSession] = None
        self._warm_symbols: tuple[str, ...] = ()
        self._depth_symbols: tuple[str, ...] = ()
        self._candidate_change = asyncio.Event()
        self._candidate_version = 0
        self._stopping = asyncio.Event()

    async def start(self) -> None:
        self.session = aiohttp.ClientSession(
            timeout=aiohttp.ClientTimeout(total=20),
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
        async with self.session.get(
            f"{self.settings.futures_rest_url}/fapi/v1/exchangeInfo"
        ) as response:
            response.raise_for_status()
            payload = await response.json()
        symbols: list[SymbolInfo] = []
        for raw in payload.get("symbols", []):
            symbol = str(raw.get("symbol", ""))
            if raw.get("quoteAsset") != "USDT" or raw.get("status") != "TRADING":
                continue
            if raw.get("contractType") != "PERPETUAL" or not symbol.endswith("USDT"):
                continue
            symbols.append(SymbolInfo(symbol, "USDT", "TRADING", False))
        self.state.set_universe(symbols)
        LOG.info("Loaded %d USD-M perpetual symbols", len(symbols))

    def set_candidate_symbols(
        self, warm_symbols: list[str], depth_symbols: Optional[list[str]] = None
    ) -> None:
        allowed = set(self.state.universe)
        warm = tuple(sorted(set(warm_symbols) & allowed))
        depth = tuple(sorted(set(depth_symbols or ()) & set(warm)))
        if warm != self._warm_symbols or depth != self._depth_symbols:
            self._warm_symbols = warm
            self._depth_symbols = depth
            self._candidate_version += 1
            self._candidate_change.set()

    @staticmethod
    def _market_streams(warm_symbols: tuple[str, ...]) -> set[str]:
        streams: set[str] = set()
        for symbol in warm_symbols:
            low = symbol.lower()
            streams.add(f"{low}@aggTrade")
            streams.add(f"{low}@markPrice@1s")
        return streams

    @staticmethod
    def _public_streams(
        warm_symbols: tuple[str, ...],
        depth_symbols: tuple[str, ...],
    ) -> set[str]:
        depth = set(depth_symbols)
        streams: set[str] = set()
        for symbol in warm_symbols:
            low = symbol.lower()
            streams.add(f"{low}@bookTicker")
            if symbol in depth:
                streams.add(f"{low}@depth20@100ms")
        return streams

    async def market_loop(self) -> None:
        assert self.session
        url = f"{self.settings.futures_ws_url}/market/ws/!miniTicker@arr"
        while not self._stopping.is_set():
            try:
                async with self.session.ws_connect(url, heartbeat=30, receive_timeout=90) as ws:
                    LOG.info("Futures market websocket connected")
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
                LOG.warning("Futures market websocket error: %s", exc)
            await asyncio.sleep(3)

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

    async def _candidate_group(self, endpoint: str) -> None:
        assert self.session
        while not self._stopping.is_set():
            symbols = self._warm_symbols
            if not symbols:
                await asyncio.sleep(1)
                continue
            version = self._candidate_version
            streams = (
                self._market_streams(symbols)
                if endpoint == "market"
                else self._public_streams(symbols, self._depth_symbols)
            )
            if not streams:
                await asyncio.sleep(1)
                continue
            url = (
                f"{self.settings.futures_ws_url}/{endpoint}/stream"
                f"?streams={'/'.join(sorted(streams))}"
            )
            try:
                async with self.session.ws_connect(url, heartbeat=30, receive_timeout=90) as ws:
                    now_ms = int(time.time() * 1000)
                    if endpoint == "market":
                        self.state.reset_candidate_stream_state(set(symbols))
                    self.state.candidate_connection_count += 1
                    self.state.last_candidate_connect_ms = now_ms
                    LOG.info(
                        "Futures %s websocket connected for %d symbols (%d depth)",
                        endpoint,
                        len(symbols),
                        len(self._depth_symbols),
                    )
                    request_id = 1
                    while not self._stopping.is_set():
                        if version != self._candidate_version:
                            desired = (
                                self._market_streams(self._warm_symbols)
                                if endpoint == "market"
                                else self._public_streams(
                                    self._warm_symbols, self._depth_symbols
                                )
                            )
                            request_id = await self._update_subscriptions(
                                ws, streams, desired, request_id
                            )
                            streams = desired
                            version = self._candidate_version
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
                        elif "@markPrice" in stream:
                            self.state.on_mark_price(symbol, data, now_ms)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                LOG.warning("Futures %s websocket error: %s", endpoint, exc)
            await asyncio.sleep(2)

    async def candidate_loop(self) -> None:
        await asyncio.gather(
            self._candidate_group("market"),
            self._candidate_group("public"),
        )


FUTURES_SCHEMA = """
CREATE TABLE IF NOT EXISTS futures_signals (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  signal_channel TEXT NOT NULL,
  symbol TEXT NOT NULL,
  episode_id TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  return_5m REAL,
  return_10m REAL,
  quote_volume_24h REAL NOT NULL,
  quote_volume_30s REAL NOT NULL,
  taker_buy_ratio_30s REAL,
  taker_buy_ratio_15s REAL,
  taker_buy_ratio_5s REAL,
  cvd_30s REAL NOT NULL,
  cvd_slope REAL NOT NULL,
  spread_bps REAL,
  buy_slippage_percent REAL,
  sell_slippage_percent REAL,
  entry_vwap REAL,
  mark_price REAL,
  funding_rate REAL,
  next_funding_time_ms INTEGER,
  algorithm_version TEXT NOT NULL,
  strategy_version TEXT NOT NULL,
  config_hash TEXT NOT NULL,
  UNIQUE(run_id, episode_id, signal_channel)
);
CREATE INDEX IF NOT EXISTS idx_futures_signals_time
  ON futures_signals(created_at_ms);
CREATE TABLE IF NOT EXISTS futures_momentum_slots (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  source_signal_id TEXT,
  signal_channel TEXT NOT NULL,
  episode_id TEXT NOT NULL,
  symbol TEXT NOT NULL,
  opened_at_ms INTEGER NOT NULL,
  entry_best_ask REAL NOT NULL,
  entry_vwap REAL NOT NULL,
  position_usdt REAL NOT NULL,
  quantity REAL NOT NULL,
  entry_fee_usdt REAL NOT NULL,
  entry_mark_price REAL,
  funding_rate REAL NOT NULL DEFAULT 0,
  next_funding_time_ms INTEGER NOT NULL DEFAULT 0,
  funding_usdt REAL NOT NULL DEFAULT 0,
  primary_status TEXT NOT NULL,
  primary_closed_at_ms INTEGER,
  primary_exit_reason TEXT,
  primary_exit_vwap REAL,
  primary_gross_return_percent REAL,
  primary_net_return_percent REAL,
  max_executable_return_percent REAL NOT NULL DEFAULT 0,
  min_executable_return_percent REAL NOT NULL DEFAULT 0,
  last_updated_at_ms INTEGER NOT NULL,
  algorithm_version TEXT NOT NULL,
  strategy_version TEXT NOT NULL,
  config_hash TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_futures_slots_open
  ON futures_momentum_slots(primary_status, opened_at_ms);
CREATE TABLE IF NOT EXISTS futures_momentum_policy_runs (
  id TEXT PRIMARY KEY,
  slot_id TEXT NOT NULL REFERENCES futures_momentum_slots(id),
  policy TEXT NOT NULL,
  state TEXT NOT NULL,
  activated_at_ms INTEGER,
  peak_return_percent REAL NOT NULL DEFAULT 0,
  closed_at_ms INTEGER,
  exit_reason TEXT,
  exit_vwap REAL,
  exit_fee_usdt REAL NOT NULL DEFAULT 0,
  funding_usdt REAL NOT NULL DEFAULT 0,
  gross_return_percent REAL,
  net_return_percent REAL,
  UNIQUE(slot_id, policy)
);
CREATE INDEX IF NOT EXISTS idx_futures_policy_open
  ON futures_momentum_policy_runs(state);
"""


class FuturesPaperStore:
    def __init__(self, settings: Settings, storage: Storage) -> None:
        self.settings = settings
        self.storage = storage
        self.conn = storage.conn
        self.lock = storage.lock
        with self.lock:
            self.conn.executescript(FUTURES_SCHEMA)
            self.conn.commit()

    def recover_incompatible(self, now_ms: int) -> None:
        with self.lock:
            rows = list(
                self.conn.execute(
                    """SELECT id FROM futures_momentum_slots
                       WHERE primary_status='OPEN' AND
                       (algorithm_version<>? OR strategy_version<>? OR config_hash<>?)""",
                    (
                        self.settings.algorithm_version,
                        self.settings.strategy_version,
                        self.settings.config_hash(),
                    ),
                )
            )
            for row in rows:
                self.conn.execute(
                    """UPDATE futures_momentum_slots SET primary_status='CLOSED',
                       primary_closed_at_ms=?,primary_exit_reason='CONFIG_CHANGED',
                       last_updated_at_ms=? WHERE id=?""",
                    (now_ms, now_ms, row["id"]),
                )
                self.conn.execute(
                    """UPDATE futures_momentum_policy_runs SET state='CLOSED',
                       closed_at_ms=?,exit_reason='CONFIG_CHANGED'
                       WHERE slot_id=? AND state='OPEN'""",
                    (now_ms, row["id"]),
                )
            self.conn.commit()

    def insert_signal(
        self,
        channel: str,
        item: EvaluatedCandidate,
        episode_id: str,
        now_ms: int,
        mark: MarkRow,
    ) -> str:
        with self.lock:
            existing = self.conn.execute(
                """SELECT id FROM futures_signals
                   WHERE run_id=? AND episode_id=? AND signal_channel=?""",
                (self.storage.run_id, episode_id, channel),
            ).fetchone()
            if existing:
                return str(existing["id"])
            signal_id = str(uuid.uuid4())
            self.conn.execute(
                """INSERT INTO futures_signals(
                  id,run_id,signal_channel,symbol,episode_id,created_at_ms,
                  return_5m,return_10m,quote_volume_24h,quote_volume_30s,
                  taker_buy_ratio_30s,taker_buy_ratio_15s,taker_buy_ratio_5s,
                  cvd_30s,cvd_slope,spread_bps,buy_slippage_percent,
                  sell_slippage_percent,entry_vwap,mark_price,funding_rate,
                  next_funding_time_ms,algorithm_version,strategy_version,config_hash
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    signal_id,
                    self.storage.run_id,
                    channel,
                    item.candidate.symbol,
                    episode_id,
                    now_ms,
                    item.candidate.return_5m,
                    item.candidate.return_10m,
                    item.candidate.quote_volume_24h,
                    item.flow.quote_volume_30s,
                    item.flow.taker_buy_ratio_30s,
                    item.flow.taker_buy_ratio_15s,
                    item.flow.taker_buy_ratio_5s,
                    item.flow.cvd_30s,
                    item.flow.cvd_slope,
                    item.book.spread_bps,
                    item.book.buy_slippage_percent,
                    item.book.sell_slippage_percent,
                    item.book.buy_vwap,
                    mark.mark_price or None,
                    mark.funding_rate,
                    mark.next_funding_time_ms or None,
                    self.settings.algorithm_version,
                    self.settings.strategy_version,
                    self.settings.config_hash(),
                ),
            )
            self.conn.commit()
            return signal_id

    def create_slot(
        self,
        signal_id: str,
        channel: str,
        item: EvaluatedCandidate,
        episode_id: str,
        now_ms: int,
        mark: MarkRow,
    ) -> str:
        if item.book.buy_vwap is None or item.book.best_ask is None:
            raise ValueError("executable futures entry is required")
        slot_id = str(uuid.uuid4())
        qty = self.settings.position_usdt / item.book.buy_vwap
        fee = self.settings.position_usdt * self.settings.futures_fee_rate
        with self.lock:
            self.conn.execute(
                """INSERT INTO futures_momentum_slots(
                  id,run_id,source_signal_id,signal_channel,episode_id,symbol,
                  opened_at_ms,entry_best_ask,entry_vwap,position_usdt,quantity,
                  entry_fee_usdt,entry_mark_price,funding_rate,next_funding_time_ms,
                  primary_status,last_updated_at_ms,algorithm_version,
                  strategy_version,config_hash
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?,?,?)""",
                (
                    slot_id,
                    self.storage.run_id,
                    signal_id,
                    channel,
                    episode_id,
                    item.candidate.symbol,
                    now_ms,
                    item.book.best_ask,
                    item.book.buy_vwap,
                    self.settings.position_usdt,
                    qty,
                    fee,
                    mark.mark_price or None,
                    mark.funding_rate,
                    mark.next_funding_time_ms,
                    now_ms,
                    self.settings.algorithm_version,
                    self.settings.strategy_version,
                    self.settings.config_hash(),
                ),
            )
            for policy in ("MC_HOLD_120", "MC_TRAIL_1P0", "MC_FIXED_TP4"):
                self.conn.execute(
                    """INSERT INTO futures_momentum_policy_runs
                       (id,slot_id,policy,state) VALUES(?,?,?,'OPEN')""",
                    (str(uuid.uuid4()), slot_id, policy),
                )
            self.conn.commit()
        return slot_id

    def open_slot(self):
        with self.lock:
            return self.conn.execute(
                """SELECT * FROM futures_momentum_slots
                   WHERE primary_status='OPEN' ORDER BY opened_at_ms LIMIT 1"""
            ).fetchone()

    def last_slot(self, symbol: str):
        with self.lock:
            return self.conn.execute(
                """SELECT * FROM futures_momentum_slots
                   WHERE symbol=? ORDER BY opened_at_ms DESC LIMIT 1""",
                (symbol,),
            ).fetchone()

    def policy_slots(self):
        with self.lock:
            return list(
                self.conn.execute(
                    """SELECT * FROM futures_momentum_slots WHERE id IN (
                         SELECT DISTINCT slot_id FROM futures_momentum_policy_runs
                         WHERE state='OPEN'
                       ) ORDER BY opened_at_ms"""
                )
            )

    def policies(self, slot_id: str):
        with self.lock:
            return list(
                self.conn.execute(
                    """SELECT * FROM futures_momentum_policy_runs
                       WHERE slot_id=? ORDER BY policy""",
                    (slot_id,),
                )
            )

    def pending_symbols(self) -> set[str]:
        with self.lock:
            return {
                str(row["symbol"])
                for row in self.conn.execute(
                    """SELECT DISTINCT symbol FROM futures_momentum_slots
                       WHERE id IN (
                         SELECT DISTINCT slot_id FROM futures_momentum_policy_runs
                         WHERE state='OPEN'
                       )"""
                )
            }

    def update_slot(self, slot_id: str, current: float, now_ms: int) -> None:
        with self.lock:
            self.conn.execute(
                """UPDATE futures_momentum_slots SET
                   max_executable_return_percent=MAX(max_executable_return_percent,?),
                   min_executable_return_percent=MIN(min_executable_return_percent,?),
                   last_updated_at_ms=? WHERE id=?""",
                (current, current, now_ms, slot_id),
            )
            self.conn.commit()

    def update_policy(self, policy_id: str, **fields: Any) -> None:
        if not fields:
            return
        assignments = ",".join(f"{key}=?" for key in fields)
        with self.lock:
            self.conn.execute(
                f"UPDATE futures_momentum_policy_runs SET {assignments} WHERE id=?",
                (*fields.values(), policy_id),
            )
            self.conn.commit()

    def close_primary(
        self,
        slot_id: str,
        now_ms: int,
        reason: str,
        price: float,
        gross: float,
        net: float,
        funding: float,
    ) -> None:
        with self.lock:
            self.conn.execute(
                """UPDATE futures_momentum_slots SET primary_status='CLOSED',
                   primary_closed_at_ms=?,primary_exit_reason=?,primary_exit_vwap=?,
                   primary_gross_return_percent=?,primary_net_return_percent=?,
                   funding_usdt=?,last_updated_at_ms=? WHERE id=?""",
                (now_ms, reason, price, gross, net, funding, now_ms, slot_id),
            )
            self.conn.commit()

    def status(self) -> dict[str, Any]:
        with self.lock:
            total = int(
                self.conn.execute(
                    "SELECT COUNT(*) FROM futures_momentum_slots"
                ).fetchone()[0]
            )
            opened = int(
                self.conn.execute(
                    """SELECT COUNT(*) FROM futures_momentum_slots
                       WHERE primary_status='OPEN'"""
                ).fetchone()[0]
            )
            futures_closed = int(
                self.conn.execute(
                    """SELECT COUNT(*) FROM futures_momentum_slots
                       WHERE primary_status='CLOSED' AND algorithm_version=?""",
                    (self.settings.algorithm_version,),
                ).fetchone()[0]
            )
            spot_closed = int(
                self.conn.execute(
                    """SELECT COUNT(*) FROM momentum_slots
                       WHERE primary_status='CLOSED' AND algorithm_version=?""",
                    (self.settings.algorithm_version,),
                ).fetchone()[0]
            )
            spot_by = {
                str(row[0]): {"n": int(row[1]), "avg_net": float(row[2] or 0)}
                for row in self.conn.execute(
                    """SELECT signal_channel,COUNT(*),AVG(primary_net_return_percent)
                       FROM momentum_slots WHERE primary_status='CLOSED'
                       GROUP BY signal_channel"""
                )
            }
            futures_by = {
                str(row[0]): {"n": int(row[1]), "avg_net": float(row[2] or 0)}
                for row in self.conn.execute(
                    """SELECT signal_channel,COUNT(*),AVG(primary_net_return_percent)
                       FROM futures_momentum_slots WHERE primary_status='CLOSED'
                       GROUP BY signal_channel"""
                )
            }
        closed = spot_closed + futures_closed
        return {
            "futures_paper_slots": total,
            "futures_paper_open": opened,
            "spot_closed_by_channel": spot_by,
            "futures_closed_by_channel": futures_by,
            "freeze_target_primary_trades": self.settings.freeze_target_primary_trades,
            "freeze_closed_primary_trades": closed,
            "freeze_remaining_primary_trades": max(
                0, self.settings.freeze_target_primary_trades - closed
            ),
        }


class FuturesPaperManager:
    def __init__(
        self,
        settings: Settings,
        state: FuturesMarketState,
        store: FuturesPaperStore,
        notify,
    ) -> None:
        self.settings = settings
        self.state = state
        self.store = store
        self.notify = notify

    def repeat_blocked(self, symbol: str, now_ms: int) -> bool:
        previous = self.store.last_slot(symbol)
        return bool(
            previous
            and now_ms - int(previous["opened_at_ms"])
            < self.settings.momentum_repeat_symbol_minutes * 60_000
        )

    async def consider(
        self,
        item: EvaluatedCandidate,
        signal_id: str,
        now_ms: int,
        episode_id: str,
        channel: str,
    ) -> None:
        if self.store.open_slot() or self.repeat_blocked(
            item.candidate.symbol, now_ms
        ):
            return
        if item.book.buy_vwap is None or item.book.best_ask is None:
            return
        mark = self.state.mark_info(item.candidate.symbol)
        slot_id = self.store.create_slot(
            signal_id, channel, item, episode_id, now_ms, mark
        )
        value = (
            item.candidate.return_5m
            if channel == "MC5"
            else item.candidate.return_10m
        )
        await self.notify(
            f"🟠 FUTURES {channel} paper\n{item.candidate.symbol}\n"
            f"signal {value:+.3f}% · entry {item.book.buy_vwap:.8g}\n"
            f"fee {self.settings.futures_fee_rate * 100:.3f}%/side · "
            f"1x paper · hold {self.settings.momentum_hold_seconds}s"
        )
        LOG.info("Opened futures %s slot %s", channel, slot_id)

    async def tick(self, now_ms: int) -> None:
        for slot in self.store.policy_slots():
            price = self.state.executable_sell_price(
                slot["symbol"],
                float(slot["quantity"]),
                now_ms,
                self.settings.max_feed_age_ms,
            )
            if price is None:
                continue
            current = (price / float(slot["entry_vwap"]) - 1) * 100
            self.store.update_slot(slot["id"], current, now_ms)
            for policy in self.store.policies(slot["id"]):
                if policy["state"] == "OPEN":
                    await self._update_policy(
                        slot, policy, price, current, now_ms
                    )

    async def _update_policy(
        self,
        slot,
        policy,
        price: float,
        current: float,
        now_ms: int,
    ) -> None:
        name = str(policy["policy"])
        age = (now_ms - int(slot["opened_at_ms"])) / 1000
        peak = max(float(policy["peak_return_percent"] or 0), current)
        activated = policy["activated_at_ms"] is not None
        updates: dict[str, Any] = {"peak_return_percent": peak}
        if (
            name == "MC_TRAIL_1P0"
            and not activated
            and current >= self.settings.momentum_trail_activation_percent
        ):
            updates["activated_at_ms"] = now_ms
            activated = True

        reason: Optional[str] = None
        if current <= -self.settings.momentum_stop_percent:
            reason = "MC_STOP_2"
        elif (
            name == "MC_FIXED_TP4"
            and current >= self.settings.momentum_fixed_target_percent
        ):
            reason = "MC_TARGET_4"
        elif (
            name == "MC_TRAIL_1P0"
            and activated
            and peak - current >= self.settings.momentum_trail_drawdown_percent
        ):
            reason = "MC_TRAIL_EXIT_1"
        elif (
            name == "MC_HOLD_120"
            and age >= self.settings.momentum_hold_seconds
        ):
            reason = "MC_HOLD_120_EXIT"
        elif age >= self.settings.momentum_horizon_seconds:
            reason = "MC_HORIZON_20M"

        self.store.update_policy(policy["id"], **updates)
        if reason is None:
            return

        qty = float(slot["quantity"])
        exit_quote = qty * price
        entry_cost = float(slot["position_usdt"])
        entry_fee = float(slot["entry_fee_usdt"])
        exit_fee = exit_quote * self.settings.futures_fee_rate
        funding = 0.0
        funding_time = int(slot["next_funding_time_ms"] or 0)
        if funding_time > int(slot["opened_at_ms"]) and now_ms >= funding_time:
            funding = entry_cost * float(slot["funding_rate"] or 0)
        gross = (exit_quote / entry_cost - 1) * 100
        net = (
            (exit_quote - entry_fee - exit_fee - funding) / entry_cost - 1
        ) * 100
        self.store.update_policy(
            policy["id"],
            state="CLOSED",
            closed_at_ms=now_ms,
            exit_reason=reason,
            exit_vwap=price,
            exit_fee_usdt=exit_fee,
            funding_usdt=funding,
            gross_return_percent=gross,
            net_return_percent=net,
        )
        if name == self.settings.momentum_primary_policy:
            self.store.close_primary(
                slot["id"], now_ms, reason, price, gross, net, funding
            )
            await self.notify(
                f"🟧 FUTURES {slot['signal_channel']} {slot['symbol']} "
                f"{reason}: net {net:+.3f}%"
            )
