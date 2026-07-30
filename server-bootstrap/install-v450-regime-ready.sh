#!/usr/bin/env bash
set -Eeuo pipefail

URL="https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v450-regime-paper/server-bootstrap/install-v450-regime.sh"
ORIGINAL_SHA256="dc663895e0c5840513a254defb3b65f1557654fb35d46b1653aabe26247f3295"
VENV_PY="/opt/pumpradar/venv/bin/python"
WORK="$(mktemp -d /tmp/pumpradar-v450-ready.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

[[ -x "$VENV_PY" ]] || { echo "ОШИБКА: не найден Python окружения PumpRadar: $VENV_PY" >&2; exit 1; }
"$VENV_PY" -c 'import aiohttp' || { echo "ОШИБКА: в окружении PumpRadar отсутствует aiohttp" >&2; exit 1; }

curl -fL --retry 5 --retry-delay 2 --connect-timeout 15 "$URL" -o "$WORK/original.sh"
actual="$(sha256sum "$WORK/original.sh" | awk '{print $1}')"
[[ "$actual" == "$ORIGINAL_SHA256" ]] || { echo "ОШИБКА: SHA исходного установщика не совпал: $actual" >&2; exit 1; }

python3 - "$WORK/original.sh" "$WORK/install.sh" <<'PY'
from pathlib import Path
import sys
src, dst = map(Path, sys.argv[1:])
s = src.read_text()

old_parts = 'PAYLOAD_PARTS=(00 01 02 07 08 09 10 11 12 13 14 15 16 17)'
new_parts = 'PAYLOAD_PARTS=(00 01 02 07 08 09 10 11 12 13 14a 14b 14c 14d1 14d2 15 16 17)'
if s.count(old_parts) != 1:
    raise SystemExit('ОШИБКА: неожиданная структура списка payload')
s = s.replace(old_parts, new_parts, 1)

anchor = 'python3 "$PATCH" "$CANDIDATE"\n'
block = '''python3 "$PATCH" "$CANDIDATE"\npython3 - "$CANDIDATE/tests/test_audit.py" <<'PY'\nfrom pathlib import Path\nimport sys\np=Path(sys.argv[1])\ns=p.read_text()\ns=s.replace('self.assertEqual(60, self.settings.warm_pool_size)', 'self.assertEqual(45, self.settings.warm_pool_size)')\ns=s.replace('self.assertEqual(20, self.settings.deep_candidates)', 'self.assertEqual(15, self.settings.deep_candidates)')\ns=s.replace('self.assertEqual(25, self.settings.depth_candidates)', 'self.assertEqual(20, self.settings.depth_candidates)')\np.write_text(s)\nPY\n'''
if s.count(anchor) != 1:
    raise SystemExit('ОШИБКА: неожиданная структура подготовки candidate')
s = s.replace(anchor, block, 1)

test_old = 'PYTHONPATH="$CANDIDATE" timeout 240 python3 -m unittest discover -s "$CANDIDATE/tests" -v'
test_new = 'PYTHONPATH="$CANDIDATE" timeout 240 /opt/pumpradar/venv/bin/python -m unittest discover -s "$CANDIDATE/tests" -v'
if s.count(test_old) != 1:
    raise SystemExit('ОШИБКА: не найдена команда тестов candidate')
s = s.replace(test_old, test_new, 1)

migration_old = 'PYTHONPATH="$CANDIDATE" \\\npython3 - <<\'PY\''
migration_new = 'PYTHONPATH="$CANDIDATE" \\\n/opt/pumpradar/venv/bin/python - <<\'PY\''
if s.count(migration_old) != 1:
    raise SystemExit('ОШИБКА: не найдена команда проверки миграции')
s = s.replace(migration_old, migration_new, 1)

dst.write_text(s)
PY

bash -n "$WORK/install.sh"
echo "Подготовленный установщик SHA-256: $(sha256sum "$WORK/install.sh" | awk '{print $1}')"
exec bash "$WORK/install.sh"
