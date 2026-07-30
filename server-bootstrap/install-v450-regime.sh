#!/usr/bin/env bash
set -Eeuo pipefail

BRANCH="chatgpt/pumpradar-v450-regime-paper"
RAW_BASE="https://raw.githubusercontent.com/aidarmk-tech/ai/${BRANCH}/server-bootstrap/v450payload"
PAYLOAD_PARTS=(00 01 02 07 08 09 10 11 12 13 14 15 16 17)
PATCH_SHA256="70f123b9ae3b009dbe0664832e54bee2d7d25ee5cf9b484119ecde36ce7e8241"
APP="/opt/pumpradar/server"
DB="/var/lib/pumpradar/pumpradar.sqlite3"
SERVICE="pumpradar.service"
WATCHDOG_TIMER="pumpradar-watchdog.timer"
STAMP="$(date +%Y%m%d-%H%M%S)"
CANDIDATE="/opt/pumpradar/server.v450-candidate-${STAMP}"
OLD_LIVE="/opt/pumpradar/server.before-v450-${STAMP}"
FAILED_LIVE="/opt/pumpradar/server.failed-v450-${STAMP}"
DB_BACKUP="${DB}.before-v450-${STAMP}"
WORK="$(mktemp -d /tmp/pumpradar-v450.XXXXXX)"
PATCH="$WORK/apply-v450.py"
MIGRATION_DB="$WORK/migration.sqlite3"
WATCHDOG_WAS_ACTIVE=0
SERVICE_WAS_STOPPED=0
SWAPPED=0

cleanup() {
  local rc=$?
  rm -rf "$WORK"
  if [[ $WATCHDOG_WAS_ACTIVE -eq 1 ]]; then
    systemctl start "$WATCHDOG_TIMER" >/dev/null 2>&1 || true
  fi
  exit "$rc"
}
trap cleanup EXIT

fail() {
  echo "ОШИБКА: $*" >&2
  exit 1
}

[[ ${EUID:-$(id -u)} -eq 0 ]] || fail "запусти команду от root"
[[ -d "$APP/pumpradar_server" ]] || fail "не найден PumpRadar: $APP"
[[ -f "$DB" ]] || fail "не найдена база: $DB"
command -v curl >/dev/null || fail "не найден curl"
command -v python3 >/dev/null || fail "не найден python3"
command -v systemctl >/dev/null || fail "не найден systemctl"

open_positions() {
  python3 - "$DB" <<'PY'
import sqlite3, sys
p=sys.argv[1]
checks=[
 ('paper_slots','baseline_status'),
 ('policy_runs','state'),
 ('momentum_slots','primary_status'),
 ('momentum_policy_runs','state'),
 ('futures_momentum_slots','primary_status'),
 ('futures_momentum_policy_runs','state'),
 ('regime_slots','status'),
]
con=sqlite3.connect(f"file:{p}?mode=ro", uri=True, timeout=10)
total=0
for table, col in checks:
    exists=con.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",(table,)).fetchone()
    if not exists:
        continue
    cols={row[1] for row in con.execute(f'PRAGMA table_info("{table}")')}
    if col not in cols:
        continue
    total += int(con.execute(f'SELECT COUNT(*) FROM "{table}" WHERE "{col}"=?',('OPEN',)).fetchone()[0])
print(total)
con.close()
PY
}

sqlite_backup() {
  local src=$1 dst=$2
  python3 - "$src" "$dst" <<'PY'
import sqlite3, sys, os
src,dst=sys.argv[1:]
os.makedirs(os.path.dirname(dst) or '.', exist_ok=True)
if os.path.exists(dst): os.remove(dst)
a=sqlite3.connect(src, timeout=30)
b=sqlite3.connect(dst, timeout=30)
with b:
    a.backup(b)
b.close(); a.close()
PY
}

restore_after_failure() {
  local why=$1
  echo "Проверка после запуска не пройдена: $why"
  systemctl stop "$SERVICE" >/dev/null 2>&1 || true
  if [[ $SWAPPED -eq 1 && -d "$APP" ]]; then
    mv "$APP" "$FAILED_LIVE" || true
  fi
  if [[ -d "$OLD_LIVE" ]]; then
    mv "$OLD_LIVE" "$APP"
  fi
  if [[ -f "$DB_BACKUP" ]]; then
    cp -a "$DB_BACKUP" "$DB"
    rm -f "${DB}-wal" "${DB}-shm"
  fi
  systemctl daemon-reload
  systemctl start "$SERVICE" || true
  sleep 8
  fail "выполнен автоматический откат; неудачная версия сохранена в $FAILED_LIVE"
}

echo "=== PumpRadar v4.5 preflight ==="
OPEN="$(open_positions)"
echo "Открытых paper-позиций: $OPEN"
[[ "$OPEN" == "0" ]] || fail "обновление остановлено до закрытия всех paper-позиций"

