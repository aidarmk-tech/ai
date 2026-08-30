#!/usr/bin/env bash
set -euo pipefail

IP="${1:-45.150.37.187}"
BRANCH="${TRADELAB_BRANCH:-tradelab-v0.1}"
REPO="https://github.com/aidarmk-tech/ai.git"
CERTBOT_VENV="/opt/certbot"
ACME_WEBROOT="/var/www/letsencrypt"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo bash bootstrap-ip.sh ${IP}" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y python3 python3-venv git nginx openssl ca-certificates curl

useradd --system --home /var/lib/tradelab --create-home --shell /usr/sbin/nologin tradelab 2>/dev/null || true
mkdir -p /opt/tradelab /var/lib/tradelab "${ACME_WEBROOT}"
rm -rf /tmp/tradelab-src
git clone --branch "${BRANCH}" --single-branch "${REPO}" /tmp/tradelab-src
rm -rf /opt/tradelab/server /opt/tradelab/venv
cp -a /tmp/tradelab-src/tradelab/server /opt/tradelab/server
python3 -m venv /opt/tradelab/venv
/opt/tradelab/venv/bin/pip install --upgrade pip
/opt/tradelab/venv/bin/pip install /opt/tradelab/server
chown -R tradelab:tradelab /opt/tradelab /var/lib/tradelab

TOKEN="$(openssl rand -hex 32)"
cat >/etc/tradelab.env <<EOF
TRADELAB_DATA_DIR=/var/lib/tradelab
TRADELAB_SNAPSHOT_KEEP=15
TRADELAB_SNAPSHOT_INTERVAL_HOURS=4
TRADELAB_SNAPSHOT_RAW_HOURS=6
TRADELAB_FULL_SNAPSHOT_KEEP=1
TRADELAB_RAW_RETENTION_HOURS=72
TRADELAB_READ_TOKEN=${TOKEN}
EOF
chmod 600 /etc/tradelab.env

cp /tmp/tradelab-src/tradelab/deploy/tradelab.service /etc/systemd/system/tradelab.service
systemctl daemon-reload
systemctl enable --now tradelab

# HTTP-only ACME challenge site. TradeLab itself remains loopback-only.
rm -f /etc/nginx/sites-enabled/default
cat >/etc/nginx/sites-available/tradelab <<EOF
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name ${IP};

    location ^~ /.well-known/acme-challenge/ {
        root ${ACME_WEBROOT};
        default_type text/plain;
    }

    location / {
        return 404;
    }
}
EOF
ln -sfn /etc/nginx/sites-available/tradelab /etc/nginx/sites-enabled/tradelab
nginx -t
systemctl enable --now nginx
systemctl reload nginx

# Install a current Certbot in an isolated venv. IP certificates require
# the short-lived profile and a recent Certbot with --ip-address support.
rm -rf "${CERTBOT_VENV}"
python3 -m venv "${CERTBOT_VENV}"
"${CERTBOT_VENV}/bin/pip" install --upgrade pip 'certbot>=5.4'

"${CERTBOT_VENV}/bin/certbot" certonly \
  --non-interactive \
  --agree-tos \
  --register-unsafely-without-email \
  --preferred-profile shortlived \
  --webroot \
  --webroot-path "${ACME_WEBROOT}" \
  --ip-address "${IP}"

CERT_DIR="/etc/letsencrypt/live/${IP}"
if [[ ! -s "${CERT_DIR}/fullchain.pem" || ! -s "${CERT_DIR}/privkey.pem" ]]; then
  echo "Certificate files not found in ${CERT_DIR}" >&2
  exit 1
fi

cat >/etc/nginx/sites-available/tradelab <<EOF
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name ${IP};

    location ^~ /.well-known/acme-challenge/ {
        root ${ACME_WEBROOT};
        default_type text/plain;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    server_name ${IP};

    ssl_certificate ${CERT_DIR}/fullchain.pem;
    ssl_certificate_key ${CERT_DIR}/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;

    client_max_body_size 8m;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 900s;
        proxy_send_timeout 900s;
    }
}
EOF
nginx -t
systemctl reload nginx

cat >/etc/systemd/system/tradelab-cert-renew.service <<EOF
[Unit]
Description=Renew TradeLab IP TLS certificate
After=network-online.target nginx.service
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=${CERTBOT_VENV}/bin/certbot renew --quiet --deploy-hook "systemctl reload nginx"
EOF

cat >/etc/systemd/system/tradelab-cert-renew.timer <<'EOF'
[Unit]
Description=Check TradeLab short-lived TLS certificate twice daily

[Timer]
OnBootSec=45min
OnUnitActiveSec=12h
RandomizedDelaySec=20min
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now tradelab-cert-renew.timer

curl -fsS http://127.0.0.1:8000/health >/dev/null
curl -fsS "https://${IP}/health" >/dev/null

echo
echo "============================================================"
echo "TradeLab deployed successfully"
echo "Server: https://${IP}"
echo "Android read token: ${TOKEN}"
echo "SAVE THIS TOKEN and enter it in the TradeLab Android app."
echo "The token is not committed to GitHub or embedded in the APK."
echo "============================================================"
echo
systemctl --no-pager --full status tradelab | sed -n '1,12p'
systemctl --no-pager --full status nginx | sed -n '1,10p'
