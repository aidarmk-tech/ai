#!/usr/bin/env bash
set -Eeuo pipefail

BASE="${PUMPRADAR_V4531_INSTALLER_BASE_URL:-https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v453-short-risk-client/server-bootstrap/v4531-installer}"
OUT="/root/install-pumpradar-v4531.py"
TMP="${OUT}.tmp"
EXPECTED_SHA256="e6cb2e5b34a5ecc2872457b12a2130208eb8ec76b63d24e1de05c681fb35235c"

: > "$TMP"
for part in 00 01 02 03 04 05 06 07; do
  curl -fsSL "${BASE}/part-${part}" >> "$TMP"
done

printf '%s  %s\n' "$EXPECTED_SHA256" "$TMP" | sha256sum -c -
install -m 0700 "$TMP" "$OUT"
rm -f "$TMP"

echo "PumpRadar v4.5.3.1 installer downloaded and verified: $OUT"

if [[ "${PUMPRADAR_V4531_DOWNLOAD_ONLY:-0}" == "1" ]]; then
  exit 0
fi

if (($#)); then
  exec python3 "$OUT" "$@"
else
  exec python3 "$OUT" install
fi
