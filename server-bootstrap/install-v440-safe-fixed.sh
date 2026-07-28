#!/usr/bin/env bash
set -Eeuo pipefail

BASE_INSTALLER_COMMIT="e7d342e04af3ff0143e46f337698f2e1ae1e4e12"
FIXED_PATCH_COMMIT="5161ce58cedd9e0778d98add331212d040603bd1"
OLD_PATCH_COMMIT="6836b15b31d8b72bba4078602cfb09a7633ef7d6"
URL="https://raw.githubusercontent.com/aidarmk-tech/ai/${BASE_INSTALLER_COMMIT}/server-bootstrap/install-v440-safe.sh"

curl --fail --silent --show-error --retry 4 --retry-delay 2 "$URL" \
  | sed "s/V440_COMMIT=\"${OLD_PATCH_COMMIT}\"/V440_COMMIT=\"${FIXED_PATCH_COMMIT}\"/" \
  | bash
