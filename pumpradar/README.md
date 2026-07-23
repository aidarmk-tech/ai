# PumpRadar — сканер аномалий Binance Spot

Локальное Android-приложение, которое анализирует **публичные** рыночные данные
Binance Spot и выдаёт объяснимые сигналы о резких движениях цены и потоке
сделок. Оно **не торгует, не хранит API-ключи и не имеет доступа к деньгам**.

> ⚠️ PumpRadar обнаруживает рыночные аномалии, но не предсказывает прибыль.
> Резкий рост часто заканчивается быстрым падением. Не используйте сигнал как
> единственное основание для сделки.

## Статус: этап 1 (фундамент)

Приложение строится этапами (ТЗ раздел 40); каждый этап — собирающийся APK.

**Готово в этапе 1:**
- Android-проект (Kotlin, Jetpack Compose + Material 3, Hilt, Room, DataStore,
  Navigation, WorkManager), Gradle Version Catalog, подписанный debug APK.
- Онбординг с предупреждением о риске и запросом разрешения на уведомления.
- Экраны: Обзор, Сканер, История, Статистика, Настройки, Диагностика, Деталь монеты.
- Foreground-сервис (`specialUse`) с постоянным уведомлением и действиями
  Открыть / Пауза / Остановить; запуск только по действию пользователя.
- Локальная БД (signals / outcomes / app_events), типизированные настройки.
- Ядро математики (robust Z-score, MAD, linearScore, safeDivide и др.) + unit-тесты.

**Следующие этапы:** живой WebSocket Binance (`!miniTicker@arr`,
`aggTrade`, `bookTicker`, `depth20@100ms`), Market Scanner, Candidate Analyzer,
Order Book Analyzer, Pump Score, конечный автомат сигналов, Outcome Tracker,
уведомления с антиспамом, полноценные экраны с живыми данными.

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
