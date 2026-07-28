#!/usr/bin/env bash
set -Eeuo pipefail

REPO_RAW="https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v440-mc7-freeze100/server-bootstrap"
PAYLOAD_PATH="v440release"
PAYLOAD_PARTS=(00 01 02 03 04)
PAYLOAD_SHA256="0f7c2aa771c5466fa433a933766cc55bb4741f4f0dd49a7428b6d43aaa863a87"
EXPECTED_VERSION="4.4.0-server"
APP_ROOT="/opt/pumpradar"
DATA_DIR="${PUMPRADAR_DATA_DIR:-/var/lib/pumpradar}"
ENV_DIR="/etc/pumpradar"
ENV_FILE="$ENV_DIR/server.env"
DB_PATH="${PUMPRADAR_DB_PATH:-$DATA_DIR/pumpradar.sqlite3}"
SERVICE_USER="pumpradar"
TMP_DIR="$(mktemp -d)"
SWITCHED=0
SERVICE_WAS_ACTIVE=0

log() { printf '\n[PumpRadar] %s\n' "$*"; }
fail() { printf '\n[PumpRadar] ERROR: %s\n' "$*" >&2; exit 1; }
cleanup() { rm -rf "$TMP_DIR"; }
rollback() {
  local code=$?
  if (( SWITCHED == 1 )); then
    log "Новая версия не запустилась; выполняется автоматический откат"
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

log "Подготовка системных пакетов"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates curl openssh-server python3 python3-venv sqlite3 rclone ufw gzip
systemctl enable --now ssh 2>/dev/null || systemctl enable --now sshd 2>/dev/null || true

count_open() {
  [[ -f "$DB_PATH" ]] || { printf '0\n'; return; }
  local total=0 table predicate n exists
  while IFS='|' read -r table predicate; do
    exists="$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table';")"
    if [[ "$exists" == "1" ]]; then
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

OPEN_COUNT="$(count_open)"
[[ "$OPEN_COUNT" == "0" ]] || \
  fail "Обновление отменено: открытых paper/policy записей — $OPEN_COUNT"

if systemctl is-active --quiet pumpradar.service; then
  SERVICE_WAS_ACTIVE=1
fi
if [[ -x "$APP_ROOT/server/scripts/backup.sh" ]] && (( SERVICE_WAS_ACTIVE == 1 )); then
  log "Создание backup перед обновлением"
  "$APP_ROOT/server/scripts/backup.sh" || \
    log "Предупреждение: экспортный backup не выполнен; основная SQLite сохраняется"
fi

log "Загрузка готового PumpRadar $EXPECTED_VERSION"
: > "$TMP_DIR/payload.b64"
for part in "${PAYLOAD_PARTS[@]}"; do
  curl --fail --silent --show-error --retry 4 --retry-delay 2 \
    "$REPO_RAW/$PAYLOAD_PATH/$part" >> "$TMP_DIR/payload.b64"
done
base64 --decode "$TMP_DIR/payload.b64" > "$TMP_DIR/payload.tar.gz"
ACTUAL_SHA256="$(sha256sum "$TMP_DIR/payload.tar.gz" | awk '{print $1}')"
[[ "$ACTUAL_SHA256" == "$PAYLOAD_SHA256" ]] || \
  fail "Контрольная сумма пакета не совпала: $ACTUAL_SHA256"
tar -tzf "$TMP_DIR/payload.tar.gz" >/dev/null
mkdir -p "$TMP_DIR/source-root"
tar -xzf "$TMP_DIR/payload.tar.gz" -C "$TMP_DIR/source-root"
SERVER_SOURCE="$TMP_DIR/source-root/pumpradar-server"
[[ -d "$SERVER_SOURCE/pumpradar_server" ]] || fail "В архиве нет сервера"
grep -q 'algorithm_version: str = "4.4.0-server"' \
  "$SERVER_SOURCE/pumpradar_server/config.py" || fail "Неверная версия архива"
if grep -R -nE 'fapi/v1/order|api/v3/order|create_order|newOrder|API_SECRET|SIGNED' \
    "$SERVER_SOURCE/pumpradar_server"; then
  fail "В архиве обнаружен запрещённый код реальных ордеров"
fi

log "Подготовка приложения"
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  useradd --system --home-dir "$DATA_DIR" --create-home --shell /usr/sbin/nologin "$SERVICE_USER"
fi
install -d -o root -g root -m 0755 "$APP_ROOT" "$ENV_DIR"
install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0750 "$DATA_DIR" "$DATA_DIR/exports"
rm -rf "$APP_ROOT/server.new"
cp -a "$SERVER_SOURCE" "$APP_ROOT/server.new"
find "$APP_ROOT/server.new" -type d -name __pycache__ -prune -exec rm -rf {} +
python3 -m compileall -q "$APP_ROOT/server.new/pumpradar_server"
chown -R root:root "$APP_ROOT/server.new"
chmod +x "$APP_ROOT/server.new/scripts/"*.sh

log "Установка Python-зависимостей"
if [[ ! -x "$APP_ROOT/venv/bin/python" ]]; then
  python3 -m venv "$APP_ROOT/venv"
fi
"$APP_ROOT/venv/bin/pip" install --disable-pip-version-check --no-cache-dir \
  -r "$APP_ROOT/server.new/requirements.txt"

log "Сохранение и дополнение конфигурации"
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
PUMPRADAR_MIN_24H_QUOTE_VOLUME=1000000
PUMPRADAR_MAX_CANDIDATES=30
PUMPRADAR_DEEP_CANDIDATES=20
PUMPRADAR_DEPTH_CANDIDATES=25
PUMPRADAR_WARM_POOL_SIZE=60
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
ENV
fi
ensure_env() {
  local key="$1" value="$2"
  grep -q "^${key}=" "$ENV_FILE" || printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
}
ensure_env PUMPRADAR_CONTROL_POOL_SIZE 5
ensure_env PUMPRADAR_CONTROL_ROTATION_SECONDS 300
ensure_env PUMPRADAR_WARM_REFRESH_SECONDS 15
ensure_env PUMPRADAR_EXPORT_KEEP_COUNT 48
ensure_env PUMPRADAR_EXPORT_MAX_TOTAL_MB 2048
ensure_env PUMPRADAR_REPORT_TIMEZONE_OFFSET_MINUTES 300
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

OPEN_COUNT="$(count_open)"
[[ "$OPEN_COUNT" == "0" ]] || \
  fail "За время загрузки открылась paper-позиция; обновление отменено"

log "Переключение на v4.4.0"
systemctl stop pumpradar.service 2>/dev/null || true
OPEN_COUNT="$(count_open)"
if [[ "$OPEN_COUNT" != "0" ]]; then
  (( SERVICE_WAS_ACTIVE == 1 )) && systemctl start pumpradar.service 2>/dev/null || true
  fail "После остановки обнаружена открытая позиция; обновление отменено"
fi
rm -rf "$APP_ROOT/server.previous"
if [[ -d "$APP_ROOT/server" ]]; then
  mv "$APP_ROOT/server" "$APP_ROOT/server.previous"
fi
mv "$APP_ROOT/server.new" "$APP_ROOT/server"
SWITCHED=1

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
MARKET_READY=0
for _ in $(seq 1 90); do
  if systemctl is-active --quiet pumpradar.service; then
    STATUS="$(curl -fsS -H "Authorization: Bearer $PUMPRADAR_API_TOKEN" \
      "http://127.0.0.1:${PORT}/api/status" 2>/dev/null || true)"
    if echo "$STATUS" | grep -Eq \
      '"algorithm_version"[[:space:]]*:[[:space:]]*"4.4.0-server"'; then
      VERSION_SEEN=1
      if echo "$STATUS" | grep -Eq '"spot_ok"[[:space:]]*:[[:space:]]*true' \
         && echo "$STATUS" | grep -Eq '"futures_ok"[[:space:]]*:[[:space:]]*true'; then
        MARKET_READY=1
        break
      fi
    fi
  fi
  sleep 2
done

if (( VERSION_SEEN != 1 )); then
  systemctl status pumpradar.service --no-pager || true
  journalctl -u pumpradar.service -n 120 --no-pager || true
  false
fi

SWITCHED=0
trap - ERR
rm -rf "$APP_ROOT/server.previous"
printf '%s\n' "$PAYLOAD_SHA256" > "$DATA_DIR/installed-v440.sha256"
chown "$SERVICE_USER:$SERVICE_USER" "$DATA_DIR/installed-v440.sha256"
printf '%s\n' "$STATUS"
printf '\nPumpRadar %s установлен.\n' "$EXPECTED_VERSION"
if (( MARKET_READY == 1 )); then
  printf 'Spot и futures потоки готовы.\n'
else
  printf 'Сервис запущен; потоки ещё прогреваются. Проверьте через 1–3 минуты.\n'
fi
printf 'Проверка: %s\n' "$APP_ROOT/server/scripts/status.sh"
