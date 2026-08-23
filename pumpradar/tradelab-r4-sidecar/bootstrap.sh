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

# Prefer the exact active DB path persisted by the first R4 install.
# Do not let backup/snapshot SQLite files win autodetection.
if [[ -z "${TRADELAB_DB:-}" && -f /etc/default/tradelab-r4-five-models ]]; then
  set -a
  # shellcheck disable=SC1091
  source /etc/default/tradelab-r4-five-models
  set +a
  if [[ -n "${TRADELAB_DB:-}" ]]; then
    echo "Using persisted active TradeLab DB: $TRADELAB_DB"
  fi
fi

if [[ -z "${TRADELAB_DB:-}" ]]; then
  TRADELAB_DB="$(python3 - <<'PY'
import os, sqlite3
from pathlib import Path
required={"participants","participant_specs","participant_events","paper_trades","market_samples","meta"}
roots=[Path('/var/lib'),Path('/opt'),Path('/srv'),Path('/root')]
preferred=[]; others=[]; seen=set()
skip_dirs={'.git','node_modules','venv','.venv','__pycache__','cache','tmp','backups','backup','snapshots','snapshot','archive','archives'}
for root in roots:
    if not root.exists(): continue
    for dirpath,dirnames,filenames in os.walk(root):
        dirnames[:]=[d for d in dirnames if d.lower() not in skip_dirs]
        for name in filenames:
            low=name.lower()
            if any(x in low for x in ('pre-r4','backup','snapshot','analysis-')): continue
            if not (low.endswith('.sqlite3') or low.endswith('.sqlite') or low.endswith('.db')): continue
            p=Path(dirpath)/name
            try: rp=str(p.resolve())
            except Exception: rp=str(p)
            if rp in seen: continue
            seen.add(rp)
            (preferred if ('tradelab' in low or 'trade' in low) else others).append(p)
def matches(p):
    try:
        con=sqlite3.connect(f"file:{p}?mode=ro",uri=True,timeout=1)
        names={r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        con.close(); return required.issubset(names)
    except Exception: return False
for p in preferred+others:
    if matches(p): print(p); raise SystemExit(0)
raise SystemExit(1)
PY
)" || true
fi

if [[ -z "${TRADELAB_DB:-}" || ! -f "${TRADELAB_DB:-}" ]]; then
  echo "Active TradeLab SQLite was not found." >&2
  echo "Persisted env:" >&2
  cat /etc/default/tradelab-r4-five-models 2>/dev/null >&2 || true
  echo "Candidate SQLite files (backups shown only for diagnosis):" >&2
  find /var/lib /opt /srv /root -type f \( -iname '*.sqlite3' -o -iname '*.sqlite' -o -iname '*.db' \) -print 2>/dev/null | head -100 >&2 || true
  exit 2
fi

export TRADELAB_DB
echo "Detected active TradeLab DB: $TRADELAB_DB"
exec bash "$TMP/install.sh"
