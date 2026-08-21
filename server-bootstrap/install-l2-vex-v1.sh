#!/usr/bin/env bash
set -Eeuo pipefail

ARCHIVE=${1:-/root/PumpRadar-L2-VEX-Research-V1.tar.gz}
EXPECTED_SHA256='97b2d3d63690632f5316d16f83a63b1b31ca44f8c3be5417d4d078d4cc4fede7'
STAGE=/root/pumpradar-l2-vex-v1-stage

[[ ${EUID:-$(id -u)} -eq 0 ]] || { echo 'Run as root' >&2; exit 1; }
[[ -f "$ARCHIVE" ]] || { echo "Missing $ARCHIVE" >&2; exit 1; }
ACTUAL=$(sha256sum "$ARCHIVE" | awk '{print $1}')
[[ "$ACTUAL" == "$EXPECTED_SHA256" ]] || {
  echo "SHA256 mismatch: actual=$ACTUAL expected=$EXPECTED_SHA256" >&2
  exit 1
}

rm -rf "$STAGE"
mkdir -p "$STAGE"
tar -xzf "$ARCHIVE" -C "$STAGE"
PKG="$STAGE/pumpradar-l2-vex-v1"
[[ -d "$PKG" ]] || { echo 'Package root missing' >&2; exit 1; }
(cd "$PKG" && sha256sum -c FILES.sha256)

if [[ "${2:-}" == 'rollback' ]]; then
  exec bash "$PKG/install.sh" rollback
fi
exec bash "$PKG/install.sh"
