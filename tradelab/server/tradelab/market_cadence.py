import asyncio

from .market import now_ms
from .market_runtime import StableMarketRecorder


class ProductionMarketRecorder(StableMarketRecorder):
    """Final production recorder with a non-drifting one-second scheduler.

    The previous loop slept one full second *after* synchronous SQLite and
    strategy work. That accumulated phase drift and occasionally skipped a
    second divisible by five, producing otherwise-valid 10-second market gaps.
    Sleeping only until the next wall-clock second keeps the 5-second sampler
    lattice stable unless actual work itself overruns a full sampling period.
    """

    @staticmethod
    def _seconds_to_next_wall_second(current_ms: int) -> float:
        next_ms = (current_ms // 1000 + 1) * 1000
        return max(0.01, (next_ms - current_ms) / 1000.0)

    async def _sample_loop(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            tick = now_ms() // 1000 * 1000
            try:
                self._flush_flow(tick)
                self._sample_depth(tick)
                if (tick // 1000) % self.settings.market_sample_seconds == 0:
                    fresh_rows = self._sample_market(tick)
                    if fresh_rows:
                        if (
                            self.last_sample_ms
                            and tick - self.last_sample_ms
                            > self.settings.max_sample_gap_seconds * 1000
                        ):
                            self._record_gap(self.last_sample_ms, tick, "live_sampler_gap")
                        self._evaluate_strategies(tick)
                        self.last_sample_ms = tick
                        self._health(
                            "market_sampler",
                            "OK",
                            f"rows={fresh_rows} universe={len(self.universe)}",
                            tick,
                        )
                    else:
                        self._health(
                            "market_sampler",
                            "DEGRADED",
                            (
                                "no fresh market rows; "
                                f"universe={len(self.universe)} "
                                f"last_real_sample_ms={self.last_sample_ms}"
                            ),
                            self.last_sample_ms or None,
                        )

                if tick - self._cleanup_last_ms >= 3600_000:
                    self._cleanup_raw(tick)
                    self._cleanup_last_ms = tick
                if tick - self._gap_audit_last_ms >= 3600_000:
                    self._audit_sample_gaps(tick)
                    self._gap_audit_last_ms = tick
            except Exception as exc:
                self._health(
                    "market_sampler",
                    "DEGRADED",
                    repr(exc),
                    self.last_sample_ms or None,
                )

            delay = self._seconds_to_next_wall_second(now_ms())
            try:
                await asyncio.wait_for(stop.wait(), timeout=delay)
            except TimeoutError:
                pass
