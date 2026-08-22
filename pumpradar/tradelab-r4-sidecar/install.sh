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
install -m 0755 "$SRC/tradelab_r4_isolation.py" "$DST/tradelab_r4_isolation.py"
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
BACKUP="$BACKUP_DIR/tradelab-pre-r4-hotfix-$(date +%Y%m%d-%H%M%S).sqlite3"
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

systemctl stop tradelab-r4-five-models.service 2>/dev/null || true
python3 "$DST/tradelab_r4_isolation.py" --db "$DB" --apply
systemctl daemon-reload
systemctl enable --now tradelab-r4-five-models.service
sleep 1
python3 "$DST/tradelab_r4_isolation.py" --db "$DB" --status
systemctl is-active tradelab-r4-five-models.service

echo "Installed R4 isolation hotfix. DB: $DB"
echo "Backup: $BACKUP"
echo "Five active candidates restarted at virtual $20 from the clean hotfix epoch."
echo "FLOW_ABSORPTION is write-blocked and retired. HFT/EXTREME paper positions are isolated from legacy paper_book."
