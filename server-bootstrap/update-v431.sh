#!/usr/bin/env bash
set -Eeuo pipefail

RAW="https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-server-v43/server-bootstrap"
EXPECTED_SHA256="3593aa2e1868a84c97f4a492056b09e49a2f5a21b48ba3adb1b0b04d1e531ed7"
APP_ROOT="/opt/pumpradar"
ENV_FILE="/etc/pumpradar/server.env"
TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

[[ ${EUID:-$(id -u)} -eq 0 ]] || { echo "Запустите от root" >&2; exit 1; }

echo "[PumpRadar 4.3.1] Создание свежего экспорта"
if [[ -x "$APP_ROOT/server/scripts/backup.sh" ]]; then
  "$APP_ROOT/server/scripts/backup.sh" || echo "Предупреждение: экспорт перед обновлением не удался"
fi

echo "[PumpRadar 4.3.1] Загрузка проверенного пакета"
curl -fsSL --retry 4 --retry-delay 2 "$RAW/payload-v431.b64" -o "$TMP_DIR/payload.b64"
base64 --decode "$TMP_DIR/payload.b64" > "$TMP_DIR/payload.tar.gz"
ACTUAL="$(sha256sum "$TMP_DIR/payload.tar.gz" | awk '{print $1}')"
[[ "$ACTUAL" == "$EXPECTED_SHA256" ]] || { echo "Ошибка SHA-256: $ACTUAL" >&2; exit 1; }
tar -tzf "$TMP_DIR/payload.tar.gz" >/dev/null

echo "[PumpRadar 4.3.1] Подготовка новой версии"
rm -rf "$APP_ROOT/server.new"
mkdir -p "$APP_ROOT/server.new"
tar -xzf "$TMP_DIR/payload.tar.gz" -C "$APP_ROOT/server.new" --strip-components=1
python3 -m compileall -q "$APP_ROOT/server.new/pumpradar_server"
chmod +x "$APP_ROOT/server.new/scripts/"*.sh
chown -R root:root "$APP_ROOT/server.new"

# Measurement coverage settings. Trading thresholds are not changed.
for line in \
  'PUMPRADAR_WARM_POOL_SIZE=35' \
  'PUMPRADAR_CONTROL_POOL_SIZE=5' \
  'PUMPRADAR_CONTROL_ROTATION_SECONDS=300' \
  'PUMPRADAR_WARM_REFRESH_SECONDS=15'; do
  key="${line%%=*}"
  grep -q "^${key}=" "$ENV_FILE" 2>/dev/null || echo "$line" >> "$ENV_FILE"
done

rollback() {
  echo "[PumpRadar 4.3.1] Проверка не пройдена, выполняется откат" >&2
  systemctl stop pumpradar.service || true
  rm -rf "$APP_ROOT/server"
  if [[ -d "$APP_ROOT/server.previous" ]]; then
    mv "$APP_ROOT/server.previous" "$APP_ROOT/server"
    systemctl start pumpradar.service || true
  fi
}
trap rollback ERR

systemctl stop pumpradar.service
rm -rf "$APP_ROOT/server.previous"
if [[ -d "$APP_ROOT/server" ]]; then
  mv "$APP_ROOT/server" "$APP_ROOT/server.previous"
fi
mv "$APP_ROOT/server.new" "$APP_ROOT/server"
systemctl daemon-reload
systemctl start pumpradar.service

for _ in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8787/healthz > "$TMP_DIR/health.json"; then
    break
  fi
  sleep 2
done
curl -fsS http://127.0.0.1:8787/healthz > "$TMP_DIR/health.json"
cat "$TMP_DIR/health.json"

grep -q '"ok": true' "$TMP_DIR/health.json"
source "$ENV_FILE"
STATUS="$(curl -fsS -H "Authorization: Bearer $PUMPRADAR_API_TOKEN" http://127.0.0.1:${PUMPRADAR_BIND_PORT:-8787}/api/status)"
echo "$STATUS"
echo "$STATUS" | grep -q '"algorithm_version": "4.3.1-server"'

trap - ERR
rm -rf "$APP_ROOT/server.previous"
echo
printf 'PumpRadar 4.3.1 установлен. Warm pool: 35, control pool: 5, depth: 8.\n'
printf 'Google Drive не изменён; локальные экспорты сохранены.\n'
