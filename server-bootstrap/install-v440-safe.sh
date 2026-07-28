#!/usr/bin/env bash
set -Eeuo pipefail

# PumpRadar v4.4.0 — paper-only self-validating installer.
# Builds from immutable repository commits, tests in /tmp, migrates a DB copy,
# and switches the service only when no paper/policy position is open.

V439_COMMIT="3aa8bf2924026eec37ebf8025e42f707b4fa6246"
V440_COMMIT="6836b15b31d8b72bba4078602cfb09a7633ef7d6"
V439_RAW="https://raw.githubusercontent.com/aidarmk-tech/ai/${V439_COMMIT}/server-bootstrap"
V440_RAW="https://raw.githubusercontent.com/aidarmk-tech/ai/${V440_COMMIT}/server-bootstrap"
V439_PAYLOAD_SHA256="f38a8b4cb8ae3a3797111bf48f34f05f91ee3e9e01567090fda49156da93d80c"
EXPECTED_VERSION="4.4.0-server"
APP_ROOT="/opt/pumpradar"
DATA_DIR="${PUMPRADAR_DATA_DIR:-/var/lib/pumpradar}"
DB_PATH="${PUMPRADAR_DB_PATH:-${DATA_DIR}/pumpradar.sqlite3}"
ENV_DIR="/etc/pumpradar"
ENV_FILE="${ENV_DIR}/server.env"
SERVICE_USER="pumpradar"
TMP_DIR="$(mktemp -d)"
SERVICE_WAS_ACTIVE=0
SWITCH_STARTED=0

log() { printf '\n[PumpRadar] %s\n' "$*"; }
fail() { printf '\n[PumpRadar] ERROR: %s\n' "$*" >&2; exit 1; }
cleanup() { rm -rf "$TMP_DIR"; }
rollback() {
  local code=$?
  if (( SWITCH_STARTED == 1 )); then
    log "Установка не завершена; выполняется автоматический откат"
    systemctl stop pumpradar.service 2>/dev/null || true
    rm -rf "$APP_ROOT/server"
    if [[ -d "$APP_ROOT/server.previous" ]]; then
      mv "$APP_ROOT/server.previous" "$APP_ROOT/server"
      systemctl start pumpradar.service 2>/dev/null || true
    fi
  elif (( SERVICE_WAS_ACTIVE == 1 )); then
    systemctl start pumpradar.service 2>/dev/null || true
  fi
  cleanup
  exit "$code"
}
trap cleanup EXIT
trap rollback ERR

[[ ${EUID:-$(id -u)} -eq 0 ]] || fail "Запустите установщик от root"

log "Установка системных инструментов для безопасной сборки"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates curl python3 python3-venv sqlite3 gzip tar rclone ufw openssh-server

open_count() {
  [[ -f "$DB_PATH" ]] || { printf '0\n'; return; }
  local total=0 table predicate n
  while IFS='|' read -r table predicate; do
    if [[ "$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table';")" == "1" ]]; then
      n="$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM $table WHERE $predicate;")"
      total=$((total + n))
    fi
  done <<'EOF'
paper_slots|baseline_status='OPEN'
policy_runs|state='OPEN'
momentum_slots|primary_status='OPEN'
momentum_policy_runs|state='OPEN'
futures_momentum_slots|primary_status='OPEN'
futures_momentum_policy_runs|state='OPEN'
EOF
  printf '%s\n' "$total"
}

initial_open="$(open_count)"
(( initial_open == 0 )) || fail "Найдены открытые paper/policy записи: $initial_open. Дождитесь закрытия."

log "Загрузка проверенного исходного пакета v4.3.9"
mkdir -p "$TMP_DIR/source-root/server-bootstrap/v440-src"
: > "$TMP_DIR/v439.b64"
for part in 00 01 02 03; do
  curl --fail --silent --show-error --retry 4 --retry-delay 2 \
    "$V439_RAW/v439release/$part" >> "$TMP_DIR/v439.b64"
done
base64 --decode "$TMP_DIR/v439.b64" > "$TMP_DIR/v439.tar.gz"
actual_v439_hash="$(sha256sum "$TMP_DIR/v439.tar.gz" | awk '{print $1}')"
[[ "$actual_v439_hash" == "$V439_PAYLOAD_SHA256" ]] || \
  fail "Контрольная сумма v4.3.9 не совпала: $actual_v439_hash"
tar -tzf "$TMP_DIR/v439.tar.gz" >/dev/null
tar -xzf "$TMP_DIR/v439.tar.gz" -C "$TMP_DIR/source-root"
[[ -d "$TMP_DIR/source-root/pumpradar-server/pumpradar_server" ]] || \
  fail "В исходном пакете нет сервера"

