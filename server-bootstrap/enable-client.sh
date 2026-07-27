#!/usr/bin/env bash
set -Eeuo pipefail

PUBLIC_IP="${1:-45.150.37.187}"
ENV_FILE="/etc/pumpradar/server.env"
WEBROOT="/var/www/pumpradar-acme"
SITE="/etc/nginx/sites-available/pumpradar-client"

log() { printf '\n[PumpRadar Client Gateway] %s\n' "$*"; }
fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

[[ ${EUID:-$(id -u)} -eq 0 ]] || fail "Запустите от root"
[[ -f "$ENV_FILE" ]] || fail "PumpRadar server не найден: $ENV_FILE"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
PORT="${PUMPRADAR_BIND_PORT:-8787}"
TOKEN="${PUMPRADAR_API_TOKEN:-}"
[[ -n "$TOKEN" ]] || fail "PUMPRADAR_API_TOKEN отсутствует"

log "Проверка локального PumpRadar"
systemctl enable --now pumpradar.service
curl -fsS --max-time 10 "http://127.0.0.1:${PORT}/healthz" >/tmp/pumpradar-health.json || {
  journalctl -u pumpradar.service -n 80 --no-pager >&2 || true
  fail "Локальный сервис не отвечает"
}
cat /tmp/pumpradar-health.json

log "Установка Nginx и Certbot"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends nginx python3-venv ca-certificates curl openssl ufw

CERTBOT=""
if command -v certbot >/dev/null 2>&1; then
  CURRENT="$(certbot --version 2>/dev/null | awk '{print $2}' || true)"
  if [[ -n "$CURRENT" ]] && dpkg --compare-versions "$CURRENT" ge 5.4; then
    CERTBOT="$(command -v certbot)"
  fi
fi
if [[ -z "$CERTBOT" ]]; then
  python3 -m venv /opt/pumpradar-certbot
  /opt/pumpradar-certbot/bin/pip install --disable-pip-version-check --no-cache-dir --upgrade pip
  /opt/pumpradar-certbot/bin/pip install --disable-pip-version-check --no-cache-dir 'certbot>=5.4,<7'
  CERTBOT="/opt/pumpradar-certbot/bin/certbot"
fi
"$CERTBOT" --version

install -d -m 0755 "$WEBROOT/.well-known/acme-challenge"
rm -f /etc/nginx/sites-enabled/default

log "Временный HTTP endpoint для выпуска IP-сертификата"
cat > "$SITE" <<NGINX
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;

    location ^~ /.well-known/acme-challenge/ {
        root $WEBROOT;
        default_type text/plain;
    }

    location / {
        default_type application/json;
        return 200 '{"ok":true,"stage":"certificate"}';
    }
}
NGINX
ln -sfn "$SITE" /etc/nginx/sites-enabled/pumpradar-client
nginx -t
systemctl enable --now nginx
systemctl reload nginx

ufw allow OpenSSH >/dev/null 2>&1 || ufw allow 22/tcp >/dev/null 2>&1 || true
ufw allow 80/tcp >/dev/null 2>&1 || true
ufw allow 443/tcp >/dev/null 2>&1 || true
ufw --force enable >/dev/null 2>&1 || true

CERT_DIR="/etc/letsencrypt/live/${PUBLIC_IP}"
if [[ ! -s "$CERT_DIR/fullchain.pem" ]] || ! openssl x509 -checkend 86400 -noout -in "$CERT_DIR/fullchain.pem"; then
  log "Получение публичного HTTPS-сертификата для IP ${PUBLIC_IP}"
  "$CERTBOT" certonly \
    --preferred-profile shortlived \
    --webroot --webroot-path "$WEBROOT" \
    --ip-address "$PUBLIC_IP" \
    --non-interactive --agree-tos --register-unsafely-without-email
fi
[[ -s "$CERT_DIR/fullchain.pem" && -s "$CERT_DIR/privkey.pem" ]] || fail "Сертификат не создан"

log "Настройка read-only HTTPS API"
umask 077
cat > "$SITE" <<NGINX
limit_req_zone \$binary_remote_addr zone=pumpradar_api:10m rate=30r/m;

server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;

    location ^~ /.well-known/acme-challenge/ {
        root $WEBROOT;
        default_type text/plain;
    }

    location / { return 301 https://\$host\$request_uri; }
}

server {
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    server_name _;

    ssl_certificate $CERT_DIR/fullchain.pem;
    ssl_certificate_key $CERT_DIR/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:SSL:5m;
    server_tokens off;
    client_max_body_size 1m;

    add_header X-Content-Type-Options nosniff always;
    add_header Cache-Control "no-store" always;

    location = / {
        default_type application/json;
        return 200 '{"service":"PumpRadar","mode":"measurement","api":"read-only"}';
    }

    location = /healthz {
        limit_req zone=pumpradar_api burst=10 nodelay;
        limit_except GET { deny all; }
        proxy_set_header Host \$host;
        proxy_pass http://127.0.0.1:${PORT};
    }

    location = /api/status {
        limit_req zone=pumpradar_api burst=10 nodelay;
        limit_except GET { deny all; }
        proxy_set_header Host \$host;
        proxy_set_header Authorization "Bearer ${TOKEN}";
        proxy_pass http://127.0.0.1:${PORT};
    }

    location ~ ^/api/export/latest/(manifest\\.json|paper_slots\\.csv\\.gz|policy_runs\\.csv\\.gz|snapshots\\.csv\\.gz|snapshot_outcomes\\.csv\\.gz|experiment_runs\\.csv\\.gz|skipped_candidates\\.csv\\.gz|service_events\\.csv\\.gz|pumpradar\\.sqlite3\\.gz)$ {
        limit_req zone=pumpradar_api burst=5 nodelay;
        limit_except GET { deny all; }
        proxy_set_header Host \$host;
        proxy_set_header Authorization "Bearer ${TOKEN}";
        proxy_pass http://127.0.0.1:${PORT};
    }

    location / { return 404; }
}
NGINX
chmod 0600 "$SITE"
nginx -t
systemctl reload nginx

log "Создание свежего экспорта"
curl -fsS -X POST -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${PORT}/api/export" >/tmp/pumpradar-export.json
cat /tmp/pumpradar-export.json

CERTBOT_ESCAPED="$(printf '%q' "$CERTBOT")"
cat > /etc/systemd/system/pumpradar-cert-renew.service <<UNIT
[Unit]
Description=Renew PumpRadar short-lived IP certificate
After=network-online.target nginx.service

[Service]
Type=oneshot
ExecStart=/bin/bash -lc '${CERTBOT_ESCAPED} renew --quiet --deploy-hook "systemctl reload nginx"'
UNIT

cat > /etc/systemd/system/pumpradar-cert-renew.timer <<'UNIT'
[Unit]
Description=Renew PumpRadar HTTPS certificate twice daily

[Timer]
OnCalendar=*-*-* 00,12:20:00
RandomizedDelaySec=15m
Persistent=true

[Install]
WantedBy=timers.target
UNIT

systemctl daemon-reload
systemctl enable --now pumpradar-cert-renew.timer

log "Финальная проверка HTTPS"
curl -fsS --max-time 20 "https://${PUBLIC_IP}/healthz" | tee /tmp/pumpradar-public-health.json
printf '\n\nPumpRadar Client API готов: https://%s\n' "$PUBLIC_IP"
printf 'APK использует только read-only GET; торговые команды наружу не открыты.\n'
