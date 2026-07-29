#!/usr/bin/env bash
set -euo pipefail

RAW_BASE="https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v440-mc7-freeze100/server-bootstrap/watchdog"
WATCHDOG_URL="${RAW_BASE}/pumpradar-watchdog"
WATCHDOG_GIT_BLOB_SHA1="009de8c8f11eb5067951c7b035d87112c90449ea"
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

actual_blob_sha="$(python3 - "$tmp" <<'PY'
import hashlib
import pathlib
import sys

data = pathlib.Path(sys.argv[1]).read_bytes()
print(hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest())
PY
)"
if [[ "$actual_blob_sha" != "$WATCHDOG_GIT_BLOB_SHA1" ]]; then
  echo "Ошибка целостности watchdog: ожидался Git blob $WATCHDOG_GIT_BLOB_SHA1, получен $actual_blob_sha"
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
