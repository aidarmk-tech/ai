#!/usr/bin/env bash
set -Eeuo pipefail

REPO_RAW="https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v440-mc7-final/server-bootstrap"
PAYLOAD_PATH="v440release"
PAYLOAD_PARTS=(00 01 02 03)
PAYLOAD_SHA256="0251ce9551371b4eed8499b2a5b2d49526834901cbde266a6779db22c3430709"
EXPECTED_VERSION="4.4.0-server"
APP_ROOT="/opt/pumpradar"
DATA_DIR="/var/lib/pumpradar"
ENV_DIR="/etc/pumpradar"
ENV_FILE="$ENV_DIR/server.env"
SERVICE_USER="pumpradar"

log() { printf '\n[PumpRadar] %s\n' "$*"; }
fail() { printf '\n[PumpRadar] ERROR: %s\n' "$*" >&2; exit 1; }

[[ ${EUID:-$(id -u)} -eq 0 ]] || fail "Запустите установщик от root"
TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

log "Подготовка Ubuntu и системных пакетов"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates curl openssh-server python3 python3-venv sqlite3 rclone ufw gzip
systemctl enable --now ssh 2>/dev/null || systemctl enable --now sshd 2>/dev/null || true

if [[ -x "$APP_ROOT/server/scripts/backup.sh" ]] && systemctl is-active --quiet pumpradar.service; then
  log "Создание SQLite/CSV backup перед обновлением"
  "$APP_ROOT/server/scripts/backup.sh" || \
    log "Предупреждение: backup не выполнен; основная SQLite база не удаляется"
fi

log "Загрузка проверенного PumpRadar $EXPECTED_VERSION"
: > "$TMP_DIR/payload.b64"
for n in "${PAYLOAD_PARTS[@]}"; do
  curl --fail --silent --show-error --retry 4 --retry-delay 2 \
    "$REPO_RAW/$PAYLOAD_PATH/$n" >> "$TMP_DIR/payload.b64"
done
base64 --decode "$TMP_DIR/payload.b64" > "$TMP_DIR/payload.tar.gz"
ACTUAL_SHA256="$(sha256sum "$TMP_DIR/payload.tar.gz" | awk '{print $1}')"
[[ "$ACTUAL_SHA256" == "$PAYLOAD_SHA256" ]] || \
  fail "Контрольная сумма пакета не совпала: $ACTUAL_SHA256"
tar -tzf "$TMP_DIR/payload.tar.gz" >/dev/null
mkdir -p "$TMP_DIR/source-root"
tar -xzf "$TMP_DIR/payload.tar.gz" -C "$TMP_DIR/source-root"
SERVER_SOURCE="$TMP_DIR/source-root/pumpradar-server"
[[ -d "$SERVER_SOURCE/pumpradar_server" ]] || fail "В пакете нет серверного приложения"
grep -q '4.4.0-server' "$SERVER_SOURCE/pumpradar_server/config.py" || \
  fail "Пакет не содержит ожидаемую версию"

log "Создание пользователя и каталогов"
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

log "Настройка конфигурации"
if [[ ! -f "$ENV_FILE" ]]; then
  API_TOKEN="$(python3 - <<'PY'
import secrets
print(secrets.token_urlsafe(32))
PY
)"
  cat > "$ENV_FILE" <<ENV
PUMPRADAR_DATA_DIR=$DATA_DIR
PUMPRADAR_DB_PATH=$DATA_DIR/pumpradar.sqlite3
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
PUMPRADAR_CONTROL_POOL_SIZE=5
PUMPRADAR_CONTROL_ROTATION_SECONDS=300
PUMPRADAR_WARM_REFRESH_SECONDS=15
PUMPRADAR_EXPORT_KEEP_COUNT=48
PUMPRADAR_EXPORT_MAX_TOTAL_MB=2048
PUMPRADAR_REPORT_TIMEZONE_OFFSET_MINUTES=300
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
ENV
fi
ensure_env() {
  local key="$1" value="$2"
  grep -q "^${key}=" "$ENV_FILE" || printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
}
migrate_env_default() {
  local key="$1" old_value="$2" new_value="$3"
  if grep -q "^${key}=${old_value}$" "$ENV_FILE"; then
    sed -i "s/^${key}=${old_value}$/${key}=${new_value}/" "$ENV_FILE"
  else
    ensure_env "$key" "$new_value"
  fi
}
migrate_env_default PUMPRADAR_MIN_24H_QUOTE_VOLUME 5000000 1000000
migrate_env_default PUMPRADAR_MAX_CANDIDATES 20 30
migrate_env_default PUMPRADAR_DEEP_CANDIDATES 15 20
migrate_env_default PUMPRADAR_DEPTH_CANDIDATES 20 25
migrate_env_default PUMPRADAR_WARM_POOL_SIZE 35 60
ensure_env PUMPRADAR_CONTROL_POOL_SIZE 5
ensure_env PUMPRADAR_CONTROL_ROTATION_SECONDS 300
ensure_env PUMPRADAR_WARM_REFRESH_SECONDS 15
ensure_env PUMPRADAR_EXPORT_KEEP_COUNT 48
ensure_env PUMPRADAR_EXPORT_MAX_TOTAL_MB 2048
ensure_env PUMPRADAR_REPORT_TIMEZONE_OFFSET_MINUTES 300
ensure_env PUMPRADAR_STOP_WATCH_INTERVAL_MS 500
chmod 0600 "$ENV_FILE"
chown -R "$SERVICE_USER:$SERVICE_USER" "$DATA_DIR"

