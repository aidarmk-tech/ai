#!/usr/bin/env bash
set -euo pipefail

ACTION="${1:-install}"
BRANCH="chatgpt/pumpradar-v452-liquidity-research"
BASE_URL="https://raw.githubusercontent.com/aidarmk-tech/ai/${BRANCH}/server-bootstrap/v452payload"
PAYLOAD_SHA256="91ecfc787c8d341dbbb6903b12b7b5ced859297578af6fefa81cd8ce673808c4"
INSTALLER_SHA256="7c6555b058e1a67e7559e625e70cb0a16e2a72e07e420fcaa6ad4551ebb0855e"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

for part in 00 01 02 03 04 05 06; do
  curl -fsSL "${BASE_URL}/${part}" -o "${TMPDIR}/${part}"
done
cat "${TMPDIR}/00" "${TMPDIR}/01" "${TMPDIR}/02" "${TMPDIR}/03" "${TMPDIR}/04" "${TMPDIR}/05" "${TMPDIR}/06" > "${TMPDIR}/installer.payload.b64"

echo "${PAYLOAD_SHA256}  ${TMPDIR}/installer.payload.b64" | sha256sum -c -
base64 -d "${TMPDIR}/installer.payload.b64" | gzip -dc > "${TMPDIR}/install-pumpradar-v452-research-v2.py"
echo "${INSTALLER_SHA256}  ${TMPDIR}/install-pumpradar-v452-research-v2.py" | sha256sum -c -
chmod 700 "${TMPDIR}/install-pumpradar-v452-research-v2.py"
exec python3 "${TMPDIR}/install-pumpradar-v452-research-v2.py" "${ACTION}"
