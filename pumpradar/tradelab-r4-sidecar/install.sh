#!/usr/bin/env bash
set -euo pipefail
if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "Run as root" >&2
  exit 1
fi
SRC="$(cd "$(dirname "$0")" && pwd)"
DST=/opt/tradelab/r4-five-models
mkdir -p "$DST"
install -m 0755 "$SRC/tradelab_r4_sidecar.py" "$DST/tradelab_r4_sidecar.py"
install -m 0644 "$SRC/README.md" "$DST/README.md"
install -m 0644 "$SRC/tradelab-r4-five-models.service" /etc/systemd/system/tradelab-r4-five-models.service

DB="${TRADELAB_DB:-}"
if [[ -z "$DB" ]]; then
  DB="$(python3 "$DST/tradelab_r4_sidecar.py" --detect-db)"
fi
if [[ ! -f "$DB" ]]; then
  echo "DB not found: $DB" >&2
  exit 2
fi

BACKUP_DIR=/var/lib/pumpradar/backups
mkdir -p "$BACKUP_DIR"
BACKUP="$BACKUP_DIR/tradelab-pre-r4-$(date +%Y%m%d-%H%M%S).sqlite3"
python3 - "$DB" "$BACKUP" <<'PY'
import sqlite3,sys
src,dst=sys.argv[1:]
a=sqlite3.connect(f"file:{src}?mode=ro",uri=True)
b=sqlite3.connect(dst)
with b:
    a.backup(b)
print(dst)
a.close(); b.close()
PY
printf 'TRADELAB_DB=%q\n' "$DB" > /etc/default/tradelab-r4-five-models

python3 "$DST/tradelab_r4_sidecar.py" --db "$DB" --activate-r4
systemctl daemon-reload
systemctl enable --now tradelab-r4-five-models.service
sleep 1
python3 "$DST/tradelab_r4_sidecar.py" --db "$DB" --status
systemctl is-active tradelab-r4-five-models.service

echo "Installed. DB: $DB"
echo "Backup: $BACKUP"
echo "All new models are SHADOW_ONLY; no exchange/order API is used."
