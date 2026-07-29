from __future__ import annotations

import shutil
from datetime import datetime
from pathlib import Path

path = Path('/opt/pumpradar/server/pumpradar_server/futures.py')
if not path.exists():
    raise SystemExit(f'Не найден {path}')

text = path.read_text()
if 'request_id = await self._update_subscriptions(' in text:
    print('Стабилизирующий hotfix уже применён')
    raise SystemExit(0)
if 'def _candidate_group(self, endpoint: str)' not in text:
    raise SystemExit('Сначала должен быть применён futures WS split hotfix')

backup = path.with_name(path.name + '.before-stable-subscriptions-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
shutil.copy2(path, backup)

anchor = '''    async def _candidate_group(self, endpoint: str) -> None:\n'''
helper = '''    async def _update_subscriptions(\n        self,\n        ws: aiohttp.ClientWebSocketResponse,\n        current: set[str],\n        desired: set[str],\n        request_id: int,\n    ) -> int:\n        removed = sorted(current - desired)\n        added = sorted(desired - current)\n        if removed:\n            await ws.send_json({"method": "UNSUBSCRIBE", "params": removed, "id": request_id})\n            request_id += 1\n        if added:\n            await ws.send_json({"method": "SUBSCRIBE", "params": added, "id": request_id})\n            request_id += 1\n        if removed or added:\n            self.state.candidate_subscription_update_count += 1\n        return request_id\n\n'''
if anchor not in text:
    raise SystemExit('Не найден якорь candidate_group')
text = text.replace(anchor, helper + anchor, 1)

old = '''                    while not self._stopping.is_set():\n                        if version != self._candidate_version:\n                            break\n                        try:\n'''
new = '''                    request_id = 1\n                    while not self._stopping.is_set():\n                        if version != self._candidate_version:\n                            desired = (\n                                self._market_streams(self._warm_symbols)\n                                if endpoint == "market"\n                                else self._public_streams(\n                                    self._warm_symbols, self._depth_symbols\n                                )\n                            )\n                            request_id = await self._update_subscriptions(\n                                ws, streams, desired, request_id\n                            )\n                            streams = desired\n                            version = self._candidate_version\n                        try:\n'''
if old not in text:
    raise SystemExit('Не найден якорь reconnect loop')
text = text.replace(old, new, 1)

path.write_text(text)
print(f'Стабилизирующий hotfix применён. Резервная копия: {backup}')
