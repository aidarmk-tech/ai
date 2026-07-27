# PumpRadar Server v4.3.7 — установка с телефона

Откройте VNC/веб-консоль сервера VDSina, войдите как `root` и выполните:

```bash
curl -fsSL https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-server-v43/server-bootstrap/install.sh | bash
```

Установщик проверяет SHA-256 пакета, сохраняет существующую SQLite базу и
конфигурацию, создаёт backup перед обновлением и автоматически откатывает код,
если версия `4.3.7-server` не отвечает через локальный API.

После установки:

```bash
/opt/pumpradar/server/scripts/status.sh
```

Сервис использует только публичные данные Binance, не содержит торговых API
ключей и не отправляет реальные ордера.
