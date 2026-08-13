@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Screen Monitor - nastroyka (okno zakroetsya samo)

set "LINKFILE=LINK.txt"
set "ERRFILE=ERROR.txt"
del "%LINKFILE%" "%ERRFILE%" >nul 2>nul

echo Nastroyka monitoringa... okno zakroetsya avtomaticheski.

rem --- 1. Python ---
where python >nul 2>nul
if errorlevel 1 (
  > "%ERRFILE%" echo Python ne nayden. Ustanovite s https://www.python.org/downloads/
  >> "%ERRFILE%" echo i postavte galochku "Add python.exe to PATH", zatem zapustite snova.
  start "" notepad "%ERRFILE%"
  exit /b 1
)

rem --- 2. Zavisimosti ---
python -m pip install --upgrade pip >nul 2>nul
python -m pip install -r requirements.txt >nul 2>nul

rem --- 3. cloudflared ---
set "CFURL=https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
if not exist cloudflared.exe curl -L -o cloudflared.exe "%CFURL%" 2>nul
if not exist cloudflared.exe powershell -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; try { Invoke-WebRequest -Uri '%CFURL%' -OutFile 'cloudflared.exe' } catch {}"
if not exist cloudflared.exe (
  > "%ERRFILE%" echo Ne udalos skachat cloudflared. Skachayte vruchnuyu:
  >> "%ERRFILE%" echo %CFURL%
  start "" notepad "%ERRFILE%"
  exit /b 1
)

rem --- 4. Server v FONE bez okna (pythonw), tiho, bez parolya ---
del server-log.txt >nul 2>nul
where pythonw >nul 2>nul
if errorlevel 1 (
  rem net pythonw - zapuskaem svernuto
  start "" /min cmd /c "python server.py --no-auth --quiet > server-log.txt 2>&1"
) else (
  start "" pythonw server.py --no-auth --quiet
)

rem Zhdem do ~20 sekund, poka server nachnet slushat port 8000
powershell -Command "for($i=0;$i -lt 20;$i++){ try{ (New-Object Net.Sockets.TcpClient).Connect('127.0.0.1',8000); exit 0 }catch{ Start-Sleep -Seconds 1 } }; exit 1"
if errorlevel 1 (
  copy /y server-log.txt "%ERRFILE%" >nul 2>nul
  start "" notepad "%ERRFILE%"
  exit /b 1
)

rem --- 5. Tunnel v FONE bez okna (cherez skrytyy vbs) ---
del cloudflared-log.txt >nul 2>nul
> run-tunnel.vbs echo CreateObject("WScript.Shell").Run "cmd /c .\cloudflared.exe tunnel --url http://127.0.0.1:8000 ^> cloudflared-log.txt 2^>^&1", 0, False
start "" wscript.exe run-tunnel.vbs

rem --- 6. Dostaem ssylku iz loga i sohranyaem v LINK.txt ---
> "%LINKFILE%" echo Ssylka gotovitsya... podozhdite i obnovite etot fayl.
powershell -Command "$u=$null; for($i=0;$i -lt 40;$i++){ if(Test-Path 'cloudflared-log.txt'){ $m=Select-String -Path 'cloudflared-log.txt' -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' -ErrorAction SilentlyContinue | Select-Object -First 1; if($m){ $u=$m.Matches[0].Value; break } }; Start-Sleep -Seconds 1 }; if($u){ 'Otkroyte na telefone:',$u | Set-Content -Path '%LINKFILE%' -Encoding UTF8 } else { 'Ssylka poka ne gotova. Otkroyte cloudflared-log.txt i naydite trycloudflare.com' | Set-Content -Path '%LINKFILE%' -Encoding UTF8 }"
start "" notepad "%LINKFILE%"

rem Vse rabotaet v fone. Eto okno bolshe ne nuzhno - zakryvaem.
exit /b 0
