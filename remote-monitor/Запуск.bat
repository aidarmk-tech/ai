@echo off
rem === Перезапуск самого себя в СВЁРНУТОМ окне ===
if not "%~1"=="MIN" (
  start "Мониторинг экрана" /min cmd /c "%~f0" MIN
  exit /b
)

chcp 65001 >nul
cd /d "%~dp0"
title Мониторинг экрана (свёрнуто — не закрывать)

rem --- 1. Python ---
where python >nul 2>nul
if errorlevel 1 (
  > ОШИБКА.txt echo Python не найден. Установите с https://www.python.org/downloads/
  >> ОШИБКА.txt echo и поставьте галочку "Add python.exe to PATH", затем запустите снова.
  start "" notepad "ОШИБКА.txt"
  exit /b 1
)

rem --- 2. Зависимости (в системный Python) ---
python -m pip install --upgrade pip >nul 2>nul
python -m pip install -r requirements.txt >nul 2>nul

rem --- 3. cloudflared ---
set "CFURL=https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
if not exist cloudflared.exe curl -L -o cloudflared.exe "%CFURL%" 2>nul
if not exist cloudflared.exe powershell -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; try { Invoke-WebRequest -Uri '%CFURL%' -OutFile 'cloudflared.exe' } catch {}"
if not exist cloudflared.exe (
  > ОШИБКА.txt echo Не удалось скачать cloudflared. Скачайте вручную:
  >> ОШИБКА.txt echo %CFURL%
  >> ОШИБКА.txt echo переименуйте в cloudflared.exe, положите в эту папку и запустите снова.
  start "" notepad "ОШИБКА.txt"
  exit /b 1
)

rem --- 4. Сервер в фоне (без пароля), лог в файл ---
del server-log.txt >nul 2>nul
start "" /b cmd /c "python server.py --no-auth > server-log.txt 2>&1"
powershell -Command "Start-Sleep -Seconds 5" >nul
powershell -Command "try { (New-Object Net.Sockets.TcpClient).Connect('127.0.0.1',8000); exit 0 } catch { exit 1 }"
if errorlevel 1 (
  start "" notepad "server-log.txt"
  exit /b 1
)

rem --- 5. Туннель в фоне, лог в файл ---
del cloudflared-log.txt >nul 2>nul
start "" /b cmd /c ".\cloudflared.exe tunnel --url http://127.0.0.1:8000 > cloudflared-log.txt 2>&1"

rem --- 6. Достаём ссылку из лога и сохраняем в ССЫЛКА.txt, открываем её ---
powershell -Command "$u=$null; for($i=0;$i -lt 40;$i++){ if(Test-Path 'cloudflared-log.txt'){ $m=Select-String -Path 'cloudflared-log.txt' -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' -ErrorAction SilentlyContinue | Select-Object -First 1; if($m){ $u=$m.Matches[0].Value; break } }; Start-Sleep -Seconds 1 }; if($u){ Set-Content -Path 'ССЫЛКА.txt' -Value ('Откройте на телефоне:' + [Environment]::NewLine + $u) -Encoding UTF8 } else { Set-Content -Path 'ССЫЛКА.txt' -Value 'Ссылка пока не готова. Откройте окно из панели задач и посмотрите cloudflared-log.txt' -Encoding UTF8 }"
start "" notepad "ССЫЛКА.txt"

rem --- 7. Держим окно живым (закрытие окна = остановка всего) ---
:keepalive
powershell -Command "Start-Sleep -Seconds 3600" >nul
goto keepalive
