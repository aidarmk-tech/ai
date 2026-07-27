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
: > "$TMP_DIR/payload.b64"
for n in $(seq 0 7); do
  curl -fsSL --retry 4 --retry-delay 2 "$RAW/v431/$n" >> "$TMP_DIR/payload.b64"
done
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

# Расширяем только измерительное покрытие. Пороги стратегии TRADE_3 не меняются.
for line in \
  'PUMPRADAR_WARM_POOL_SIZE=35' \
  'PUMPRADAR_CONTROL_POOL_SIZE=5' \
  'PUMPRADAR_CONTROL_ROTATION_SECONDS=300' \
  'PUMPRADAR_WARM_REFRESH_SECONDS=15'; do
  key="${line%%=*}"
  grep -q "^${key}=" "$ENV_FILE" 2>/dev/null || echo "$line" >> "$ENV_FILE"
done

rollback() {
  echo "[PumpRadar 4.3.1] Проверка запуска не пройдена, выполняется откат" >&2
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

# Проверяем отдельно запуск процесса и готовность рыночных потоков.
# Первый тикер может появиться не сразу; это не является ошибкой установки.
source "$ENV_FILE"
PORT="${PUMPRADAR_BIND_PORT:-8787}"
STATUS=""
VERSION_SEEN=0
MARKET_READY=0

for _ in $(seq 1 90); do
  if systemctl is-active --quiet pumpradar.service; then
    STATUS="$(curl -fsS \
      -H "Authorization: Bearer $PUMPRADAR_API_TOKEN" \
      "http://127.0.0.1:${PORT}/api/status" 2>/dev/null || true)"

    if echo "$STATUS" | grep -Eq '"algorithm_version"[[:space:]]*:[[:space:]]*"4\.3\.1-server"'; then
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
  echo "[PumpRadar 4.3.1] Новая версия не ответила через API" >&2
  systemctl status pumpradar.service --no-pager || true
  journalctl -u pumpradar.service -n 80 --no-pager || true
  false
fi

echo "$STATUS"

trap - ERR
rm -rf "$APP_ROOT/server.previous"
echo
printf 'PumpRadar 4.3.1 установлен. Warm pool: 35, control pool: 5, depth: 8.\n'
if [[ "$MARKET_READY" -eq 1 ]]; then
  printf 'Рыночные потоки готовы.\n'
else
  printf 'Сервис запущен; рыночные потоки ещё инициализируются. Проверьте клиент через 1–3 минуты.\n'
fi
printf 'Google Drive не изменён; локальные экспорты сохранены.\n'
