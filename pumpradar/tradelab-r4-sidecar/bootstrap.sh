#!/usr/bin/env bash
set -euo pipefail
REV="062af39fd7623a4ecceebaef05b762d52583b813"
BASE="https://raw.githubusercontent.com/aidarmk-tech/ai/${REV}/pumpradar/tradelab-r4-sidecar"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for f in tradelab_r4_sidecar.py tradelab_r4_isolation.py README.md tradelab-r4-five-models.service install.sh; do
  curl -fsSL "$BASE/$f" -o "$TMP/$f"
done
chmod +x "$TMP/install.sh" "$TMP/tradelab_r4_sidecar.py" "$TMP/tradelab_r4_isolation.py"

# Locate the LIVE TradeLab DB. A previous bad autodetection may have persisted a
# backup path, so running recorder/server processes are authoritative.
if [[ -z "${TRADELAB_DB:-}" ]]; then
  TRADELAB_DB="$(python3 - <<'PY'
import os, sqlite3
from pathlib import Path
required={"participants","participant_specs","participant_events","paper_trades","market_samples","meta"}
skip_parts={"backups","backup","snapshots","snapshot","archive","archives"}

def unsafe(p: Path) -> bool:
    lowparts={x.lower() for x in p.parts}
    low=p.name.lower()
    return bool(lowparts & skip_parts) or any(x in low for x in ("pre-r4","backup","snapshot","analysis-"))

def matches(p: Path) -> bool:
    if unsafe(p) or not p.is_file(): return False
    try:
        con=sqlite3.connect(f"file:{p}?mode=ro",uri=True,timeout=1)
        names={r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        con.close()
        return required.issubset(names)
    except Exception:
        return False

# 1) Prefer DB files actually opened by the legacy recorder/server. Explicitly
# de-prioritize the R4 sidecar because it may already be attached to a wrong backup.
proc_candidates=[]
proc=Path('/proc')
for pd in proc.iterdir() if proc.exists() else []:
    if not pd.name.isdigit(): continue
    try:
        cmd=(pd/'cmdline').read_bytes().replace(b'\0',b' ').decode('utf-8','ignore').lower()
    except Exception:
        cmd=''
    if 'tradelab_r4_sidecar.py' in cmd:
        continue
    score=0
    if 'recorder' in cmd: score += 200
    if 'pumpradar' in cmd: score += 100
    if 'python' in cmd: score += 10
    try:
        fds=list((pd/'fd').iterdir())
    except Exception:
        continue
    for fd in fds:
        try:
            raw=os.readlink(fd)
        except Exception:
            continue
        if raw.endswith(' (deleted)'): continue
        p=Path(raw)
        low=p.name.lower()
        if not (low.endswith('.sqlite3') or low.endswith('.sqlite') or low.endswith('.db')): continue
        if matches(p):
            extra=20 if ('tradelab' in low or 'pumpradar' in low) else 0
            try: mt=p.stat().st_mtime
            except Exception: mt=0
            proc_candidates.append((score+extra,mt,str(p)))
if proc_candidates:
    proc_candidates.sort(reverse=True)
    print(proc_candidates[0][2])
    raise SystemExit(0)

# 2) Safe persisted path, only if it is not a backup/snapshot.
envfile=Path('/etc/default/tradelab-r4-five-models')
if envfile.exists():
    try:
        for line in envfile.read_text().splitlines():
            if line.startswith('TRADELAB_DB='):
                raw=line.split('=',1)[1].strip().strip("'\"")
                p=Path(raw)
                if matches(p):
                    print(p); raise SystemExit(0)
    except Exception:
        pass

# 3) Disk scan excluding backup/snapshot directories; newest valid DB wins.
roots=[Path('/var/lib'),Path('/opt'),Path('/srv'),Path('/root')]
found=[]
for root in roots:
    if not root.exists(): continue
    for dirpath,dirnames,filenames in os.walk(root):
        dirnames[:]=[d for d in dirnames if d.lower() not in skip_parts|{'.git','node_modules','venv','.venv','__pycache__','cache','tmp'}]
        for name in filenames:
            low=name.lower()
            if not (low.endswith('.sqlite3') or low.endswith('.sqlite') or low.endswith('.db')): continue
            p=Path(dirpath)/name
            if matches(p):
                try: mt=p.stat().st_mtime
                except Exception: mt=0
                found.append((mt,str(p)))
if found:
    found.sort(reverse=True)
    print(found[0][1]); raise SystemExit(0)
raise SystemExit(1)
PY
)" || true
fi

if [[ -z "${TRADELAB_DB:-}" || ! -f "${TRADELAB_DB:-}" ]]; then
  echo "Active TradeLab SQLite was not found." >&2
  echo "Persisted env (may be stale):" >&2
  cat /etc/default/tradelab-r4-five-models 2>/dev/null >&2 || true
  echo "SQLite candidates:" >&2
  find /var/lib /opt /srv /root -type f \( -iname '*.sqlite3' -o -iname '*.sqlite' -o -iname '*.db' \) -print 2>/dev/null | head -100 >&2 || true
  exit 2
fi

export TRADELAB_DB
echo "Detected LIVE TradeLab DB: $TRADELAB_DB"
exec bash "$TMP/install.sh"
