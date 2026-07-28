#!/usr/bin/env bash
set -Eeuo pipefail

python3 server-bootstrap/apply-v440.py
python3 -m compileall -q pumpradar-server/pumpradar_server
python3 -m unittest discover -s pumpradar-server/tests -v

# Migration smoke test against a fresh SQLite database.
TMP_DB_DIR=$(mktemp -d)
PUMPRADAR_DATA_DIR="$TMP_DB_DIR" PUMPRADAR_DB_PATH="$TMP_DB_DIR/test.sqlite3" \
PYTHONPATH=pumpradar-server python3 - <<'PY'
from pumpradar_server.config import Settings
from pumpradar_server.futures import FuturesPaperStore
from pumpradar_server.storage import Storage
s = Settings()
st = Storage(s)
st.start_run('ci')
fs = FuturesPaperStore(s, st)
assert st.conn.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
assert not list(st.conn.execute('PRAGMA foreign_key_check'))
assert fs.status()['freeze_target_primary_trades'] == 100
st.conn.close()
PY
rm -rf "$TMP_DB_DIR"

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
p = Path('server-bootstrap/install.sh')
s = p.read_text()
s = s.replace(
    'chatgpt/pumpradar-v439-profit/server-bootstrap',
    'chatgpt/pumpradar-v440-mc7-freeze100/server-bootstrap',
)
s = s.replace('PAYLOAD_PATH="v439release"', 'PAYLOAD_PATH="v440release"')
s = s.replace('PAYLOAD_PARTS=(00 01 02 03)', f'PAYLOAD_PARTS=({parts})')
s = s.replace(
    'PAYLOAD_SHA256="f38a8b4cb8ae3a3797111bf48f34f05f91ee3e9e01567090fda49156da93d80c"',
    f'PAYLOAD_SHA256="{h}"',
)
s = s.replace('EXPECTED_VERSION="4.3.9-server"', 'EXPECTED_VERSION="4.4.0-server"')
s = s.replace("grep -q '4.3.9-server'", "grep -q '4.4.0-server'")
s = s.replace(
    'PUMPRADAR_FEE_RATE=0.001\n',
    'PUMPRADAR_FEE_RATE=0.001\n'
    'PUMPRADAR_FUTURES_FEE_RATE=0.0005\n'
    'BINANCE_FUTURES_REST_URL=https://fapi.binance.com\n'
    'BINANCE_FUTURES_WS_URL=wss://fstream.binance.com\n',
)
s = s.replace(
    'PUMPRADAR_WARM_POOL_SIZE=60\n',
    'PUMPRADAR_WARM_POOL_SIZE=60\n'
    'PUMPRADAR_FUTURES_MIN_24H_QUOTE_VOLUME=1000000\n'
    'PUMPRADAR_FUTURES_MAX_CANDIDATES=30\n'
    'PUMPRADAR_FUTURES_WARM_POOL_SIZE=80\n'
    'PUMPRADAR_FUTURES_DEEP_CANDIDATES=25\n'
    'PUMPRADAR_FUTURES_DEPTH_CANDIDATES=30\n'
    'PUMPRADAR_FUTURES_MAX_SPREAD_BPS=30\n'
    'PUMPRADAR_FUTURES_MAX_BUY_SLIPPAGE_PERCENT=0.15\n'
    'PUMPRADAR_FUTURES_MAX_SELL_SLIPPAGE_PERCENT=0.35\n'
    'PUMPRADAR_FREEZE_TARGET_PRIMARY_TRADES=100\n',
)
s = s.replace(
    'ensure_env PUMPRADAR_STOP_WATCH_INTERVAL_MS 500\n',
    'ensure_env PUMPRADAR_STOP_WATCH_INTERVAL_MS 500\n'
    'ensure_env PUMPRADAR_FUTURES_FEE_RATE 0.0005\n'
    'ensure_env BINANCE_FUTURES_REST_URL https://fapi.binance.com\n'
    'ensure_env BINANCE_FUTURES_WS_URL wss://fstream.binance.com\n'
    'ensure_env PUMPRADAR_FUTURES_MIN_24H_QUOTE_VOLUME 1000000\n'
    'ensure_env PUMPRADAR_FUTURES_MAX_CANDIDATES 30\n'
    'ensure_env PUMPRADAR_FUTURES_WARM_POOL_SIZE 80\n'
    'ensure_env PUMPRADAR_FUTURES_DEEP_CANDIDATES 25\n'
    'ensure_env PUMPRADAR_FUTURES_DEPTH_CANDIDATES 30\n'
    'ensure_env PUMPRADAR_FUTURES_MAX_SPREAD_BPS 30\n'
    'ensure_env PUMPRADAR_FUTURES_MAX_BUY_SLIPPAGE_PERCENT 0.15\n'
    'ensure_env PUMPRADAR_FUTURES_MAX_SELL_SLIPPAGE_PERCENT 0.35\n'
    'ensure_env PUMPRADAR_FREEZE_TARGET_PRIMARY_TRADES 100\n',
)
p.write_text(s)

