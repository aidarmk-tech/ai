/**
 * lmnp — Lampa Native Player companion plugin
 * Перехватывает воспроизведение в Lampa и запускает нативный APK
 * с полными данными через intent:// scheme.
 *
 * Установка: Lampa → Настройки → Расширения → добавить URL:
 * https://raw.githubusercontent.com/aidarmk-tech/ai/main/lmnp.js
 */
(function () {
    'use strict';

    var PACKAGE = 'com.lampplayer.tv';   // дефолт; можно переопределить в настройках
    var PLUGIN_NAME = 'lmnp';

    // Целевой package (настраиваемый — спека Part 2).
    function pkg() {
        try {
            var v = Lampa.Storage.get(PLUGIN_NAME + '_package', PACKAGE);
            return (typeof v === 'string' && v.trim()) ? v.trim() : PACKAGE;
        } catch (_) { return PACKAGE; }
    }

    // Включён ли запуск через нативный плеер (тумблер в настройках).
    function enabled() {
        try { return Lampa.Storage.get(PLUGIN_NAME + '_on', true) !== false; }
        catch (_) { return true; }
    }

    // Есть ли нативный Android-мост Lampa (TV/мобильное приложение-обёртка).
    function hasAndroidBridge() {
        try {
            return !!(window.Android && (typeof Android.openUrl === 'function' || typeof Android.open === 'function'))
                || !!(window.LampaAndroid && typeof LampaAndroid.openUrl === 'function');
        } catch (_) { return false; }
    }

    // НАСТОЯЩИЙ браузер (а не WebView Lampa-приложения). Только здесь можно
    // запускать APK через intent:// навигацией — WebView приложения этого не умеет
    // (ERR_UNKNOWN_URL_SCHEME) и запускает внешний плеер сам. Признаки приложения:
    // наличие Android-моста или токен "wv"/Lampa в User-Agent.
    function isPlainBrowser() {
        try {
            if (window.Android || window.LampaAndroid) return false;
            var ua = (navigator.userAgent || '');
            if (/\bwv\b/i.test(ua)) return false;          // Android System WebView (приложение)
            if (/lampa/i.test(ua)) return false;           // кастомный UA обёртки Lampa
            return /Mozilla|Chrome|Safari|Firefox/i.test(ua);
        } catch (_) { return false; }
    }

    // Выбран ли ВНЕШНИЙ плеер в Lampa (player_lauch != внутренний).
    // Если да — Lampa сама запустит APK штатным intent, и метаданные надо
    // упаковывать в title (этот канал реально доезжает). Если нет — наш APK
    // можно открыть только своим intent (см. hookPlayer / start).
    function externalPlayerSelected() {
        try {
            var pl = (Lampa.Storage.field ? Lampa.Storage.field('player_lauch') : null)
                  || Lampa.Storage.get('player_lauch', '');
            return !!pl && pl !== 'inner';
        } catch (_) { return false; }
    }

    // base64(UTF-8) — безопасно для кириллицы.
    function b64utf8(str) { return btoa(unescape(encodeURIComponent(str))); }

    // Каталог lmtv может попросить открыть канал сразу в архиве конкретной
    // передачи — пробрасываем её границы в конверт (as/ae сек, at название).
    function addArchive(meta, video) {
        try {
            if (video && video.archive_start > 0) {
                meta.as = Math.floor(video.archive_start);
                meta.ae = Math.floor(video.archive_end || (video.archive_start + 3600));
                if (video.archive_title) meta.at = ('' + video.archive_title).slice(0, 80);
            }
        } catch (e) {}
    }

    // ── Формирование Chrome-совместимого intent URI ───────────────────────
    // intent://HOST/PATH#Intent;scheme=SCHEME;package=...;S.lampa_data=JSON;end
    // (raw-формат intent:https://... вызывает ERR_UNKNOWN_URL_SCHEME в браузере)
    function buildIntentUri(videoUrl, json) {
        var m = videoUrl.match(/^(https?|rtsp|udp):\/\//);
        if (!m) return null;
        var scheme = m[1];
        var rest   = videoUrl.slice(m[0].length).replace(/#/g, '%23');
        return 'intent://' + rest
            + '#Intent'
            + ';scheme=' + scheme
            + ';action=android.intent.action.VIEW'
            + ';type=video/*'
            + ';package=' + pkg()
            + ';S.lampa_data=' + encodeURIComponent(json)
            + ';end;';
    }

    // Для запуска ИЗ БРАУЗЕРА (Chrome) нужен intent на activity с категорией
    // BROWSABLE. У фильтра https+video/* её нет (Chrome не находит плеер и зовёт
    // Google Play), а у схемы lmnp:// — есть. Поэтому в браузере идём через lmnp://.
    function buildLmnpIntentUri(videoUrl, json) {
        var b64 = b64utf8(json);
        return 'intent://play?url=' + encodeURIComponent(videoUrl) + '&d=' + encodeURIComponent(b64)
            + '#Intent;scheme=lmnp;package=' + pkg() + ';end;';
    }

    // ── Запуск APK ────────────────────────────────────────────────────────
    /**
     * Launch through Lampa's own Android player bridge whenever a real timeline is
     * available. Unlike a plain intent:// navigation, Android.openPlayer starts the
     * activity for result and Lampa.Android.timeCall writes the returned position
     * through timeline.handler into file_view / account sync.
     */
    function launchWithTimelineResult(videoUrl, cardData, sourceData) {
        try {
            if (!window.Lampa || !Lampa.Android || typeof Lampa.Android.openPlayer !== 'function') return false;
            if (typeof window.AndroidJS === 'undefined') return false;

            var timeline = sourceData && sourceData.timeline;
            // Most balancers supply the canonical timeline object. A few only send
            // season/episode metadata, so reconstruct Lampa's standard hash there.
            if ((!timeline || !timeline.hash || typeof timeline.handler !== 'function') &&
                Lampa.Timeline && Lampa.Utils && typeof Lampa.Timeline.view === 'function' &&
                typeof Lampa.Utils.hash === 'function') {
                var original = cardData && (cardData.original_title || cardData.title);
                if (original) {
                    var season = parseInt(cardData.season_number || 0, 10);
                    var episode = parseInt(cardData.episode_number || 0, 10);
                    var hash = season && episode
                        ? Lampa.Utils.hash([season, season > 10 ? ':' : '', episode, original].join(''))
                        : Lampa.Utils.hash(original);
                    timeline = Lampa.Timeline.view(hash);
                }
            }
            if (!timeline || !timeline.hash || typeof timeline.handler !== 'function') return false;

            // The wrapper reliably forwards title and timeline. Put the complete
            // metadata envelope into title so Native Player still receives artwork,
            // subtitles and the episode window while the result callback stays live.
            var packed = JSON.parse(JSON.stringify(cardData || {}));
            packed.timeline_time = timeline.time || packed.timeline_time || 0;
            packed.timeline_duration = timeline.duration || packed.timeline_duration || 0;
            cleanMeta(packed);

            Lampa.Android.openPlayer(videoUrl, {
                title: 'lmpmeta://' + b64utf8(JSON.stringify(packed)),
                timeline: timeline,
                position: Math.max(0, Math.round((timeline.time || 0) * 1000)),
                from_start: false
            });
            return true;
        } catch (e) {
            try { if (window.console) console.warn('[lmnp] timeline result bridge failed', e); } catch (_) {}
            return false;
        }
    }

    function launch(videoUrl, cardData, sourceData, resultBridge) {
        if (resultBridge && launchWithTimelineResult(videoUrl, cardData, sourceData)) return true;

        var json      = JSON.stringify(cardData);
        var intentUri = buildIntentUri(videoUrl, json);
        if (!intentUri) return false;

        // 1. Синхронный Android bridge (Lampa-wrapper / TV-приложение)
        try {
            if (window.Android && typeof Android.openUrl === 'function') {
                Android.openUrl(intentUri); return true;
            }
        } catch (e) {}
        try {
            if (window.Android && typeof Android.open === 'function') {
                Android.open(intentUri); return true;
            }
        } catch (e) {}
        try {
            if (window.LampaAndroid && typeof LampaAndroid.openUrl === 'function') {
                LampaAndroid.openUrl(intentUri); return true;
            }
        } catch (e) {}

        // 2. Браузер (Chrome на Android): запускаем через lmnp:// — его activity
        //    объявлена BROWSABLE, поэтому Chrome открывает именно наш плеер.
        try {
            window.location.href = buildLmnpIntentUri(videoUrl, json); return true;
        } catch (e) {}

        // 3. Запасные схемы навигации
        try { window.location.href = intentUri; return true; } catch (e) {}
        try {
            var b64 = btoa(unescape(encodeURIComponent(json)));
            window.location.href = 'lmnp://play?url=' + encodeURIComponent(videoUrl) + '&d=' + encodeURIComponent(b64);
            return true;
        } catch (e) {}
        return false;
    }

    // ── Хранилище TMDB-карточки ───────────────────────────────────────────
    var _savedCard = null;
    var _savedFile = null;

    // Признак настоящей TMDB-карточки (не карточки балансера/переводчика)
    function isTmdbCard(c) {
        if (!c || typeof c !== 'object') return false;
        return !!(c.poster_path || c.backdrop_path || c.overview ||
                  c.vote_average || c.release_date || c.first_air_date ||
                  c.original_title || c.original_name);
    }

    // Оценка качества карточки: больше TMDB-полей — выше балл,
    // карточки-переводчики от балансера получают штраф.
    function cardScore(c) {
        if (!c || typeof c !== 'object') return -1;
        if (!(c.id || c.tmdb_id) || !(c.title || c.name)) return -1;
        var s = 0;
        if (c.poster_path)                        s += 4;
        if (c.backdrop_path)                      s += 4;
        if (c.overview)                           s += 4;
        if (c.vote_average)                       s += 2;
        if (c.release_date || c.first_air_date)   s += 2;
        if (c.original_title || c.original_name)  s += 2;
        // Штраф за признаки карточки-переводчика от балансера
        if (c.voice_name)   s -= 20;
        if (c.translate)    s -= 20;
        if (c.translator)   s -= 10;
        if (c.voice_id)     s -= 10;
        return s;
    }

    // Сохраняем карточку с наибольшим количеством TMDB-полей
    function saveTmdbCard(obj) {
        if (!obj || typeof obj !== 'object') return;
        var all = [obj.movie, obj.show, obj.card, obj.item, obj];
        if (obj.data)   [obj.data, obj.data.movie, obj.data.card, obj.data.show, obj.data.item].forEach(function(x){ all.push(x); });
        if (obj.params) [obj.params, obj.params.movie, obj.params.card].forEach(function(x){ all.push(x); });
        // online_mod.js и аналоги балансеров хранят карточку в object.movie
        // activity.params.object.movie или activity.object.movie
        if (obj.object) [obj.object, obj.object.movie, obj.object.card].forEach(function(x){ all.push(x); });
        if (obj.params && obj.params.object) [obj.params.object, obj.params.object.movie, obj.params.object.card].forEach(function(x){ all.push(x); });
        all.forEach(function(c) {
            if (!c || typeof c !== 'object') return;
            var s = cardScore(c);
            if (s < 0) return;
            var cid = c.id || c.tmdb_id;
            var sid = _savedCard && (_savedCard.id || _savedCard.tmdb_id);
            // Заменяем если: (а) выше балл для того же фильма, ИЛИ (б) пришёл
            // ДРУГОЙ настоящий TMDB-фильм (другой id + сильные TMDB-поля) —
            // иначе карточка прошлого фильма залипает между запусками.
            var newFilm = isTmdbCard(c) && cid && sid && cid !== sid;
            if (!_savedCard || s > cardScore(_savedCard) || newFilm) _savedCard = c;
        });
    }

    function hookActivity() {
        // Переопределяем Lampa.Activity.push — самый надёжный способ поймать карточку
        try {
            if (typeof Lampa.Activity.push === 'function') {
                var _origPush = Lampa.Activity.push;
                Lampa.Activity.push = function (data) {
                    try { saveTmdbCard(data); } catch (_) {}
                    return _origPush.apply(this, arguments);
                };
            }
        } catch (_) {}

        // Переопределяем Lampa.Activity.replace — z01 и ряд балансеров используют
        // replace() вместо push() при переключении балансера / сезона / серии
        try {
            if (typeof Lampa.Activity.replace === 'function') {
                var _origReplace = Lampa.Activity.replace;
                Lampa.Activity.replace = function (data) {
                    try { saveTmdbCard(data); } catch (_) {}
                    return _origReplace.apply(this, arguments);
                };
            }
        } catch (_) {}

        // Listener на push/replace (некоторые версии Lampa)
        try { Lampa.Activity.listener.follow('push',    function(e) { saveTmdbCard(e); }); } catch (_) {}
        try { Lampa.Activity.listener.follow('replace', function(e) { saveTmdbCard(e); }); } catch (_) {}

        // Глобальные события
        // full: e.object.card = data.movie устанавливается в full.js компоненте Lampa
        try {
            Lampa.Listener.follow('full', function (e) {
                if (!e) return;
                [e, e.data, e.movie, e.card, e.item,
                 e.object, e.object && e.object.card, e.object && e.object.movie,
                 e.data && e.data.movie].forEach(function(t) {
                    try { saveTmdbCard(t); } catch (_) {}
                });
            });
        } catch (_) {}
        try {
            Lampa.Listener.follow('activity', function (e) {
                if (!e) return;
                [e, e.data, e.object, e.movie, e.card, e.item, e.params,
                 e.object && e.object.card, e.object && e.object.movie].forEach(function(t) {
                    try { saveTmdbCard(t); } catch (_) {}
                });
            });
        } catch (_) {}
        // Дополнительные события: card, movie, show
        ['card','movie','show','full_open'].forEach(function(ev) {
            try {
                Lampa.Listener.follow(ev, function(e) {
                    if (!e) return;
                    [e, e.card, e.movie, e.data, e.item,
                     e.object, e.object && e.object.card].forEach(function(t) {
                        try { saveTmdbCard(t); } catch(_) {}
                    });
                });
            } catch(_) {}
        });

        // ── Событие нажатия «Смотреть» / открытия источника ─────────────────
        // В Lampa/BylAmpa эти события стреляют ДО того как балансер получает
        // управление — именно здесь лежит чистая TMDB-карточка.
        // Разные форки используют разные имена: 'source', 'online', 'sources',
        // 'stream', 'open', 'watch' — слушаем все.
        ['source','online','sources','stream','open','watch','start_watch'].forEach(function(ev) {
            try {
                Lampa.Listener.follow(ev, function(e) {
                    if (!e) return;
                    var t = [e, e.card, e.movie, e.item, e.film, e.data,
                             e.object, e.object && e.object.movie, e.object && e.object.card,
                             e.params && e.params.card, e.params && e.params.movie];
                    t.forEach(function(x){ try { saveTmdbCard(x); } catch(_) {} });
                });
            } catch(_) {}
        });
    }

    // ── Поиск TMDB-карточки ───────────────────────────────────────────────
    function findCard(data, file) {
        // 1. Предсохранённая TMDB-карточка (из hookActivity или сканирования на старте)
        if (_savedCard && isTmdbCard(_savedCard)) return _savedCard;

        var candidates = [];

        // 2. Полный стек активностей (в т.ч. activity.params.movie у балансера)
        try {
            var all = Lampa.Activity.all ? Lampa.Activity.all() : null;
            if (!all || !all.length) { try { all = Lampa.Activity.history || null; } catch(_) {} }
            if (all && all.length) {
                all.slice().reverse().forEach(function(act) {
                    if (!act) return;
                    [act.card, act.movie, act.item,
                     act.object,                                                       // act.object может быть самой карточкой
                     act.object && act.object.movie, act.object && act.object.card,
                     act.data && act.data.movie, act.data && act.data.card,
                     act.params && act.params.movie, act.params && act.params.card,
                     act.params && act.params.object,
                     act.params && act.params.object && act.params.object.movie].forEach(function(x){ candidates.push(x); });
                });
            }
        } catch (_) {}
        try {
            var act = Lampa.Activity.active();
            if (act) {
                [act.card, act.movie, act.item,
                 act.object,                                                           // act.object может быть самой карточкой
                 act.object && act.object.movie, act.object && act.obje…9297 tokens truncated… = data;

            if (doLaunch(videoUrl, data, file)) {
                _lastLaunch = Date.now();
                stopWebViewPlayer();
            }
        });

        // ── Хук 2: playlist change — обновляем APK со списком серий ──────
        // Балансеры (online_mod и др.) передают URL серий как ленивые функции:
        //   cell.url = function(callback) { getStream(elem, function(e){ cell.url = realUrl; callback(); }) }
        // Нужно вызвать каждую из них, дождаться ответа и только тогда слать intent.
        try {
            Lampa.PlayerPlaylist.listener.follow('change', function () {
                if (!enabled() || !_pendingUrl) return;
                var pl = getPlaylist();
                if (!pl.list || pl.list.length < 2) return;

                var snapUrl  = _pendingUrl;
                var snapData = _pendingData;
                var snapFile = _savedFile;

                function sendUpdate(list) {
                    if (snapUrl !== _pendingUrl) return;   // уже запустили другое видео
                    var rawCard  = findCard(snapData || {}, snapFile || {});
                    var cardData = buildCard(rawCard, snapFile || {}, snapData || {}, list, pl.pos);
                    launch(snapUrl, cardData);
                }

                var items    = pl.list.slice();
                var lazyIdx  = [];
                items.forEach(function(x, i){ if (typeof x.url === 'function') lazyIdx.push(i); });

                if (!lazyIdx.length) {
                    // Все URL уже строки
                    sendUpdate(items);
                    return;
                }

                // Разрешаем ленивые URL параллельно; таймаут 12 с на весь процесс
                var pending   = lazyIdx.length;
                var finished  = false;
                var timer     = setTimeout(function() {
                    if (!finished) { finished = true; sendUpdate(items); }
                }, 12000);

                function oneDone() {
                    if (finished) return;
                    if (--pending <= 0) {
                        finished = true;
                        clearTimeout(timer);
                        sendUpdate(items);
                    }
                }

                lazyIdx.forEach(function(i) {
                    try {
                        items[i].url(oneDone);
                    } catch(_) { oneDone(); }
                });
            });
        } catch (_) {}
    }

    // ── Хук на Lampa.Player.play — упаковка метаданных в title ────────────
    // Подтверждено диагностикой: при выбранном внешнем плеере Lampa запускает
    // APK своим штатным intent, надёжно форвардит только `title`, и кладёт туда
    // имя озвучки, а не фильма. Поэтому подменяем video.title на конверт
    // lmpmeta://<base64(JSON)> с настоящими метаданными — нативный плеер его
    // распакует (поддержка уже есть в IntentParser).
    function hookPlayerPlay() {
        if (!window.Lampa || !Lampa.Player || typeof Lampa.Player.play !== 'function') return;
        if (Lampa.Player.__lmnp_play) return;
        Lampa.Player.__lmnp_play = true;
        var _origPlay = Lampa.Player.play;
        Lampa.Player.play = function (video) {
            var self = this, args = arguments;
            // Каждый вызов play() поднимает поколение: отложенный (resolveWindow)
            // запуск прошлого видео не должен стрелять, если начали новое.
            var myGen = (++_playGen);
            try {
                var alreadyPacked = video && typeof video.title === 'string' &&
                                    /^lmpmeta:\/\//.test(video.title);

                // ТОЛЬКО настоящий браузер (Chrome на Android): Lampa внешний плеер
                // запустить не может — открываем APK своим intent://. В приложении
                // (WebView) эта ветка не срабатывает — там запуск штатный, через Lampa.
                if (enabled() && isPlainBrowser() && !alreadyPacked &&
                    video && typeof video === 'object' &&
                    typeof video.url === 'string' && /^https?:|^rtsp:|^udp:/.test(video.url) &&
                    Date.now() - _lastLaunch > 6000) {
                    if (doLaunch(video.url, video, video)) {
                        _lastLaunch = Date.now();
                        try { stopWebViewPlayer(); } catch (_) {}
                        return;   // встроенный плеер не запускаем
                    }
                }

                if (enabled() && externalPlayerSelected() &&
                    video && typeof video === 'object' && video.url && !alreadyPacked) {

                    var origTitle = (typeof video.title === 'string') ? video.title : '';
                    var g = getPlaylist();
                    var list = null, pos = 0;
                    if (g.list && g.list.length > 1)                      { list = g.list; pos = g.pos || 0; }
                    else if (video.playlist && video.playlist.length > 1) {
                        list = video.playlist;
                        // Позиция запущенного канала — по URL, а не всегда 0: иначе
                        // плеер получает имя/подсветку первого канала категории.
                        pos = 0;
                        for (var vp = 0; vp < list.length; vp++) {
                            var vu = list[vp] && (list[vp].url || list[vp].file);
                            if (vu && vu === video.url) { pos = vp; break; }
                        }
                    }
                    var iptvish = isIptvList(list) || !!video.iptv ||
                                  !!(list && list[pos] && (list[pos].tv || list[pos].iptv));

                    // Открытая карточка решает: запуск из TMDB-карточки — это кино,
                    // что бы ни лежало в плейлисте (балансеры вроде E Online оформляют
                    // потоки как ТВ, а в Lampa.PlayerPlaylist может остаться список
                    // каналов от прошлого IPTV-сеанса). Смотрим ТОЛЬКО активную
                    // активность — стек может хранить карточку старого фильма.
                    var isFilmCard = !!activeFilmCard();
                    var iptv = iptvish && !isFilmCard;
                    // Фильм + чужой список каналов → список не его, выбрасываем.
                    if (!iptv && list && isIptvList(list)) { list = null; pos = 0; }

                    // Для IPTV берём имя канала (а НЕ старую TMDB-карточку сериала).
                    var cardData = iptv ? null : buildCard(findCard(video, video), video, video, null, 0);
                    var hasMeta  = iptv || (cardData && (cardData.tmdb_id || cardData.overview || cardData.poster_url));

                    if (hasMeta) {
                        var finishPack = function () {
                            try {
                                var meta;
                                if (iptv) {
                                    var ch0 = (list && list[pos]) || {};
                                    meta = { title: (ch0.title || ch0.name || origTitle || 'IPTV'), iptv: true };
                                    addArchive(meta, video);
                                } else {
                                    meta = compactMeta(cardData);
                                    var cpl = buildCompactPlaylist(list, pos);   // title fallback (окно)
                                    if (cpl) meta.pl = cpl;
                                }
                                cleanMeta(meta);
                                if (meta.title || meta.tmdb_id || meta.iptv)
                                    video.title = 'lmpmeta://' + b64utf8(JSON.stringify(meta));

                                // Полный плейлист — через заголовок (без лимита title).
                                var hdr = {};
                                var full = buildFullPlaylist(list, pos);
                                if (full) hdr['X-Lmnp-Pl'] = b64utf8(JSON.stringify(full));
                                // EPG: сначала готовая программа из отрисованного Lampa
                                // (текущий канал), плюс m3u-источник как запасной (XMLTV).
                                if (iptv) {
                                    var epgNow = scrapeEpg(meta.title);
                                    if (epgNow) hdr['X-Lmnp-Epg'] = b64utf8(epgNow);
                                    // карта программ всех каналов — для переключения внутри плеера
                                    var epgMap = scrapeEpgMap();
                                    if (epgMap) hdr['X-Lmnp-EpgMap'] = b64utf8(JSON.stringify(epgMap));
                                    var src = iptvSource();
                                    if (src) hdr['X-Lmnp-Src'] = b64utf8(src);
                                }
                                if (hdr && Object.keys(hdr).length) {
                                    var merged = {}, k;
                                    if (video.headers) for (k in video.headers) if (video.headers.hasOwnProperty(k)) merged[k] = video.headers[k];
                                    for (k in hdr) if (hdr.hasOwnProperty(k)) merged[k] = hdr[k];
                                    video.headers = merged;
                                }
                            } catch (e) {}
                            _origPlay.apply(self, args);
                        };

                        if (list) {
                            try { Lampa.Noty.show(iptv ? 'Готовлю каналы…' : 'Готовлю серии…'); } catch (_) {}
                            resolveWindow(list, pos, function () {
                                if (myGen !== _playGen) return;   // пользователь начал другое видео
                                finishPack();
                            });
                            return;   // запуск отложен до резолва ссылок
                        }

                        // Плейлиста нет (фильм/одиночная серия) — пакуем сразу.
                        var meta = iptv ? { title: origTitle || 'IPTV', iptv: true } : compactMeta(cardData);
                        if (iptv) addArchive(meta, video);
                        cleanMeta(meta);
                        if (meta.title || meta.tmdb_id || meta.iptv)
                            video.title = 'lmpmeta://' + b64utf8(JSON.stringify(meta));
                    }
                }
            } catch (e) {}
            return _origPlay.apply(self, args);
        };
    }

    // Финальный зонд: модуль Lampa.PlayerIPTV + объект активности + пример канала.
    function probeEpg(list, pos) {
        function ks(o, n) {
            try { return (o && typeof o === 'object') ? Object.keys(o).slice(0, n || 25) : (typeof o); }
            catch (e) { return 'e'; }
        }
        function sample(c) {
            if (!c || typeof c !== 'object') return c;
            var s = {};
            Object.keys(c).slice(0, 25).forEach(function (k) {
                var v = c[k];
                s[k] = (v && typeof v === 'object') ? ('[' + (Array.isArray(v) ? 'arr' + v.length : 'obj') + ']')
                                                    : ('' + v).slice(0, 30);
            });
            return s;
        }
        var out = {};
        try {
            out.piptv = ks(Lampa.PlayerIPTV, 45);
            var act = Lampa.Activity.active && Lampa.Activity.active();
            if (act && act.activity) {
                var o = act.activity.object;
                out.objKeys = ks(o, 40);
                var lst = o && (o.channels || o.list || o.items || o.playlist || o.data || o.results);
                if (Array.isArray(lst) && lst.length) {
                    out.listLen = lst.length;
                    out.ch = sample(lst[Math.min(pos || 0, lst.length - 1)] || lst[0]);
                }
            }
            out.glob = Object.keys(window).filter(function (k) { return /epg|gettv|genres_/i.test(k); }).slice(0, 20);
        } catch (e) { out.err = String(e); }
        return JSON.stringify(out);
    }

    // ── Хук на регистрацию плагинов-балансеров ───────────────────────────
    // Самая надёжная точка: Lampa передаёт чистую TMDB-карточку именно в
    // moment вызова plugin.component(object). Оборачиваем её у каждого плагина.

    function hookPlugins() {
        function wrapComponent(plugin) {
            if (!plugin || typeof plugin.component !== 'function') return;
            if (plugin.__lmnp) return;   // уже обёрнут
            plugin.__lmnp = true;
            var _orig = plugin.component;
            plugin.component = function(object) {
                try { saveTmdbCard(object); } catch(_) {}
                return _orig.apply(this, arguments);
            };
        }

        function wrapExisting() {
            try {
                var store = (Lampa.Plugin || {}).plugins
                         || (Lampa.Plugin || {}).list
                         || (Lampa.Plugin || {}).all;
                if (!store) return;
                var arr;
                if (Array.isArray(store)) arr = store;
                else { arr = []; for (var k in store) if (store.hasOwnProperty(k)) arr.push(store[k]); }
                arr.forEach(function(p) { try { wrapComponent(p); } catch(_) {} });
            } catch(_) {}
        }

        // Хук на Lampa.Plugin.add / register — регистрация нового плагина
        ['add','register'].forEach(function(method) {
            try {
                if (!Lampa.Plugin || typeof Lampa.Plugin[method] !== 'function') return;
                var _orig = Lampa.Plugin[method];
                Lampa.Plugin[method] = function(plugin) {
                    try { wrapComponent(plugin); } catch(_) {}
                    return _orig.apply(this, arguments);
                };
            } catch(_) {}
        });

        // Хук на Lampa.InteractionSource.add (BylAmpa и другие форки)
        try {
            if (Lampa.InteractionSource && typeof Lampa.InteractionSource.add === 'function') {
                var _isAdd = Lampa.InteractionSource.add;
                Lampa.InteractionSource.add = function(plugin) {
                    try { wrapComponent(plugin); } catch(_) {}
                    return _isAdd.apply(this, arguments);
                };
            }
        } catch(_) {}

        // Хук на методы запуска источника в разных форках
        ['Source','InteractionSource','InteractionMain','Online'].forEach(function(ns) {
            try {
                var obj = Lampa[ns];
                if (!obj) return;
                ['show','online','start','open','component'].forEach(function(m) {
                    if (typeof obj[m] !== 'function' || obj['__lmnp_' + m]) return;
                    obj['__lmnp_' + m] = true;
                    var _orig = obj[m];
                    obj[m] = function() {
                        try { if (arguments[0]) saveTmdbCard(arguments[0]); } catch(_) {}
                        return _orig.apply(this, arguments);
                    };
                });
            } catch(_) {}
        });

        wrapExisting();
    }

    // ── Настройки ────────────────────────────────────────────────────────

    function addSettings() {
        if (!window.Lampa || !Lampa.SettingsApi) return;
        // Seed defaults in Storage first so older builds don't read an undefined value.
        try { if (Lampa.Storage.get(PLUGIN_NAME + '_on', '__x') === '__x') Lampa.Storage.set(PLUGIN_NAME + '_on', true); } catch (_) {}
        try { if (Lampa.Storage.get(PLUGIN_NAME + '_package', '__x') === '__x') Lampa.Storage.set(PLUGIN_NAME + '_package', PACKAGE); } catch (_) {}

        try {
            Lampa.SettingsApi.addComponent({
                component: PLUGIN_NAME,
                name: 'Native Player',
                icon: '<svg viewBox="0 0 24 24" width="24" height="24"><path fill="currentColor" d="M8 5v14l11-7z"/></svg>'
            });
        } catch (_) {}

        // Each param is added independently: a SettingsApi quirk on one Lampa build
        // must not block the others (or, via start(), the player hook itself).
        function safeParam(p) { try { Lampa.SettingsApi.addParam(p); } catch (_) {} }

        safeParam({
            component: PLUGIN_NAME,
            param: { name: PLUGIN_NAME + '_on', type: 'trigger', 'default': true },
            field: { name: 'Нативный плеер', description: 'Запускать видео через Native Player APK' },
            onChange: function (v) {
                try { Lampa.Storage.set(PLUGIN_NAME + '_on', v === true || v === 'true'); } catch (_) {}
            }
        });
        // NB: no 'input' param here — on some Lampa builds (incl. Bylampa) rendering
        // a stored input value crashes the whole settings page
        // ("Cannot read properties of undefined (reading '<value>')"). The target
        // package is fixed (com.lampplayer.tv); override via Storage if ever needed.
    }

    // ── Инициализация ────────────────────────────────────────────────────

    function start() {
        // Isolate each step: a failure in one (e.g. SettingsApi on a particular Lampa
        // build) must never stop the player hook from installing.
        [addSettings, hookActivity, hookPlugins, hookPlayerPlay, hookPlayer].forEach(function (f) {
            try { f(); } catch (_) {}
        });
        try { Lampa.Noty.show('Native Player подключён'); } catch (_) {}
    }

    if (window.appready) {
        start();
    } else {
        try {
            Lampa.Listener.follow('app', function (e) {
                if (e.type === 'ready') start();
            });
        } catch (_) {}
    }

})();
