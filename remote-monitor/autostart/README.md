# Автозапуск

Скрипты запускают мониторинг автоматически при входе в систему. Пароль
спрашивается при установке и сохраняется в файле службы с правами `600`
(только для вашего пользователя).

> Значок в трее и уведомления остаются видимыми — автозапуск не делает
> инструмент скрытным.

## Linux (systemd, пользовательский сервис)

```bash
cd remote-monitor
bash autostart/linux-systemd/install.sh          # или: ... install.sh 8080  (свой порт)
```

Управление: `systemctl --user status|stop|start remote-monitor`,
логи — `journalctl --user -u remote-monitor -f`.
Удалить: `systemctl --user disable --now remote-monitor && rm ~/.config/systemd/user/remote-monitor.service`.

## macOS (launchd, пользовательский агент)

```bash
cd remote-monitor
bash autostart/macos-launchd/install.sh          # или: ... install.sh 8080
```

После установки выдайте разрешение **«Запись экрана»**:
Системные настройки → Конфиденциальность и безопасность → Запись экрана.
Удалить: `launchctl unload ~/Library/LaunchAgents/com.remote-monitor.plist && rm ~/Library/LaunchAgents/com.remote-monitor.plist`.

## Windows (Планировщик заданий)

```powershell
cd remote-monitor
powershell -ExecutionPolicy Bypass -File .\autostart\windows\install.ps1     # -Port 8080 по желанию
```

Запускается через `pythonw.exe` (без окна консоли) при входе пользователя.
Удалить: `Unregister-ScheduledTask -TaskName RemoteMonitor -Confirm:$false`.
