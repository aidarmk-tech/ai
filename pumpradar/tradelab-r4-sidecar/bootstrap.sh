#!/usr/bin/env bash
set -euo pipefail
BRANCH="tradelab-r4-five-models"
BASE="https://raw.githubusercontent.com/aidarmk-tech/ai/${BRANCH}/pumpradar/tradelab-r4-sidecar"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
for f in tradelab_r4_sidecar.py README.md tradelab-r4-five-models.service install.sh; do
  curl -fsSL "$BASE/$f" -o "$TMP/$f"
done
chmod +x "$TMP/install.sh" "$TMP/tradelab_r4_sidecar.py"
exec bash "$TMP/install.sh"
