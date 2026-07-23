# PumpRadar — сканер аномалий Binance Spot

Локальное Android-приложение, которое анализирует **публичные** рыночные данные
Binance Spot и выдаёт объяснимые сигналы о резких движениях цены и потоке
сделок. Оно **не торгует, не хранит API-ключи и не имеет доступа к деньгам**.

> ⚠️ PumpRadar обнаруживает рыночные аномалии, но не предсказывает прибыль.
> Резкий рост часто заканчивается быстрым падением. Не используйте сигнал как
> единственное основание для сделки.

## Статус: этап 2 (живой сканер)

Приложение строится этапами (ТЗ раздел 40); каждый этап — собирающийся APK.

**Готово (этапы 1–2):**
- Android-проект (Kotlin, Jetpack Compose + Material 3, Hilt, Room, DataStore,
  Navigation), Gradle Version Catalog, подписанный debug APK, unit-тесты.
- Онбординг, 7 экранов, нижняя навигация.
- Foreground-сервис (`specialUse`) с постоянным уведомлением и действиями
  Открыть / Пауза / Остановить; запуск только по действию пользователя.
- **REST Binance**: exchangeInfo → universe пар к USDT (TRADING, спот,
  без стейблов и leveraged-токенов); синхронизация серверного времени.
- **WebSocket `!miniTicker@arr`** с устойчивым переподключением (backoff +
  jitter, переключение портов 443/9443, ping/pong).
- **Market Scanner**: кольцевые буферы секундных цен (10 мин), доходности
  5с/15с/30с/60с/3м/5м, скорость, ускорение, относительная сила к BTC, PreScore.
- **Живой список кандидатов** на экране «Сканер» + счётчики на «Обзоре».

**Следующие этапы:** углублённые потоки кандидатов (`aggTrade`, `bookTicker`),
поток агрессивных покупок/продаж (CVD, taker buy ratio), стакан
(`depth20@100ms`, OBI, проскальзывание), полный Pump Score, конечный автомат
сигналов, Outcome Tracker, уведомления с антиспамом.

## Как получить APK

Собирается автоматически в GitHub Actions при каждом пуше в `pumpradar/`.
Релиз **PumpRadar (последняя сборка)** → `PumpRadar.apk`.

## Сборка вручную

```bash
cd pumpradar
gradle test
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Требуется JDK 17. compileSdk в CI — 35 (ТЗ просит 36; на функциональность MVP
не влияет).

## Публичные источники Binance

- REST: `https://api.binance.com` (fallback `https://data-api.binance.vision`).
- Spot WebSocket: `wss://stream.binance.com:443/stream` (fallback `:9443`).
- Без API-ключа, без торговых endpoint.

## Приватность

Данные сигналов и настроек хранятся только на устройстве. Приложение не
запрашивает доступ к аккаунту Binance, не собирает device ID, не подключает
рекламные SDK и аналитику.
