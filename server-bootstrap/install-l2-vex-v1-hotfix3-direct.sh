#!/usr/bin/env bash
set -Eeuo pipefail
MAIN='pumpradar.service'
EP='pumpradar-research-episodes.service'
SIDE='pumpradar-l2-vex.service'
APP='/opt/pumpradar-l2-vex/l2_vex.py'
PY='/opt/pumpradar/venv/bin/python'
BASE_SHA='2188524aa3722f358a834eba36ec3289762e4476c36b9e8aed91feda4eeeedfc'
HOTFIX_SHA='4f1944d1720805ea0c82352285c67986672e659d1be406712712e23f8d826ce9'
STAMP="$(date -u +%Y%m%d-%H%M%S)"
BACKUP="/opt/pumpradar/backups/pre-l2-vex-direct-hotfix3-$STAMP"

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
  echo 'HOTFIX3_ALREADY_APPLIED=1'
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
    if n!=1:
        raise SystemExit(f'{label}: expected 1 match, got {n}')
    s=s.replace(old,new,1)

one('VERSION = "L2-VEX-RESEARCH-V1-HOTFIX2"','VERSION = "L2-VEX-RESEARCH-V1-HOTFIX3"','version')
one(
'''        st.last_book=BookSnapshot(ts,bids,asks); st.last_book_ms=received
        if st.bid<=0: st.bid=bids[0].price
        if st.ask<=0: st.ask=asks[0].price
''',
'''        st.last_book=BookSnapshot(ts,bids,asks); st.last_book_ms=received
        # Partial depth already contains the live best bid/ask. Refresh spread from
        # each depth frame so per-symbol bookTicker subscriptions are unnecessary.
        st.bid=bids[0].price
        st.ask=asks[0].price
''','depth best bid/ask')

start=s.index('    async def public_ws_loop(')
end=s.index('    async def trade_ws_loop(',start)
new_public='''    async def public_ws_shard_loop(self,session:aiohttp.ClientSession,streams:list[str],shard_id:int)->None:\n        # Keep high-frequency depth traffic well below per-connection stream/load\n        # limits. Each shard uses a short combined URL and carries depth only.\n        url=self.cfg.public_ws_url.rstrip("/")+"/stream?streams="+"/".join(streams)\n        LOG.info("public depth shard connect id=%d streams=%d url_len=%d",shard_id,len(streams),len(url))\n        async with session.ws_connect(url,heartbeat=20,receive_timeout=45,max_msg_size=8*1024*1024) as ws:\n            async for msg in ws:\n                if msg.type==aiohttp.WSMsgType.TEXT:\n                    received=now_ms()\n                    try:\n                        w=json.loads(msg.data); data=w.get("data",w); stream=str(w.get("stream","")); symbol=str(data.get("s") or stream.split("@",1)[0].upper())\n                        ev=str(data.get("e") or "")\n                        if "lastUpdateId" in data or ev=="depthUpdate": self.on_depth(symbol,data,received)\n                    except Exception:\n                        self.errors+=1; LOG.exception("public depth shard message failed id=%d",shard_id)\n                elif msg.type in (aiohttp.WSMsgType.ERROR,aiohttp.WSMsgType.CLOSED,aiohttp.WSMsgType.CLOSE):\n                    raise RuntimeError(f"public depth shard {shard_id} ended type={msg.type} data={msg.data!r} extra={msg.extra!r}")\n        raise RuntimeError(f"public depth shard {shard_id} ended close_code={ws.close_code}")\n\n    async def public_ws_loop(self,session:aiohttp.ClientSession,symbols:list[str],depth:set[str])->None:\n        # L2 states are produced only for depth_symbols, so bookTicker for the\n        # entire trading universe is redundant. Shard partial depth20@100ms into\n        # small connections to avoid close_code=1006 under bursty public traffic.\n        streams=[f"{s.lower()}@depth20@100ms" for s in sorted(depth)]\n        shard_size=12\n        shards=[streams[i:i+shard_size] for i in range(0,len(streams),shard_size)]\n        if not shards:\n            await self.stop_event.wait(); return\n        LOG.info("public depth sharding symbols=%d shards=%d shard_size=%d",len(streams),len(shards),shard_size)\n        tasks=[asyncio.create_task(self.public_ws_shard_loop(session,chunk,i+1),name=f"l2vex-public-depth-{i+1}") for i,chunk in enumerate(shards)]\n        try:\n            done,_=await asyncio.wait(set(tasks),return_when=asyncio.FIRST_COMPLETED)\n            for task in done:\n                if task.cancelled(): continue\n                exc=task.exception()\n                if exc is not None: raise exc\n            raise RuntimeError("public depth shard ended without exception")\n        finally:\n            for task in tasks:\n                if not task.done(): task.cancel()\n            await asyncio.gather(*tasks,return_exceptions=True)\n\n'''
s=s[:start]+new_public+s[end:]

