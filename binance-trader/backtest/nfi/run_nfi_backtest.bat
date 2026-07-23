@echo off
REM ── Бэктест NostalgiaForInfinity через Docker (Windows) ──
REM Требуется Docker Desktop (docker.com/products/docker-desktop), запущенный.
REM Дважды кликни по этому файлу.
setlocal
cd /d "%~dp0"

docker --version >nul 2>nul
if errorlevel 1 (
    echo [!] Docker не найден. Установи Docker Desktop и запусти его, потом попробуй снова.
    pause
    exit /b 1
)

if not exist user_data\strategies mkdir user_data\strategies

echo [1/4] Скачиваю стратегию NostalgiaForInfinityX...
curl -fsSL -o user_data\strategies\NostalgiaForInfinityX.py ^
    https://raw.githubusercontent.com/iterativv/NostalgiaForInfinity/main/NostalgiaForInfinityX.py

echo [2/4] Тяну образ freqtrade (один раз)...
docker compose pull

echo [3/4] Качаю год истории (5m/15m/1h/4h/1d, 35 пар) - это долго...
docker compose run --rm freqtrade download-data --config /freqtrade/user_data/config.json -t 5m 15m 1h 4h 1d --days 365

echo [4/4] Прогоняю бэктест за последний год...
docker compose run --rm freqtrade backtesting --config /freqtrade/user_data/config.json --strategy NostalgiaForInfinityX --timeframe 5m --timerange=20250101-

echo.
echo Готово. Скопируй итоговую таблицу (SUMMARY METRICS) и пришли в чат.
pause
