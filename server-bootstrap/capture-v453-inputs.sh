#!/usr/bin/env bash
set -Eeuo pipefail

TS="$(date +%Y%m%d-%H%M%S)"
OUT="/root/pumpradar-v453-inputs-${TS}.tar.gz"
SUM="${OUT}.sha256"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

APP_ROOT="/opt/pumpradar"
DATA_ROOT="/var/lib/pumpradar"

if [[ ! -d "$APP_ROOT" ]]; then
  echo "ERROR: $APP_ROOT not found" >&2
  exit 1
fi

mkdir -p \
  "$TMP/rootfs/opt/pumpradar" \
  "$TMP/systemd" \
  "$TMP/runtime"

# Copy only application sources and deployment files. Never include virtualenv,
# SQLite databases, exports, market captures, logs, caches or environment files.
(
  cd "$APP_ROOT"
  tar \
    --exclude='./venv' \
    --exclude='./.venv' \
    --exclude='./env' \
    --exclude='./.env' \
    --exclude='./*.env' \
    --exclude='./**/.env' \
    --exclude='./**/*.env' \
    --exclude='./__pycache__' \
    --exclude='./**/__pycache__' \
    --exclude='./*.pyc' \
    --exclude='./**/*.pyc' \
    --exclude='./*.pyo' \
    --exclude='./**/*.pyo' \
    --exclude='./*.sqlite3' \
    --exclude='./*.sqlite3-*' \
    --exclude='./**/*.sqlite3' \
    --exclude='./**/*.sqlite3-*' \
    --exclude='./*.db' \
    --exclude='./**/*.db' \
    --exclude='./*.log' \
    --exclude='./**/*.log' \
    --exclude='./exports' \
    --exclude='./captures' \
    -cf - .
) | tar -C "$TMP/rootfs/opt/pumpradar" -xf -

# Store unit definitions while redacting inline Environment= values.
mapfile -t UNITS < <(
  systemctl list-unit-files --type=service --no-legend 2>/dev/null \
    | awk '$1 ~ /pumpradar/ {print $1}' \
    | sort -u
)

for unit in "${UNITS[@]:-}"; do
  [[ -n "$unit" ]] || continue
  safe="${unit//\//_}"
  {
    echo "# unit: $unit"
    systemctl cat "$unit" 2>/dev/null \
      | sed -E 's/^([[:space:]]*Environment=).*/\1<REDACTED>/'
  } > "$TMP/systemd/${safe}.cat.txt" || true

  systemctl show "$unit" \
    --property=Id \
    --property=LoadState \
    --property=ActiveState \
    --property=SubState \
    --property=FragmentPath \
    --property=DropInPaths \
    --property=ExecStart \
    --property=WorkingDirectory \
    --property=EnvironmentFiles \
    > "$TMP/systemd/${safe}.show.txt" 2>/dev/null || true
done

{
  echo "captured_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "hostname=$(hostname)"
  echo "kernel=$(uname -srmo)"
  echo "app_root=$APP_ROOT"
  echo "data_root=$DATA_ROOT"
  echo "units=${UNITS[*]:-}"
  python3 --version 2>&1 || true
  if [[ -x "$APP_ROOT/venv/bin/python" ]]; then
    "$APP_ROOT/venv/bin/python" --version 2>&1 || true
  fi
} > "$TMP/runtime/system.txt"

if [[ -x "$APP_ROOT/venv/bin/pip" ]]; then
  "$APP_ROOT/venv/bin/pip" freeze \
    | sed -E 's#(https?://)[^/@]+@#\1<REDACTED>@#g' \
    > "$TMP/runtime/pip-freeze.txt" || true
fi

# Status files are useful for determining the actual recorder schema/version.
for status in \
  "$DATA_ROOT/status.json" \
  "$DATA_ROOT/research/status.json" \
  "$DATA_ROOT/research-v2/status.json"; do
  if [[ -f "$status" ]]; then
    name="$(echo "$status" | sed 's#^/##; s#/#_#g')"
    cp "$status" "$TMP/runtime/${name}" || true
  fi
done

# Source inventory and hashes; excludes any file that was deliberately omitted.
(
  cd "$TMP/rootfs/opt/pumpradar"
  find . -type f -print0 \
    | sort -z \
    | xargs -0 -r sha256sum
) > "$TMP/runtime/source-sha256.txt"

cat > "$TMP/README.txt" <<'EOF'
PumpRadar v4.5.3 implementation input snapshot.

Included:
- installed application/deployment source from /opt/pumpradar;
- redacted systemd unit definitions and runtime paths;
- Python/package versions;
- recorder status files when present;
- SHA-256 inventory of included source files.

Excluded:
- venv contents;
- .env and environment values;
- SQLite databases and WAL/SHM files;
- exports, captures and recorder market data;
- logs and Python caches.
EOF

tar -C "$TMP" -czf "$OUT" .
sha256sum "$OUT" > "$SUM"

printf '\n=== READY ===\n'
ls -lh "$OUT" "$SUM"
cat "$SUM"
printf '\nUpload both files. No trading or recorder service was stopped.\n'
