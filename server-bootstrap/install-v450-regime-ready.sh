#!/usr/bin/env bash
set -Eeuo pipefail

URL="https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v450-regime-paper/server-bootstrap/install-v450-regime.sh"
ORIGINAL_SHA256="303d000badde6608ede4dd6d399fa810c90778f05ff9ed70444bb64d8cfeb9e4"
PATCHED_SHA256="c23acc649db7b8145d5013f268208dd558a5fc3de959b696fd5759b8974a8978"
WORK="$(mktemp -d /tmp/pumpradar-v450-ready.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

curl -fL --retry 5 --retry-delay 2 --connect-timeout 15 "$URL" -o "$WORK/original.sh"
actual="$(sha256sum "$WORK/original.sh" | awk '{print $1}')"
[[ "$actual" == "$ORIGINAL_SHA256" ]] || { echo "ОШИБКА: SHA исходного установщика не совпал: $actual" >&2; exit 1; }

sed 's/PAYLOAD_PARTS=(00 01 02 07 08 09 10 11 12 13 14 15 16 17)/PAYLOAD_PARTS=(00 01 02 07 08 09 10 11 12 13 14a 14b 14c 14d1 14d2 15 16 17)/' \
  "$WORK/original.sh" > "$WORK/install.sh"
actual="$(sha256sum "$WORK/install.sh" | awk '{print $1}')"
[[ "$actual" == "$PATCHED_SHA256" ]] || { echo "ОШИБКА: SHA подготовленного установщика не совпал: $actual" >&2; exit 1; }
bash -n "$WORK/install.sh"
exec bash "$WORK/install.sh"