log "Загрузка зафиксированного патча v4.4.0: MC7 + USD-M futures paper"
curl --fail --silent --show-error --retry 4 \
  "$V440_RAW/apply-v440.py" -o "$TMP_DIR/source-root/server-bootstrap/apply-v440.py"
for name in futures.00 futures.01 futures.02 futures.03 futures.04 \
            apply.00 apply.01 apply.02 apply.03 test_futures.py; do
  curl --fail --silent --show-error --retry 4 \
    "$V440_RAW/v440-src/$name" \
    -o "$TMP_DIR/source-root/server-bootstrap/v440-src/$name"
done

log "Сборка и тестирование v4.4.0 во временном каталоге"
(
  cd "$TMP_DIR/source-root"
  python3 server-bootstrap/apply-v440.py
  grep -q 'algorithm_version: str = "4.4.0-server"' \
    pumpradar-server/pumpradar_server/config.py
  python3 -m compileall -q pumpradar-server/pumpradar_server
  PYTHONPATH=pumpradar-server python3 -m unittest discover \
    -s pumpradar-server/tests -v
  if grep -R -nE 'fapi/v1/order|api/v3/order|create_order|newOrder|API_SECRET|SIGNED' \
      pumpradar-server/pumpradar_server; then
    echo "Обнаружен запрещённый код реальных ордеров" >&2
    exit 1
  fi
)

log "Проверка миграции на отдельной копии текущей SQLite"
MIGRATION_DB="$TMP_DIR/migration.sqlite3"
if [[ -f "$DB_PATH" ]]; then
  sqlite3 "$DB_PATH" ".backup '$MIGRATION_DB'"
else
  : > "$MIGRATION_DB"
fi
PUMPRADAR_DATA_DIR="$TMP_DIR/migration-data" \
PUMPRADAR_DB_PATH="$MIGRATION_DB" \
PYTHONPATH="$TMP_DIR/source-root/pumpradar-server" python3 - <<'PY'
from pumpradar_server.config import Settings
from pumpradar_server.futures import FuturesPaperStore
from pumpradar_server.storage import Storage
s = Settings()
st = Storage(s)
st.start_run("installer-migration-check")
fs = FuturesPaperStore(s, st)
assert st.conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
assert not list(st.conn.execute("PRAGMA foreign_key_check"))
for table in ("futures_signals", "futures_momentum_slots", "futures_momentum_policy_runs"):
    assert st.conn.execute(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", (table,)
    ).fetchone()[0] == 1
assert fs.status()["freeze_target_primary_trades"] == 100
st.conn.close()
print("migration_check=ok")
PY

SERVER_SOURCE="$TMP_DIR/source-root/pumpradar-server"
find "$SERVER_SOURCE" -type d -name __pycache__ -prune -exec rm -rf {} +
tar -czf "$TMP_DIR/pumpradar-v440.tar.gz" -C "$TMP_DIR/source-root" pumpradar-server
BUILD_SHA256="$(sha256sum "$TMP_DIR/pumpradar-v440.tar.gz" | awk '{print $1}')"
log "Локально проверенный пакет собран: SHA-256 $BUILD_SHA256"

if systemctl is-active --quiet pumpradar.service; then
  SERVICE_WAS_ACTIVE=1
fi
if [[ -x "$APP_ROOT/server/scripts/backup.sh" ]] && (( SERVICE_WAS_ACTIVE == 1 )); then
  log "Создание backup перед переключением"
  "$APP_ROOT/server/scripts/backup.sh"
fi

log "Подготовка каталогов, пользователя и Python-окружения"
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  useradd --system --home-dir "$DATA_DIR" --create-home --shell /usr/sbin/nologin "$SERVICE_USER"
fi
install -d -o root -g root -m 0755 "$APP_ROOT" "$ENV_DIR"
install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0750 "$DATA_DIR" "$DATA_DIR/exports"
rm -rf "$APP_ROOT/server.new"
cp -a "$SERVER_SOURCE" "$APP_ROOT/server.new"
chown -R root:root "$APP_ROOT/server.new"
chmod +x "$APP_ROOT/server.new/scripts/"*.sh

if [[ ! -x "$APP_ROOT/venv/bin/python" ]]; then
  python3 -m venv "$APP_ROOT/venv"
fi
"$APP_ROOT/venv/bin/pip" install --disable-pip-version-check --no-cache-dir \
  -r "$APP_ROOT/server.new/requirements.txt"

if [[ ! -f "$ENV_FILE" ]]; then
  API_TOKEN="$(python3 - <<'PY'
import secrets
print(secrets.token_urlsafe(32))
PY
)"
  cat > "$ENV_FILE" <<ENV