one(
'''                for r in rows:
                    ts=int(r["created_at_ms"]);self.last_source_signal_ms=max(self.last_source_signal_ms,ts);s=str(r["symbol"]);self.forced_until[s]=now_ms()+self.cfg.forced_signal_seconds*1000
                    st=self.states.get(s);l=st.current_l2 if st else None
                    self.db.conn.execute("INSERT OR IGNORE INTO research_l2_signal_context(signal_id,symbol,channel,signal_at_ms,matched_frame_at_ms,l2_state,l2_quality,buy_ratio_15s,pressure_score,ask_refill_to_eaten_15s,bid_refill_to_eaten_15s,ret_15s,ret_60s,volume_z_15s,spread_bps,obi10,oi_change_1m_pct,engine_hint,details_json,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     (r["id"],s,r["channel"],ts,l.get("frame_at_ms") if l else None,l.get("state") if l else None,l.get("quality") if l else None,l.get("buy_ratio_15s") if l else None,l.get("pressure_score") if l else None,l.get("ask_refill_to_eaten_15s") if l else None,l.get("bid_refill_to_eaten_15s") if l else None,l.get("ret_15s") if l else None,l.get("ret_60s") if l else None,l.get("volume_z_15s") if l else None,l.get("spread_bps") if l else None,l.get("obi10") if l else None,l.get("oi_change_1m_pct") if l else None,self._engine_from_l2(l),json.dumps({"disposition":r["disposition"],"matched_live":bool(l)},sort_keys=True,separators=(",",":")),now_ms()))
                    if s not in self.depth_symbols:self.universe_kick.set()
''',
'''                for r in rows:
                    ts=int(r["created_at_ms"]);self.last_source_signal_ms=max(self.last_source_signal_ms,ts);s=str(r["symbol"]);channel=str(r["channel"])
                    # Only active/control channels force temporary L2 coverage. Shadow
                    # DUMP/auxiliary signals are still logged if already covered, but
                    # cannot inflate the high-frequency depth universe.
                    force_l2=channel in ("REV_MC5_SHORT_600_2X","REV_MC5_EURO_LONG60_2X")
                    if force_l2:self.forced_until[s]=now_ms()+self.cfg.forced_signal_seconds*1000
                    st=self.states.get(s);l=st.current_l2 if st else None
                    self.db.conn.execute("INSERT OR IGNORE INTO research_l2_signal_context(signal_id,symbol,channel,signal_at_ms,matched_frame_at_ms,l2_state,l2_quality,buy_ratio_15s,pressure_score,ask_refill_to_eaten_15s,bid_refill_to_eaten_15s,ret_15s,ret_60s,volume_z_15s,spread_bps,obi10,oi_change_1m_pct,engine_hint,details_json,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     (r["id"],s,channel,ts,l.get("frame_at_ms") if l else None,l.get("state") if l else None,l.get("quality") if l else None,l.get("buy_ratio_15s") if l else None,l.get("pressure_score") if l else None,l.get("ask_refill_to_eaten_15s") if l else None,l.get("bid_refill_to_eaten_15s") if l else None,l.get("ret_15s") if l else None,l.get("ret_60s") if l else None,l.get("volume_z_15s") if l else None,l.get("spread_bps") if l else None,l.get("obi10") if l else None,l.get("oi_change_1m_pct") if l else None,self._engine_from_l2(l),json.dumps({"disposition":r["disposition"],"matched_live":bool(l),"forced_l2":force_l2},sort_keys=True,separators=(",",":")),now_ms()))
                    if force_l2 and s not in self.depth_symbols:self.universe_kick.set()
''','signal forcing')
one('"transport_version":"BINANCE_ROUTED_WS_SUBSCRIBE_V2"','"transport_version":"BINANCE_PUBLIC_DEPTH_SHARDS_V3"','transport version')
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

echo 'PUMPRADAR_L2_VEX_DIRECT_HOTFIX3_OK=1'
echo 'MAIN_4.9.2_UNCHANGED=1 MAIN_SERVICE_RESTARTED=0'
echo 'RESEARCH_4.9.4_UNCHANGED=1 RESEARCH_4.9.4_RESTARTED=0'
echo 'ACTIVE_STRATEGY_EFFECT=NONE'
echo 'TRANSPORT=BINANCE_PUBLIC_DEPTH_SHARDS_V3'
echo 'PUBLIC_DEPTH_ONLY=1 SHARD_SIZE=12 CONTROL_SIGNAL_FORCE_ONLY=1'
echo "backup=$BACKUP"
cat /var/lib/pumpradar/l2-vex/status.json || true
