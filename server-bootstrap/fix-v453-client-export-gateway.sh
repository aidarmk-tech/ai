#!/usr/bin/env bash
set -Eeuo pipefail

SITE="/etc/nginx/sites-available/pumpradar-client"
ENV_FILE="/etc/pumpradar/server.env"
PUBLIC_IP="${1:-45.150.37.187}"
TS="$(date +%Y%m%d-%H%M%S)"
BACKUP="${SITE}.before-v453-export-${TS}"

log() { printf '\n[PumpRadar v4.5.3 gateway] %s\n' "$*"; }
fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

[[ ${EUID:-$(id -u)} -eq 0 ]] || fail "Run as root"
[[ -f "$SITE" ]] || fail "Nginx site not found: $SITE"
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

if grep -q 'location = /api/export' "$SITE"; then
  log "Export route already exists; validating configuration"
else
  cp -a "$SITE" "$BACKUP"
  export SITE PORT TOKEN
  python3 <<'PY'
from __future__ import annotations

import os
from pathlib import Path

site = Path(os.environ["SITE"])
port = int(os.environ["PORT"])
token = os.environ["TOKEN"].replace("\\", "\\\\").replace('"', '\\"')
text = site.read_text(encoding="utf-8")

zone_line = 'limit_req_zone $binary_remote_addr zone=pumpradar_api:10m rate=30r/m;'
export_zone = 'limit_req_zone $binary_remote_addr zone=pumpradar_export:10m rate=2r/m;'
if export_zone not in text:
    if zone_line not in text:
        raise SystemExit("Expected pumpradar_api limit_req_zone was not found")
    text = text.replace(zone_line, zone_line + "\n" + export_zone, 1)

fallback = "    location / { return 404; }"
if fallback not in text:
    raise SystemExit("Expected HTTPS fallback location was not found")

block = f'''    # PumpRadar client 1.1+: create a fresh verified database export.
    # This endpoint cannot trade; it only invokes Storage.export_all().
    location = /api/export {{
        limit_req zone=pumpradar_export burst=1 nodelay;
        limit_except POST {{ deny all; }}
        proxy_set_header Host $host;
        proxy_set_header Authorization "Bearer {token}";
        proxy_connect_timeout 10s;
        proxy_send_timeout 120s;
        proxy_read_timeout 120s;
        proxy_pass http://127.0.0.1:{port};
    }}

'''
text = text.replace(fallback, block + fallback, 1)
site.write_text(text, encoding="utf-8")
PY
fi

if ! nginx -t; then
  if [[ -f "$BACKUP" ]]; then
    cp -a "$BACKUP" "$SITE"
    nginx -t || true
  fi
  fail "Nginx validation failed; original configuration restored"
fi
systemctl reload nginx

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
[[ -f "$BACKUP" ]] && printf 'Nginx backup: %s\n' "$BACKUP"
