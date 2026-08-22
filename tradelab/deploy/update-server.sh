#!/usr/bin/env bash
set -euo pipefail

BRANCH="${TRADELAB_BRANCH:-tradelab-v0.1}"
REPO="https://github.com/aidarmk-tech/ai.git"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash update-server.sh" >&2
  exit 1
fi

if [[ ! -f /etc/tradelab.env ]]; then
  echo "/etc/tradelab.env is missing; run bootstrap-ip.sh first" >&2
  exit 1
fi

rm -rf /tmp/tradelab-update /opt/tradelab/server.new
git clone --depth 1 --branch "${BRANCH}" --single-branch "${REPO}" /tmp/tradelab-update
cp -a /tmp/tradelab-update/tradelab/server /opt/tradelab/server.new

# Build/install while the old process is still serving. A package/install error
# therefore cannot turn a healthy recorder into downtime.
/opt/tradelab/venv/bin/pip install --upgrade /opt/tradelab/server.new

# Keep the existing read token and TLS configuration. Add only missing settings.
grep -q '^TRADELAB_SNAPSHOT_RAW_HOURS=' /etc/tradelab.env || echo 'TRADELAB_SNAPSHOT_RAW_HOURS=6' >>/etc/tradelab.env
grep -q '^TRADELAB_FULL_SNAPSHOT_KEEP=' /etc/tradelab.env || echo 'TRADELAB_FULL_SNAPSHOT_KEEP=1' >>/etc/tradelab.env
grep -q '^TRADELAB_MARKET_ENABLED=' /etc/tradelab.env || echo 'TRADELAB_MARKET_ENABLED=true' >>/etc/tradelab.env
grep -q '^TRADELAB_UNIVERSE_SIZE=' /etc/tradelab.env || echo 'TRADELAB_UNIVERSE_SIZE=40' >>/etc/tradelab.env
grep -q '^TRADELAB_MICROSTRUCTURE_SIZE=' /etc/tradelab.env || echo 'TRADELAB_MICROSTRUCTURE_SIZE=12' >>/etc/tradelab.env
grep -q '^TRADELAB_MARKET_SAMPLE_SECONDS=' /etc/tradelab.env || echo 'TRADELAB_MARKET_SAMPLE_SECONDS=5' >>/etc/tradelab.env
grep -q '^TRADELAB_RAW_RETENTION_HOURS=' /etc/tradelab.env || echo 'TRADELAB_RAW_RETENTION_HOURS=72' >>/etc/tradelab.env
grep -q '^TRADELAB_SUBSCRIPTION_REFRESH_SECONDS=' /etc/tradelab.env || echo 'TRADELAB_SUBSCRIPTION_REFRESH_SECONDS=300' >>/etc/tradelab.env
grep -q '^TRADELAB_OI_INTERVAL_SECONDS=' /etc/tradelab.env || echo 'TRADELAB_OI_INTERVAL_SECONDS=60' >>/etc/tradelab.env
grep -q '^TRADELAB_MAX_SAMPLE_GAP_SECONDS=' /etc/tradelab.env || echo 'TRADELAB_MAX_SAMPLE_GAP_SECONDS=12' >>/etc/tradelab.env
grep -q '^TRADELAB_PAPER_EXIT_GRACE_SECONDS=' /etc/tradelab.env || echo 'TRADELAB_PAPER_EXIT_GRACE_SECONDS=15' >>/etc/tradelab.env
grep -q '^TRADELAB_LABEL_GRACE_SECONDS=' /etc/tradelab.env || echo 'TRADELAB_LABEL_GRACE_SECONDS=10' >>/etc/tradelab.env
grep -q '^TRADELAB_PAPER_FEE_BPS_PER_SIDE=' /etc/tradelab.env || echo 'TRADELAB_PAPER_FEE_BPS_PER_SIDE=5' >>/etc/tradelab.env
grep -q '^TRADELAB_PAPER_SLIPPAGE_BPS_PER_SIDE=' /etc/tradelab.env || echo 'TRADELAB_PAPER_SLIPPAGE_BPS_PER_SIDE=2' >>/etc/tradelab.env
grep -q '^TRADELAB_PAPER_MAX_OPEN=' /etc/tradelab.env || echo 'TRADELAB_PAPER_MAX_OPEN=2' >>/etc/tradelab.env
grep -q '^TRADELAB_PAPER_STARTING_NOTIONAL_USDT=' /etc/tradelab.env || echo 'TRADELAB_PAPER_STARTING_NOTIONAL_USDT=10' >>/etc/tradelab.env
chmod 600 /etc/tradelab.env

systemctl stop tradelab
rm -rf /opt/tradelab/server
mv /opt/tradelab/server.new /opt/tradelab/server
chown -R tradelab:tradelab /opt/tradelab/server /var/lib/tradelab
install -m 0644 /tmp/tradelab-update/tradelab/deploy/tradelab.service /etc/systemd/system/tradelab.service
systemctl daemon-reload
systemctl start tradelab

# Startup now includes schema/epoch checks and Binance supervisors. Wait for the
# local control plane instead of assuming an arbitrary four seconds is enough.
for _ in $(seq 1 30); do
  if curl -fsS --max-time 2 http://127.0.0.1:8000/health; then
    echo
    systemctl --no-pager --full status tradelab | sed -n '1,14p'
    exit 0
  fi
  sleep 1
done

echo "TradeLab did not become healthy within 30 seconds" >&2
systemctl --no-pager --full status tradelab >&2 || true
journalctl -u tradelab -n 100 --no-pager >&2 || true
exit 1
