@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Удалённый мониторинг экрана (через интернет)

echo ============================================================
echo   УДАЛЁННЫЙ МОНИТОРИНГ ЭКРАНА (доступ через интернет)
echo ============================================================
echo.

rem --- 1. Проверяем Python ---
where python >nul 2>nul
if errorlevel 1 (
  echo [ОШИБКА] Python не найден.
  echo   Установите его с https://www.python.org/downloads/
  echo   и ОБЯЗАТЕЛЬНО поставьте галочку "Add python.exe to PATH".
  echo   Потом запустите этот файл снова.
  pause
  exit /b 1
)

rem --- 2. Виртуальное окружение + зависимости ---
if not exist venv (
  echo Создаю виртуальное окружение...
  python -m venv venv
)
echo Устанавливаю компоненты...
call venv\Scripts\activate.bat
python -m pip install --upgrade pip >nul 2>nul
pip install -r requirements.txt
if errorlevel 1 (
  echo [ОШИБКА] Не удалось поставить компоненты. Проверьте интернет и запустите снова.
  pause
  exit /b 1
)

rem --- 3. Скачиваем cloudflared, если его ещё нет ---
if not exist cloudflared.exe (
  echo Скачиваю cloudflared (мостик в интернет)...
  powershell -Command "try { Invoke-WebRequest -Uri 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile 'cloudflared.exe' } catch { exit 1 }"
  if not exist cloudflared.exe (
    echo [ОШИБКА] Не удалось скачать cloudflared. Проверьте интернет и запустите снова.
    pause
    exit /b 1
  )
)

rem --- 4. Пароль ---
echo.
echo Задайте ДЛИННЫЙ пароль (ссылка будет публичной, защищает только пароль!):
set /p MONITOR_PASSWORD=Пароль:
if "%MONITOR_PASSWORD%"=="" (
  echo Пароль обязателен. Запустите файл снова.
  pause
  exit /b 1
)

rem --- 5. Запускаем сервер в отдельном окне (пароль наследуется из окружения) ---
echo.
echo Запускаю мониторинг в отдельном окне...
start "Мониторинг — СЕРВЕР (не закрывать)" cmd /k "call venv\Scripts\activate.bat && python server.py"

rem Ждём, пока сервер поднимется
powershell -Command "Start-Sleep -Seconds 5" >nul

rem --- 6. Поднимаем туннель; здесь появится публичная ссылка ---
echo.
echo ------------------------------------------------------------
echo  Сейчас ниже появится ссылка вида:
echo      https://что-то.trycloudflare.com
echo  Откройте её в браузере ТЕЛЕФОНА (из любой точки, хоть 4G).
echo  Логин — любой, пароль — тот, что вы задали.
echo.
echo  ЧТОБЫ ОСТАНОВИТЬ: закройте это окно И окно "Мониторинг — СЕРВЕР".
echo ------------------------------------------------------------
echo.
cloudflared.exe tunnel --url http://localhost:8000
pause
