#!/usr/bin/env bash
# ── Бэктест NostalgiaForInfinity через Docker (Mac / Linux) ──
# Требуется установленный Docker. Запуск:  bash run_nfi_backtest.sh
set -e
cd "$(dirname "$0")"

if ! command -v docker >/dev/null 2>&1; then
    echo "[!] Docker не установлен. Поставь Docker Desktop: https://docker.com/products/docker-desktop"
    exit 1
fi

mkdir -p user_data/strategies

echo "[1/4] Скачиваю стратегию NostalgiaForInfinityX…"
curl -fsSL -o user_data/strategies/NostalgiaForInfinityX.py \
    https://raw.githubusercontent.com/iterativv/NostalgiaForInfinity/main/NostalgiaForInfinityX.py

echo "[2/4] Тяну образ freqtrade (один раз, ~пара минут)…"
docker compose pull

echo "[3/4] Качаю год истории (5m/15m/1h/4h/1d, 35 пар) — это долго, оставь работать…"
docker compose run --rm freqtrade download-data \
    --config /freqtrade/user_data/config.json \
    -t 5m 15m 1h 4h 1d --days 365

echo "[4/4] Прогоняю бэктест…"
docker compose run --rm freqtrade backtesting \
    --config /freqtrade/user_data/config.json \
    --strategy NostalgiaForInfinityX \
    --timeframe 5m --timerange=$(date -u -d '365 days ago' +%Y%m%d 2>/dev/null || date -u -v-365d +%Y%m%d)-

echo
echo "Готово. Скопируй итоговую таблицу (SUMMARY METRICS) и пришли в чат."
