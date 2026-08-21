#!/usr/bin/env bash
set -Eeuo pipefail

MAIN='pumpradar.service'
EP='pumpradar-research-episodes.service'
SIDE='pumpradar-l2-vex.service'
APP='/opt/pumpradar-l2-vex/l2_vex.py'
PY='/opt/pumpradar/venv/bin/python'
BASE_SHA='4f1944d1720805ea0c82352285c67986672e659d1be406712712e23f8d826ce9'
STAMP="$(date -u +%Y%m%d-%H%M%S)"
BACKUP="/opt/pumpradar/backups/pre-l2-vex-direct-hotfix4-$STAMP"

[[ ${EUID:-$(id -u)} -eq 0 ]] || { echo 'Run as root' >&2; exit 1; }
[[ -f "$APP" ]] || { echo "Missing $APP" >&2; exit 1; }
systemctl is-active --quiet "$MAIN" || { echo "$MAIN not active" >&2; exit 1; }
systemctl is-active --quiet "$EP" || { echo "$EP not active" >&2; exit 1; }
systemctl is-active --quiet "$SIDE" || { echo "$SIDE not active" >&2; exit 1; }

MAIN_PID_BEFORE=$(systemctl show -p MainPID --value "$MAIN")
EP_PID_BEFORE=$(systemctl show -p MainPID --value "$EP")
CURRENT_SHA=$(sha256sum "$APP" | awk '{print $1}')
echo "current_l2_vex_sha=$CURRENT_SHA"

if grep -Fq 'VERSION = "L2-VEX-RESEARCH-V1-HOTFIX4"' "$APP"; then
  echo 'HOTFIX4_ALREADY_APPLIED=1'
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

one('VERSION = "L2-VEX-RESEARCH-V1-HOTFIX3"',
    'VERSION = "L2-VEX-RESEARCH-V1-HOTFIX4"',
    'version')

one(
'''        spread=finite(f.get("spread_bps")); book_age=int(f.get("book_age_ms") or 10**9); trade_age=int(f.get("trade_age_ms") or 10**9)\n''',
'''        spread=finite(f.get("spread_bps"))\n        # Age=0 ms means the frame is maximally fresh. Do not use `or` here:\n        # Python treats numeric zero as false and previously converted a valid\n        # 0 ms age into the 1e9-ms missing-data sentinel, forcing DATA_UNSAFE.\n        book_age_raw=f.get("book_age_ms"); trade_age_raw=f.get("trade_age_ms")\n        book_age=int(book_age_raw) if book_age_raw is not None else 10**9\n        trade_age=int(trade_age_raw) if trade_age_raw is not None else 10**9\n''',
    'zero-age freshness')

p.write_text(s,encoding='utf-8')
PY

"$PY" -m py_compile "$APP"
if grep -nE '(/fapi/v[0-9]+/(order|batchOrders|leverage|marginType)|X-MBX-APIKEY|signature=|secret_key|api_key)' "$APP"; then
  echo 'Forbidden order/auth marker found; restoring' >&2
  cp -a "$BACKUP/l2_vex.py.before" "$APP"
  exit 1
fi

NEW_SHA=$(sha256sum "$APP" | awk '{print $1}')
echo "patched_l2_vex_sha=$NEW_SHA"

systemctl restart "$SIDE"
sleep 10
systemctl is-active --quiet "$SIDE"

MAIN_PID_AFTER=$(systemctl show -p MainPID --value "$MAIN")
EP_PID_AFTER=$(systemctl show -p MainPID --value "$EP")
[[ "$MAIN_PID_AFTER" == "$MAIN_PID_BEFORE" ]] || { echo 'Main service PID changed' >&2; exit 1; }
[[ "$EP_PID_AFTER" == "$EP_PID_BEFORE" ]] || { echo '4.9.4 research PID changed' >&2; exit 1; }

echo 'PUMPRADAR_L2_VEX_DIRECT_HOTFIX4_OK=1'
echo 'MAIN_4.9.2_UNCHANGED=1 MAIN_SERVICE_RESTARTED=0'
echo 'RESEARCH_4.9.4_UNCHANGED=1 RESEARCH_4.9.4_RESTARTED=0'
echo 'ACTIVE_STRATEGY_EFFECT=NONE'
echo 'TRANSPORT=BINANCE_PUBLIC_DEPTH_SHARDS_V3'
echo 'QUALITY_FIX=ZERO_AGE_IS_FRESH_V1'
echo "backup=$BACKUP"
cat /var/lib/pumpradar/l2-vex/status.json || true
