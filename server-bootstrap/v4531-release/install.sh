#!/usr/bin/env bash
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PAYLOAD="$HERE/payload/server"
LIVE="/opt/pumpradar/server"
DATA="/var/lib/pumpradar"
SERVICE="pumpradar.service"
PYTHON="/opt/pumpradar/venv/bin/python"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
BACKUP="/opt/pumpradar/backups/v4531-$STAMP"
DB_BACKUP="$DATA/backups/pumpradar-before-v4531-$STAMP.sqlite3"
CANDIDATE="/opt/pumpradar/server.v4531-candidate-$STAMP"

log() { printf '\n[PumpRadar 4.5.3.1] %s\n' "$*"; }
fail() { log "ERROR: $*"; exit 1; }

[[ -d "$PAYLOAD/pumpradar_server" ]] || fail "release payload is incomplete"
[[ -x "$PYTHON" ]] || fail "server Python runtime not found"
[[ -f "$DATA/pumpradar.sqlite3" ]] || fail "live database not found"

log "Waiting for paper slots to close naturally"
deadline=$((SECONDS + 4500))
while [[ "$(sqlite3 "$DATA/pumpradar.sqlite3" "SELECT COUNT(*) FROM regime_slots WHERE status='OPEN';")" != "0" ]]; do
  (( SECONDS < deadline )) || fail "open slots did not close within 75 minutes"
  sleep 15
done

log "Validating candidate and idempotent migration on a database copy"
mkdir -p "$DATA/backups"
sqlite3 "$DATA/pumpradar.sqlite3" ".backup '$DB_BACKUP'"
rm -rf "$CANDIDATE"
mkdir -p "$CANDIDATE"
cp -a "$PAYLOAD/." "$CANDIDATE/"
PUMPRADAR_DB_PATH="$DB_BACKUP" PYTHONPATH="$CANDIDATE" "$PYTHON" -m unittest discover -s "$CANDIDATE/tests" -v
PYTHONPATH="$CANDIDATE" "$PYTHON" -m compileall -q "$CANDIDATE/pumpradar_server"

log "Installing with automatic rollback"
mkdir -p "$BACKUP"
cp -a "$LIVE/." "$BACKUP/"
systemctl stop "$SERVICE"
if [[ "$(sqlite3 "$DATA/pumpradar.sqlite3" "SELECT COUNT(*) FROM regime_slots WHERE status='OPEN';")" != "0" ]]; then
  systemctl start "$SERVICE"
  fail "slot opened during install handoff"
fi

rollback_on_error() {
  log "Installation failed; restoring 4.5.3"
  rm -rf "$LIVE.failed-v4531-$STAMP"
  mv "$LIVE" "$LIVE.failed-v4531-$STAMP" || true
  mkdir -p "$LIVE"
  cp -a "$BACKUP/." "$LIVE/"
  systemctl start "$SERVICE" || true
}
trap rollback_on_error ERR
rm -rf "$LIVE"
mv "$CANDIDATE" "$LIVE"
systemctl start "$SERVICE"
sleep 8
systemctl is-active --quiet "$SERVICE"
version="$($PYTHON -c 'import sys; sys.path.insert(0,"/opt/pumpradar/server"); import pumpradar_server; print(pumpradar_server.__version__)')"
[[ "$version" == "4.5.3.1" ]] || false
trap - ERR

log "READY: PumpRadar 4.5.3.1 research patch is active"
printf 'Backup: %s\nDatabase snapshot: %s\n' "$BACKUP" "$DB_BACKUP"
