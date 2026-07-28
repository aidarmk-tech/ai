#!/usr/bin/env bash
set -Eeuo pipefail

python3 server-bootstrap/apply-v440.py

python3 -m pip install --disable-pip-version-check -q -r pumpradar-server/requirements.txt
python3 -m compileall -q pumpradar-server/pumpradar_server pumpradar-server/tests
PYTHONPATH=pumpradar-server python3 -m unittest discover -s pumpradar-server/tests -v

PYTHONPATH=pumpradar-server python3 - <<'PY'
import sqlite3
import tempfile
from dataclasses import replace
from pathlib import Path

from pumpradar_server.config import Settings
from pumpradar_server.storage import Storage

main = Path('pumpradar-server/pumpradar_server/main.py').read_text()
paper = Path('pumpradar-server/pumpradar_server/paper.py').read_text()
storage_source = Path('pumpradar-server/pumpradar_server/storage.py').read_text()
settings = Settings()
assert settings.algorithm_version == '4.4.0-server'
assert settings.freeze_primary_trade_target == 100
assert '"MC7": "MC7_CHALLENGER"' in main
assert 'if arm in ("MC5", "MC7"):' in main
assert 'channel=arm' in main
assert 'channel: str = "MC5"' in paper
assert 'MC7_CHALLENGER' in storage_source

root = Path(tempfile.mkdtemp())
db = root / 'pumpradar.sqlite3'
sqlite3.connect(db).close()
test_settings = replace(settings, data_dir=root, db_path=db, api_token='test')
store = Storage(test_settings)
store.start_run('v440-validation')
assert store.conn.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
assert not list(store.conn.execute('PRAGMA foreign_key_check'))
assert store.frozen_primary_trade_progress() == {
    'closed': 0,
    'target': 100,
    'remaining': 100,
}
store.conn.close()
print('v4.4.0 migration smoke ok')
PY

rm -rf server-bootstrap/v440 server-bootstrap/v440release
mkdir -p server-bootstrap/v440 server-bootstrap/v440release
printf '%s\n' '4.4.0-server' > server-bootstrap/v440/EXPECTED_VERSION
printf '%s\n' 'PAPER_ONLY' > server-bootstrap/v440/PAPER_ONLY
printf '%s\n' 'NO_REAL_ORDERS' > server-bootstrap/v440/NO_REAL_ORDERS
cat > server-bootstrap/v440/CHANGESET <<'EOF'
MC7 promoted from shadow to paper challenger.
MC5 and MC7 share one momentum risk slot.
TRADE3 and MC5 entry/exit thresholds unchanged.
Frozen until 100 closed primary paper trades.
EOF
cat > server-bootstrap/v440/README.md <<'EOF'
PumpRadar v4.4.0 is a paper-only frozen experiment.
The sole trading change is MC7 activation as a second momentum signal channel.
No additional strategy changes should be made before 100 closed primary trades.
EOF
cat > server-bootstrap/v440/INSTALL_COMMAND.txt <<'EOF'
curl -fsSL https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v440-mc7-freeze100/server-bootstrap/install-v440-safe.sh | bash
EOF

find pumpradar-server -type d -name __pycache__ -prune -exec rm -rf {} +
tar --sort=name --mtime='UTC 2020-01-01' --owner=0 --group=0 --numeric-owner \
  -czf /tmp/pumpradar-v440.tar.gz pumpradar-server
sha="$(sha256sum /tmp/pumpradar-v440.tar.gz | awk '{print $1}')"
base64 -w0 /tmp/pumpradar-v440.tar.gz > /tmp/payload.b64
split -n l/4 -d -a 2 /tmp/payload.b64 server-bootstrap/v440release/

python3 - "$sha" <<'PY'
from pathlib import Path
import re
import sys

sha = sys.argv[1]
path = Path('server-bootstrap/install.sh')
text = path.read_text()
text = re.sub(
    r'^REPO_RAW=.*$',
    'REPO_RAW="https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v440-mc7-freeze100/server-bootstrap"',
    text,
    flags=re.M,
)
text = re.sub(r'^PAYLOAD_PATH=.*$', 'PAYLOAD_PATH="v440release"', text, flags=re.M)
text = re.sub(r'^PAYLOAD_PARTS=.*$', 'PAYLOAD_PARTS=(00 01 02 03)', text, flags=re.M)
text = re.sub(r'^PAYLOAD_SHA256=.*$', f'PAYLOAD_SHA256="{sha}"', text, flags=re.M)
text = re.sub(r'^EXPECTED_VERSION=.*$', 'EXPECTED_VERSION="4.4.0-server"', text, flags=re.M)
text = text.replace("grep -q '4.3.9-server'", "grep -q '4.4.0-server'")
path.write_text(text)

safe = Path('server-bootstrap/install-v439-safe.sh').read_text()
safe = safe.replace(
    'chatgpt/pumpradar-v439-profit',
    'chatgpt/pumpradar-v440-mc7-freeze100',
)
safe = safe.replace('v4.3.9', 'v4.4.0')
Path('server-bootstrap/install-v440-safe.sh').write_text(safe)
PY
chmod +x server-bootstrap/install.sh server-bootstrap/install-v440-safe.sh server-bootstrap/build-v440.sh

cat server-bootstrap/v440release/{00..03} > /tmp/release.b64
base64 --decode /tmp/release.b64 > /tmp/release.tar.gz
echo "$sha  /tmp/release.tar.gz" | sha256sum -c -
tar -tzf /tmp/release.tar.gz >/dev/null
printf 'payload_sha256=%s\n' "$sha" > server-bootstrap/v440/LOCAL_VALIDATION.md
bash -n server-bootstrap/install.sh server-bootstrap/install-v440-safe.sh server-bootstrap/build-v440.sh

git config user.name github-actions[bot]
git config user.email 41898282+github-actions[bot]@users.noreply.github.com
git add pumpradar-server server-bootstrap
if git diff --cached --quiet; then
  echo 'v4.4.0 files already generated'
  exit 0
fi
git commit -m 'PumpRadar v4.4.0: activate MC7 paper channel [skip ci]'
git push origin HEAD:chatgpt/pumpradar-v440-mc7-freeze100
