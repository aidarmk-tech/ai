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

    // ── Запуск через intent:// (Lampa WebView умеет это обрабатывать) ────
    function launch(videoUrl, cardData) {
        var json = JSON.stringify(cardData);

        // Экранируем # в URL чтобы не сломать intent:// формат
        var safeUrl = videoUrl.replace(/#/g, '%23');

        // Все данные в одном extra lampa_data — нет проблем с ; в JSON
        var intentUri = 'intent:' + safeUrl +
            '#Intent' +
            ';action=android.intent.action.VIEW' +
            ';type=video/*' +
            ';package=' + PACKAGE +
            ';S.lampa_data=' + encodeURIComponent(json) +
            ';end;';

        // Метод 1: навигация WebView — Lampa перехватывает intent://
        try { window.location.href = intentUri; return true; } catch (e) {}

        // Метод 2: Android-мост если есть
        try {
            if (window.Android && typeof Android.openUrl === 'function') {
                Android.openUrl(intentUri);
                return true;
            }
        } catch (e) {}

        // Метод 3: lmnp:// fallback
        try {
            var b64 = btoa(unescape(encodeURIComponent(json)));
            window.location.href = 'lmnp://play?url=' + encodeURIComponent(videoUrl) + '&d=' + encodeURIComponent(b64);
            return true;
        } catch (e) {}

        return false;
    }

    // ── Поиск TMDB-карточки из стека активностей ─────────────────────────
    // Плеер в Lampa — это оверлей, не новая активность.
    // Lampa.Activity.active() возвращает последний экран перед плеером.
    // Для сериала это может быть экран серий (без TMDB id), а выше — экран фильма (с TMDB id).
    // Ищем карточку с id, перебирая весь стек снизу вверх.

    function findTmdbCard(data, file) {
        var candidates = [];

        // Стек активностей Lampa (от самой старой к последней)
        // activity.card содержит карточку открытого фильма/шоу
        try {
            var all = Lampa.Activity.all ? Lampa.Activity.all() : [];
            for (var i = 0; i < all.length; i++) {
                if (!all[i]) continue;
                if (all[i].card)  candidates.push(all[i].card);
                if (all[i].movie) candidates.push(all[i].movie);
            }
        } catch (_) {}

        // Текущая активность (то, что под плеером)
        try {
            var act = Lampa.Activity.active();
            if (act) {
                if (act.card)  candidates.push(act.card);
                if (act.movie) candidates.push(act.movie);
            }
        } catch (_) {}

        // Player.card и Player.item как запасной вариант
        try { if (Lampa.Player.card)  candidates.push(Lampa.Player.card); } catch (_) {}
        try { if (Lampa.Player.item)  candidates.push(Lampa.Player.item); } catch (_) {}

        // Данные из события start
        try { if (data.card) candidates.push(data.card); } catch (_) {}
        try { if (file && file.card) candidates.push(file.card); } catch (_) {}

        // Разворачиваем обёртки (балансер может обернуть карточку в {movie: {...}})
        function unwrap(c) {
            if (!c || typeof c !== 'object') return null;
            if (c.id || c.tmdb_id) return c;
            var inner = c.movie || c.show || c.card || c.item;
            return (inner && inner !== c && (inner.id || inner.tmdb_id)) ? inner : c;
        }

        // Приоритет — карточка с TMDB id
        for (var i = 0; i < candidates.length; i++) {
            var u = unwrap(candidates[i]);
            if (u && (u.id || u.tmdb_id)) return u;
        }
        // Иначе — любая с названием
        for (var i = 0; i < candidates.length; i++) {
            var u = unwrap(candidates[i]);
            if (u && (u.title || u.name)) return u;
        }
        return null;
    }

    // ── Плейлист серий ────────────────────────────────────────────────────
    // Для сериалов используем Lampa.PlayerPlaylist — именно его использует Lampa.
    // Для IPTV (tv: true / iptv: true) плейлист — это список каналов, не передаём.

    function getPlaylist() {
        var list = null, pos = 0;
        try {
            if (window.Lampa && Lampa.PlayerPlaylist) {
                list = Lampa.PlayerPlaylist.get ? Lampa.PlayerPlaylist.get() : null;
                pos  = Lampa.PlayerPlaylist.position ? (Lampa.PlayerPlaylist.position() || 0) : 0;
            }
        } catch (_) {}
        // Fallback: Lampa.Player.playlist()
        try {
            if (!list && Lampa.Player.playlist) list = Lampa.Player.playlist();
        } catch (_) {}
        // IPTV каналы не нужны как список серий
        if (list && list.length && (list[0].tv || list[0].iptv)) {
            list = null; pos = 0;
        }
        return { playlist: list, index: pos };
    }

    // ── EPG из данных IPTV-плагина ────────────────────────────────────────
    // IPTV-плагины (rootu.top, etc.) передают EPG как массив:
    //   data.epg = [startMinutes, durationMinutes, title, description?]
    // Для архива/catchup этот массив есть в data.epg.
    // Для текущей прямой трансляции — в data.epg тоже, если плагин заполнил.

    function getEpg(card, data) {
        // Формат от IPTV-плагина: массив [startMin, durMin, title, ...]
        var epgArr = null;
        try { epgArr = (data && Array.isArray(data.epg)) ? data.epg : null; } catch (_) {}
        try { if (!epgArr && card && Array.isArray(card.epg)) epgArr = card.epg; } catch (_) {}

        if (epgArr && epgArr.length >= 2) {
            var startMs = epgArr[0] * 60000;
            var endMs   = (epgArr[0] + epgArr[1]) * 60000;
            var fmt = function(ms) {
                var d = new Date(ms);
                return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
            };
            return {
                epg_title: epgArr[2] || '',
                epg_start: fmt(startMs),
                epg_end:   fmt(endMs),
            };
        }

        // Lampa EPG API (для VOD-карточек с TMDB)
        var r = {};
        try {
            var now = null;
            if (window.Lampa && Lampa.EPG && Lampa.EPG.now) now = Lampa.EPG.now(card);
            if (!now && card && card.current) now = card.current;
            if (!now && card && card.epg && typeof card.epg === 'object') now = card.epg;
            if (now) {
                r.epg_title = now.title || now.name  || '';
                r.epg_start = now.start || now.time_start || '';
                r.epg_end   = now.stop  || now.time_end   || '';
            }
        } catch (_) {}
        return r;
    }

    // ── Постер ────────────────────────────────────────────────────────────
    function posterUrl(c) {
        if (!c) return null;
        if (c.poster && /^https?:\/\//.test(c.poster)) return c.poster;
        if (c.img    && /^https?:\/\//.test(c.img))    return c.img;
        if (c.poster_path) return 'https://image.tmdb.org/t/p/w500' + c.poster_path;
        return null;
    }

    // ── Список эпизодов (≤50 вокруг текущего) ────────────────────────────
    function buildEpisodes(playlist, currentIndex) {
        if (!playlist || playlist.length < 2) return null;
        try {
            var start = Math.max(0, (currentIndex || 0) - 25);
            var slice = playlist.slice(start, start + 50);
            var eps = slice.map(function (item, i) {
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

    // ── Сборка карточки для передачи плееру ──────────────────────────────
    function buildCard(rawCard, fileData, epgData, playlist, episodeIndex) {
        var c = rawCard || {};

        // Если у карточки нет TMDB id — пробуем развернуть обёртку
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
            backdrop_url:   c.backdrop_path
                ? 'https://image.tmdb.org/t/p/w1280' + c.backdrop_path : null,
            overview:       c.overview || null,
            release_year:   parseInt((c.release_date || c.first_air_date || '').substring(0, 4)) || 0,
            rating:         c.vote_average || 0,
            quality:        file.quality   || null,
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

        // Убираем пустые значения
        Object.keys(card).forEach(function (k) {
            var v = card[k];
            if (v === null || v === undefined || v === 0 || v === '') delete card[k];
        });

        return card;
    }

    // ── Хук на старт плеера ──────────────────────────────────────────────

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

            // TMDB-карточка: ищем в стеке активностей (1-2 уровня выше плеера)
            var rawCard = findTmdbCard(data, file);

            // Плейлист серий через Lampa.PlayerPlaylist
            var pl = getPlaylist();

            var cardData = buildCard(
                rawCard,
                typeof file === 'object' ? file : {},
                data,                   // для EPG: data.epg = [startMin, durMin, title]
                pl.playlist,
                pl.index
            );

            if (launch(videoUrl, cardData)) {
                _lastLaunch = Date.now();
                // Останавливаем WebView-плеер — иначе двоится звук
                try { Lampa.Player.pause && Lampa.Player.pause(); } catch (_) {}
                try {
                    var vid = document.querySelector('video');
                    if (vid) { vid.pause(); vid.removeAttribute('src'); }
                } catch (_) {}
            }
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
