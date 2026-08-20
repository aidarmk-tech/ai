#!/usr/bin/env bash
set -Eeuo pipefail

EP='pumpradar-research-episodes.service'
MAINT_SVC='pumpradar-research-maintenance.service'
MAINT_TIMER='pumpradar-research-maintenance.timer'
MAIN='pumpradar.service'
EP_DIR=/opt/pumpradar-research-episodes
ENV_FILE=${PUMPRADAR_ENV_FILE:-/etc/pumpradar/server.env}
PY=${PUMPRADAR_PYTHON:-/opt/pumpradar/venv/bin/python}
STAMP="$(date -u +%Y%m%d-%H%M%S)"
BACKUP="/opt/pumpradar/backups/pre-4931-disk-guard-$STAMP"
EXPECTED_RESEARCH_SHA='99b0687048fc4b8dfed6aceb25dcf226c98a366d92c3f7affa6a834c2d11d7ed'
EXPECTED_MAINT_SHA='77b2ac454bf2c1feefe7375515b331d59a0ee639ec14b98a6ceec71e0b3b2ea2'
EXPECTED_TIMER_SHA='ae9cf76f044b91a7fc30413c6cd9193a7474e7fc6212c42ebacd7e0b71ce54b6'
MUTATED=0

[[ ${EUID:-$(id -u)} -eq 0 ]] || { echo 'Run as root' >&2; exit 1; }
[[ -f "$ENV_FILE" ]] || { echo "Missing $ENV_FILE" >&2; exit 1; }
set -a; source "$ENV_FILE"; set +a
DB=${PUMPRADAR_DB_PATH:-/var/lib/pumpradar/pumpradar.sqlite3}
[[ -f "$DB" && -x "$PY" ]] || { echo 'Missing DB or PumpRadar Python' >&2; exit 1; }
for f in "$EP_DIR/research_episodes.py" "$EP_DIR/research-maintenance.sh" "/etc/systemd/system/$MAINT_TIMER"; do
  [[ -f "$f" ]] || { echo "Missing deployed 4.9.3 research file: $f" >&2; exit 1; }
done
systemctl is-active --quiet "$MAIN" || { echo "$MAIN is not active; abort" >&2; exit 1; }
systemctl is-active --quiet "$EP" || { echo "$EP is not active; abort" >&2; exit 1; }

sha_check() {
  local path=$1 expected=$2 actual
  actual=$(sha256sum "$path" | awk '{print $1}')
  [[ "$actual" == "$expected" ]] || {
    echo "Refusing unknown/newer file: $path sha256=$actual expected=$expected" >&2
    exit 1
  }
}
sha_check "$EP_DIR/research_episodes.py" "$EXPECTED_RESEARCH_SHA"
sha_check "$EP_DIR/research-maintenance.sh" "$EXPECTED_MAINT_SHA"
sha_check "/etc/systemd/system/$MAINT_TIMER" "$EXPECTED_TIMER_SHA"

state_json() {
  "$PY" - "$DB" <<'PY'
import json,sqlite3,sys
c=sqlite3.connect(f'file:{sys.argv[1]}?mode=ro',uri=True); c.row_factory=sqlite3.Row
r=c.execute("select * from r2_paper_cohorts where cohort_id='RESEARCH-FOUNDATION-PAPER-20260817'").fetchone()
if not r or r['status']!='RUNNING' or r['algorithm_version']!='4.9.2-server':
    raise SystemExit('4.9.2 research cohort not RUNNING')
print(json.dumps({k:r[k] for k in ('started_at_ms','algorithm_version','strategy_version','config_hash','status')},sort_keys=True))
c.close()
PY
}
BEFORE="$(state_json)"
echo "BEFORE=$BEFORE"

mkdir -p "$BACKUP"
cp -a "$EP_DIR/research_episodes.py" "$BACKUP/research_episodes.py.before"
cp -a "$EP_DIR/research-maintenance.sh" "$BACKUP/research-maintenance.sh.before"
cp -a "/etc/systemd/system/$MAINT_TIMER" "$BACKUP/$MAINT_TIMER.before"
printf '%s\n' "$BEFORE" > "$BACKUP/state-before.json"

rollback() {
  set +e
  echo 'Hotfix failed; restoring research sidecar files. Main trading service was not stopped/restarted.' >&2
  cp -a "$BACKUP/research_episodes.py.before" "$EP_DIR/research_episodes.py"
  cp -a "$BACKUP/research-maintenance.sh.before" "$EP_DIR/research-maintenance.sh"
  cp -a "$BACKUP/$MAINT_TIMER.before" "/etc/systemd/system/$MAINT_TIMER"
  systemctl daemon-reload
  systemctl restart "$EP" >/dev/null 2>&1 || true
  systemctl restart "$MAINT_TIMER" >/dev/null 2>&1 || true
  echo "rollback=$BACKUP" >&2
  exit 1
}
trap '[[ "$MUTATED" -eq 1 ]] && rollback' ERR

