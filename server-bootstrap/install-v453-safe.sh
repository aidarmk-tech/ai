#!/usr/bin/env bash
set -Eeuo pipefail
ACTION="${1:-install}"
BRANCH="chatgpt/pumpradar-v453-short-risk-client"
BASE="https://raw.githubusercontent.com/aidarmk-tech/ai/${BRANCH}/server-bootstrap"
INSTALLER_SHA256="3dab870c08b5e00dfd6c7e84beb2e539d0d2e82d6c7bc26fa3dd640647d3a3ff"
PAYLOAD_B64_SHA256="fa8f2a78717a902790e68165e4dad3267a60e34599debe852e0fbbb44e2e7c9b"
PAYLOAD_SHA256="f99bc7577d346eb98b7d8f65186d021ba1f2221cca1c995020e5e92381301df6"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL "$BASE/install-pumpradar-v453.py" -o "$TMP/install.py"
echo "$INSTALLER_SHA256  $TMP/install.py" | sha256sum -c -
: > "$TMP/payload.b64"
for part in 00 01 02 ; do
  curl -fsSL "$BASE/v453payload/$part" >> "$TMP/payload.b64"
done
echo "$PAYLOAD_B64_SHA256  $TMP/payload.b64" | sha256sum -c -
base64 -d "$TMP/payload.b64" > "$TMP/payload.tar.gz"
echo "$PAYLOAD_SHA256  $TMP/payload.tar.gz" | sha256sum -c -
chmod 700 "$TMP/install.py"
exec python3 "$TMP/install.py" "$ACTION" "$TMP/payload.tar.gz"
