# PumpRadar Server v4.3 — установка с телефона

## Один обязательный шаг в панели VDSina

Поскольку внешний SSH на сервере пока отвечает `Connection refused`, откройте веб-консоль/VNC сервера в панели VDSina, войдите как `root` и выполните:

```bash
curl -fsSL https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-server-v43/server-bootstrap/install.sh | bash
```

Установщик:

- ставит Python, SQLite, rclone, OpenSSH и UFW;
- включает SSH;
- создаёт swap 1 ГБ;
- проверяет SHA-256 серверного пакета;
- устанавливает PumpRadar в `/opt/pumpradar/server`;
- хранит данные в `/var/lib/pumpradar`;
- запускает сервис и backup timer;
- выполняет локальный health-check;
- не использует Binance API keys и не отправляет ордера.

## После успешного запуска

Проверка:

```bash
/opt/pumpradar/server/scripts/status.sh
```

Сразу сменить временный root-пароль:

```bash
passwd
```

## Google Drive

Подготовленная папка: `PumpRadar Server Data`.

Однократная настройка rclone:

```bash
/opt/pumpradar/server/scripts/configure-drive.sh
```

На headless-сервере OAuth может потребовать дополнительную авторизацию через устройство с браузером. До её завершения почасовые сжатые выгрузки сохраняются локально в `/var/lib/pumpradar/exports`.