: > "$WORK/payload.b64"
for part in "${PAYLOAD_PARTS[@]}"; do
  curl -fL --retry 5 --retry-delay 2 --connect-timeout 15 \
    "$RAW_BASE/$part" >> "$WORK/payload.b64"
done
base64 -d "$WORK/payload.b64" > "$PATCH" || fail "payload не декодируется"
ACTUAL_SHA="$(sha256sum "$PATCH" | awk '{print $1}')"
[[ "$ACTUAL_SHA" == "$PATCH_SHA256" ]] || fail "SHA-256 payload не совпал: $ACTUAL_SHA"
python3 -m py_compile "$PATCH"

echo "=== Build candidate ==="
rm -rf "$CANDIDATE"
cp -a --reflink=auto "$APP" "$CANDIDATE"
python3 "$PATCH" "$CANDIDATE"
python3 -m compileall -q "$CANDIDATE/pumpradar_server" "$CANDIDATE/tests"

if grep -RInE --exclude-dir='__pycache__' \
  '(/fapi/v1/order|/fapi/v1/batchOrders|/api/v3/order|X-MBX-APIKEY|signature=)' \
  "$CANDIDATE/pumpradar_server"; then
  fail "в candidate найден запрещённый контур реальных ордеров"
fi

PYTHONPATH="$CANDIDATE" timeout 240 python3 -m unittest discover -s "$CANDIDATE/tests" -v

sqlite_backup "$DB" "$MIGRATION_DB"
mkdir -p "$WORK/data"
PUMPRADAR_DB_PATH="$MIGRATION_DB" \
PUMPRADAR_DATA_DIR="$WORK/data" \
PYTHONPATH="$CANDIDATE" \
python3 - <<'PY'
from pumpradar_server.config import Settings
from pumpradar_server.storage import Storage
from pumpradar_server.futures import FuturesMarketState
from pumpradar_server.regime import RegimePaperManager
s=Settings()
st=Storage(s)
async def notify(_: str): pass
manager=RegimePaperManager(s, FuturesMarketState(), st, notify)
for table in ('regime_signals','regime_slots'):
    assert st.conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",(table,)).fetchone(), table
assert st.conn.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
assert not list(st.conn.execute('PRAGMA foreign_key_check'))
assert manager.status()['regime_primary_channel'] == 'REV_MC5_SHORT_600_2X'
st.conn.close()
print('migration check: ok')
PY

OPEN="$(open_positions)"
[[ "$OPEN" == "0" ]] || fail "за время подготовки появилась paper-позиция; обновление отменено"

if systemctl is-active --quiet "$WATCHDOG_TIMER" 2>/dev/null; then
  WATCHDOG_WAS_ACTIVE=1
  systemctl stop "$WATCHDOG_TIMER"
fi

systemctl stop "$SERVICE"
SERVICE_WAS_STOPPED=1
OPEN="$(open_positions)"
if [[ "$OPEN" != "0" ]]; then
  systemctl start "$SERVICE"
  SERVICE_WAS_STOPPED=0
  fail "после остановки обнаружены открытые paper-позиции: $OPEN"
fi

sqlite_backup "$DB" "$DB_BACKUP"
mv "$APP" "$OLD_LIVE"
mv "$CANDIDATE" "$APP"
SWAPPED=1
systemctl daemon-reload
systemctl start "$SERVICE" || restore_after_failure "systemd не запустил сервис"
SERVICE_WAS_STOPPED=0

sleep 18
set -a
# shellcheck disable=SC1091
source /etc/pumpradar/server.env
set +a
PORT="${PUMPRADAR_BIND_PORT:-8787}"
HEALTH="$WORK/health.json"
if ! curl -fsS --max-time 10 "http://127.0.0.1:${PORT}/healthz" -o "$HEALTH"; then
  restore_after_failure "/healthz не отвечает"
fi
if ! python3 - "$HEALTH" <<'PY'
import json,sys
p=json.load(open(sys.argv[1]))
assert p.get('ok') is True, p
assert p.get('spot_ok') is True, p
assert p.get('futures_ok') is True, p
assert p.get('regime_primary_channel') == 'REV_MC5_SHORT_600_2X', p
print(json.dumps(p, ensure_ascii=False, indent=2))
PY
then
  restore_after_failure "health payload не подтвердил v4.5"
fi

if [[ $WATCHDOG_WAS_ACTIVE -eq 1 ]]; then
  systemctl start "$WATCHDOG_TIMER"
fi

echo "=== Installed ==="
echo "Версия: 4.5.0-server"
echo "Основной paper: REV_MC5_SHORT_600_2X"
echo "Shadow: ONSET_LONG_SHADOW + DUMP_EXHAUSTION_LONG_SHADOW"
echo "Старый код: $OLD_LIVE"
echo "Бэкап базы: $DB_BACKUP"
systemctl --no-pager --full status "$SERVICE" | sed -n '1,12p'
if systemctl list-unit-files "$WATCHDOG_TIMER" >/dev/null 2>&1; then
  systemctl --no-pager --full status "$WATCHDOG_TIMER" | sed -n '1,10p'
fi
