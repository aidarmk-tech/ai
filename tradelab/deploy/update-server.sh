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

rm -rf /tmp/tradelab-update
git clone --depth 1 --branch "${BRANCH}" --single-branch "${REPO}" /tmp/tradelab-update

systemctl stop tradelab
rm -rf /opt/tradelab/server.new
cp -a /tmp/tradelab-update/tradelab/server /opt/tradelab/server.new
/opt/tradelab/venv/bin/pip install --upgrade /opt/tradelab/server.new
rm -rf /opt/tradelab/server
mv /opt/tradelab/server.new /opt/tradelab/server
chown -R tradelab:tradelab /opt/tradelab/server /var/lib/tradelab

# Keep the existing read token and TLS configuration. Add only missing settings.
grep -q '^TRADELAB_SNAPSHOT_RAW_HOURS=' /etc/tradelab.env || echo 'TRADELAB_SNAPSHOT_RAW_HOURS=6' >>/etc/tradelab.env
grep -q '^TRADELAB_MARKET_ENABLED=' /etc/tradelab.env || cat >>/etc/tradelab.env <<'EOF'
TRADELAB_MARKET_ENABLED=true
TRADELAB_UNIVERSE_SIZE=40
TRADELAB_MICROSTRUCTURE_SIZE=12
TRADELAB_MARKET_SAMPLE_SECONDS=5
TRADELAB_RAW_RETENTION_HOURS=72
TRADELAB_SUBSCRIPTION_REFRESH_SECONDS=300
TRADELAB_OI_INTERVAL_SECONDS=60
TRADELAB_PAPER_FEE_BPS_PER_SIDE=5
TRADELAB_PAPER_SLIPPAGE_BPS_PER_SIDE=2
TRADELAB_PAPER_MAX_OPEN=2
TRADELAB_PAPER_STARTING_NOTIONAL_USDT=10
EOF
chmod 600 /etc/tradelab.env

systemctl daemon-reload
systemctl start tradelab
sleep 3
curl -fsS http://127.0.0.1:8000/health
echo
systemctl --no-pager --full status tradelab | sed -n '1,14p'