safe = '''#!/usr/bin/env bash
set -Eeuo pipefail

DB_PATH="${PUMPRADAR_DB_PATH:-/var/lib/pumpradar/pumpradar.sqlite3}"
INSTALL_URL="https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v440-mc7-freeze100/server-bootstrap/install.sh"

fail() { printf '\n[PumpRadar] ERROR: %s\n' "$*" >&2; exit 1; }
[[ ${EUID:-$(id -u)} -eq 0 ]] || fail "Запустите установщик от root"
command -v sqlite3 >/dev/null 2>&1 || fail "sqlite3 не установлен"
command -v curl >/dev/null 2>&1 || fail "curl не установлен"

open_count=0
if [[ -f "$DB_PATH" ]]; then
  table_count() {
    local table="$1" predicate="$2"
    if [[ "$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table';")" == "1" ]]; then
      sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM $table WHERE $predicate;"
    else
      printf '0\n'
    fi
  }
  open_count=$((
    $(table_count paper_slots "baseline_status='OPEN'") +
    $(table_count policy_runs "state='OPEN'") +
    $(table_count momentum_slots "primary_status='OPEN'") +
    $(table_count momentum_policy_runs "state='OPEN'") +
    $(table_count futures_momentum_slots "primary_status='OPEN'") +
    $(table_count futures_momentum_policy_runs "state='OPEN'")
  ))
fi

if (( open_count > 0 )); then
  fail "Обновление отменено: найдено открытых paper/policy записей: $open_count. Дождитесь закрытия позиции."
fi

printf '[PumpRadar] Открытых paper-позиций нет. Запускается проверенный установщик v4.4.0.\n'
curl -fsSL "$INSTALL_URL" | bash
'''
Path('server-bootstrap/install-v440-safe.sh').write_text(safe)
PY
chmod +x server-bootstrap/install-v440-safe.sh

rm -f server-bootstrap/v440/TRIGGER
mkdir -p server-bootstrap/v440
printf '4.4.0-server\n' > server-bootstrap/v440/EXPECTED_VERSION
printf '%s\n' "$HASH" > server-bootstrap/v440/PAYLOAD_SHA256
cat > server-bootstrap/v440/README.md <<EOF
PumpRadar v4.4.0 paper-only release.

- Spot MC5 unchanged; spot MC7 paper enabled.
- Binance USD-M futures MC5/MC7 mirror uses independent public market data.
- Futures fee default: 0.05% per side, configurable.
- Futures liquidity thresholds are separately configurable.
- No API key, signed endpoint or real order code exists.
- Freeze target: 100 closed primary spot+futures momentum trades.
- Payload SHA-256: $HASH
EOF

git config user.name github-actions[bot]
git config user.email 41898282+github-actions[bot]@users.noreply.github.com
git add \
  pumpradar-server \
  server-bootstrap/install.sh \
  server-bootstrap/install-v440-safe.sh \
  server-bootstrap/v440 \
  server-bootstrap/v440release
git commit -m 'PumpRadar v4.4.0: add USD-M futures MC5/MC7 mirror'
git push origin HEAD:chatgpt/pumpradar-v440-mc7-freeze100
