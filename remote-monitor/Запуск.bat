@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Мониторинг экрана — всё в одном окне

echo ============================================================
echo   МОНИТОРИНГ ЭКРАНА — одно окно, без пароля
echo ============================================================
echo   ВНИМАНИЕ: доступ будет БЕЗ ПАРОЛЯ. Ссылку trycloudflare
echo   никому не показывайте — по ней виден ваш экран.
echo ============================================================
echo.

rem --- 1. Python ---
where python >nul 2>nul
if errorlevel 1 (
  echo [ОШИБКА] Python не найден. Установите с https://www.python.org/downloads/
  echo   и поставьте галочку "Add python.exe to PATH". Потом запустите снова.
  pause
  exit /b 1
)

rem --- 2. Зависимости (в системный Python, без venv) ---
echo Проверяю и устанавливаю компоненты...
python -m pip install --upgrade pip >nul 2>nul
python -m pip install -r requirements.txt
if errorlevel 1 (
  echo [ОШИБКА] Не удалось поставить компоненты. Проверьте интернет и запустите снова.
  pause
  exit /b 1
)

rem --- 3. cloudflared ---
set "CFURL=https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
if not exist cloudflared.exe (
  echo Скачиваю cloudflared...
  curl -L -o cloudflared.exe "%CFURL%" 2>nul
)
if not exist cloudflared.exe (
  powershell -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; try { Invoke-WebRequest -Uri '%CFURL%' -OutFile 'cloudflared.exe' } catch {}"
)
if not exist cloudflared.exe (
  echo [ОШИБКА] Не удалось скачать cloudflared. Скачайте вручную:
  echo   %CFURL%
  echo переименуйте в cloudflared.exe, положите сюда и запустите снова.
  pause
  exit /b 1
)

rem --- 4. Запускаем сервер в фоне (лог пишем в файл для диагностики) ---
echo Запускаю мониторинг в фоне...
start "" /b cmd /c "python server.py --no-auth > server-log.txt 2>&1"

rem Ждём, пока сервер поднимется
powershell -Command "Start-Sleep -Seconds 5" >nul

rem Проверяем, слушает ли сервер порт 8000
powershell -Command "try { (New-Object Net.Sockets.TcpClient).Connect('127.0.0.1',8000); exit 0 } catch { exit 1 }"
if errorlevel 1 (
  echo.
  echo [ОШИБКА] Сервер не запустился. Причина ниже:
  echo ------------------------------------------------------------
  type server-log.txt
  echo ------------------------------------------------------------
  pause
  exit /b 1
)

rem --- 5. Туннель в этом же окне; ниже появится ссылка ---
echo.
echo ------------------------------------------------------------
echo  Ниже появится ссылка вида https://...trycloudflare.com
echo  Откройте её на телефоне — сразу увидите экран, без пароля.
echo  ЧТОБЫ ОСТАНОВИТЬ ВСЁ: просто закройте это окно.
echo ------------------------------------------------------------
echo.
.\cloudflared.exe tunnel --url http://127.0.0.1:8000
pause
