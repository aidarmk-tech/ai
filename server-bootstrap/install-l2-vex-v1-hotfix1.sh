#!/usr/bin/env bash
set -Eeuo pipefail

ARCHIVE=${1:-/root/PumpRadar-L2-VEX-Research-V1-HOTFIX1.tar.gz}
EXPECTED_SHA256='4180c6d97476474dad64c08560a9121ee12ba04251dfd2f94d6d89401fdfe192'
STAGE=/root/pumpradar-l2-vex-v1-hotfix1-stage

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
PKG="$STAGE/pumpradar-l2-vex-v1-hotfix1"
[[ -d "$PKG" ]] || { echo 'Package root missing' >&2; exit 1; }
(cd "$PKG" && sha256sum -c FILES.sha256)
exec bash "$PKG/install.sh"
