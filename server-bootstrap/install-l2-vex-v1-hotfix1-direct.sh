#!/usr/bin/env bash
set -Eeuo pipefail
MAIN='pumpradar.service'
EP='pumpradar-research-episodes.service'
SIDE='pumpradar-l2-vex.service'
APP='/opt/pumpradar-l2-vex/l2_vex.py'
PY='/opt/pumpradar/venv/bin/python'
BASE_SHA='eba8066181398e5f01f427c77e20191d6b26d2d2214763d2e0ad54cf1ae89b70'
HOTFIX_SHA='6e09dccb7ee0e2350ca6842df28b8651a5bf7c1f584255cbf45173429beed893'
STAMP="$(date -u +%Y%m%d-%H%M%S)"
BACKUP="/opt/pumpradar/backups/pre-l2-vex-direct-hotfix1-$STAMP"

[[ ${EUID:-$(id -u)} -eq 0 ]] || { echo 'Run as root' >&2; exit 1; }
[[ -f "$APP" ]] || { echo "Missing $APP" >&2; exit 1; }
systemctl is-active --quiet "$MAIN" || { echo "$MAIN not active" >&2; exit 1; }
systemctl is-active --quiet "$EP" || { echo "$EP not active" >&2; exit 1; }
systemctl is-active --quiet "$SIDE" || { echo "$SIDE not active" >&2; exit 1; }

MAIN_PID_BEFORE=$(systemctl show -p MainPID --value "$MAIN")
EP_PID_BEFORE=$(systemctl show -p MainPID --value "$EP")
CURRENT_SHA=$(sha256sum "$APP" | awk '{print $1}')
echo "current_l2_vex_sha=$CURRENT_SHA"

if [[ "$CURRENT_SHA" == "$HOTFIX_SHA" ]]; then
  echo 'HOTFIX1_ALREADY_APPLIED=1'
  systemctl restart "$SIDE"
  sleep 8
  cat /var/lib/pumpradar/l2-vex/status.json || true
  exit 0
fi
[[ "$CURRENT_SHA" == "$BASE_SHA" ]] || {
  echo "Refusing unexpected l2_vex.py sha: $CURRENT_SHA" >&2
  exit 1
}

mkdir -p "$BACKUP"
cp -a "$APP" "$BACKUP/l2_vex.py.before"

"$PY" - "$APP" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8')
def one(old,new,label):
    global s
    n=s.count(old)
    if n!=1: raise SystemExit(f'{label}: expected 1 match, got {n}')
    s=s.replace(old,new,1)

one('VERSION = "L2-VEX-RESEARCH-V1"','VERSION = "L2-VEX-RESEARCH-V1-HOTFIX1"','version')
one('    futures_ws_url: str = os.getenv("L2VEX_FUTURES_WS_URL", "wss://fstream.binance.com")\n',
    '    futures_ws_url: str = os.getenv("L2VEX_FUTURES_WS_URL", "wss://fstream.binance.com")\n'
    '    public_ws_url: str = os.getenv("L2VEX_PUBLIC_WS_URL", "wss://fstream.binance.com/public")\n'
    '    market_ws_url: str = os.getenv("L2VEX_MARKET_WS_URL", "wss://fstream.binance.com/market")\n','config')