MUTATED=1
systemctl stop "$EP"

"$PY" - "$EP_DIR/research_episodes.py" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text()
def rep(old,new,label):
    global s
    n=s.count(old)
    if n!=1: raise SystemExit(f'{label}: expected 1 match, got {n}')
    s=s.replace(old,new,1)
rep(
"DISK_WARN_BYTES = 1_500_000_000\nDISK_CRITICAL_BYTES = 750_000_000\n",
"DISK_WARN_BYTES = 1_500_000_000\nDISK_CRITICAL_BYTES = 750_000_000\nDISK_EVENT_REMINDER_MS = 30 * 60_000\n",
'constants')
rep(
'''            "disk_warn_bytes": str(DISK_WARN_BYTES),\n            "disk_critical_bytes": str(DISK_CRITICAL_BYTES),\n''',
'''            "disk_warn_bytes": str(DISK_WARN_BYTES),\n            "disk_critical_bytes": str(DISK_CRITICAL_BYTES),\n            "disk_event_reminder_ms": str(DISK_EVENT_REMINDER_MS),\n''',
'meta')
needle='''    def disk_free(self) -> int:\n        return int(shutil.disk_usage("/").free)\n\n'''
helper='''    def disk_free(self) -> int:\n        return int(shutil.disk_usage("/").free)\n\n    def record_onset_disk_state(self, free: int) -> None:\n        now = int(time.time() * 1000)\n        state = "OK" if free >= DISK_WARN_BYTES else ("WARNING" if free >= DISK_CRITICAL_BYTES else "CRITICAL")\n        previous = self._meta("onset_disk_state") or "UNKNOWN"\n        try:\n            last_event_ms = int(self._meta("onset_disk_event_at_ms") or "0")\n        except ValueError:\n            last_event_ms = 0\n\n        emit = state != previous\n        if state != "OK" and now - last_event_ms >= DISK_EVENT_REMINDER_MS:\n            emit = True\n\n        if emit:\n            if state == "OK":\n                if previous in ("WARNING", "CRITICAL"):\n                    self.event("INFO", "ONSET_COLLECTION_RESUMED_DISK_OK", {"free_bytes": free, "previous_state": previous})\n            else:\n                self.event(state, "ONSET_COLLECTION_PAUSED_LOW_DISK", {\n                    "free_bytes": free,\n                    "previous_state": previous,\n                    "reminder": state == previous,\n                })\n            self.conn.execute(\n                "INSERT INTO research_episode_meta(key,value,updated_at_ms) VALUES('onset_disk_event_at_ms',?,?) "\n                "ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at_ms=excluded.updated_at_ms",\n                (str(now), now),\n            )\n\n        if state != previous:\n            self.conn.execute(\n                "INSERT INTO research_episode_meta(key,value,updated_at_ms) VALUES('onset_disk_state',?,?) "\n                "ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at_ms=excluded.updated_at_ms",\n                (state, now),\n            )\n\n'''
rep(needle,helper,'disk helper')
rep(
'''        free = self.disk_free()\n        if free >= DISK_WARN_BYTES:\n            pending = self.onset_pending()\n            for row in pending:\n                await self.collect_onset_one(session, row); onset += 1\n        else:\n            self.event("WARNING" if free >= DISK_CRITICAL_BYTES else "CRITICAL", "ONSET_COLLECTION_PAUSED_LOW_DISK", {"free_bytes": free})\n''',
'''        free = self.disk_free()\n        self.record_onset_disk_state(free)\n        if free >= DISK_WARN_BYTES:\n            pending = self.onset_pending()\n            for row in pending:\n                await self.collect_onset_one(session, row); onset += 1\n''',
'cycle disk block')
p.write_text(s)
PY

cat > "$EP_DIR/research-maintenance.sh" <<'SH2'
#!/usr/bin/env bash
set -Eeuo pipefail
WARN_BYTES=1500000000
TARGET_BYTES=2500000000
CRITICAL_BYTES=750000000
KEEP_EXPORTS=1
KEEP_BACKUPS=2
EXPORTS=/var/lib/pumpradar/exports
BACKUPS=/opt/pumpradar/backups
free_bytes(){ df --output=avail -B1 / | tail -1 | tr -d ' '; }
log(){ logger -t pumpradar-research-maintenance -- "$*"; echo "$*"; }
FREE=$(free_bytes)
if (( FREE >= WARN_BYTES )); then
  log "disk_ok free_bytes=$FREE no_prune=1"
  exit 0
fi
log "disk_low free_bytes=$FREE adaptive_retention=1 target_bytes=$TARGET_BYTES"

