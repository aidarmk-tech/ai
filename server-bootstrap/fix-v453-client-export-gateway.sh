#!/usr/bin/env bash
set -Eeuo pipefail

ENV_FILE="/etc/pumpradar/server.env"
PUBLIC_IP="${1:-45.150.37.187}"
TS="$(date +%Y%m%d-%H%M%S)"

log() { printf '\n[PumpRadar v4.5.3 gateway] %s\n' "$*"; }
fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

[[ ${EUID:-$(id -u)} -eq 0 ]] || fail "Run as root"
[[ -f "$ENV_FILE" ]] || fail "PumpRadar environment not found: $ENV_FILE"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
PORT="${PUMPRADAR_BIND_PORT:-8787}"
TOKEN="${PUMPRADAR_API_TOKEN:-}"
[[ -n "$TOKEN" ]] || fail "PUMPRADAR_API_TOKEN is empty"

log "Checking direct server export endpoint"
curl -fsS --max-time 120 -X POST \
  -H "Authorization: Bearer ${TOKEN}" \
  "http://127.0.0.1:${PORT}/api/export" \
  >/tmp/pumpradar-v453-direct-export.json
cat /tmp/pumpradar-v453-direct-export.json

log "Locating the active nginx file that serves /api/status"
ACTIVE_CONFIG="$(nginx -T 2>&1 | awk '
  /^# configuration file / {
    file=$4
    sub(/:$/, "", file)
  }
  /location = \/api\/status/ {
    print file
    exit
  }
')"
[[ -n "$ACTIVE_CONFIG" ]] || fail "Could not locate active nginx config containing /api/status"
[[ -f "$ACTIVE_CONFIG" ]] || fail "Active nginx config is not a regular file: $ACTIVE_CONFIG"
TARGET="$(readlink -f "$ACTIVE_CONFIG")"
BACKUP="${TARGET}.before-v453-export-${TS}"
printf 'Active config: %s\nResolved target: %s\n' "$ACTIVE_CONFIG" "$TARGET"
cp -a "$TARGET" "$BACKUP"

export TARGET PORT TOKEN
python3 <<'PY'
from __future__ import annotations

import os
from pathlib import Path

path = Path(os.environ["TARGET"])
port = int(os.environ["PORT"])
token = os.environ["TOKEN"].replace("\\", "\\\\").replace('"', '\\"')
text = path.read_text(encoding="utf-8")

status_pos = text.find("location = /api/status")
if status_pos < 0:
    raise SystemExit("Active file no longer contains location = /api/status")

latest_marker = "location ~ ^/api/export/latest/"
latest_pos = text.find(latest_marker, status_pos)
if latest_pos < 0:
    raise SystemExit("Could not find the latest-export route after /api/status")

segment = text[status_pos:latest_pos]
if "location = /api/export" not in segment:
    line_start = text.rfind("\n", 0, latest_pos) + 1
    indent = text[line_start:latest_pos]
    if indent.strip():
        indent = "    "
    else:
        indent = indent or "    "

    block = f'''{indent}# PumpRadar client 1.1+: create a fresh verified database export.
{indent}# This endpoint cannot trade; it only invokes Storage.export_all().
{indent}location = /api/export {{
{indent}    limit_req zone=pumpradar_api burst=2 nodelay;
{indent}    limit_except POST {{ deny all; }}
{indent}    proxy_set_header Host $host;
{indent}    proxy_set_header Authorization "Bearer {token}";
{indent}    proxy_connect_timeout 10s;
{indent}    proxy_send_timeout 120s;
{indent}    proxy_read_timeout 120s;
{indent}    proxy_pass http://127.0.0.1:{port};
{indent}}}

'''
    text = text[:line_start] + block + text[line_start:]
    path.write_text(text, encoding="utf-8")
PY

if ! nginx -t; then
  cp -a "$BACKUP" "$TARGET"
  nginx -t || true
  fail "Nginx validation failed; active configuration restored"
fi
systemctl reload nginx

log "Confirming the route is present in the loaded configuration"
if ! nginx -T 2>&1 | grep -q 'location = /api/export'; then
  cp -a "$BACKUP" "$TARGET"
  nginx -t && systemctl reload nginx
  fail "The export route is still absent from nginx -T; configuration restored"
fi

log "Checking public export endpoint through nginx"
curl -fsS --max-time 120 -X POST \
  -H 'Accept: application/json' \
  -H 'Cache-Control: no-cache' \
  "https://${PUBLIC_IP}/api/export?ts=$(date +%s%3N)" \
  >/tmp/pumpradar-v453-public-export.json
cat /tmp/pumpradar-v453-public-export.json

log "Checking manifest and SQLite availability"
curl -fsS --max-time 30 \
  -H 'Cache-Control: no-cache' \
  "https://${PUBLIC_IP}/api/export/latest/manifest.json?ts=$(date +%s%3N)" \
  >/tmp/pumpradar-v453-manifest.json
python3 - <<'PY'
import json
from pathlib import Path
m = json.loads(Path('/tmp/pumpradar-v453-manifest.json').read_text(encoding='utf-8'))
f = m.get('files', {}).get('pumpradar.sqlite3.gz', {})
assert f.get('bytes', 0) > 0, 'manifest has no SQLite size'
assert len(str(f.get('sha256', ''))) == 64, 'manifest has no SQLite SHA-256'
print(json.dumps({
    'algorithm_version': m.get('algorithm_version'),
    'run_id': m.get('run_id'),
    'sqlite_bytes': f.get('bytes'),
    'sqlite_sha256': f.get('sha256'),
}, ensure_ascii=False, indent=2))
PY

printf '\nREADY: Android client fresh snapshot route is working.\n'
printf 'Nginx backup: %s\n' "$BACKUP"
