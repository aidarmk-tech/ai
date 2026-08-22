import asyncio

from .market import MarketRecorder, _f, now_ms


class StableMarketRecorder(MarketRecorder):
    """Runtime hardening around the research recorder."""

    def _refresh_universe_once(self) -> None:
        ranked = []
        for symbol, ticker in self.tickers.items():
            if not symbol.endswith("USDT") or ticker.get("st") not in (None, 1):
                continue
            ranked.append((_f(ticker.get("q")), symbol))
        ranked.sort(reverse=True)

        target = max(2, int(self.settings.universe_size))
        if len(ranked) < target:
            # Keep the bootstrap universe unchanged until enough all-market
            # tickers exist for one stable transition directly to top-N.
            self._health(
                "universe",
                "WARMING",
                f"tickers={len(ranked)}/{target} bootstrap={len(self.universe)} gen={self.universe_generation}",
                now_ms(),
            )
            return

        selected = [s for _, s in ranked[:target]]
        for required in ("ETHUSDT", "BTCUSDT"):
            if required not in selected:
                selected.insert(0, required)
        new_universe = list(dict.fromkeys(selected))[:target]
        new_micro = new_universe[: self.settings.microstructure_size]
        if new_universe != self.universe or new_micro != self.micro:
            self.universe = new_universe
            self.micro = new_micro
            self.universe_generation += 1
        self._health(
            "universe",
            "OK",
            f"universe={len(self.universe)} micro={len(self.micro)} gen={self.universe_generation}",
            now_ms(),
        )

    async def _universe_loop(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            try:
                self._refresh_universe_once()
            except Exception as exc:
                self._health("universe", "DEGRADED", repr(exc))

            interval = 2 if len(self.tickers) < self.settings.universe_size else 60
            try:
                await asyncio.wait_for(stop.wait(), timeout=interval)
            except TimeoutError:
                pass

    async def _sample_loop(self, stop: asyncio.Event) -> None:
        """Only advance sampler health/time when real market rows were added.

        The base recorder used to mark ``market_sampler=OK`` and advance
        ``last_sample_ms`` even when every ticker was stale and `_sample_market`
        inserted zero rows. That masked the FD-exhaustion failure as a healthy
        sampler. This hardened loop treats the database/history write itself as
        the heartbeat source.
        """
        while not stop.is_set():
            tick = now_ms() // 1000 * 1000
            try:
                self._flush_flow(tick)
                self._sample_depth(tick)
                if (tick // 1000) % self.settings.market_sample_seconds == 0:
                    self._sample_market(tick)
                    fresh_rows = sum(
                        1
                        for symbol in self.universe
                        if self.history.get(symbol) and self.history[symbol][-1][0] == tick
                    )
                    if fresh_rows:
                        if self.last_sample_ms and tick - self.last_sample_ms > self.settings.max_sample_gap_seconds * 1000:
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
                            f"no fresh market rows; universe={len(self.universe)} last_real_sample_ms={self.last_sample_ms}",
                            self.last_sample_ms or None,
                        )

                if tick - self._cleanup_last_ms >= 3600_000:
                    self._cleanup_raw(tick)
                    self._cleanup_last_ms = tick
                if tick - self._gap_audit_last_ms >= 3600_000:
                    self._audit_sample_gaps(tick)
                    self._gap_audit_last_ms = tick
            except Exception as exc:
                self._health("market_sampler", "DEGRADED", repr(exc), self.last_sample_ms or None)

            try:
                await asyncio.wait_for(stop.wait(), timeout=1)
            except TimeoutError:
                pass
