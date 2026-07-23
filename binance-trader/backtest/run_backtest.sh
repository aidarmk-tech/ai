#!/usr/bin/env bash
# ── Бэктест «Хамелеон» в один клик (Mac / Linux) ──
# Запуск:  bash run_backtest.sh
# (или сделай исполняемым: chmod +x run_backtest.sh && ./run_backtest.sh)

cd "$(dirname "$0")" || exit 1

PY=python3
command -v python3 >/dev/null 2>&1 || PY=python
if ! command -v "$PY" >/dev/null 2>&1; then
    echo
    echo "[!] Python не найден. Установи Python 3:"
    echo "    Mac:   brew install python3"
    echo "    Linux: sudo apt install python3"
    echo
    exit 1
fi

echo
echo "============================================================"
echo "  Сценарий 1: депозит 1000 USDT, до 3 позиций (как в приложении)"
echo "============================================================"
"$PY" chameleon_backtest.py --equity 1000 --max-positions 3 --pos-pct 20

echo
echo "============================================================"
echo "  Сценарий 2: ТВОЙ случай — 30 USDT, 1 позиция, 90% на сделку"
echo "============================================================"
"$PY" chameleon_backtest.py --equity 30 --max-positions 1 --pos-pct 90

echo
echo "============================================================"
echo "  Сценарий 3: ПЕРЕБОР — какая настройка (если есть) прибыльна"
echo "============================================================"
"$PY" chameleon_backtest.py --sweep

echo
echo "Готово. Скопируй таблицу перебора и пришли в чат — разберём."
echo
