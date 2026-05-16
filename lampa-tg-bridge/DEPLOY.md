# Деплой FilmKent Telegram Bridge

## Требования
- Node.js 18+
- Cloudflare аккаунт
- Telegram Bot Token (от @BotFather)
- TMDB API Key (themoviedb.org → Settings → API)

---

## Шаг 1 — Логин в Cloudflare

```bash
cd lampa-tg-bridge/worker
npx wrangler login
```
Откроется браузер → войди в аккаунт → подтверди доступ.

---

## Шаг 2 — Создать KV namespace

```bash
npx wrangler kv:namespace create "TG_BRIDGE_KV"
```

Скопируй `id` из вывода и вставь в `wrangler.toml`:
```toml
[[kv_namespaces]]
binding = "KV"
id = "ПОЛУЧЕННЫЙ_ID"
```

Для preview_id повтори:
```bash
npx wrangler kv:namespace create "TG_BRIDGE_KV" --preview
```

---

## Шаг 3 — Установить зависимости

```bash
npm install
```

---

## Шаг 4 — Добавить секреты

```bash
npx wrangler secret put BOT_TOKEN
# Вставь токен от @BotFather (формат: 123456:ABC-DEF...)

npx wrangler secret put TMDB_KEY
# Вставь TMDB API Key (v3 auth)
```

---

## Шаг 5 — Задеплоить Worker

```bash
npx wrangler deploy
```

Запомни URL вида: `https://filmkenttg.workers.dev`

---

## Шаг 6 — Установить Telegram Webhook

Замени `<BOT_TOKEN>` на свой токен:
```bash
curl "https://api.telegram.org/bot<BOT_TOKEN>/setWebhook?url=https://filmkenttg.workers.dev"
```

Должен прийти ответ: `{"ok":true,...}`

---

## Шаг 7 — Проверить работу

```bash
curl "https://filmkenttg.workers.dev/api/health"
# Должно вернуть: {"ok":true,...}
```

---

## Шаг 8 — Добавить плагин в Lampa

1. Открой Lampa → Настройки → Плагины
2. Добавь URL плагина или загрузи файл `lampa-plugin/telegram-bridge.js`
3. Перезапусти Lampa

---

## Использование

1. Открой своего бота в Telegram (имя бота из @BotFather)
2. Напиши `/link` — получишь 6-значный код
3. В Lampa: меню «От друзей» → введи код → подтверди
4. Добавь бота в чат с друзьями
5. Друзья пишут `/rec Дюна 2 — обязательно посмотри!`
6. Ты видишь карточку в Lampa

---

## Если что-то пошло не так

| Проблема | Решение |
|---|---|
| Webhook не устанавливается | Проверь BOT_TOKEN |
| `/rec` не находит фильм | Проверь TMDB_KEY: `curl "https://api.themoviedb.org/3/movie/popular?api_key=ВАШ_КЛЮЧ"` |
| Lampa не получает фид | Проверь токен в хранилище Lampa (DevTools → Storage) |
| KV ошибки | Убедись что id в wrangler.toml совпадает с созданным namespace |