old='''    async def market_ws_loop(self,session:aiohttp.ClientSession,symbols:list[str],depth:set[str])->None:\n        streams=[]\n        for s in symbols:\n            low=s.lower(); streams.extend([f"{low}@aggTrade",f"{low}@bookTicker"])\n            if s in depth: streams.append(f"{low}@depth20@100ms")\n        url=self.cfg.futures_ws_url.rstrip("/")+"/stream?streams="+"/".join(streams)\n        async with session.ws_connect(url,heartbeat=20,receive_timeout=45,max_msg_size=8*1024*1024) as ws:\n            async for msg in ws:\n                if msg.type!=aiohttp.WSMsgType.TEXT: continue\n                received=now_ms()\n                try:\n                    w=json.loads(msg.data); data=w.get("data",w); stream=str(w.get("stream","")); symbol=str(data.get("s") or stream.split("@",1)[0].upper())\n                    ev=str(data.get("e") or "")\n                    if ev=="aggTrade": self.on_trade(symbol,data,received)\n                    elif "lastUpdateId" in data or ev=="depthUpdate": self.on_depth(symbol,data,received)\n                    elif data.get("b") is not None and data.get("a") is not None: self.on_book(symbol,data,received)\n                except Exception: self.errors+=1; LOG.exception("ws message failed")\n\n    async def ws_supervisor(self,session:aiohttp.ClientSession)->None:\n        while not self.stop_event.is_set():\n            self.reconnect_event.clear(); symbols=list(self.symbols); depth=set(self.depth_symbols)\n            if not symbols: await asyncio.sleep(1); continue\n            task=asyncio.create_task(self.market_ws_loop(session,symbols,depth))\n            kick=asyncio.create_task(self.reconnect_event.wait()); stop=asyncio.create_task(self.stop_event.wait())\n            done,_=await asyncio.wait({task,kick,stop},return_when=asyncio.FIRST_COMPLETED)\n            for x in (task,kick,stop):\n                if x not in done: x.cancel()\n            await asyncio.gather(task,kick,stop,return_exceptions=True)\n            if not self.stop_event.is_set(): self.ws_reconnects+=1; await asyncio.sleep(1)\n\n    async def mark_ws_loop(self,session:aiohttp.ClientSession)->None:\n        url=self.cfg.futures_ws_url.rstrip("/")+"/ws/!markPrice@arr@1s"\n'''
new='''    async def public_ws_loop(self,session:aiohttp.ClientSession,symbols:list[str],depth:set[str])->None:\n        # Binance USD-M split WebSocket routing (2026): bookTicker/depth are PUBLIC.\n        streams=[]\n        for s in symbols:\n            low=s.lower(); streams.append(f"{low}@bookTicker")\n            if s in depth: streams.append(f"{low}@depth20@100ms")\n        url=self.cfg.public_ws_url.rstrip("/")+"/stream?streams="+"/".join(streams)\n        LOG.info("public ws connect streams=%d url_len=%d",len(streams),len(url))\n        async with session.ws_connect(url,heartbeat=20,receive_timeout=45,max_msg_size=8*1024*1024) as ws:\n            async for msg in ws:\n                if msg.type==aiohttp.WSMsgType.TEXT:\n                    received=now_ms()\n                    try:\n                        w=json.loads(msg.data); data=w.get("data",w); stream=str(w.get("stream","")); symbol=str(data.get("s") or stream.split("@",1)[0].upper())\n                        ev=str(data.get("e") or "")\n                        if "lastUpdateId" in data or ev=="depthUpdate": self.on_depth(symbol,data,received)\n                        elif data.get("b") is not None and data.get("a") is not None: self.on_book(symbol,data,received)\n                    except Exception: self.errors+=1; LOG.exception("public ws message failed")\n                elif msg.type in (aiohttp.WSMsgType.ERROR,aiohttp.WSMsgType.CLOSED,aiohttp.WSMsgType.CLOSE):\n                    raise RuntimeError(f"public websocket ended type={msg.type} data={msg.data!r}")\n        raise RuntimeError("public websocket ended")\n\n    async def trade_ws_loop(self,session:aiohttp.ClientSession,symbols:list[str])->None:\n        # aggTrade moved to the MARKET routed endpoint; it no longer pushes on the legacy root URL.\n        streams=[f"{s.lower()}@aggTrade" for s in symbols]\n        url=self.cfg.market_ws_url.rstrip("/")+"/stream?streams="+"/".join(streams)\n        LOG.info("market trade ws connect streams=%d url_len=%d",len(streams),len(url))\n        async with session.ws_connect(url,heartbeat=20,receive_timeout=45,max_msg_size=8*1024*1024) as ws:\n            async for msg in ws:\n                if msg.type==aiohttp.WSMsgType.TEXT:\n                    received=now_ms()\n                    try:\n                        w=json.loads(msg.data); data=w.get("data",w); stream=str(w.get("stream","")); symbol=str(data.get("s") or stream.split("@",1)[0].upper())\n                        if str(data.get("e") or "")=="aggTrade": self.on_trade(symbol,data,received)\n                    except Exception: self.errors+=1; LOG.exception("market trade ws message failed")\n                elif msg.type in (aiohttp.WSMsgType.ERROR,aiohttp.WSMsgType.CLOSED,aiohttp.WSMsgType.CLOSE):\n                    raise RuntimeError(f"market trade websocket ended type={msg.type} data={msg.data!r}")\n        raise RuntimeError("market trade websocket ended")\n\n    async def ws_supervisor(self,session:aiohttp.ClientSession)->None:\n        while not self.stop_event.is_set():\n            self.reconnect_event.clear(); symbols=list(self.symbols); depth=set(self.depth_symbols)\n            if not symbols: await asyncio.sleep(1); continue\n            public_task=asyncio.create_task(self.public_ws_loop(session,symbols,depth),name="l2vex-public-ws")\n            market_task=asyncio.create_task(self.trade_ws_loop(session,symbols),name="l2vex-market-trades-ws")\n            kick=asyncio.create_task(self.reconnect_event.wait(),name="l2vex-universe-reconnect")\n            stop=asyncio.create_task(self.stop_event.wait(),name="l2vex-stop")\n            tasks=(public_task,market_task,kick,stop)\n            done,_=await asyncio.wait(set(tasks),return_when=asyncio.FIRST_COMPLETED)\n            for x in done:\n                if x in (public_task,market_task) and not x.cancelled():\n                    exc=x.exception()\n                    if exc is not None:\n                        self.errors+=1; LOG.error("%s failed: %r",x.get_name(),exc)\n            for x in tasks:\n                if x not in done: x.cancel()\n            await asyncio.gather(*tasks,return_exceptions=True)\n            if not self.stop_event.is_set(): self.ws_reconnects+=1; await asyncio.sleep(1)\n\n    async def mark_ws_loop(self,session:aiohttp.ClientSession)->None:\n        # markPrice is also a MARKET stream after Binance's routed-endpoint migration.\n        url=self.cfg.market_ws_url.rstrip("/")+"/ws/!markPrice@arr@1s"\n'''
one(old,new,'ws block')
one('        payload={"version":VERSION,"l2_version":L2_VERSION,"vex_version":VEX_VERSION,"active_strategy_effect":"NONE",',
    '        payload={"version":VERSION,"l2_version":L2_VERSION,"vex_version":VEX_VERSION,"transport_version":"BINANCE_ROUTED_WS_V1","public_ws_url":self.cfg.public_ws_url,"market_ws_url":self.cfg.market_ws_url,"active_strategy_effect":"NONE",','status payload')
