# LampPlayer (Android TV)

Внешний медиаплеер для Android TV, запускаемый из **Lampa** через Android-интент.
Играет видео движком **ExoPlayer (Media3)** по умолчанию и умеет переключаться
на **libVLC** как запасной движок для контейнеров/кодеков, которые ExoPlayer не
тянет. Управление полностью под пульт (D-pad), кинематографичный тёмный интерфейс,
богатые метаданные (постер/описание/год/рейтинг) и возврат позиции в Лампу для
функции «Продолжить».

- **applicationId / package:** `com.lampplayer.tv`
- **Главная активити:** `com.lampplayer.tv.player.PlayerActivity`
- **Лаунчер (IPTV/домашний экран):** `com.lampplayer.tv.iptv.IptvActivity`
- **minSdk 21**, **targetSdk 34**

---

## Движки воспроизведения

В **Настройках → Движок** доступны три режима (хранится в `engine`):

| Режим | Значение | Поведение |
|------|----------|-----------|
| Авто (по умолчанию) | `auto` | ExoPlayer; при фатальной ошибке декодера/контейнера автоматически перезапуск на libVLC |
| Только ExoPlayer | `exoplayer` | всегда Media3/ExoPlayer |
| Только libVLC | `vlc` | всегда libVLC |

**libVLC:** `org.videolan.android:libvlc-all:3.7.2` (последняя стабильная линия 3.x,
есть в Maven Central — отдельный репозиторий не нужен). Класс-обёртка —
[`engine/VlcController.kt`](app/src/main/kotlin/com/lampplayer/tv/engine/VlcController.kt).

> ⚠️ **Версия libVLC:** обёртка использует **3.x** track-API
> (`MediaPlayer.TrackDescription` / `setAudioTrack` / `setSpuTrack`). В libVLC **4.x**
> (на сейчас только EAP-сборки) этот API заменён — не поднимайте версию до 4.x без
> правок в `VlcController`.

### 4K / HDR — честные ограничения
- Аппаратное декодирование (`--avcodec-hw=mediacodec`, `setHWDecoderEnabled`) включено
  для плавного 4K, но **зависит от железа приставки**.
- **HDR tone-mapping** в libVLC на Android ограничен и сильно зависит от SoC/прошивки:
  на части боксов HDR-видео может выглядеть тускло/пересвечено. Для гарантированного
  HDR-passthrough обычно лучше системный/ExoPlayer-путь на сертифицированных
  устройствах (Shield TV и т.п.). Проверяйте на целевой модели.

---

## Сборка

Требуется JDK 17 и Android SDK (compileSdk 34). Из каталога `lampa-player-native/`:

```bash
gradle assembleDebug        # debug APK
# → app/build/outputs/apk/debug/app-debug.apk
```

> В репозитории нет `gradlew`-обёртки; используйте установленный Gradle 8.7
> (как в CI) или Android Studio. CI собирает APK автоматически —
> см. `.github/workflows/build-lampa-player.yml` (артефакт `LampaPlayer-debug-*`).

---

## Установка на приставку (sideload)

```bash
adb connect <IP_приставки>:5555
adb install -r app-debug.apk
```

Либо скопируйте APK на флешку и установите файловым менеджером (включите
«Неизвестные источники»). После установки в списке приложений Android TV появится
**LampPlayer**.

---

## Подключение к Lampa

### Вариант A — плагин-компаньон `lmnp.js` (рекомендуется)
Передаёт в плеер полные метаданные (описание, постер, арт, год, рейтинг, tmdb_id,
сезон/серия), которых ядро Lampa штатно не форвардит.

1. Lampa → **Настройки → Расширения → Добавить плагин** по URL:
   `https://raw.githubusercontent.com/aidarmk-tech/ai/claude/lampplayer-android-tv-BNB6g/lmnp.js`
2. В настройках плагина включите «Сделать плеером по умолчанию» (выставляет
   `player_lauch`) и упаковку метаданных.
3. Метаданные уезжают двумя каналами: extra `lampa_meta` и упаковкой в `title`
   (`lmpmeta://<base64 JSON>`) — основной канал для byLampa, т.к. ядро надёжно
   форвардит только `title`.

### Вариант B — ванильная Lampa без плагина
Запуск идёт обычным `ACTION_VIEW` (наши intent-filter'ы это покрывают). Доступны
название, ссылка, субтитры и позиция, но без постера/описания.

---

## Контракт интента (приём)

**Стандартные / MX-VLC-совместимые extras:**
- `data` (Uri) — поток (`http`/`https`/`file`/`content`/`rtsp`/`udp`);
- `title` (String) — название **или** конверт `lmpmeta://<base64 JSON>`;
- `position` (long/int, **мс**) + `from_start` (bool) — возобновление;
- субтитры MX: `subs` (`Uri[]`), `subs.name` (`String[]`), `subs.enable` (int);
  VLC: `subtitles_location` (String);
- `headers` — `Bundle`, `headers_json`, **или** `String[]` пар ключ/значение
  (User-Agent / Referer пробрасываются в опции медиа).

**Богатые метаданные:** extra `lampa_meta` (base64 JSON) **или** `lampa_data` (JSON),
либо `lmpmeta://`-конверт в `title`. JSON:

```json
{ "title":"", "original_title":"", "overview":"", "year":0, "rating":0.0,
  "poster":"https://…", "backdrop":"https://…", "tmdb_id":0,
  "season":null, "episode":null }
```

Приоритет позиции: `from_start` → `position` → локально сохранённая → `timeline_time`.
Плеер не падает при отсутствии любых полей — всё опционально.

## Контракт результата (возврат для «Продолжить»)

`setResult(RESULT_OK, …)` с extras (продублировано в двух стилях):
- `position`, `duration` (long, мс), `watched_percent`, `completed`;
- `end_by` — `"user"` | `"playback_completion"`;
- VLC-зеркала: `extra_position`, `extra_duration`.

Позиция также сохраняется **локально** (ключ = url) как запасной механизм.

---

## Управление с пульта

- **OK** — пауза/плей; **←/→** — перемотка ±10с (ускоряется при удержании);
- кнопка **скорости** (0.5×…2×) и **соотношения/зума** (FIT/FILL/ZOOM на
  ExoPlayer; FIT/FILL на libVLC) в панели управления;
- **↑** — попап аудио/субтитров; **↓** — инфо-оверлей (серии/EPG/формат);
- **Menu/Info** — настройки/инфо; **Back** — сначала скрыть оверлей, затем выход
  (с возвратом позиции в Лампу).

## Настройки
Движок · профиль буфера · автопереход к следующей серии (+задержка) · запоминание
дорожек · пропуск интро · таймер сна · диагностика.

## Структура
```
engine/        MediaEngine, EngineType, VlcController (libVLC)
player/        PlayerActivity, PlayerViewModel, IntentParser, *Manager
domain/model/  CardMeta, ExternalSubtitle, EpisodeItem, …
data/          datastore (позиции/настройки), db (Room), tmdb (Retrofit)
iptv/ epg/ settings/   лаунчер, EPG, экран настроек
```
