#!/usr/bin/env bash
# Установка автозапуска на macOS через launchd (пользовательский агент).
# Запускать БЕЗ sudo — от своего пользователя.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PORT="${1:-8000}"
LABEL="com.remote-monitor"

if [[ -x "$DIR/venv/bin/python" ]]; then
  PYTHON="$DIR/venv/bin/python"
else
  PYTHON="$(command -v python3)"
fi

read -r -s -p "Задайте пароль для доступа к мониторингу: " PASSWORD
echo
if [[ -z "$PASSWORD" ]]; then echo "Пароль обязателен."; exit 1; fi

AGENTS_DIR="$HOME/Library/LaunchAgents"
mkdir -p "$AGENTS_DIR"
PLIST="$AGENTS_DIR/$LABEL.plist"

cat > "$PLIST" <<PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$PYTHON</string>
    <string>$DIR/server.py</string>
    <string>--port</string>
    <string>$PORT</string>
  </array>
  <key>WorkingDirectory</key><string>$DIR</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>MONITOR_PASSWORD</key><string>$PASSWORD</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$DIR/monitor.log</string>
  <key>StandardErrorPath</key><string>$DIR/monitor.err.log</string>
</dict>
</plist>
PLIST_EOF

chmod 600 "$PLIST"   # в файле пароль — закрываем от чужих глаз

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"

echo
echo "Готово. Агент загружен и будет стартовать при входе в систему."
echo "  ВАЖНО: разрешите приложению «Запись экрана» в"
echo "         Системные настройки → Конфиденциальность и безопасность → Запись экрана."
echo "  Стоп/удалить:  launchctl unload '$PLIST' && rm '$PLIST'"
echo "  Логи:          tail -f '$DIR/monitor.log'"