if [[ -d "$EXPORTS" ]]; then
  mapfile -t exports < <(find "$EXPORTS" -mindepth 1 -maxdepth 1 -type d -regextype posix-extended \
    -regex '.*/[0-9]{8}-[0-9]{6}-[0-9]{3}' -printf '%T@ %p\n' | sort -n | cut -d' ' -f2-)
  count=${#exports[@]}
  for d in "${exports[@]}"; do
    (( $(free_bytes) >= TARGET_BYTES )) && break
    (( count <= KEEP_EXPORTS )) && break
    rm -rf --one-file-system -- "$d"
    count=$((count-1))
    log "pruned_export=$d free_bytes=$(free_bytes) remaining=$count"
  done
fi

if (( $(free_bytes) < TARGET_BYTES )) && [[ -d "$BACKUPS" ]]; then
  mapfile -t backups < <(find "$BACKUPS" -mindepth 1 -maxdepth 1 -type d -name 'pre-*' -printf '%T@ %p\n' | sort -n | cut -d' ' -f2-)
  count=${#backups[@]}
  for d in "${backups[@]}"; do
    (( $(free_bytes) >= TARGET_BYTES )) && break
    (( count <= KEEP_BACKUPS )) && break
    rm -rf --one-file-system -- "$d"
    count=$((count-1))
    log "pruned_pre_backup=$d free_bytes=$(free_bytes) remaining=$count"
  done
fi

FREE2=$(free_bytes)
if (( FREE2 < CRITICAL_BYTES )); then
  log "CRITICAL disk_free_bytes=$FREE2 manual_action_required=1 live_data_preserved=1"
  exit 2
fi
if (( FREE2 < WARN_BYTES )); then
  log "WARNING disk_free_bytes=$FREE2 below_onset_resume_threshold=1 no_more_safe_retention_candidates=1"
  exit 1
fi
log "retention_done free_bytes=$FREE2 onset_can_resume=1"
SH2
chmod 0755 "$EP_DIR/research-maintenance.sh"

cat > "/etc/systemd/system/$MAINT_TIMER" <<'UNIT'
[Unit]
Description=Run PumpRadar research disk retention guard

[Timer]
OnBootSec=2min
OnUnitActiveSec=15min
AccuracySec=1min
Persistent=true

[Install]
WantedBy=timers.target
UNIT

"$PY" - "$DB" <<'PY'
import sqlite3,sys
p=sys.argv[1]
c=sqlite3.connect(p,timeout=30,isolation_level=None)
c.execute('pragma busy_timeout=30000')
before=c.execute("select count(*) from research_episode_events where event='ONSET_COLLECTION_PAUSED_LOW_DISK'").fetchone()[0]
c.execute("""delete from research_episode_events
 where event='ONSET_COLLECTION_PAUSED_LOW_DISK'
   and id not in (
     select id from research_episode_events
      where event='ONSET_COLLECTION_PAUSED_LOW_DISK'
      order by id desc limit 50
   )""")
after=c.execute("select count(*) from research_episode_events where event='ONSET_COLLECTION_PAUSED_LOW_DISK'").fetchone()[0]
print(f'low_disk_events_before={before} after={after} deleted={before-after}')
c.close()
PY

"$PY" -m py_compile "$EP_DIR/research_episodes.py"
systemctl daemon-reload
systemctl restart "$MAINT_TIMER"

set +e
systemctl start "$MAINT_SVC"
MAINT_RC=$?
set -e

systemctl start "$EP"
systemctl is-active --quiet "$EP"
systemctl is-active --quiet "$MAIN"

AFTER="$(state_json)"
[[ "$AFTER" == "$BEFORE" ]] || { echo "Main cohort changed! before=$BEFORE after=$AFTER" >&2; false; }

FREE=$(df --output=avail -B1 / | tail -1 | tr -d ' ')
EVENT_COUNTS=$("$PY" - "$DB" <<'PY'
import sqlite3,sys
c=sqlite3.connect(f'file:{sys.argv[1]}?mode=ro',uri=True)
state=dict(c.execute("select key,value from research_episode_meta where key in ('active_strategy_effect','onset_disk_state')"))
warn=c.execute("select count(*) from research_episode_events where event='ONSET_COLLECTION_PAUSED_LOW_DISK'").fetchone()[0]
print('active_strategy_effect='+state.get('active_strategy_effect','?')+' onset_disk_state='+state.get('onset_disk_state','?')+' low_disk_events='+str(warn))
c.close()
PY
)

echo "AFTER=$AFTER"
echo "disk_free_bytes=$FREE maintenance_rc=$MAINT_RC"
echo "$EVENT_COUNTS"
echo "main_service=$(systemctl is-active "$MAIN" 2>/dev/null || true) research_episodes=$(systemctl is-active "$EP" 2>/dev/null || true) maintenance_timer=$(systemctl is-active "$MAINT_TIMER" 2>/dev/null || true)"
echo 'V4931_DISK_GUARD_OK=1'
echo 'TRADING_LOGIC_CHANGED=0 MAIN_SERVICE_RESTARTED=0'
echo 'LOW_DISK_EVENT_POLICY=transition_plus_30min_reminder'
echo 'RETENTION_POLICY=adaptive_target_2.5GB_keep_1_export_keep_2_pre_backups'
echo "backup=$BACKUP"
MUTATED=0
trap - ERR
