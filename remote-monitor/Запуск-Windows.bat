@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Прозрачный мониторинг экрана

echo ============================================================
echo   ПРОЗРАЧНЫЙ МОНИТОРИНГ ЭКРАНА — установка и запуск
echo ============================================================
echo.

rem --- Проверяем, установлен ли Python ---
where python >nul 2>nul
if errorlevel 1 (
  echo [ОШИБКА] Python не найден.
  echo.
  echo   1^) Откройте https://www.python.org/downloads/
  echo   2^) Скачайте и установите Python.
  echo   3^) ВАЖНО: на первом экране установщика поставьте галочку
  echo      "Add python.exe to PATH", затем "Install Now".
  echo   4^) После установки запустите этот файл заново.
  echo.
  pause
  exit /b 1
)

rem --- Создаём виртуальное окружение (один раз) ---
if not exist venv (
  echo Создаю виртуальное окружение...
  python -m venv venv
)

rem --- Ставим зависимости ---
echo Устанавливаю компоненты (первый раз занимает минуту)...
call venv\Scripts\activate.bat
python -m pip install --upgrade pip >nul 2>nul
pip install -r requirements.txt
if errorlevel 1 (
  echo.
  echo [ОШИБКА] Не удалось установить компоненты. Проверьте интернет и запустите снова.
  pause
  exit /b 1
)

rem --- Спрашиваем пароль ---
echo.
set /p MONITOR_PASSWORD=Придумайте пароль для доступа к мониторингу:
if "%MONITOR_PASSWORD%"=="" (
  echo Пароль обязателен. Запустите файл снова.
  pause
  exit /b 1
)

echo.
echo ------------------------------------------------------------
echo  Запускаю. Ниже появится адрес вида http://192.168.x.x:8000
echo  Откройте его в браузере ТЕЛЕФОНА (телефон в той же Wi-Fi).
echo  Логин — любой, пароль — тот, что вы задали.
echo  Остановить: закройте это окно.
echo ------------------------------------------------------------
echo.

python server.py
pause
