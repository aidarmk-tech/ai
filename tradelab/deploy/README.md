# TradeLab server deployment

TradeLab 0.1 is research/paper infrastructure only. It intentionally has no live-order endpoint.

## Fresh Ubuntu host

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

Create `/etc/tradelab.env` as root. Use a long random read token; never reuse a Binance API key here.

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

## Network exposure

Do not expose port 8000 directly to the Internet in production. Put HTTPS/authenticated reverse proxy or a private network in front of it. The Android read token must not be sent over plain public HTTP.
