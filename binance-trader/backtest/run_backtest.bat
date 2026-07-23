@echo off
REM ── Бэктест «Хамелеон» в один клик (Windows) ──
REM Дважды кликни по этому файлу. Нужен установленный Python 3
REM (python.org, при установке отметь галочку "Add to PATH").

setlocal
cd /d "%~dp0"

set PY=python
where python >nul 2>nul || set PY=py
%PY% --version >nul 2>nul
if errorlevel 1 (
    echo.
    echo [!] Python не найден. Установи с https://python.org
    echo     (при установке отметь "Add Python to PATH"^) и запусти снова.
    echo.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo   Сценарий 1: депозит 1000 USDT, до 3 позиций (как в приложении)
echo ============================================================
%PY% chameleon_backtest.py --equity 1000 --max-positions 3 --pos-pct 20

echo.
echo ============================================================
echo   Сценарий 2: ТВОЙ случай — 30 USDT, 1 позиция, 90%% на сделку
echo ============================================================
%PY% chameleon_backtest.py --equity 30 --max-positions 1 --pos-pct 90

echo.
echo ============================================================
echo   Сценарий 3: ПЕРЕБОР — какая настройка (если есть^) прибыльна
echo ============================================================
%PY% chameleon_backtest.py --sweep

echo.
echo Готово. Скопируй таблицу перебора и пришли в чат — разберём.
echo.
pause
