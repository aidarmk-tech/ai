# TradeLab server deployment

TradeLab 0.1 is research/paper infrastructure only. It intentionally has no live-order endpoint.

## Recommended: fresh Ubuntu VPS with public IPv4

For the current TradeLab host (`45.150.37.187`), run as a sudo-capable user:

```bash
curl -fsSL https://raw.githubusercontent.com/aidarmk-tech/ai/tradelab-v0.1/tradelab/deploy/bootstrap-ip.sh -o /tmp/bootstrap-tradelab.sh
sudo bash /tmp/bootstrap-tradelab.sh 45.150.37.187
```

The bootstrap:

- installs the Python server, Nginx and a current Certbot;
- binds TradeLab itself to `127.0.0.1:8000` only;
- generates a random 256-bit read token locally on the VPS;
- requests a public Let’s Encrypt short-lived certificate for the IPv4 address;
- exposes only HTTPS through Nginx;
- enables automatic TLS renewal checks twice daily;
- starts the TradeLab systemd service;
- checks both local and external `/health` endpoints;
- prints the Android read token once at the end.

Save that token and enter it in the Android client. Do not put it in GitHub and do not reuse any Binance credential.

The ACME validation needs inbound TCP 80 and the client needs TCP 443. Port 8000 should remain closed externally.

## Manual install

```bash
sudo apt update
sudo apt install -y python3 python3-venv git
sudo useradd --system --home /var/lib/tradelab --create-home --shell /usr/sbin/nologin tradelab || true
sudo mkdir -p /opt/tradelab /var/lib/tradelab
sudo chown -R tradelab:tradelab /opt/tradelab /var/lib/tradelab
```

Clone the `tradelab-v0.1` branch and install the server package:

```bash
sudo -u tradelab git clone --branch tradelab-v0.1 --single-branch https://github.com/aidarmk-tech/ai.git /tmp/tradelab-src
sudo -u tradelab cp -a /tmp/tradelab-src/tradelab/server /opt/tradelab/server
sudo -u tradelab python3 -m venv /opt/tradelab/venv
sudo -u tradelab /opt/tradelab/venv/bin/pip install --upgrade pip
sudo -u tradelab /opt/tradelab/venv/bin/pip install /opt/tradelab/server
```

Create `/etc/tradelab.env` as root:

```text
TRADELAB_DATA_DIR=/var/lib/tradelab
TRADELAB_SNAPSHOT_KEEP=15
TRADELAB_SNAPSHOT_INTERVAL_HOURS=4
TRADELAB_READ_TOKEN=<long-random-read-only-token>
```

Install the service:

```bash
sudo cp /tmp/tradelab-src/tradelab/deploy/tradelab.service /etc/systemd/system/tradelab.service
sudo chmod 600 /etc/tradelab.env
sudo systemctl daemon-reload
sudo systemctl enable --now tradelab
curl http://127.0.0.1:8000/health
```

Expected health response includes `"live_trading": false`.
