#!/usr/bin/env bash
set -Eeuo pipefail
python3 server-bootstrap/apply-v440.py
python3 -m compileall -q pumpradar-server/pumpradar_server
python3 -m unittest discover -s pumpradar-server/tests -v
rm -rf server-bootstrap/v440release
mkdir -p server-bootstrap/v440release
tar -czf /tmp/pumpradar-v440.tar.gz pumpradar-server
HASH=$(sha256sum /tmp/pumpradar-v440.tar.gz | awk '{print $1}')
base64 -w 0 /tmp/pumpradar-v440.tar.gz | split -b 700000 -d -a 2 - server-bootstrap/v440release/
PARTS=$(find server-bootstrap/v440release -maxdepth 1 -type f -printf '%f\n' | sort | tr '\n' ' ')
python3 - "$HASH" "$PARTS" <<'PY'
from pathlib import Path
import sys
h, parts = sys.argv[1], sys.argv[2].strip()
p=Path('server-bootstrap/install.sh')
s=p.read_text()
s=s.replace('chatgpt/pumpradar-v439-profit/server-bootstrap','chatgpt/pumpradar-v440-mc7-freeze100/server-bootstrap')
s=s.replace('PAYLOAD_PATH="v439release"','PAYLOAD_PATH="v440release"')
s=s.replace('PAYLOAD_PARTS=(00 01 02 03)',f'PAYLOAD_PARTS=({parts})')
s=s.replace('PAYLOAD_SHA256="f38a8b4cb8ae3a3797111bf48f34f05f91ee3e9e01567090fda49156da93d80c"',f'PAYLOAD_SHA256="{h}"')
s=s.replace('EXPECTED_VERSION="4.3.9-server"','EXPECTED_VERSION="4.4.0-server"')
s=s.replace("grep -q '4.3.9-server'", "grep -q '4.4.0-server'")
p.write_text(s)
p=Path('server-bootstrap/install-v439-safe.sh')
s=p.read_text().replace('install-v439-safe.sh','install-v440-safe.sh')
s=s.replace('chatgpt/pumpradar-v439-profit/server-bootstrap/install.sh','chatgpt/pumpradar-v440-mc7-freeze100/server-bootstrap/install.sh')
s=s.replace('v4.3.9','v4.4.0')
Path('server-bootstrap/install-v440-safe.sh').write_text(s)
PY
rm -f server-bootstrap/v440/TRIGGER
mkdir -p server-bootstrap/v440
printf '4.4.0-server\n' > server-bootstrap/v440/EXPECTED_VERSION
printf '%s\n' "$HASH" > server-bootstrap/v440/PAYLOAD_SHA256
git config user.name github-actions[bot]
git config user.email 41898282+github-actions[bot]@users.noreply.github.com
git add pumpradar-server server-bootstrap/install.sh server-bootstrap/install-v440-safe.sh server-bootstrap/v440 server-bootstrap/v440release
git commit -m 'PumpRadar v4.4.0: enable MC7 paper and freeze to 100 trades'
git push origin HEAD:chatgpt/pumpradar-v440-mc7-freeze100