PUMPRADAR_DATA_DIR=$DATA_DIR
PUMPRADAR_DB_PATH=$DB_PATH
PUMPRADAR_BIND_HOST=127.0.0.1
PUMPRADAR_BIND_PORT=8787
PUMPRADAR_API_TOKEN=$API_TOKEN
PUMPRADAR_POSITION_USDT=20
PUMPRADAR_FEE_RATE=0.001
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
ENV
fi
ensure_env() {
  local key="$1" value="$2"
  grep -q "^${key}=" "$ENV_FILE" || printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
}
ensure_env PUMPRADAR_STOP_WATCH_INTERVAL_MS 500
ensure_env PUMPRADAR_FUTURES_FEE_RATE 0.0005
ensure_env BINANCE_FUTURES_REST_URL https://fapi.binance.com
ensure_env BINANCE_FUTURES_WS_URL wss://fstream.binance.com
ensure_env PUMPRADAR_FUTURES_MIN_24H_QUOTE_VOLUME 1000000
ensure_env PUMPRADAR_FUTURES_MAX_CANDIDATES 30
ensure_env PUMPRADAR_FUTURES_WARM_POOL_SIZE 80
ensure_env PUMPRADAR_FUTURES_DEEP_CANDIDATES 25
ensure_env PUMPRADAR_FUTURES_DEPTH_CANDIDATES 30
ensure_env PUMPRADAR_FUTURES_MAX_SPREAD_BPS 30
ensure_env PUMPRADAR_FUTURES_MAX_BUY_SLIPPAGE_PERCENT 0.15
ensure_env PUMPRADAR_FUTURES_MAX_SELL_SLIPPAGE_PERCENT 0.35
ensure_env PUMPRADAR_FREEZE_TARGET_PRIMARY_TRADES 100
chmod 0600 "$ENV_FILE"
chown -R "$SERVICE_USER:$SERVICE_USER" "$DATA_DIR"

log "Финальная проверка слотов и остановка старой версии"
(( "$(open_count)" == 0 )) || fail "За время сборки открылась paper-позиция; установка отменена."
systemctl stop pumpradar.service 2>/dev/null || true
if (( "$(open_count)" != 0 )); then
  (( SERVICE_WAS_ACTIVE == 1 )) && systemctl start pumpradar.service 2>/dev/null || true
  fail "После остановки обнаружена открытая позиция; установка отменена."
fi

log "Переключение на v4.4.0 с автоматическим откатом"
SWITCH_STARTED=1
rm -rf "$APP_ROOT/server.previous"
if [[ -d "$APP_ROOT/server" ]]; then
  mv "$APP_ROOT/server" "$APP_ROOT/server.previous"
fi
mv "$APP_ROOT/server.new" "$APP_ROOT/server"
install -m 0644 "$APP_ROOT/server/systemd/pumpradar.service" /etc/systemd/system/pumpradar.service
install -m 0644 "$APP_ROOT/server/systemd/pumpradar-backup.service" /etc/systemd/system/pumpradar-backup.service
install -m 0644 "$APP_ROOT/server/systemd/pumpradar-backup.timer" /etc/systemd/system/pumpradar-backup.timer
systemctl daemon-reload
systemctl enable --now pumpradar.service
systemctl enable --now pumpradar-backup.timer

set -a
source "$ENV_FILE"
set +a
PORT="${PUMPRADAR_BIND_PORT:-8787}"
STATUS=""
VERSION_SEEN=0
for _ in $(seq 1 90); do
  if systemctl is-active --quiet pumpradar.service; then
    STATUS="$(curl -fsS -H "Authorization: Bearer $PUMPRADAR_API_TOKEN" \
      "http://127.0.0.1:${PORT}/api/status" 2>/dev/null || true)"
    if echo "$STATUS" | grep -Eq \
      '"algorithm_version"[[:space:]]*:[[:space:]]*"4.4.0-server"'; then
      VERSION_SEEN=1
      break
    fi
  fi
  sleep 2
done
(( VERSION_SEEN == 1 )) || {
  systemctl status pumpradar.service --no-pager || true
  journalctl -u pumpradar.service -n 120 --no-pager || true
  false
}

SWITCH_STARTED=0
trap - ERR
rm -rf "$APP_ROOT/server.previous"
printf '%s\n' "$BUILD_SHA256" > "$DATA_DIR/installed-v440.sha256"
chown "$SERVICE_USER:$SERVICE_USER" "$DATA_DIR/installed-v440.sha256"

printf '%s\n' "$STATUS"
printf '\nPumpRadar %s установлен.\n' "$EXPECTED_VERSION"
printf 'Пакет SHA-256: %s\n' "$BUILD_SHA256"
printf 'Проверка: %s\n' "$APP_ROOT/server/scripts/status.sh"
