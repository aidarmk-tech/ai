from __future__ import annotations

import asyncio
import logging
import os
import random
import signal
import socket
import time

import aiohttp
from aiohttp import web

from .config import Settings
from .episodes import EpisodeTelemetry, EpisodeTracker
from .market import BinanceFeed, MarketState
from .models import EvaluatedCandidate
from .paper import MomentumPaperManager, PaperManager
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
        self.started_at_ms = int(time.time() * 1000)

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
        progress = self.storage.frozen_primary_trade_progress()
        return {
            "ok": now - self.state.last_market_message_ms < 15_000,
            "uptime_seconds": (now - self.started_at_ms) // 1000,
            "freeze_closed_primary_trades": progress["closed"],
            "freeze_target_primary_trades": progress["target"],
            "freeze_remaining_primary_trades": progress["remaining"],
            "market_feed_age_ms": now - self.state.last_market_message_ms if self.state.last_market_message_ms else None,
            "candidate_feed_age_ms": now - self.state.last_candidate_message_ms if self.state.last_candidate_message_ms else None,
            "universe_symbols": len(self.state.universe),
            # Backward-compatible field used by client 1.0: warm symbols receive
            # continuous aggTrade + bookTicker analysis, not merely miniTicker.
            "evaluated_symbols": len(self.warm_symbols),
            "warm_pool_symbols": len(self.warm_symbols),
            "decision_symbols": len(self.decision_symbols),
            "depth_symbols": len(self.depth_symbols),
            "control_symbols": len(self.control_symbols),
            "candidate_connection_count": self.state.candidate_connection_count,
            "candidate_subscription_update_count": self.state.candidate_subscription_update_count,
            "last_candidate_connect_age_ms": (
                now - self.state.last_candidate_connect_ms
                if self.state.last_candidate_connect_ms
                else None
            ),
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
                forced_momentum = [
                    c
                    for c in ranked
                    if (
                        (c.return_3m is not None and c.return_3m >= self.settings.momentum_mc3_return_3m)
                        or (c.return_5m is not None and c.return_5m >= self.settings.momentum_mc5_return_5m)
                        or (c.return_10m is not None and c.return_10m >= self.settings.momentum_mc7_return_10m)
                    )
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
                    if changed:
                        self.storage.event(
                            "INFO",
                            "coverage",
                            f"warm={len(warm)} controls={len(controls)} "
                            f"depth={len(depth)} candidates={len(candidates)} "
                            f"outcomes={len(outcome_symbols)}",
                        )

                if self._ensure_decision_coverage(next_decision_symbols):
                    self.storage.event(
                        "INFO",
                        "coverage",
                        f"immediate decision coverage warm={len(self.warm_symbols)} "
                        f"depth={len(self.depth_symbols)} decisions={len(next_decision_symbols)}",
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
                self._update_snapshot_outcomes(now)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                LOG.exception("Engine tick failed: %s", exc)
                self.storage.event("ERROR", "engine", repr(exc))
            await asyncio.sleep(1)

    async def position_loop(self) -> None:
        """Watch executable exits independently from the heavier scan loop."""
        interval = max(0.1, self.settings.stop_watch_interval_ms / 1000)
        while not self.stop_event.is_set():
            now = int(time.time() * 1000)
            try:
                await self.paper.tick(self.evaluated_by_symbol, now)
                await self.momentum.tick(now)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                LOG.exception("Position watcher failed: %s", exc)
                self.storage.event("ERROR", "position_watcher", repr(exc))
            await asyncio.sleep(interval)

    async def _record_and_select(
        self,
        evaluated: list[EvaluatedCandidate],
        controls: list[EvaluatedCandidate],
        now_ms: int,
    ) -> None:
        telemetry_by_symbol: dict[str, EpisodeTelemetry] = {
            item.candidate.symbol: self.episodes.observe(item, now_ms)
            for item in evaluated
        }
        self.episodes.prune(now_ms)

        momentum_events: list[tuple[str, EvaluatedCandidate, EpisodeTelemetry]] = []
        for item in evaluated:
            telemetry = telemetry_by_symbol[item.candidate.symbol]
            feed_age = self.state.measurement_age_ms(item.candidate.symbol, now_ms)
            arms, _momentum_blockers = momentum_continuation_arms(
                item.candidate,
                item.flow,
                item.book,
                self.settings,
                feed_age,
            )
            for arm, passed in arms.items():
                key = (telemetry.episode_id, arm)
                if passed and key not in self.momentum_emitted:
                    self.momentum_emitted.add(key)
                    momentum_events.append((arm, item, telemetry))

        strict = sorted(
            [e for e in evaluated if e.decision.strict_passed],
            key=lambda e: (e.decision.risk.impulse, e.decision.risk.confidence, -(e.book.spread_bps or 999)),
            reverse=True,
        )
        triggered = [e for e in evaluated if e.decision.label not in ("NO_TRADE",)]
        snapshot_ids: dict[str, str] = {}
        for item in triggered:
            telemetry = telemetry_by_symbol[item.candidate.symbol]
            snapshot_type = (
                "TRIGGERED"
                if item.decision.strict_passed
                else "SHADOW"
                if item.decision.shadow_passed
                else "TRIGGERED"
            )
            snapshot_ids[item.candidate.symbol] = self.storage.insert_snapshot(
                item,
                snapshot_type,
                telemetry.episode_id,
                now_ms,
                telemetry,
            )
        for arm, item, telemetry in momentum_events:
            snapshot_type = {
                "MC3": "MC3_SHADOW",
                "MC5": "MC5_CHALLENGER",
                "MC7": "MC7_CHALLENGER",
            }[arm]
            sid = self.storage.insert_snapshot(
                item,
                snapshot_type,
                telemetry.episode_id,
                now_ms,
                telemetry,
            )
            if arm in ("MC5", "MC7"):
                await self.momentum.consider(
                    item,
                    sid,
                    now_ms,
                    telemetry.episode_id,
                    channel=arm,
                )

        if strict:
            best = strict[0]
            telemetry = telemetry_by_symbol[best.candidate.symbol]
            sid = snapshot_ids.get(best.candidate.symbol) or self.storage.insert_snapshot(
                best,
                "TRIGGERED",
                telemetry.episode_id,
                now_ms,
                telemetry,
            )
            await self.paper.consider(
                best,
                sid,
                now_ms,
                telemetry.episode_id,
            )
        if now_ms - self.last_near_miss_ms >= self.settings.snapshot_near_miss_seconds * 1000:
            near = [e for e in evaluated if not e.decision.strict_passed and e.decision.risk.impulse >= 40]
            if near:
                best = max(near, key=lambda e: e.decision.risk.impulse)
                telemetry = telemetry_by_symbol[best.candidate.symbol]
                self.storage.insert_snapshot(
                    best,
                    "NEAR_MISS",
                    telemetry.episode_id,
                    now_ms,
                    telemetry,
                )
                self.last_near_miss_ms = now_ms
        if now_ms - self.last_random_ms >= self.settings.snapshot_random_seconds * 1000:
            ready_controls = [e for e in controls if e.flow.trade_count_30s > 0]
            normal = ready_controls or [e for e in evaluated if e.decision.risk.impulse < 40]
            if normal:
                kind = "CONTROL_NORMAL" if ready_controls else "RANDOM_NORMAL"
                self.storage.insert_snapshot(random.choice(normal), kind, None, now_ms)
                self.last_random_ms = now_ms

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
        web_app = WebApp(self.settings, self.storage, self.health).make()
        runner = web.AppRunner(web_app)
        await runner.setup()
        site = web.TCPSite(runner, self.settings.bind_host, self.settings.bind_port)
        await site.start()
        tasks = [
            asyncio.create_task(self.feed.market_loop(), name="market-ws"),
            asyncio.create_task(self.feed.candidate_loop(), name="candidate-ws"),
            asyncio.create_task(self.engine_loop(), name="engine"),
            asyncio.create_task(self.position_loop(), name="position-watcher"),
        ]
        LOG.info("PumpRadar server started; config=%s", self.settings.config_hash())
        await self.stop_event.wait()
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await self.feed.stop()
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
