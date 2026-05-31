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

    var PACKAGE = 'com.lampa.player';
    var PLUGIN_NAME = 'lmnp';

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
            + ';package=' + PACKAGE
            + ';S.lampa_data=' + encodeURIComponent(json)
            + ';end;';
    }

    // ── Запуск APK ────────────────────────────────────────────────────────
    function launch(videoUrl, cardData) {
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

        // 2. Навигация (Chrome intent:// формат — корректно обрабатывается браузером)
        try { window.location.href = intentUri; return true; } catch (e) {}

        // 3. lmnp:// запасная схема
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

    // Сохраняем карточку; TMDB-карточка всегда приоритетнее карточки балансера
    function saveTmdbCard(obj) {
        if (!obj || typeof obj !== 'object') return;
        var all = [obj.movie, obj.show, obj.card, obj.item, obj];
        if (obj.data)   [obj.data, obj.data.movie, obj.data.card, obj.data.show, obj.data.item].forEach(function(x){ all.push(x); });
        if (obj.params) [obj.params, obj.params.movie, obj.params.card].forEach(function(x){ all.push(x); });
        all.forEach(function(c) {
            if (!c || typeof c !== 'object') return;
            if (!(c.id || c.tmdb_id) || !(c.title || c.name)) return;
            // Сохраняем если: ничего нет, или новая карточка — TMDB-качества
            if (!_savedCard || isTmdbCard(c)) _savedCard = c;
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

        // Listener на push (некоторые версии Lampa)
        try { Lampa.Activity.listener.follow('push', function(e) { saveTmdbCard(e); }); } catch (_) {}

        // Глобальные события
        try {
            Lampa.Listener.follow('full', function (e) {
                if (!e) return;
                [e, e.data, e.movie, e.card, e.item].forEach(function(t) {
                    try { saveTmdbCard(t); } catch (_) {}
                });
            });
        } catch (_) {}
        try {
            Lampa.Listener.follow('activity', function (e) {
                if (!e) return;
                [e, e.data, e.object, e.movie, e.card, e.item, e.params].forEach(function(t) {
                    try { saveTmdbCard(t); } catch (_) {}
                });
            });
        } catch (_) {}
        // Дополнительные события: card, movie, show
        ['card','movie','show','full_open','full'].forEach(function(ev) {
            try {
                Lampa.Listener.follow(ev, function(e) {
                    if (!e) return;
                    [e, e.card, e.movie, e.data, e.item].forEach(function(t) {
                        try { saveTmdbCard(t); } catch(_) {}
                    });
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
                     act.data && act.data.movie, act.data && act.data.card,
                     act.params && act.params.movie, act.params && act.params.card].forEach(function(x){ candidates.push(x); });
                });
            }
        } catch (_) {}
        try {
            var act = Lampa.Activity.active();
            if (act) {
                [act.card, act.movie, act.item,
                 act.data && act.data.movie, act.data && act.data.card,
                 act.params && act.params.movie, act.params && act.params.card].forEach(function(x){ candidates.push(x); });
            }
        } catch (_) {}

        // 3. Lampa.Player.card / .movie / .film
        try { candidates.push(Lampa.Player.card);  } catch (_) {}
        try { candidates.push(Lampa.Player.movie); } catch (_) {}
        try { candidates.push(Lampa.Player.film);  } catch (_) {}

        // 4. Поля события start
        try { candidates.push(data.card);  } catch (_) {}
        try { candidates.push(data.movie); } catch (_) {}
        try { candidates.push(data.film);  } catch (_) {}
        try { candidates.push(data.item);  } catch (_) {}
        try { if (data.id || data.tmdb_id) candidates.push(data); } catch (_) {}
        try { candidates.push(file && file.card);  } catch (_) {}
        try { candidates.push(file && file.movie); } catch (_) {}

        // 5. Предсохранённая карточка (запасная)
        if (_savedCard && (_savedCard.id || _savedCard.tmdb_id)) candidates.push(_savedCard);

        function unwrap(c) {
            if (!c || typeof c !== 'object') return null;
            if (c.id || c.tmdb_id) return c;
            var inner = c.movie || c.show || c.card || c.item;
            return (inner && inner !== c && (inner.id || inner.tmdb_id)) ? inner : c;
        }

        // Первый проход: только настоящие TMDB-карточки
        for (var i = 0; i < candidates.length; i++) {
            var u = unwrap(candidates[i]);
            if (u && (u.id || u.tmdb_id) && isTmdbCard(u)) return u;
        }
        // Второй проход: любая карточка с id, но НЕ элемент балансера (voice_name/translate без TMDB-признаков)
        for (var i = 0; i < candidates.length; i++) {
            var u = unwrap(candidates[i]);
            if (u && (u.id || u.tmdb_id) && !(u.voice_name || u.translate)) return u;
        }
        // Третий проход НЕ делаем — иначе вернём «Оригинальный» как название фильма
        return null;
    }

    // ── Плейлист ──────────────────────────────────────────────────────────
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
    function getEpg(card, data) {
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

    // ── Сборка данных для APK ─────────────────────────────────────────────
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
            // prefer per-episode values from the play element over the card defaults
            season_number:  file.season  || c.season_number || c.season  || 0,
            episode_number: file.episode || c.episode_number || c.episode || 0,
            poster_url:     posterUrl(c),
            backdrop_url:   c.backdrop_path ? 'https://image.tmdb.org/t/p/w1280' + c.backdrop_path : null,
            overview:       c.overview || null,
            release_year:   parseInt((c.release_date || c.first_air_date || '').substring(0, 4)) || 0,
            rating:         c.vote_average || 0,
            quality:        file.quality  || null,
            // balancer stores translator as voice_name in the play element
            translator:     file.translate || file.voice_name || null,
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
    var _lastLaunch  = 0;
    var _pendingUrl  = '';
    var _pendingData = null;

    function doLaunch(videoUrl, data, file) {
        var rawCard  = findCard(data, file);
        var pl       = getPlaylist();
        var cardData = buildCard(rawCard, file, data, pl.list, pl.pos);
        return launch(videoUrl, cardData);
    }

    function stopWebViewPlayer() {
        try { Lampa.Player.pause && Lampa.Player.pause(); } catch (_) {}
        try {
            var vid = document.querySelector('video');
            if (vid) { vid.pause(); vid.removeAttribute('src'); }
        } catch (_) {}
    }

    function hookPlayer() {
        if (!window.Lampa || !Lampa.Player) return;

        // ── Хук 1: start-событие ─────────────────────────────────────────
        // Наш listener регистрируется ПОСЛЕ встроенного обработчика Lampa,
        // поэтому наш window.location.href перезаписывает Lampa's в очереди
        // навигации — APK получает наш intent с lampa_data.
        Lampa.Player.listener.follow('start', function (e) {
            if (Date.now() - _lastLaunch < 6000) return;

            // Сканируем всё доступное состояние Lampa прямо в момент запуска
            try { if (Lampa.Player.card)  saveTmdbCard({card: Lampa.Player.card}); } catch (_) {}
            try { if (Lampa.Player.movie) saveTmdbCard({card: Lampa.Player.movie}); } catch (_) {}
            try { if (Lampa.Player.film)  saveTmdbCard({card: Lampa.Player.film}); } catch (_) {}
            try {
                var _acts = [];
                try { var _a = Lampa.Activity.all ? Lampa.Activity.all() : null; if (_a) _acts = _a; } catch(_e) {}
                try { var _h = Lampa.Activity.history; if (_h && _h.length) _acts = _acts.concat(_h); } catch(_e) {}
                try { var _ac = Lampa.Activity.active(); if (_ac) _acts.push(_ac); } catch(_e) {}
                _acts.forEach(function(act) {
                    if (!act) return;
                    try { saveTmdbCard(act); } catch(_e) {}
                    try { if (act.params) saveTmdbCard(act.params); } catch(_e) {}
                    try { if (act.data)   saveTmdbCard(act.data);   } catch(_e) {}
                });
            } catch (_) {}

            var data    = (e && e.data) ? e.data : (e || {});
            // Balancer's play element has quality/timeline/voice_name at top level,
            // not nested under .file — fall back to data itself when .file is absent
            var file    = (typeof data.file === 'object') ? data.file : data;
            var videoUrl = data.url
                        || (file.url || file.file)
                        || (typeof data.file === 'string' ? data.file : '')
                        || '';
            if (!videoUrl || !/^https?:|^rtsp:|^udp:/.test(videoUrl)) return;

            _savedFile   = file;
            _pendingUrl  = videoUrl;
            _pendingData = data;

            if (doLaunch(videoUrl, data, file)) {
                _lastLaunch = Date.now();
                stopWebViewPlayer();
            }
        });

        // ── Хук 2: playlist change — обновляем APK со списком серий ──────
        // Балансер вызывает Lampa.Player.playlist(list) ПОСЛЕ play() синхронно;
        // повторный intent → onNewIntent() в APK обновляет список серий.
        try {
            Lampa.PlayerPlaylist.listener.follow('change', function () {
                if (!_pendingUrl || Date.now() - _lastLaunch > 4000) return;
                var pl = getPlaylist();
                if (!pl.list || pl.list.length < 2) return;
                var rawCard  = findCard(_pendingData || {}, _savedFile || {});
                var cardData = buildCard(rawCard, _savedFile || {}, _pendingData || {}, pl.list, pl.pos);
                launch(_pendingUrl, cardData);
            });
        } catch (_) {}
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
        hookActivity();
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
