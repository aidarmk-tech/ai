# Установка автозапуска на Windows.
# Регистрирует задачу в Планировщике заданий, стартующую при входе пользователя.
# Запускать в PowerShell от своего пользователя (не обязательно администратором):
#   powershell -ExecutionPolicy Bypass -File .\install.ps1
param(
    [int]$Port = 8000
)

$ErrorActionPreference = "Stop"

# Каталог remote-monitor (на два уровня выше этого скрипта)
$Dir = (Resolve-Path "$PSScriptRoot\..\..").Path

# pythonw.exe запускает без окна консоли; берём из venv, если есть
$Pythonw = Join-Path $Dir "venv\Scripts\pythonw.exe"
if (-not (Test-Path $Pythonw)) {
    $Pythonw = (Get-Command pythonw.exe).Source
}

$Password = Read-Host "Задайте пароль для доступа к мониторингу" -AsSecureString
$PlainPwd = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password))
if ([string]::IsNullOrEmpty($PlainPwd)) { Write-Error "Пароль обязателен."; exit 1 }

# Пароль передаём как аргумент; задача запускается только под вашей учёткой.
$Args = "`"$Dir\server.py`" --port $Port --password `"$PlainPwd`""

$Action  = New-ScheduledTaskAction -Execute $Pythonw -Argument $Args -WorkingDirectory $Dir
$Trigger = New-ScheduledTaskTrigger -AtLogOn
$Settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -StartWhenAvailable

Register-ScheduledTask -TaskName "RemoteMonitor" -Action $Action -Trigger $Trigger `
    -Settings $Settings -Description "Прозрачный удалённый мониторинг экрана" -Force

Start-ScheduledTask -TaskName "RemoteMonitor"

Write-Host ""
Write-Host "Готово. Задача 'RemoteMonitor' создана и запущена." -ForegroundColor Green
Write-Host "  Остановить:  Stop-ScheduledTask -TaskName RemoteMonitor"
Write-Host "  Удалить:     Unregister-ScheduledTask -TaskName RemoteMonitor -Confirm:`$false"
