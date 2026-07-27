#!/usr/bin/env bash
set -euo pipefail
set -a
source /etc/pumpradar/server.env
set +a
printf '\n=== service ===\n'
systemctl status pumpradar --no-pager -l || true
printf '\n=== health ===\n'
curl -fsS http://127.0.0.1:${PUMPRADAR_BIND_PORT:-8787}/healthz | python3 -m json.tool || true
printf '\n=== strategy status ===\n'
curl -fsS -H "Authorization: Bearer $PUMPRADAR_API_TOKEN" \
  http://127.0.0.1:${PUMPRADAR_BIND_PORT:-8787}/api/status | python3 -m json.tool || true
printf '\n=== memory ===\n'
free -h
systemctl show pumpradar \
  -p MemoryCurrent -p MemoryPeak -p MemoryHigh -p MemoryMax -p MemorySwapCurrent -p NRestarts
printf '\n=== disk ===\n'
df -h /
du -sh "${PUMPRADAR_DATA_DIR:-/var/lib/pumpradar}" || true
