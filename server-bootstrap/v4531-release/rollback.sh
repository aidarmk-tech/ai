#!/usr/bin/env bash
set -Eeuo pipefail

LIVE="/opt/pumpradar/server"
SERVICE="pumpradar.service"
BACKUP="${1:-$(find /opt/pumpradar/backups -maxdepth 1 -type d -name 'v4531-*' -printf '%T@ %p\n' | sort -nr | head -1 | cut -d' ' -f2-)}"
[[ -n "$BACKUP" && -d "$BACKUP" ]] || { echo "No v4.5.3.1 backup found" >&2; exit 1; }

open="$(sqlite3 /var/lib/pumpradar/pumpradar.sqlite3 "SELECT COUNT(*) FROM regime_slots WHERE status='OPEN';")"
[[ "$open" == "0" ]] || { echo "Rollback refused while a paper slot is open" >&2; exit 1; }

STAMP="$(date -u +%Y%m%d-%H%M%S)"
systemctl stop "$SERVICE"
mv "$LIVE" "/opt/pumpradar/server.before-v4531-rollback-$STAMP"
mkdir -p "$LIVE"
cp -a "$BACKUP/." "$LIVE/"
systemctl start "$SERVICE"
systemctl is-active --quiet "$SERVICE"
echo "Rollback complete from $BACKUP"
