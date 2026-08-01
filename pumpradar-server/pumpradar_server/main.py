from __future__ import annotations

import asyncio
import json
import logging
import os
import random
import signal
import socket
import time
from dataclasses import replace

import aiohttp
from aiohttp import web

from .config import Settings
from .episodes import EpisodeTelemetry, EpisodeTracker
from .futures import FuturesFeed, FuturesMarketState, FuturesPaperManager, FuturesPaperStore
from .market import BinanceFeed, MarketState
from .models import EvaluatedCandidate
from .paper import MomentumPaperManager, PaperManager
from .regime import RegimePaperManager
from .storage import Storage
from .strategy import assess, momentum_continuation_arms
from .webapp import WebApp

LOG = logging.getLogger(__name__)


class Service:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.state = MarketState()
        self.storage = Storage(settings)
        self.feed = BinanceFeed(settings, self.state)
        self.paper = PaperManager(settings, self.state, self.storage, self.notify)
        self.momentum = MomentumPaperManager(settings, self.state, self.storage, self.notify)
        self.episodes = EpisodeTracker(settings)
        self.futures_state = FuturesMarketState()
        self.futures_feed = FuturesFeed(settings, self.futures_state)
        self.futures_store = FuturesPaperStore(settings, self.storage)
        self.futures_paper = FuturesPaperManager(
            settings, self.futures_state, self.futures_store, self.notify
        )
        self.futures_gate_settings = replace(
            settings,
            momentum_max_spread_bps=settings.futures_momentum_max_spread_bps,
            momentum_max_buy_slippage_percent=settings.futures_momentum_max_buy_slippage_percent,
            momentum_max_sell_slippage_percent=settings.futures_momentum_max_sell_slippage_percent,
        )
        self.futures_episodes = EpisodeTracker(self.futures_gate_settings)
        self.regime = RegimePaperManager(
            settings, self.futures_state, self.storage, self.notify
        )
        self.stop_event = asyncio.Event()
        self.last_near_miss_ms = 0
        self.last_random_ms = 0
        self.last_candidate_set_ms = 0
        self.last_control_rotation_ms = 0
        self.warm_symbols: set[str] = set()
        self.control_symbols: set[str] = set()
        self.decision_symbols: set[str] = set()
        self.depth_symbols: set[str] = set()
        self.evaluated_by_symbol: dict[str, EvaluatedCandidate] = {}
        self.momentum_emitted: set[tuple[str, str]] = set()
        self.futures_momentum_emitted: set[tuple[str, str]] = set()
        self.futures_last_candidate_set_ms = 0
        self.futures_warm_symbols: set[str] = set()
        self.futures_depth_symbols: set[str] = set()
        self.futures_decision_symbols: set[str] = set()
        self.started_at_ms = int(time.time() * 1000)
        self.last_regime_engine_success_ms = 0
        self.last_regime_position_success_ms = 0
        self.regime_engine_error_count = 0
        self.regime_position_error_count = 0
        self.last_regime_engine_error: str | None = None
        self.last_regime_position_error: str | None = None
        self.last_coverage_event_ms = 0
        self.last_coverage_message: str | None = None

    def _coverage_event(self, message: str, now_ms: int, *, state_changed: bool = False) -> bool:
        interval_ms = max(30, self.settings.coverage_log_interval_seconds) * 1000
        if not state_changed and now_ms - self.last_coverage_event_ms < interval_ms:
            return False
        self.storage.event("INFO", "coverage", message)
        self.last_coverage_event_ms = now_ms
        self.last_coverage_message = message
        return True

    async def notify(self, message: str) -> None:
        LOG.info("NOTIFY %s", message.replace("\n", " | "))
        if not self.settings.telegram_bot_token or not self.settings.telegram_chat_id:
            return
        url = f"https://api.telegram.org/bot{self.settings.telegram_bot_token}/sendMessage"
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as session:
                await session.post(url, json={"chat_id": self.settings.telegram_chat_id, "text": message})
        except Exception as exc:
            LOG.warning("Telegram notification failed: %s", exc)

    def health(self) -> dict:
        now = int(time.time() * 1000)
        uptime_ms = now - self.started_at_ms
        spot_ok = bool(
            self.state.last_market_message_ms
            and now - self.state.last_market_message_ms < 15_000
        )
        futures_ok = bool(
            self.futures_state.last_market_message_ms
            and now - self.futures_state.last_market_message_ms < 15_000
        )
        engine_age = (
            now - self.last_regime_engine_success_ms
            if self.last_regime_engine_success_ms
            else None
        )
        position_age = (
            now - self.last_regime_position_success_ms
            if self.last_regime_position_success_ms
            else None
        )
        startup_grace = uptime_ms < 30_000
        regime_engine_ok = startup_grace or (
            engine_age is not None and engine_age < 10_000
        )
        regime_position_ok = startup_grace or (
            position_age is not None and position_age < 10_000
        )
        regime_status = self.regime.status()
        recorder_status = None
        try:
            if self.settings.research_status_path.is_file():
                recorder_status = json.loads(
                    self.settings.research_status_path.read_text(encoding="utf-8")
                )
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            recorder_status = {"ok": False, "error": "INVALID_STATUS_JSON"}
        return {
            "ok": spot_ok and futures_ok and regime_engine_ok and regime_position_ok,
            "algorithm_version": self.settings.algorithm_version,
            "strategy_version": self.settings.strategy_version,
            "config_hash": self.settings.config_hash(),
            "spot_ok": spot_ok,
            "futures_ok": futures_ok,
            "regime_engine_ok": regime_engine_ok,
            "regime_position_ok": regime_position_ok,
            "regime_engine_age_ms": engine_age,
            "regime_position_age_ms": position_age,
            "regime_engine_error_count": self.regime_engine_error_count,
            "regime_position_error_count": self.regime_position_error_count,
            "last_regime_engine_error": self.last_regime_engine_error,
            "last_regime_position_error": self.last_regime_position_error,
            "uptime_seconds": uptime_ms // 1000,
            "market_feed_age_ms": now - self.state.last_market_message_ms if self.state.last_market_message_ms else None,
            "candidate_feed_age_ms": now - self.state.last_candidate_message_ms if self.state.last_candidate_message_ms else None,
            "universe_symbols": len(self.state.universe),
            "evaluated_symbols": len(self.warm_symbols),
            "warm_pool_symbols": len(self.warm_symbols),
            "decision_symbols": len(self.decision_symbols),
            "depth_symbols": len(self.depth_symbols),
            "control_symbols": len(self.control_symbols),
            "research_recorder": recorder_status,
            "candidate_connection_count": self.state.candidate_connection_count,
            "candidate_subscription_update_count": self.state.candidate_subscription_update_count,
            "last_candidate_connect_age_ms": (
                now - self.state.last_candidate_connect_ms
                if self.state.last_candidate_connect_ms else None
            ),
            "futures_market_feed_age_ms": (
                now - self.futures_state.last_market_message_ms
                if self.futures_state.last_market_message_ms else None
            ),
            "futures_candidate_feed_age_ms": (
                now - self.futures_state.last_candidate_message_ms
                if self.futures_state.last_candidate_message_ms else None
            ),
            "futures_universe_symbols": len(self.futures_state.universe),
            "futures_warm_pool_symbols": len(self.futures_warm_symbols),
            "futures_decision_symbols": len(self.futures_decision_symbols),
            "futures_depth_symbols": len(self.futures_depth_symbols),
            **self.futures_store.status(),
            **regime_status,
        }

    def _select_warm_core(self, ranked) -> list[str]:
        size = self.settings.warm_pool_size
        if size <= 0:
            return []
        ranked_symbols = [c.symbol for c in ranked]
        mandatory_count = min(size, max(10, size // 2))
        selected = list(ranked_symbols[:mandatory_count])
        selected_set = set(selected)
        previous_core = self.warm_symbols - self.control_symbols
        retention_zone = set(ranked_symbols[: max(size * 3, size)])
        for symbol in sorted(previous_core):
            if len(selected) >= size:
                break
            if symbol in retention_zone and symbol not in selected_set:
                selected.append(symbol)
                selected_set.add(symbol)
        for symbol in ranked_symbols:
            if len(selected) >= size:
                break
            if symbol not in selected_set:
                selected.append(symbol)
                selected_set.add(symbol)
        return selected

    def _rotate_controls(self, ranked, excluded: set[str], now_ms: int) -> set[str]:
        still_valid = self.control_symbols - excluded
        due = now_ms - self.last_control_rotation_ms >= self.settings.control_rotation_seconds * 1000
        if not due and len(still_valid) >= self.settings.control_pool_size:
            return set(list(sorted(still_valid))[: self.settings.control_pool_size])
        eligible = [c.symbol for c in ranked if c.symbol not in excluded]
        count = min(self.settings.control_pool_size, len(eligible))
        self.last_control_rotation_ms = now_ms
        return set(random.sample(eligible, count)) if count else set()

    def _ensure_decision_coverage(self, decision_symbols: set[str]) -> bool:
        """Subscribe new decision symbols immediately instead of waiting 15 seconds."""
        missing_warm = decision_symbols - self.warm_symbols
        missing_depth = decision_symbols - self.depth_symbols
        if not missing_warm and not missing_depth:
            return False
        self.warm_symbols.update(decision_symbols)
        self.depth_symbols.update(decision_symbols)
        self.feed.set_candidate_symbols(
            sorted(self.warm_symbols),
            sorted(self.depth_symbols),
        )
        return True

    async def engine_loop(self) -> None:
        while not self.stop_event.is_set():
            now = int(time.time() * 1000)
            try:
                ranked = self.state.rank_universe(self.settings.minimum_24h_quote_volume, now)
                candidates = [
                    c for c in ranked if self.state.is_pre_candidate(c)
                ][: self.settings.max_candidates]
                candidate_map = {c.symbol: c for c in ranked}
                decision_candidates = list(
                    candidates[: self.settings.deep_candidates]
                )
                decision_seen = {c.symbol for c in decision_candidates}
                # v4.5: only MC5 is retained as the spot exhaustion trigger.
                forced_momentum = [
                    c
                    for c in ranked
                    if c.return_5m is not None
                    and c.return_5m >= self.settings.momentum_mc5_return_5m
                ]
                for candidate in forced_momentum:
                    if candidate.symbol not in decision_seen:
                        decision_candidates.append(candidate)
                        decision_seen.add(candidate.symbol)
                    if len(decision_candidates) >= self.settings.max_candidates:
                        break
                next_decision_symbols = {c.symbol for c in decision_candidates}
                active = self.storage.baseline_open_slot()
                active_symbol = str(active["symbol"]) if active else None
                active_momentum = self.storage.momentum_primary_open_slot()
                active_momentum_symbol = (
                    str(active_momentum["symbol"]) if active_momentum else None
                )
                outcome_symbols = self.storage.pending_snapshot_symbols()
                outcome_symbols.update(self.storage.pending_momentum_symbols())

                if now - self.last_candidate_set_ms >= self.settings.warm_refresh_seconds * 1000:
                    warm_core = self._select_warm_core(ranked)
                    excluded = set(warm_core)
                    excluded.update(outcome_symbols)
                    if active_symbol:
                        excluded.add(active_symbol)
                    if active_momentum_symbol:
                        excluded.add(active_momentum_symbol)
                    controls = self._rotate_controls(ranked, excluded, now)
                    warm = set(warm_core) | controls
                    warm.update(next_decision_symbols)
                    warm.update(outcome_symbols)
                    if active_symbol:
                        warm.add(active_symbol)
                    if active_momentum_symbol:
                        warm.add(active_momentum_symbol)
                    # Every symbol that can reach a frozen decision needs an
                    # executable depth book. The configured depth pool may be
                    # larger, but never smaller than the decision set.
                    depth_limit = max(
                        self.settings.depth_candidates,
                        self.settings.deep_candidates,
                    )
                    depth = {c.symbol for c in candidates[:depth_limit]}
                    depth.update(next_decision_symbols)
                    depth.update(outcome_symbols)
                    if active_symbol:
                        depth.add(active_symbol)
                    if active_momentum_symbol:
                        depth.add(active_momentum_symbol)
                    changed = warm != self.warm_symbols or controls != self.control_symbols or depth != self.depth_symbols
                    self.warm_symbols = warm
                    self.control_symbols = controls
                    self.depth_symbols = depth
                    self.state.retain_detailed_symbols(warm)
                    self.state.retain_depth_symbols(depth)
                    self.feed.set_candidate_symbols(sorted(warm), sorted(depth))
                    self.last_candidate_set_ms = now
                    self._coverage_event(
                        f"warm={len(warm)} controls={len(controls)} "
                        f"depth={len(depth)} candidates={len(candidates)} "
                        f"outcomes={len(outcome_symbols)}",
                        now,
                        state_changed=False,
                    )

                if self._ensure_decision_coverage(next_decision_symbols):
                    self._coverage_event(
                        f"immediate decision coverage warm={len(self.warm_symbols)} "
                        f"depth={len(self.depth_symbols)} decisions={len(next_decision_symbols)}",
                        now,
                        state_changed=False,
                    )

                median_ret, breadth = self.state.market_context(now)
                self.decision_symbols = next_decision_symbols
                evaluation_symbols = set(self.decision_symbols) | set(self.control_symbols)
                if active_symbol:
                    evaluation_symbols.add(active_symbol)
                if active_momentum_symbol:
                    evaluation_symbols.add(active_momentum_symbol)

                evaluated: list[EvaluatedCandidate] = []
                for symbol in evaluation_symbols:
                    candidate = candidate_map.get(symbol)
                    if candidate is None:
                        continue
                    flow = self.state.flow_metrics(symbol, now)
                    book = self.state.book_metrics(symbol, self.settings.position_usdt, now)
                    peak = self.state.update_peak(symbol, candidate.price, flow.cvd_30s, now)
                    repeat = self.paper.repeat_blocked(symbol, now)
                    feed_age = self.state.measurement_age_ms(symbol, now)
                    decision = assess(
                        candidate, flow, book, peak, self.settings, feed_age, median_ret, breadth, repeat
                    )
                    evaluated.append(EvaluatedCandidate(candidate, flow, book, peak, decision))
                self.evaluated_by_symbol = {e.candidate.symbol: e for e in evaluated}
                decision_items = [e for e in evaluated if e.candidate.symbol in self.decision_symbols]
                control_items = [e for e in evaluated if e.candidate.symbol in self.control_symbols]
                await self._record_and_select(decision_items, control_items, now)
                pass  # Legacy snapshot-outcome writes are disabled in v4.5.
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                LOG.exception("Engine tick failed: %s", exc)
                self.storage.event("ERROR", "engine", repr(exc))
            await asyncio.sleep(1)

    async def futures_engine_loop(self) -> None:
        """Balanced positive/negative warm pool for the v4.5 regime model."""
        while not self.stop_event.is_set():
            now = int(time.time() * 1000)
            try:
                ranked = self.futures_state.rank_universe(
                    self.settings.futures_minimum_24h_quote_volume,
                    now,
                )
                candidate_map = {c.symbol: c for c in ranked}
                negative_ranked = sorted(
                    ranked,
                    key=lambda c: (
                        3.0 * (c.return_15s or 0.0)
                        + 2.0 * (c.return_60s or 0.0)
                        + (c.return_5m or 0.0)
                    ),
                )
                side_size = self.settings.regime_decision_side_size
                onset = [
                    c for c in ranked if self.regime.pre_onset_candidate(c)
                ][:side_size]
                dumps = [
                    c for c in negative_ranked if self.regime.pre_dump_candidate(c)
                ][:side_size]
                decision_symbols = {c.symbol for c in onset}
                decision_symbols.update(c.symbol for c in dumps)
                decision_symbols.update(self.regime.pending_symbols())
                decision_symbols.intersection_update(self.futures_state.universe)

                refresh_due = (
                    now - self.futures_last_candidate_set_ms
                    >= self.settings.warm_refresh_seconds * 1000
                )
                if refresh_due:
                    half = max(1, self.settings.regime_warm_pool_size // 2)
                    warm = {c.symbol for c in ranked[:half]}
                    warm.update(c.symbol for c in negative_ranked[:half])
                    warm.update(decision_symbols)
                    depth_half = max(1, self.settings.regime_depth_pool_size // 2)
                    depth = {c.symbol for c in ranked[:depth_half]}
                    depth.update(c.symbol for c in negative_ranked[:depth_half])
                    depth.update(decision_symbols)
                    self.futures_warm_symbols = warm
                    self.futures_depth_symbols = depth
                    self.futures_state.retain_detailed_symbols(warm)
                    self.futures_state.retain_depth_symbols(depth)
                    self.futures_feed.set_candidate_symbols(
                        sorted(warm), sorted(depth)
                    )
                    self.futures_last_candidate_set_ms = now

                missing = decision_symbols - self.futures_warm_symbols
                missing_depth = decision_symbols - self.futures_depth_symbols
                if missing or missing_depth:
                    self.futures_warm_symbols.update(decision_symbols)
                    self.futures_depth_symbols.update(decision_symbols)
                    self.futures_feed.set_candidate_symbols(
                        sorted(self.futures_warm_symbols),
                        sorted(self.futures_depth_symbols),
                    )

                self.futures_decision_symbols = decision_symbols
                median_ret, breadth = self.futures_state.market_context(now)
                for symbol in decision_symbols:
                    candidate = candidate_map.get(symbol)
                    if candidate is None:
                        continue
                    flow = self.futures_state.flow_metrics(symbol, now)
                    book = self.futures_state.book_metrics(
                        symbol,
                        self.settings.regime_margin_usdt
                        * self.settings.regime_short_leverage,
                        now,
                    )
                    peak = self.futures_state.update_peak(
                        symbol, candidate.price, flow.cvd_30s, now
                    )
                    feed_age = self.futures_state.measurement_age_ms(symbol, now)
                    decision = assess(
                        candidate,
                        flow,
                        book,
                        peak,
                        self.futures_gate_settings,
                        feed_age,
                        median_ret,
                        breadth,
                        False,
                    )
                    item = EvaluatedCandidate(candidate, flow, book, peak, decision)
                    telemetry = self.futures_episodes.observe(item, now)
                    await self.regime.observe_futures(
                        item,
                        now,
                        telemetry.episode_id,
                    )
                self.futures_episodes.prune(now)
                self.last_regime_engine_success_ms = int(time.time() * 1000)
                self.last_regime_engine_error = None
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self.regime_engine_error_count += 1
                self.last_regime_engine_error = f"{type(exc).__name__}: {exc}"[:500]
                LOG.exception("Regime futures engine tick failed: %s", exc)
                self.storage.event("ERROR", "regime_futures_engine", repr(exc))
            await asyncio.sleep(1)

    async def position_loop(self) -> None:
        """Watch only the three v4.5 regime paper slots."""
        interval = max(0.1, self.settings.stop_watch_interval_ms / 1000)
        while not self.stop_event.is_set():
            now = int(time.time() * 1000)
            try:
                await self.regime.tick(now)
                self.last_regime_position_success_ms = int(time.time() * 1000)
                self.last_regime_position_error = None
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self.regime_position_error_count += 1
                self.last_regime_position_error = f"{type(exc).__name__}: {exc}"[:500]
                LOG.exception("Regime position watcher failed: %s", exc)
                self.storage.event("ERROR", "regime_position_watcher", repr(exc))
            await asyncio.sleep(interval)

    async def _record_and_select(
        self,
        evaluated: list[EvaluatedCandidate],
        controls: list[EvaluatedCandidate],
        now_ms: int,
    ) -> None:
        """Only the historically supported spot MC5 exhaustion signal remains active."""
        del controls
        for item in evaluated:
            telemetry = self.episodes.observe(item, now_ms)
            feed_age = self.state.measurement_age_ms(item.candidate.symbol, now_ms)
            arms, _ = momentum_continuation_arms(
                item.candidate,
                item.flow,
                item.book,
                self.settings,
                feed_age,
            )
            key = (telemetry.episode_id, "MC5")
            if not arms.get("MC5") or key in self.momentum_emitted:
                continue
            self.momentum_emitted.add(key)
            symbol = item.candidate.symbol
            if symbol in self.futures_state.universe:
                self.futures_warm_symbols.add(symbol)
                self.futures_depth_symbols.add(symbol)
                self.futures_feed.set_candidate_symbols(
                    sorted(self.futures_warm_symbols),
                    sorted(self.futures_depth_symbols),
                )
            self.regime.signal_short_from_spot(
                item,
                telemetry.episode_id,
                now_ms,
            )
        self.episodes.prune(now_ms)

    def _update_snapshot_outcomes(self, now_ms: int) -> None:
        observations: list[tuple[str, float]] = []
        for outcome in self.storage.pending_snapshot_outcomes():
            entry_vwap = outcome["entry_vwap"]
            if entry_vwap is None or float(entry_vwap) <= 0:
                continue
            quantity = float(outcome["position_usdt"]) / float(entry_vwap)
            sell_vwap = self.state.executable_sell_price(
                str(outcome["symbol"]),
                quantity,
                now_ms,
                self.settings.max_feed_age_ms,
            )
            if sell_vwap is None:
                continue
            current_return = (sell_vwap / float(entry_vwap) - 1) * 100
            observations.append(
                (str(outcome["snapshot_id"]), current_return)
            )
        self.storage.record_snapshot_observations(observations, now_ms)

    async def run(self) -> None:
        self.storage.start_run(socket.gethostname())
        self.storage.event("INFO", "service", "PumpRadar server starting")
        await self.feed.start()
        await self.futures_feed.start()
        self.futures_store.recover_incompatible(int(time.time() * 1000))
        self.regime.recover_incompatible(int(time.time() * 1000))
        web_app = WebApp(self.settings, self.storage, self.health).make()
        runner = web.AppRunner(web_app)
        await runner.setup()
        site = web.TCPSite(runner, self.settings.bind_host, self.settings.bind_port)
        await site.start()
        tasks = [
            asyncio.create_task(self.feed.market_loop(), name="market-ws"),
            asyncio.create_task(self.feed.candidate_loop(), name="candidate-ws"),
            asyncio.create_task(self.futures_feed.market_loop(), name="futures-market-ws"),
            asyncio.create_task(self.futures_feed.candidate_loop(), name="futures-candidate-ws"),
            asyncio.create_task(self.engine_loop(), name="engine"),
            asyncio.create_task(self.futures_engine_loop(), name="futures-engine"),
            asyncio.create_task(self.position_loop(), name="position-watcher"),
        ]
        LOG.info("PumpRadar server started; config=%s", self.settings.config_hash())
        await self.stop_event.wait()
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await self.feed.stop()
        await self.futures_feed.stop()
        self.storage.finish_run()
        self.storage.checkpoint()
        await runner.cleanup()


def setup_logging() -> None:
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


async def async_main() -> None:
    setup_logging()
    settings = Settings()
    if not settings.api_token:
        raise SystemExit("PUMPRADAR_API_TOKEN is required")
    service = Service(settings)
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, service.stop_event.set)
    await service.run()


def main() -> None:
    asyncio.run(async_main())


if __name__ == "__main__":
    main()
