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

if [[ -z "${TRADELAB_DB:-}" ]]; then
  TRADELAB_DB="$(python3 - <<'PY'
import os, sqlite3
from pathlib import Path
required={"participants","participant_specs","participant_events","paper_trades","market_samples","meta"}
roots=[Path('/var/lib'),Path('/opt'),Path('/srv'),Path('/root')]
preferred=[]; others=[]; seen=set()
for root in roots:
    if not root.exists(): continue
    for dirpath,dirnames,filenames in os.walk(root):
        dirnames[:]=[d for d in dirnames if d not in {'.git','node_modules','venv','.venv','__pycache__','cache','tmp'}]
        for name in filenames:
            low=name.lower()
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

if [[ -z "${TRADELAB_DB:-}" ]]; then
  echo "TradeLab SQLite with the required schema was not found under /var/lib, /opt, /srv or /root." >&2
  find /var/lib /opt /srv /root -type f \( -iname '*.sqlite3' -o -iname '*.sqlite' -o -iname '*.db' \) -print 2>/dev/null | head -100 >&2 || true
  exit 2
fi

export TRADELAB_DB
echo "Detected TradeLab DB: $TRADELAB_DB"
exec bash "$TMP/install.sh"
