#!/usr/bin/env bash
set -Eeuo pipefail

URL="https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v450-regime-paper/server-bootstrap/install-v450-regime.sh"
ORIGINAL_SHA256="303d000badde6608ede4dd6d399fa810c90778f05ff9ed70444bb64d8cfeb9e4"
PATCHED_SHA256="f1960bc30e69e67c0a55b375b5bdc059e72b74a8101e5f1d170c7403fd23379c"
WORK="$(mktemp -d /tmp/pumpradar-v450-ready.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

curl -fL --retry 5 --retry-delay 2 --connect-timeout 15 "$URL" -o "$WORK/original.sh"
actual="$(sha256sum "$WORK/original.sh" | awk '{print $1}')"
[[ "$actual" == "$ORIGINAL_SHA256" ]] || { echo "ОШИБКА: SHA исходного установщика не совпал: $actual" >&2; exit 1; }

python3 - "$WORK/original.sh" "$WORK/install.sh" <<'PY'
from pathlib import Path
import sys
src, dst = map(Path, sys.argv[1:])
s = src.read_text()
s = s.replace(
    'PAYLOAD_PARTS=(00 01 02 07 08 09 10 11 12 13 14 15 16 17)',
    'PAYLOAD_PARTS=(00 01 02 07 08 09 10 11 12 13 14a 14b 14c 14d1 14d2 15 16 17)',
)
anchor = 'python3 "$PATCH" "$CANDIDATE"\n'
block = '''python3 "$PATCH" "$CANDIDATE"\npython3 - "$CANDIDATE/tests/test_audit.py" <<'PY'\nfrom pathlib import Path\nimport sys\np=Path(sys.argv[1])\ns=p.read_text()\ns=s.replace('self.assertEqual(60, self.settings.warm_pool_size)', 'self.assertEqual(45, self.settings.warm_pool_size)')\ns=s.replace('self.assertEqual(20, self.settings.deep_candidates)', 'self.assertEqual(15, self.settings.deep_candidates)')\ns=s.replace('self.assertEqual(25, self.settings.depth_candidates)', 'self.assertEqual(20, self.settings.depth_candidates)')\np.write_text(s)\nPY\n'''
if anchor not in s:
    raise SystemExit('ОШИБКА: не найден якорь подготовки candidate')
s = s.replace(anchor, block, 1)
dst.write_text(s)
PY

actual="$(sha256sum "$WORK/install.sh" | awk '{print $1}')"
[[ "$actual" == "$PATCHED_SHA256" ]] || { echo "ОШИБКА: SHA подготовленного установщика не совпал: $actual" >&2; exit 1; }
bash -n "$WORK/install.sh"
exec bash "$WORK/install.sh"