p.write_text(s,encoding='utf-8')
PY

NEW_SHA=$(sha256sum "$APP" | awk '{print $1}')
if [[ "$NEW_SHA" != "$HOTFIX_SHA" ]]; then
  echo "Patched SHA mismatch: $NEW_SHA expected $HOTFIX_SHA; restoring" >&2
  cp -a "$BACKUP/l2_vex.py.before" "$APP"
  exit 1
fi
"$PY" -m py_compile "$APP"
if grep -nE '(/fapi/v[0-9]+/(order|batchOrders|leverage|marginType)|X-MBX-APIKEY|signature=|secret_key|api_key)' "$APP"; then
  echo 'Forbidden order/auth marker found; restoring' >&2
  cp -a "$BACKUP/l2_vex.py.before" "$APP"
  exit 1
fi

systemctl restart "$SIDE"
sleep 10
systemctl is-active --quiet "$SIDE"
MAIN_PID_AFTER=$(systemctl show -p MainPID --value "$MAIN")
EP_PID_AFTER=$(systemctl show -p MainPID --value "$EP")
[[ "$MAIN_PID_AFTER" == "$MAIN_PID_BEFORE" ]] || { echo 'Main service PID changed' >&2; exit 1; }
[[ "$EP_PID_AFTER" == "$EP_PID_BEFORE" ]] || { echo '4.9.4 research PID changed' >&2; exit 1; }

echo 'PUMPRADAR_L2_VEX_DIRECT_HOTFIX1_OK=1'
echo 'MAIN_4.9.2_UNCHANGED=1 MAIN_SERVICE_RESTARTED=0'
echo 'RESEARCH_4.9.4_UNCHANGED=1 RESEARCH_4.9.4_RESTARTED=0'
echo 'ACTIVE_STRATEGY_EFFECT=NONE'
echo 'TRANSPORT=BINANCE_ROUTED_WS_V1'
echo "backup=$BACKUP"
cat /var/lib/pumpradar/l2-vex/status.json || true
