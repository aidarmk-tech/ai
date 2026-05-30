/**
 * Lampa Native Player — companion plugin
 * Intercepts Lampa playback and launches the native Android player APK
 * with full card metadata, episode list, and EPG passed as intent extras.
 *
 * Install: Lampa → Settings → Extensions → add URL of this file.
 */
(function () {
    'use strict';

    var PACKAGE = 'com.lampa.player';
    var PLUGIN_NAME = 'lampa_native_player';

    // ── Helpers ──────────────────────────────────────────────────────

    function isAndroid() {
        return !!(window.Android || (typeof AndroidPlayer !== 'undefined'));
    }

    /** Build Android intent URI with all extras */
    function buildIntentUri(url, extras) {
        var parts = ['intent:' + encodeURI(url) + '#Intent'];
        parts.push('action=android.intent.action.VIEW');
        parts.push('type=video/*');
        parts.push('package=' + PACKAGE);

        Object.keys(extras).forEach(function (key) {
            var val = extras[key];
            if (val === null || val === undefined) return;
            if (typeof val === 'number' && Number.isInteger(val)) {
                parts.push('i.' + key + '=' + val);
            } else if (typeof val === 'number') {
                parts.push('f.' + key + '=' + val);
            } else if (typeof val === 'boolean') {
                parts.push('b.' + key + '=' + val);
            } else {
                // String — encode value
                parts.push('S.' + key + '=' + encodeURIComponent(String(val)));
            }
        });

        parts.push('end');
        return parts.join(';');
    }

    /** Launch our native player. Falls back to ACTION_VIEW if bridge unavailable. */
    function launch(url, extras) {
        var intentUri = buildIntentUri(url, extras);

        // Lampa Android WebView bridge
        if (window.Android && typeof Android.openUrl === 'function') {
            Android.openUrl(intentUri);
            return true;
        }
        if (window.Android && typeof Android.externalPlayer === 'function') {
            Android.externalPlayer(JSON.stringify({ url: url, package: PACKAGE, extras: extras }));
            return true;
        }
        // Fallback: navigate WebView to intent:// URI (works in most Android browsers)
        try {
            window.location.href = intentUri;
            return true;
        } catch (e) {}
        return false;
    }

    // ── Card data extraction ─────────────────────────────────────────

    function extractCard(rawCard) {
        if (!rawCard) return {};
        // Normalise: Lampa sometimes nests the card
        var c = rawCard.movie || rawCard.show || rawCard.card || rawCard;
        return c;
    }

    /** Build episode list from Lampa's playlist or from card seasons */
    function buildEpisodes(playlist, card) {
        // Prefer explicit playlist (already-resolved URLs)
        if (playlist && playlist.length > 1) {
            return JSON.stringify(playlist.map(function (item, i) {
                return {
                    title: item.title || item.name || ('Серия ' + (i + 1)),
                    url: item.url || item.file || '',
                    season: item.season || null,
                    episode: item.episode || null
                };
            }).filter(function (e) { return !!e.url; }));
        }
        return null;
    }

    /** Extract EPG data if this is an IPTV channel */
    function extractEpg(card) {
        var result = {};
        // Lampa EPG comes from card.epg or Lampa.EPG (if loaded)
        var epg = card.epg || null;
        if (!epg && window.Lampa && Lampa.EPG) {
            try { epg = Lampa.EPG.now(card); } catch (e) {}
        }
        if (epg) {
            result.epg_title = epg.title || epg.name || '';
            result.epg_start = epg.start || epg.time_start || '';
            result.epg_end   = epg.stop  || epg.time_end   || '';
        } else if (card.current) {
            result.epg_title = card.current.title || card.current.name || '';
            result.epg_start = card.current.start || '';
            result.epg_end   = card.current.stop  || '';
        }
        return result;
    }

    function posterUrl(card) {
        if (card.poster)      return card.poster;
        if (card.poster_path) return 'https://image.tmdb.org/t/p/w500' + card.poster_path;
        return null;
    }

    function backdropUrl(card) {
        if (card.backdrop_path) return 'https://image.tmdb.org/t/p/w1280' + card.backdrop_path;
        return null;
    }

    // ── Player hook ──────────────────────────────────────────────────

    var _lastLaunch = 0;

    function hookPlayer() {
        if (!window.Lampa || !Lampa.Player) return;

        Lampa.Player.listener.follow('start', function (e) {
            // Debounce: Lampa sometimes fires 'start' twice
            if (Date.now() - _lastLaunch < 5000) return;

            var data = (e && e.data) ? e.data : (e || {});
            var url  = data.url
                     || (data.file && (data.file.url || data.file))
                     || (data.timeline && data.timeline.url)
                     || '';
            if (!url) return;

            var rawCard = data.card || (data.file && data.file.card) || null;
            try { if (!rawCard && Lampa.Player.card) rawCard = Lampa.Player.card; } catch (e2) {}

            var card = extractCard(rawCard);

            // Playlist for episodes
            var playlist = null;
            try { playlist = Lampa.Player.playlist ? Lampa.Player.playlist() : null; } catch (e2) {}
            var episodesJson = buildEpisodes(playlist, card);

            // Current episode index
            var episodeIndex = 0;
            try {
                if (Lampa.Player.playlistIndex) episodeIndex = Lampa.Player.playlistIndex();
            } catch (e2) {}

            // Timeline (resume position)
            var timeline = data.timeline || {};

            // Headers
            var headersJson = null;
            if (data.headers && Object.keys(data.headers).length) {
                headersJson = JSON.stringify(data.headers);
            }

            var epg = extractEpg(card);

            var extras = {
                title:          card.title || card.name || '',
                original_title: card.original_title || card.original_name || null,
                tmdb_id:        (card.id || card.tmdb_id || 0),
                imdb_id:        card.imdb_id || null,
                season_number:  card.season_number || card.season || 0,
                episode_number: card.episode_number || card.episode || 0,
                poster_url:     posterUrl(card),
                backdrop_url:   backdropUrl(card),
                overview:       card.overview || null,
                release_year:   (card.release_date  || card.first_air_date || '').substring(0, 4) | 0 || 0,
                rating:         card.vote_average || 0,
                quality:        data.quality || (data.file && data.file.quality) || null,
                translator:     data.translate || (data.file && data.file.translate) || null,
                timeline_time:  timeline.time     || 0,
                timeline_duration: timeline.duration || 0,
                headers_json:   headersJson,
                episodes:       episodesJson,
                episode_index:  episodeIndex,
                epg_title:      epg.epg_title || null,
                epg_start:      epg.epg_start || null,
                epg_end:        epg.epg_end   || null,
            };

            // Remove nulls / zero-value non-meaningful ints
            Object.keys(extras).forEach(function (k) {
                if (extras[k] === null || extras[k] === undefined) delete extras[k];
            });

            if (launch(url, extras)) {
                _lastLaunch = Date.now();
                // Pause/stop Lampa's built-in player so audio doesn't double-play
                try { Lampa.Player.pause && Lampa.Player.pause(); } catch (e2) {}
                // Let native player take over — Lampa's activity goes to background
            }
        });
    }

    // ── Settings toggle ──────────────────────────────────────────────

    function addSettings() {
        if (!window.Lampa || !Lampa.SettingsApi) return;
        Lampa.SettingsApi.addComponent({
            component: PLUGIN_NAME,
            name: 'Native Player',
            icon: '<svg viewBox="0 0 24 24" width="24" height="24"><path fill="currentColor" d="M8 5v14l11-7z"/></svg>'
        });
        Lampa.SettingsApi.addParam({
            component: PLUGIN_NAME,
            param: { name: PLUGIN_NAME + '_enabled', type: 'trigger', default: true },
            field: { name: 'Использовать нативный плеер', description: 'Запускать видео через Native Player APK' },
            onChange: function (v) {
                Lampa.Storage.set(PLUGIN_NAME + '_enabled', v === true || v === 'true');
            }
        });
    }

    // ── Init ─────────────────────────────────────────────────────────

    function start() {
        if (!isAndroid()) return; // Plugin only makes sense on Android
        addSettings();
        hookPlayer();
        try { Lampa.Noty.show('Native Player подключён'); } catch (e) {}
    }

    if (window.appready) {
        start();
    } else {
        Lampa.Listener.follow('app', function (e) {
            if (e.type === 'ready') start();
        });
    }

})();
