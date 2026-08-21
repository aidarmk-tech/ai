#!/usr/bin/env bash
set -Eeuo pipefail
MAIN='pumpradar.service'
EP='pumpradar-research-episodes.service'
SIDE='pumpradar-l2-vex.service'
APP='/opt/pumpradar-l2-vex/l2_vex.py'
PY='/opt/pumpradar/venv/bin/python'
BASE_SHA='6e09dccb7ee0e2350ca6842df28b8651a5bf7c1f584255cbf45173429beed893'
HOTFIX_SHA='2188524aa3722f358a834eba36ec3289762e4476c36b9e8aed91feda4eeeedfc'
STAMP="$(date -u +%Y%m%d-%H%M%S)"
BACKUP="/opt/pumpradar/backups/pre-l2-vex-direct-hotfix2-$STAMP"

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
  echo 'HOTFIX2_ALREADY_APPLIED=1'
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

one('VERSION = "L2-VEX-RESEARCH-V1-HOTFIX1"','VERSION = "L2-VEX-RESEARCH-V1-HOTFIX2"','version')
old='    async def public_ws_loop(self,session:aiohttp.ClientSession,symbols:list[str],depth:set[str])->None:\n        # Binance USD-M split WebSocket routing (2026): bookTicker/depth are PUBLIC.\n        streams=[]\n        for s in symbols:\n            low=s.lower(); streams.append(f"{low}@bookTicker")\n            if s in depth: streams.append(f"{low}@depth20@100ms")\n        url=self.cfg.public_ws_url.rstrip("/")+"/stream?streams="+"/".join(streams)\n        LOG.info("public ws connect streams=%d url_len=%d",len(streams),len(url))\n        async with session.ws_connect(url,heartbeat=20,receive_timeout=45,max_msg_size=8*1024*1024) as ws:\n            async for msg in ws:\n                if msg.type==aiohttp.WSMsgType.TEXT:\n                    received=now_ms()\n                    try:\n                        w=json.loads(msg.data); data=w.get("data",w); stream=str(w.get("stream","")); symbol=str(data.get("s") or stream.split("@",1)[0].upper())\n                        ev=str(data.get("e") or "")\n                        if "lastUpdateId" in data or ev=="depthUpdate": self.on_depth(symbol,data,received)\n                        elif data.get("b") is not None and data.get("a") is not None: self.on_book(symbol,data,received)\n                    except Exception: self.errors+=1; LOG.exception("public ws message failed")\n                elif msg.type in (aiohttp.WSMsgType.ERROR,aiohttp.WSMsgType.CLOSED,aiohttp.WSMsgType.CLOSE):\n                    raise RuntimeError(f"public websocket ended type={msg.type} data={msg.data!r}")\n        raise RuntimeError("public websocket ended")\n'
new='    async def public_ws_loop(self,session:aiohttp.ClientSession,symbols:list[str],depth:set[str])->None:\n        # Binance USD-M PUBLIC streams. Subscribe after connect instead of encoding\n        # the full universe in the URL; this avoids long combined URLs being closed\n        # by the public gateway and gives us explicit subscribe error responses.\n        streams=[]\n        for s in symbols:\n            low=s.lower(); streams.append(f"{low}@bookTicker")\n            if s in depth: streams.append(f"{low}@depth20@100ms")\n        url=self.cfg.public_ws_url.rstrip("/")+"/stream"\n        LOG.info("public ws connect/subscription streams=%d url=%s",len(streams),url)\n        async with session.ws_connect(url,heartbeat=20,receive_timeout=45,max_msg_size=8*1024*1024) as ws:\n            request_id=1\n            for start in range(0,len(streams),50):\n                chunk=streams[start:start+50]\n                await ws.send_json({"method":"SUBSCRIBE","params":chunk,"id":request_id})\n                LOG.info("public ws subscribe id=%d streams=%d",request_id,len(chunk))\n                request_id+=1\n                await asyncio.sleep(0.25)\n            async for msg in ws:\n                if msg.type==aiohttp.WSMsgType.TEXT:\n                    received=now_ms()\n                    try:\n                        w=json.loads(msg.data)\n                        if isinstance(w,dict) and ("code" in w or ("msg" in w and "data" not in w and "stream" not in w)):\n                            raise RuntimeError(f"public subscribe error payload={w!r}")\n                        if isinstance(w,dict) and "result" in w and "id" in w:\n                            if w.get("result") is not None:\n                                LOG.warning("public ws subscribe ack id=%s result=%r",w.get("id"),w.get("result"))\n                            continue\n                        data=w.get("data",w) if isinstance(w,dict) else {}\n                        stream=str(w.get("stream","")) if isinstance(w,dict) else ""\n                        symbol=str(data.get("s") or stream.split("@",1)[0].upper())\n                        ev=str(data.get("e") or "")\n                        if "lastUpdateId" in data or ev=="depthUpdate": self.on_depth(symbol,data,received)\n                        elif data.get("b") is not None and data.get("a") is not None: self.on_book(symbol,data,received)\n                    except RuntimeError:\n                        raise\n                    except Exception:\n                        self.errors+=1; LOG.exception("public ws message failed payload=%r",msg.data[:500] if isinstance(msg.data,str) else msg.data)\n                elif msg.type in (aiohttp.WSMsgType.ERROR,aiohttp.WSMsgType.CLOSED,aiohttp.WSMsgType.CLOSE):\n                    raise RuntimeError(f"public websocket ended type={msg.type} data={msg.data!r} extra={msg.extra!r}")\n        raise RuntimeError(f"public websocket ended close_code={ws.close_code}")\n'
one(old,new,'public websocket block')
one('"transport_version":"BINANCE_ROUTED_WS_V1"','"transport_version":"BINANCE_ROUTED_WS_SUBSCRIBE_V2"','transport version')
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

echo 'PUMPRADAR_L2_VEX_DIRECT_HOTFIX2_OK=1'
echo 'MAIN_4.9.2_UNCHANGED=1 MAIN_SERVICE_RESTARTED=0'
echo 'RESEARCH_4.9.4_UNCHANGED=1 RESEARCH_4.9.4_RESTARTED=0'
echo 'ACTIVE_STRATEGY_EFFECT=NONE'
echo 'TRANSPORT=BINANCE_ROUTED_WS_SUBSCRIBE_V2'
echo "backup=$BACKUP"
cat /var/lib/pumpradar/l2-vex/status.json || true
