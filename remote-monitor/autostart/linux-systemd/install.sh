#!/usr/bin/env bash
# Установка автозапуска на Linux через systemd (пользовательский сервис).
# Запускать БЕЗ sudo — от своего пользователя.
set -euo pipefail

# Каталог remote-monitor (на два уровня выше этого скрипта)
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PORT="${1:-8000}"

# Python из venv, если есть, иначе системный
if [[ -x "$DIR/venv/bin/python" ]]; then
  PYTHON="$DIR/venv/bin/python"
else
  PYTHON="$(command -v python3)"
fi

read -r -s -p "Задайте пароль для доступа к мониторингу: " PASSWORD
echo
if [[ -z "$PASSWORD" ]]; then echo "Пароль обязателен."; exit 1; fi

UNIT_DIR="$HOME/.config/systemd/user"
mkdir -p "$UNIT_DIR"
UNIT="$UNIT_DIR/remote-monitor.service"

sed -e "s#__WORKDIR__#$DIR#g" \
    -e "s#__PYTHON__#$PYTHON#g" \
    -e "s#__PASSWORD__#$PASSWORD#g" \
    -e "s#__PORT__#$PORT#g" \
    "$DIR/autostart/linux-systemd/remote-monitor.service" > "$UNIT"
chmod 600 "$UNIT"   # в файле пароль — закрываем от чужих глаз

systemctl --user daemon-reload
systemctl --user enable --now remote-monitor.service
# Чтобы сервис работал и без активной сессии (по желанию):
loginctl enable-linger "$USER" 2>/dev/null || true

echo
echo "Готово. Сервис запущен и добавлен в автозапуск."
echo "  Статус:   systemctl --user status remote-monitor"
echo "  Логи:     journalctl --user -u remote-monitor -f"
echo "  Стоп:     systemctl --user stop remote-monitor"
echo "  Удалить:  systemctl --user disable --now remote-monitor && rm '$UNIT'"
