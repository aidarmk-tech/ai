#!/usr/bin/env bash
set -Eeuo pipefail

REPO_RAW="https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-server-v43/server-bootstrap"
EXPECTED_SHA256="5eb688a0b024ceaa53b537885293a7ff3c93ece621cc703b68e81d58f34208fb"
APP_ROOT="/opt/pumpradar"
DATA_DIR="/var/lib/pumpradar"
ENV_DIR="/etc/pumpradar"
SERVICE_USER="pumpradar"

log() { printf '\n[PumpRadar] %s\n' "$*"; }
fail() { printf '\n[PumpRadar] ERROR: %s\n' "$*" >&2; exit 1; }

[[ ${EUID:-$(id -u)} -eq 0 ]] || fail "Запустите установщик от root"
command -v curl >/dev/null 2>&1 || true

TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

log "Подготовка Ubuntu и системных пакетов"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates curl openssh-server python3 python3-venv sqlite3 rclone ufw

systemctl enable --now ssh 2>/dev/null || systemctl enable --now sshd 2>/dev/null || true

log "Загрузка проверенного серверного пакета"
: > "$TMP_DIR/payload.b64"
for n in $(seq -w 0 15); do
  curl --fail --silent --show-error --retry 4 --retry-delay 2 \
    "$REPO_RAW/chunks/$n" >> "$TMP_DIR/payload.b64"
done
base64 --decode "$TMP_DIR/payload.b64" > "$TMP_DIR/payload.tar.gz"
ACTUAL_SHA256="$(sha256sum "$TMP_DIR/payload.tar.gz" | awk '{print $1}')"
[[ "$ACTUAL_SHA256" == "$EXPECTED_SHA256" ]] || \
  fail "Контрольная сумма пакета не совпала: $ACTUAL_SHA256"
tar -tzf "$TMP_DIR/payload.tar.gz" >/dev/null

log "Создание системного пользователя и каталогов"
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  useradd --system --home-dir "$DATA_DIR" --create-home --shell /usr/sbin/nologin "$SERVICE_USER"
fi
install -d -o root -g root -m 0755 "$APP_ROOT" "$ENV_DIR"
install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 0750 "$DATA_DIR" "$DATA_DIR/exports"

log "Установка приложения"
rm -rf "$APP_ROOT/server.new"
mkdir -p "$APP_ROOT/server.new"
tar -xzf "$TMP_DIR/payload.tar.gz" -C "$APP_ROOT/server.new" --strip-components=1
rm -rf "$APP_ROOT/server.previous"
if [[ -d "$APP_ROOT/server" ]]; then
  mv "$APP_ROOT/server" "$APP_ROOT/server.previous"
fi
mv "$APP_ROOT/server.new" "$APP_ROOT/server"

python3 -m venv "$APP_ROOT/venv"
"$APP_ROOT/venv/bin/pip" install --disable-pip-version-check --no-cache-dir --upgrade pip
"$APP_ROOT/venv/bin/pip" install --disable-pip-version-check --no-cache-dir \
  -r "$APP_ROOT/server/requirements.txt"

log "Настройка конфигурации"
if [[ ! -f "$ENV_DIR/server.env" ]]; then
  API_TOKEN="$(python3 - <<'PY'
import secrets
print(secrets.token_urlsafe(32))
PY
)"
  cat > "$ENV_DIR/server.env" <<ENV
PUMPRADAR_DATA_DIR=$DATA_DIR
PUMPRADAR_DB_PATH=$DATA_DIR/pumpradar.sqlite3
PUMPRADAR_BIND_HOST=127.0.0.1
PUMPRADAR_BIND_PORT=8787
PUMPRADAR_API_TOKEN=$API_TOKEN
PUMPRADAR_POSITION_USDT=20
PUMPRADAR_FEE_RATE=0.001
PUMPRADAR_MIN_24H_QUOTE_VOLUME=5000000
PUMPRADAR_MAX_CANDIDATES=20
PUMPRADAR_DEEP_CANDIDATES=10
PUMPRADAR_DEPTH_CANDIDATES=8
PUMPRADAR_EXPORT_INTERVAL_MINUTES=60
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
ENV
  chmod 0600 "$ENV_DIR/server.env"
fi

chown -R root:root "$APP_ROOT/server"
chmod +x "$APP_ROOT/server/scripts/"*.sh
chown -R "$SERVICE_USER:$SERVICE_USER" "$DATA_DIR"

log "Установка systemd и автоматических резервных копий"
install -m 0644 "$APP_ROOT/server/systemd/pumpradar.service" /etc/systemd/system/pumpradar.service
install -m 0644 "$APP_ROOT/server/systemd/pumpradar-backup.service" /etc/systemd/system/pumpradar-backup.service
install -m 0644 "$APP_ROOT/server/systemd/pumpradar-backup.timer" /etc/systemd/system/pumpradar-backup.timer

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

log "Базовая защита firewall"
ufw allow OpenSSH >/dev/null 2>&1 || ufw allow 22/tcp >/dev/null 2>&1 || true
ufw --force enable >/dev/null 2>&1 || true

log "Запуск PumpRadar"
systemctl daemon-reload
systemctl enable --now pumpradar.service
systemctl enable --now pumpradar-backup.timer
sleep 8

if ! curl --fail --silent --show-error "http://127.0.0.1:8787/healthz" > "$TMP_DIR/health.json"; then
  journalctl -u pumpradar.service -n 100 --no-pager >&2 || true
  fail "Сервис не прошёл health-check"
fi

cat "$TMP_DIR/health.json"
printf '\n\nPumpRadar v4.3 Measurement Server установлен.\n'
printf 'Статус: %s\n' "$APP_ROOT/server/scripts/status.sh"
printf 'Google Drive: %s\n' "$APP_ROOT/server/scripts/configure-drive.sh"
printf 'Локальные данные: %s\n' "$DATA_DIR"
printf '\nВАЖНО: сразу после проверки смените временный root-пароль командой: passwd\n'
