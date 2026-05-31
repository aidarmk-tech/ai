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

    // ── Запуск через intent:// ────────────────────────────────────────────
    function launch(videoUrl, cardData) {
        var json    = JSON.stringify(cardData);
        var safeUrl = videoUrl.replace(/#/g, '%23');
        var intentUri = 'intent:' + safeUrl
            + '#Intent'
            + ';action=android.intent.action.VIEW'
            + ';type=video/*'
            + ';package=' + PACKAGE
            + ';S.lampa_data=' + encodeURIComponent(json)
            + ';end;';

        // 1. Синхронный вызов Android bridge (приоритет — не конкурирует с window.location)
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

        // 2. Навигация через href (асинхронно)
        try { window.location.href = intentUri; return true; } catch (e) {}

        // 3. Запасной схем lmnp://
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

    function saveTmdbCard(obj) {
        if (!obj || typeof obj !== 'object') return;
        var candidates = [
            obj.movie, obj.show, obj.card, obj.item, obj
        ];
        if (obj.data)   [obj.data, obj.data.movie, obj.data.card, obj.data.show, obj.data.item].forEach(function(x){ candidates.push(x); });
        if (obj.params) [obj.params, obj.params.movie, obj.params.card].forEach(function(x){ candidates.push(x); });
        candidates.forEach(function(c) {
            if (c && typeof c === 'object' && (c.id || c.tmdb_id) && (c.title || c.name)) {
                _savedCard = c;
            }
        });
    }

    function hookActivity() {
        try {
            Lampa.Listener.follow('full', function (e) {
                if (!e) return;
                saveTmdbCard(e.data || e);
                if (e.movie) saveTmdbCard(e.movie);
                if (e.card)  saveTmdbCard(e.card);
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
    }

    // ── Поиск TMDB-карточки в момент запуска ─────────────────────────────
    function findCard(data, file) {
        if (_savedCard && (_savedCard.id || _savedCard.tmdb_id)) return _savedCard;

        var candidates = [];

        try {
            var act = Lampa.Activity.active();
            if (act) {
                [act.card, act.movie, act.item,
                 act.data && act.data.movie, act.data && act.data.card,
                 act.params && act.params.movie, act.params && act.params.card].forEach(function(x){ candidates.push(x); });
                if (act.id || act.tmdb_id) candidates.push(act);
            }
        } catch (_) {}

        try { candidates.push(data.card); } catch (_) {}
        try { candidates.push(data.movie); } catch (_) {}
        try { candidates.push(data.item);  } catch (_) {}
        try { if (data.id || data.tmdb_id) candidates.push(data); } catch (_) {}

        try { candidates.push(Lampa.Player.card); } catch (_) {}
        try { candidates.push(file && file.card);  } catch (_) {}

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

        // ── Хук 1: Lampa.Player.play() — основной, синхронный перехват ───────
        // Переопределяем play() чтобы запустить APK ДО того, как Lampa
        // сформирует собственный intent (без наших данных).
        // Не вызываем _origPlay() → Lampa не отправляет конкурирующий intent.
        if (typeof Lampa.Player.play === 'function') {
            var _origPlay = Lampa.Player.play;
            Lampa.Player.play = function (video) {
                if (!video || video._lmnp_done) {
                    return _origPlay.apply(this, arguments);
                }
                video._lmnp_done = true;

                var file = (video.file && typeof video.file === 'object') ? video.file : {};
                var url  = video.url
                        || (file.url || file.file)
                        || (typeof video.file === 'string' ? video.file : '')
                        || '';

                if (!url || !/^https?:|^rtsp:|^udp:/.test(url)) {
                    return _origPlay.apply(this, arguments);
                }
                if (Date.now() - _lastLaunch < 6000) return;

                _savedFile   = file;
                _pendingUrl  = url;
                _pendingData = video;

                var rawCard  = findCard(video, file);
                var pl       = getPlaylist();
                var cardData = buildCard(rawCard, file, video, pl.list, pl.pos);

                if (launch(url, cardData)) {
                    _lastLaunch = Date.now();
                    // Не вызываем _origPlay — предотвращаем конкурирующий intent от Lampa.
                    // Балансер всё равно вызовет Lampa.Player.playlist() после нас — это нужно.
                    return;
                }

                // APK не установлен — откатываемся на встроенный плеер
                return _origPlay.apply(this, arguments);
            };
        }

        // ── Хук 2: start-событие — резерв (если _origPlay всё же вызван) ──────
        Lampa.Player.listener.follow('start', function (e) {
            if (Date.now() - _lastLaunch < 6000) return;

            var data    = (e && e.data) ? e.data : (e || {});
            var file    = typeof data.file === 'object' ? data.file : {};
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

        // ── Хук 3: playlist change — APK обновляется с полным списком серий ──
        // Балансер вызывает Lampa.Player.playlist(list) ПОСЛЕ play() — синхронно,
        // пока WebView ещё не заморожен.
        // Повторный intent → onNewIntent() в APK обновит список серий.
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
