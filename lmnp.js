/**
 * lmnp — Lampa Native Player companion plugin
 * Перехватывает воспроизведение в Lampa и запускает нативный APK
 * с полными данными через intent:// scheme (которую Lampa обрабатывает).
 *
 * Установка: Lampa → Настройки → Расширения → добавить URL:
 * https://raw.githubusercontent.com/aidarmk-tech/ai/main/lmnp.js
 */
(function () {
    'use strict';

    var PACKAGE = 'com.lampa.player';
    var PLUGIN_NAME = 'lmnp';

    // ── Запуск через intent:// ────────────────────────────────────────────
    function launch(videoUrl, cardData) {
        var json = JSON.stringify(cardData);
        var safeUrl = videoUrl.replace(/#/g, '%23');
        var intentUri = 'intent:' + safeUrl +
            '#Intent' +
            ';action=android.intent.action.VIEW' +
            ';type=video/*' +
            ';package=' + PACKAGE +
            ';S.lampa_data=' + encodeURIComponent(json) +
            ';end;';

        try { window.location.href = intentUri; return true; } catch (e) {}
        try {
            if (window.Android && typeof Android.openUrl === 'function') {
                Android.openUrl(intentUri); return true;
            }
        } catch (e) {}
        try {
            var b64 = btoa(unescape(encodeURIComponent(json)));
            window.location.href = 'lmnp://play?url=' + encodeURIComponent(videoUrl) + '&d=' + encodeURIComponent(b64);
            return true;
        } catch (e) {}
        return false;
    }

    // ── Хранилище: сохраняем карточку когда пользователь открывает фильм ─
    // Балансер вызывает play() с карточкой источника (без TMDB id).
    // Настоящая TMDB-карточка сохраняется заранее, когда пользователь
    // открывает страницу фильма — до запуска плеера.

    var _savedCard = null;
    var _savedFile = null;

    function saveTmdbCard(obj) {
        if (!obj || typeof obj !== 'object') return;
        // Разворачиваем обёртки
        var c = obj.movie || obj.show || obj.card || obj;
        if (c && (c.id || c.tmdb_id) && (c.title || c.name)) {
            _savedCard = c;
        }
    }

    function hookActivity() {
        // Слушаем открытие карточки фильма/сериала через глобальный Listener.
        // Событие 'activity' (или 'full') сигнализирует о переходе пользователя
        // на страницу с TMDB-данными.
        try {
            Lampa.Listener.follow('full', function (e) {
                saveTmdbCard(e && (e.data || e));
            });
        } catch (_) {}
        try {
            Lampa.Listener.follow('activity', function (e) {
                var obj = e && (e.data || e.object || e);
                saveTmdbCard(obj);
            });
        } catch (_) {}
        // Также пробуем напрямую из active() при каждом старте
        // (резервный вариант если listener не сработал)
    }

    // ── Получение TMDB-карточки в момент запуска ──────────────────────────
    function findCard(data, file) {
        // 1. Предсохранённая карточка — самый надёжный источник
        if (_savedCard && (_savedCard.id || _savedCard.tmdb_id)) return _savedCard;

        var candidates = [];

        // 2. Текущая активность (последний экран перед плеером)
        try {
            var act = Lampa.Activity.active();
            if (act) {
                candidates.push(act.card);
                candidates.push(act.movie);
                // Сам объект активности может быть карточкой
                if (act.id || act.tmdb_id) candidates.push(act);
            }
        } catch (_) {}

        // 3. Данные из события start
        try { candidates.push(data.card); } catch (_) {}
        try { candidates.push(data.movie); } catch (_) {}
        // data может быть самой карточкой (некоторые балансеры передают так)
        try { if (data.id || data.tmdb_id) candidates.push(data); } catch (_) {}

        // 4. Player.card
        try { candidates.push(Lampa.Player.card); } catch (_) {}
        try { candidates.push(file && file.card); } catch (_) {}

        function unwrap(c) {
            if (!c || typeof c !== 'object') return null;
            if (c.id || c.tmdb_id) return c;
            var inner = c.movie || c.show || c.card || c.item;
            return (inner && inner !== c && (inner.id || inner.tmdb_id)) ? inner : c;
        }

        for (var i = 0; i < candidates.length; i++) {
            var u = unwrap(candidates[i]);
            if (u && (u.id || u.tmdb_id)) return u;
        }
        for (var i = 0; i < candidates.length; i++) {
            var u = unwrap(candidates[i]);
            if (u && (u.title || u.name)) return u;
        }
        return null;
    }

    // ── Плейлист ──────────────────────────────────────────────────────────
    // Балансер устанавливает плейлист ПОСЛЕ вызова play(), поэтому читаем
    // его с задержкой (вызываем через setTimeout).
    // IPTV-каналы (tv: true / iptv: true) не передаём как список серий.

    function getPlaylist() {
        var list = null, pos = 0;
        try {
            if (window.Lampa && Lampa.PlayerPlaylist) {
                list = Lampa.PlayerPlaylist.get ? Lampa.PlayerPlaylist.get() : null;
                pos  = Lampa.PlayerPlaylist.position ? (Lampa.PlayerPlaylist.position() || 0) : 0;
            }
        } catch (_) {}
        try { if (!list && Lampa.Player.playlist) list = Lampa.Player.playlist(); } catch (_) {}
        if (list && list.length && (list[0].tv || list[0].iptv)) { list = null; pos = 0; }
        return { list: list, pos: pos };
    }

    // ── EPG ───────────────────────────────────────────────────────────────
    // Формат от IPTV-плагинов: data.epg = [startMinutes, durationMinutes, title]
    // Для живого ТВ data.epg обычно не заполнен — принимаем это ограничение.

    function getEpg(card, data) {
        // Массив [startMin, durMin, title] — формат IPTV-плагинов (rootu.top и др.)
        var arr = null;
        try { arr = (data && Array.isArray(data.epg)) ? data.epg : null; } catch (_) {}
        try { if (!arr && card && Array.isArray(card.epg)) arr = card.epg; } catch (_) {}
        if (arr && arr.length >= 2) {
            var fmt = function (ms) {
                var d = new Date(ms);
                return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
            };
            return {
                epg_title: arr[2] || '',
                epg_start: fmt(arr[0] * 60000),
                epg_end:   fmt((arr[0] + arr[1]) * 60000),
            };
        }
        // Lampa EPG API (VOD/IPTV через Lampa)
        try {
            var now = null;
            if (window.Lampa && Lampa.EPG && Lampa.EPG.now) now = Lampa.EPG.now(card);
            if (!now && card && card.current) now = card.current;
            if (!now && card && card.epg && typeof card.epg === 'object' && !Array.isArray(card.epg)) now = card.epg;
            if (now && (now.title || now.name)) {
                return {
                    epg_title: now.title || now.name || '',
                    epg_start: now.start || now.time_start || '',
                    epg_end:   now.stop  || now.time_end   || '',
                };
            }
        } catch (_) {}
        return {};
    }

    // ── Постер ────────────────────────────────────────────────────────────
    function posterUrl(c) {
        if (!c) return null;
        if (c.poster && /^https?:\/\//.test(c.poster)) return c.poster;
        if (c.img    && /^https?:\/\//.test(c.img))    return c.img;
        if (c.poster_path) return 'https://image.tmdb.org/t/p/w500' + c.poster_path;
        return null;
    }

    // ── Список эпизодов ───────────────────────────────────────────────────
    function buildEpisodes(playlist, idx) {
        if (!playlist || playlist.length < 2) return null;
        try {
            var start = Math.max(0, (idx || 0) - 25);
            var eps = playlist.slice(start, start + 50).map(function (item, i) {
                return {
                    title:   item.title || item.name || ('Серия ' + (start + i + 1)),
                    url:     item.url || item.file || '',
                    season:  item.season  || null,
                    episode: item.episode || null,
                };
            }).filter(function (e) { return !!e.url; });
            return eps.length > 1 ? JSON.stringify(eps) : null;
        } catch (_) { return null; }
    }

    // ── Сборка объекта для передачи в APK ────────────────────────────────
    function buildCard(rawCard, fileData, epgData, playlist, episodeIndex) {
        var c = rawCard || {};
        if (!c.id && !c.tmdb_id) {
            var inner = c.movie || c.show || c.card;
            if (inner && (inner.id || inner.tmdb_id || inner.title || inner.name)) c = inner;
        }
        var file = (fileData && typeof fileData === 'object') ? fileData : {};
        var epg  = getEpg(c, epgData);

        var card = {
            title:          c.title || c.name || '',
            original_title: c.original_title || c.original_name || null,
            tmdb_id:        c.id || c.tmdb_id || 0,
            imdb_id:        c.imdb_id || null,
            season_number:  c.season_number || c.season || 0,
            episode_number: c.episode_number || c.episode || 0,
            poster_url:     posterUrl(c),
            backdrop_url:   c.backdrop_path ? 'https://image.tmdb.org/t/p/w1280' + c.backdrop_path : null,
            overview:       c.overview || null,
            release_year:   parseInt((c.release_date || c.first_air_date || '').substring(0, 4)) || 0,
            rating:         c.vote_average || 0,
            quality:        file.quality  || null,
            translator:     file.translate || null,
            timeline_time:     (file.timeline && file.timeline.time)     || 0,
            timeline_duration: (file.timeline && file.timeline.duration) || 0,
            headers:        (file.headers && Object.keys(file.headers).length)
                ? JSON.stringify(file.headers) : null,
            episodes:       buildEpisodes(playlist, episodeIndex),
            episode_index:  episodeIndex || 0,
        };
        if (epg.epg_title) {
            card.epg_title = epg.epg_title;
            card.epg_start = epg.epg_start || null;
            card.epg_end   = epg.epg_end   || null;
        }
        Object.keys(card).forEach(function (k) {
            var v = card[k];
            if (v === null || v === undefined || v === 0 || v === '') delete card[k];
        });
        return card;
    }

    // ── Хук на плеер ─────────────────────────────────────────────────────

    var _lastLaunch = 0;

    function hookPlayer() {
        if (!window.Lampa || !Lampa.Player) return;

        Lampa.Player.listener.follow('start', function (e) {
            if (Date.now() - _lastLaunch < 6000) return;

            var data = (e && e.data) ? e.data : (e || {});
            var file = data.file || {};

            var videoUrl = data.url
                        || (typeof file === 'object' && (file.url || file.file))
                        || (typeof file === 'string'  && file)
                        || '';
            if (!videoUrl || !/^https?:|^rtsp:|^udp:/.test(videoUrl)) return;

            // Сохраняем file для buildCard
            _savedFile = typeof file === 'object' ? file : {};

            // Задержка 300мс: балансер устанавливает плейлист ПОСЛЕ play(),
            // поэтому читаем Lampa.PlayerPlaylist.get() с задержкой.
            var capturedData = data;
            setTimeout(function () {
                var rawCard = findCard(capturedData, _savedFile);
                var pl      = getPlaylist();

                var cardData = buildCard(
                    rawCard,
                    _savedFile,
                    capturedData,
                    pl.list,
                    pl.pos
                );

                if (launch(videoUrl, cardData)) {
                    _lastLaunch = Date.now();
                    try { Lampa.Player.pause && Lampa.Player.pause(); } catch (_) {}
                    try {
                        var vid = document.querySelector('video');
                        if (vid) { vid.pause(); vid.removeAttribute('src'); }
                    } catch (_) {}
                }
            }, 300);
        });
    }

    // ── Настройки ────────────────────────────────────────────────────────

    function addSettings() {
        if (!window.Lampa || !Lampa.SettingsApi) return;
        try {
            Lampa.SettingsApi.addComponent({
                component: PLUGIN_NAME,
                name: 'Native Player',
                icon: '<svg viewBox="0 0 24 24" width="24" height="24"><path fill="currentColor" d="M8 5v14l11-7z"/></svg>'
            });
            Lampa.SettingsApi.addParam({
                component: PLUGIN_NAME,
                param: { name: PLUGIN_NAME + '_on', type: 'trigger', default: true },
                field: { name: 'Нативный плеер', description: 'Запускать видео через Native Player APK' },
                onChange: function (v) {
                    try { Lampa.Storage.set(PLUGIN_NAME + '_on', v); } catch (_) {}
                }
            });
        } catch (e) {}
    }

    // ── Инициализация ────────────────────────────────────────────────────

    function start() {
        addSettings();
        hookActivity();   // сохраняем TMDB-карточку при открытии фильма
        hookPlayer();
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
