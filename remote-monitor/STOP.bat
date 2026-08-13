@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Screen Monitor - ostanovka

rem Ostanavlivaem tunnel
taskkill /f /im cloudflared.exe >nul 2>nul

rem Ostanavlivaem server (tolko process s server.py, drugie Python ne trogaem)
powershell -Command "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'server\.py' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" >nul 2>nul

echo Monitoring ostanovlen.
timeout /t 2 >nul
exit /b 0
