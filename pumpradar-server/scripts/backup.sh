#!/usr/bin/env bash
set -euo pipefail
ENV_FILE=/etc/pumpradar/server.env
[[ -f "$ENV_FILE" ]] && set -a && source "$ENV_FILE" && set +a
TOKEN="${PUMPRADAR_API_TOKEN:?missing token}"
PORT="${PUMPRADAR_BIND_PORT:-8787}"
curl -fsS --connect-timeout 10 --max-time 1800 -X POST \
  -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:$PORT/api/export" >/dev/null
LATEST="${PUMPRADAR_DATA_DIR:-/var/lib/pumpradar}/exports/latest"
if command -v rclone >/dev/null 2>&1 && rclone listremotes 2>/dev/null | grep -qx 'gdrive:'; then
  rclone copy "$LATEST" "gdrive:" \
    --create-empty-src-dirs --transfers 1 --checkers 2 --log-level INFO
fi
