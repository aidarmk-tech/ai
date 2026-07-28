from __future__ import annotations

import shutil
from datetime import datetime
from pathlib import Path

path = Path('/opt/pumpradar/server/pumpradar_server/futures.py')
if not path.exists():
    raise SystemExit(f'Не найден {path}')
text = path.read_text()
if '/market/ws/!miniTicker@arr' in text and 'def _candidate_group(' in text:
    print('Hotfix уже применён')
    raise SystemExit(0)

backup = path.with_name(path.name + '.before-ws-split-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
shutil.copy2(path, backup)

old_init = """        self._candidate_change = asyncio.Event()\n        self._stopping = asyncio.Event()\n"""
new_init = """        self._candidate_change = asyncio.Event()\n        self._candidate_version = 0\n        self._stopping = asyncio.Event()\n"""
if old_init not in text:
    raise SystemExit('Не найден якорь __init__')
text = text.replace(old_init, new_init, 1)

old_set = """            self._warm_symbols = warm\n            self._depth_symbols = depth\n            self._candidate_change.set()\n"""
new_set = """            self._warm_symbols = warm\n            self._depth_symbols = depth\n            self._candidate_version += 1\n            self._candidate_change.set()\n"""
if old_set not in text:
    raise SystemExit('Не найден якорь set_candidate_symbols')
text = text.replace(old_set, new_set, 1)

start = text.find('    @staticmethod\n    def _streams(')
end = text.find('\n\nFUTURES_SCHEMA = """', start)
if start < 0 or end < 0:
    raise SystemExit('Не найден блок FuturesFeed для замены')

replacement = '''    @staticmethod
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
                    while not self._stopping.is_set():
                        if version != self._candidate_version:
                            break
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
'''

path.write_text(text[:start] + replacement + text[end:])
print(f'Hotfix применён. Резервная копия: {backup}')
