#!/usr/bin/env bash
set -Eeuo pipefail

BASE_INSTALLER_COMMIT="e7d342e04af3ff0143e46f337698f2e1ae1e4e12"
FIXED_PATCH_COMMIT="08ea60b201739e943491b8fe22cbfa5eeb5b5b5e"
OLD_PATCH_COMMIT="6836b15b31d8b72bba4078602cfb09a7633ef7d6"
URL="https://raw.githubusercontent.com/aidarmk-tech/ai/${BASE_INSTALLER_COMMIT}/server-bootstrap/install-v440-safe.sh"
TMP_SCRIPT="$(mktemp)"
trap 'rm -f "$TMP_SCRIPT"' EXIT

curl --fail --silent --show-error --retry 4 --retry-delay 2 \
  "$URL" -o "$TMP_SCRIPT"

python3 - "$TMP_SCRIPT" "$OLD_PATCH_COMMIT" "$FIXED_PATCH_COMMIT" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
old_commit = sys.argv[2]
new_commit = sys.argv[3]
text = path.read_text()

old_line = f'V440_COMMIT="{old_commit}"'
new_line = f'V440_COMMIT="{new_commit}"'
if old_line not in text:
    raise SystemExit("base installer patch commit anchor not found")
text = text.replace(old_line, new_line, 1)

old_tests = '''  python3 -m compileall -q pumpradar-server/pumpradar_server
  PYTHONPATH=pumpradar-server python3 -m unittest discover \\
    -s pumpradar-server/tests -v
'''
new_tests = '''  python3 -m compileall -q pumpradar-server/pumpradar_server
  TEST_VENV="$TMP_DIR/test-venv"
  python3 -m venv "$TEST_VENV"
  "$TEST_VENV/bin/pip" install --disable-pip-version-check --no-cache-dir \\
    -r pumpradar-server/requirements.txt
  PYTHONPATH=pumpradar-server "$TEST_VENV/bin/python" -m unittest discover \\
    -s pumpradar-server/tests -v
'''
if old_tests not in text:
    raise SystemExit("base installer test block anchor not found")
text = text.replace(old_tests, new_tests, 1)

old_migration = '''PYTHONPATH="$TMP_DIR/source-root/pumpradar-server" python3 - <<'PY'
'''
new_migration = '''PYTHONPATH="$TMP_DIR/source-root/pumpradar-server" "$TMP_DIR/test-venv/bin/python" - <<'PY'
'''
if old_migration not in text:
    raise SystemExit("base installer migration Python anchor not found")
text = text.replace(old_migration, new_migration, 1)

path.write_text(text)
PY

bash "$TMP_SCRIPT"