log "Создание swap для VPS с 1 ГБ RAM"
if [[ "$(swapon --show --noheadings | wc -l)" -eq 0 ]]; then
  if [[ ! -f /swapfile ]]; then
    fallocate -l 1G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=1024 status=progress
    chmod 0600 /swapfile
    mkswap /swapfile >/dev/null
  fi
  swapon /swapfile
  grep -qE '^/swapfile\s' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
cat > /etc/sysctl.d/99-pumpradar.conf <<'SYSCTL'
vm.swappiness=15
vm.vfs_cache_pressure=80
SYSCTL
sysctl --system >/dev/null || true

log "Переключение на новую версию с автоматическим откатом"
systemctl stop pumpradar.service 2>/dev/null || true
rm -rf "$APP_ROOT/server.previous"
if [[ -d "$APP_ROOT/server" ]]; then
  mv "$APP_ROOT/server" "$APP_ROOT/server.previous"
fi
mv "$APP_ROOT/server.new" "$APP_ROOT/server"
rollback() {
  log "Проверка запуска не пройдена; выполняется откат"
  systemctl stop pumpradar.service 2>/dev/null || true
  rm -rf "$APP_ROOT/server"
  if [[ -d "$APP_ROOT/server.previous" ]]; then
    mv "$APP_ROOT/server.previous" "$APP_ROOT/server"
    systemctl start pumpradar.service 2>/dev/null || true
  fi
}
trap rollback ERR

install -m 0644 "$APP_ROOT/server/systemd/pumpradar.service" /etc/systemd/system/pumpradar.service
install -m 0644 "$APP_ROOT/server/systemd/pumpradar-backup.service" /etc/systemd/system/pumpradar-backup.service
install -m 0644 "$APP_ROOT/server/systemd/pumpradar-backup.timer" /etc/systemd/system/pumpradar-backup.timer
ufw allow OpenSSH >/dev/null 2>&1 || ufw allow 22/tcp >/dev/null 2>&1 || true
ufw --force enable >/dev/null 2>&1 || true
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
    STATUS="$(curl -fsS \
      -H "Authorization: Bearer $PUMPRADAR_API_TOKEN" \
      "http://127.0.0.1:${PORT}/api/status" 2>/dev/null || true)"
    if echo "$STATUS" | grep -Eq \
      "\"algorithm_version\"[[:space:]]*:[[:space:]]*\"$EXPECTED_VERSION\""; then
      VERSION_SEEN=1
      if echo "$STATUS" | grep -Eq '"ok"[[:space:]]*:[[:space:]]*true'; then
        MARKET_READY=1
        break
      fi
    fi
  fi
  sleep 2
done

if [[ "$VERSION_SEEN" -ne 1 ]]; then
  systemctl status pumpradar.service --no-pager || true
  journalctl -u pumpradar.service -n 100 --no-pager || true
  false
fi

trap - ERR
rm -rf "$APP_ROOT/server.previous"
printf '%s\n' "$STATUS"
printf '\nPumpRadar %s установлен.\n' "$EXPECTED_VERSION"
if [[ "$MARKET_READY" -eq 1 ]]; then
  printf 'Рыночные потоки готовы.\n'
else
  printf 'Сервис запущен; рыночные потоки прогреваются. Проверьте через 1–3 минуты.\n'
fi
printf 'Проверка: %s\n' "$APP_ROOT/server/scripts/status.sh"
