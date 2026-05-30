/**
 * lmnp — Lampa Native Player companion plugin
 * Перехватывает воспроизведение в Lampa и запускает нативный APK
 * с полными данными о фильме/сериале через схему lmnp://
 *
 * Установка: Lampa → Настройки → Расширения → добавить URL этого файла
 * URL: https://raw.githubusercontent.com/aidarmk-tech/ai/main/lmnp.js
 */
(function () {
    'use strict';

    var PLUGIN_NAME = 'lmnp';

    // ── Безопасный base64 для UTF-8 ──────────────────────────────────
    function b64(str) {
        try { return btoa(unescape(encodeURIComponent(str))); } catch (e) { return btoa(str); }
    }

    // ── Запуск нативного плеера через lmnp:// ────────────────────────
    function launch(videoUrl, cardData) {
        var json = JSON.stringify(cardData);
        // lmnp://play?url=VIDEO_URL&d=BASE64_JSON
        var lmnpUri = 'lmnp://play?url=' + encodeURIComponent(videoUrl) + '&d=' + encodeURIComponent(b64(json));

        // Метод 1: навигация WebView (стандартный — перехватывается Lampa Android)
        try { window.location.href = lmnpUri; return true; } catch (e) {}

        // Метод 2: Android-мост
        try {
            if (window.Android && Android.openUrl) { Android.openUrl(lmnpUri); return true; }
        } catch (e) {}

        return false;
    }

    // ── Извлечение данных из карточки Lampa ──────────────────────────

    function poster(c) {
        if (!c) return null;
        if (c.poster) return c.poster;
        if (c.img) return c.img;
        if (c.poster_path) return 'https://image.tmdb.org/t/p/w500' + c.poster_path;
        return null;
    }

    function buildEpisodes(playlist) {
        if (!playlist || !playlist.length) return null;
        try {
            var eps = playlist.map(function (item, i) {
                return {
                    title: item.title || item.name || ('Серия ' + (i + 1)),
                    url:   item.url  || item.file || '',
                    season:  item.season  || null,
                    episode: item.episode || null
                };
            }).filter(function (e) { return !!e.url; });
            return eps.length > 1 ? JSON.stringify(eps) : null;
        } catch (e) { return null; }
    }

    function getEpg(card) {
        var epg = {};
        try {
            var now = null;
            if (window.Lampa && Lampa.EPG && Lampa.EPG.now) now = Lampa.EPG.now(card);
            if (!now && card && card.current) now = card.current;
            if (now) {
                epg.epg_title = now.title || now.name || '';
                epg.epg_start = now.start || now.time_start || '';
                epg.epg_end   = now.stop  || now.time_end   || '';
            }
        } catch (e) {}
        return epg;
    }

    function buildCard(rawCard, fileData, playlist, episodeIndex) {
        var c = rawCard || {};
        // Lampa иногда оборачивает карточку в .movie/.show/.card
        if (!c.title && !c.name && !c.id) c = c.movie || c.show || c.card || c;

        var file = fileData || {};
        var epg  = getEpg(c);

        var card = {
            title:          c.title || c.name || '',
            original_title: c.original_title || c.original_name || null,
            tmdb_id:        c.id || c.tmdb_id || 0,
            imdb_id:        c.imdb_id || null,
            season_number:  c.season_number || c.season || 0,
            episode_number: c.episode_number || c.episode || 0,
            poster_url:     poster(c),
            backdrop_url:   c.backdrop_path ? 'https://image.tmdb.org/t/p/w1280' + c.backdrop_path : null,
            overview:       c.overview || null,
            release_year:   parseInt((c.release_date || c.first_air_date || '').substring(0, 4)) || 0,
            rating:         c.vote_average || 0,
            quality:        file.quality   || null,
            translator:     file.translate || null,
            timeline_time:     (file.timeline && file.timeline.time)     || 0,
            timeline_duration: (file.timeline && file.timeline.duration) || 0,
            headers:        (file.headers && Object.keys(file.headers).length) ? JSON.stringify(file.headers) : null,
            episodes:       buildEpisodes(playlist),
            episode_index:  episodeIndex || 0,
        };

        // EPG-данные
        if (epg.epg_title) { card.epg_title = epg.epg_title; card.epg_start = epg.epg_start; card.epg_end = epg.epg_end; }

        // Убираем null/0
        Object.keys(card).forEach(function (k) {
            if (card[k] === null || card[k] === undefined || card[k] === 0 || card[k] === '') delete card[k];
        });

        return card;
    }

    // ── Хук на старт плеера Lampa ────────────────────────────────────

    var _lastLaunch = 0;

    function hookPlayer() {
        if (!window.Lampa || !Lampa.Player) return;

        Lampa.Player.listener.follow('start', function (e) {
            if (Date.now() - _lastLaunch < 6000) return;

            var data = (e && e.data) ? e.data : (e || {});
            var file = data.file || {};
            var videoUrl = data.url
                        || (typeof file === 'object' && (file.url || file.file))
                        || (typeof file === 'string' && file)
                        || '';
            if (!videoUrl || !/^https?:\/\/|^rtsp:\/\/|^udp:\/\//.test(videoUrl)) return;

            var rawCard = data.card
                       || (file && typeof file === 'object' && file.card)
                       || null;
            try { if (!rawCard && Lampa.Player.card) rawCard = Lampa.Player.card; } catch (e2) {}

            var playlist = null, episodeIndex = 0;
            try { playlist      = Lampa.Player.playlist    ? Lampa.Player.playlist()    : null; } catch (e2) {}
            try { episodeIndex  = Lampa.Player.playlistIndex ? Lampa.Player.playlistIndex() : 0; } catch (e2) {}

            var cardData = buildCard(rawCard, typeof file === 'object' ? file : {}, playlist, episodeIndex);

            if (launch(videoUrl, cardData)) {
                _lastLaunch = Date.now();
                // Останавливаем WebView-плеер чтобы не двоилось аудио
                try { Lampa.Player.pause && Lampa.Player.pause(); } catch (e2) {}
                try {
                    var vid = document.querySelector('video');
                    if (vid) { vid.pause(); vid.removeAttribute('src'); }
                } catch (e2) {}
            }
        });
    }

    // ── Настройки ────────────────────────────────────────────────────

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
                onChange: function (v) { Lampa.Storage.set(PLUGIN_NAME + '_on', v); }
            });
        } catch (e) {}
    }

    // ── Инициализация ────────────────────────────────────────────────

    function start() {
        addSettings();
        hookPlayer();
        try { Lampa.Noty.show('Native Player подключён'); } catch (e) {}
    }

    if (window.appready) {
        start();
    } else {
        window.Lampa && Lampa.Listener.follow('app', function (e) {
            if (e.type === 'ready') start();
        });
    }

})();
