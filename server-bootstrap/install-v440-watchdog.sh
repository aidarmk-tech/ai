#!/usr/bin/env bash
set -euo pipefail

RAW_BASE="https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v440-mc7-freeze100/server-bootstrap/watchdog"
WATCHDOG_URL="${RAW_BASE}/pumpradar-watchdog"
WATCHDOG_SHA256="9a78e61d5c062129f013de9ba6b7219646e078f7aef969d366a2463d66820618"
WATCHDOG_BIN="/usr/local/sbin/pumpradar-watchdog"
SERVICE_FILE="/etc/systemd/system/pumpradar-watchdog.service"
TIMER_FILE="/etc/systemd/system/pumpradar-watchdog.timer"

if [[ ${EUID} -ne 0 ]]; then
  echo "Запусти от root."
  exit 1
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

curl -fL --retry 4 --retry-delay 2 --connect-timeout 15 \
  "$WATCHDOG_URL" -o "$tmp"

actual_sha="$(sha256sum "$tmp" | awk '{print $1}')"
if [[ "$actual_sha" != "$WATCHDOG_SHA256" ]]; then
  echo "Ошибка SHA-256 watchdog: ожидался $WATCHDOG_SHA256, получен $actual_sha"
  exit 1
fi

python3 -m py_compile "$tmp"

stamp="$(date +%Y%m%d-%H%M%S)"
for path in "$WATCHDOG_BIN" "$SERVICE_FILE" "$TIMER_FILE"; do
  if [[ -e "$path" ]]; then
    cp -a "$path" "${path}.backup-${stamp}"
  fi
done

install -m 0755 "$tmp" "$WATCHDOG_BIN"

cat > "$SERVICE_FILE" <<'EOF'
[Unit]
Description=PumpRadar feed-health watchdog
After=network-online.target pumpradar.service
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/pumpradar-watchdog
TimeoutStartSec=40
Nice=10
IOSchedulingClass=idle
EOF

cat > "$TIMER_FILE" <<'EOF'
[Unit]
Description=Run PumpRadar feed-health watchdog every minute

[Timer]
OnBootSec=2min
OnUnitActiveSec=1min
AccuracySec=10s
Persistent=true
Unit=pumpradar-watchdog.service

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now pumpradar-watchdog.timer
systemctl start pumpradar-watchdog.service

echo "=== watchdog timer ==="
systemctl status pumpradar-watchdog.timer --no-pager -l
echo "=== last watchdog check ==="
journalctl -u pumpradar-watchdog.service --no-pager -n 20
