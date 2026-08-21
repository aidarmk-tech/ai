#!/usr/bin/env bash
set -Eeuo pipefail

ARCHIVE=${1:-/root/PumpRadar-L2-VEX-Research-V1-HOTFIX1.tar.gz}
EXPECTED_SHA256='4180c6d97476474dad64c08560a9121ee12ba04251dfd2f94d6d89401fdfe192'
STAGE=/root/pumpradar-l2-vex-v1-hotfix1-stage
PAYLOAD_URL='https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-l2-vex-research-v1/server-bootstrap/PumpRadar-L2-VEX-Research-V1-HOTFIX1.tar.gz.b64?rev=cacf7300'

[[ ${EUID:-$(id -u)} -eq 0 ]] || { echo 'Run as root' >&2; exit 1; }

if [[ ! -f "$ARCHIVE" ]]; then
  echo "Archive not found locally; downloading verified embedded payload..."
  TMP_B64="${ARCHIVE}.b64.tmp"
  rm -f "$TMP_B64" "$ARCHIVE"
  curl -fL --retry 3 --connect-timeout 15 "$PAYLOAD_URL" -o "$TMP_B64"
  base64 -d "$TMP_B64" > "$ARCHIVE"
  rm -f "$TMP_B64"
fi

ACTUAL=$(sha256sum "$ARCHIVE" | awk '{print $1}')
[[ "$ACTUAL" == "$EXPECTED_SHA256" ]] || {
  echo "SHA256 mismatch: actual=$ACTUAL expected=$EXPECTED_SHA256" >&2
  rm -f "$ARCHIVE"
  exit 1
}
echo "SHA256_OK=$ACTUAL"

rm -rf "$STAGE"
mkdir -p "$STAGE"
tar -xzf "$ARCHIVE" -C "$STAGE"
PKG="$STAGE/pumpradar-l2-vex-v1-hotfix1"
[[ -d "$PKG" ]] || { echo 'Package root missing' >&2; exit 1; }
(cd "$PKG" && sha256sum -c FILES.sha256)
exec bash "$PKG/install.sh"
