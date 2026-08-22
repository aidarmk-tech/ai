import asyncio

from .market import MarketRecorder, _f, now_ms


class StableMarketRecorder(MarketRecorder):
    """Runtime hardening around the research recorder.

    The all-market ticker cache is empty for a brief moment after process start.
    The base implementation must not replace the six-symbol bootstrap universe
    with only BTC/ETH during that moment, because doing so bumps the universe
    generation and forces both WebSocket sessions to reconnect before their
    first useful market payload arrives.
    """

    async def _universe_loop(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            try:
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
                else:
                    selected = [s for _, s in ranked[:target]]
                    # BTC and ETH are structural references for A/B and must always
                    # be present. If insertion grows the list, trim back to target.
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
            except Exception as exc:
                self._health("universe", "DEGRADED", repr(exc))

            interval = 2 if len(self.tickers) < self.settings.universe_size else 60
            try:
                await asyncio.wait_for(stop.wait(), timeout=interval)
            except TimeoutError:
                pass
