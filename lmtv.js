/*!
 * lmtv.js — IPTV-каталог для Lampa (спутник LampPlayer).
 * Браузер каналов: плейлисты → группы → каналы с логотипами, номерами, EPG,
 * избранным, недавними, поиском и родительским фильтром. Воспроизведение
 * отдаётся плееру (LampPlayer умеет архив/EPG/зоппинг) с флагом tv + плейлистом.
 * ES5 — работает на старых ТВ-браузерах. Тестировать на устройстве.
 */
(function () {
    'use strict';

    if (window.lmtv_plugin_ready) return;
    window.lmtv_plugin_ready = true;

    var PLUGIN = 'lmtv';
    var TITLE = 'IPTV · ТВ';
    // Облачный EPG нашего конвейера (тот же, что тянет LampPlayer).
    var REMOTE_EPG = 'https://raw.githubusercontent.com/aidarmk-tech/ai/epg-data/epg';
    var ADULT_RE = /(18\+|xxx|adult|эротик|взросл|porn|for adults|секс)/i;

    // ── утилиты ────────────────────────────────────────────────────────────
    function S(key, def) { try { return Lampa.Storage.get(PLUGIN + '_' + key, def); } catch (e) { return def; } }
    function Sset(key, val) { try { Lampa.Storage.set(PLUGIN + '_' + key, val); } catch (e) {} }
    function noty(t) { try { Lampa.Noty.show(t); } catch (e) {} }
    function esc(s) { return ('' + (s == null ? '' : s)).replace(/[<>&"]/g, function (c) { return ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' })[c]; }); }
    function norm(s) { return ('' + (s || '')).toLowerCase().replace(/[^a-zа-я0-9]/g, ''); }
    function uid() { return 'p' + Math.abs((Date.now() + '' + Math.round(performance && performance.now ? performance.now() : 0)).split('').reduce(function (a, c) { return ((a << 5) - a) + c.charCodeAt(0) | 0; }, 0)); }

    function httpText(url, done) {
        try {
            var x = new XMLHttpRequest();
            x.open('GET', url, true);
            x.timeout = 20000;
            x.onreadystatechange = function () {
                if (x.readyState === 4) done(x.status === 200 ? x.responseText : null);
            };
            x.onerror = x.ontimeout = function () { done(null); };
            x.send();
        } catch (e) { done(null); }
    }

    // ── плейлисты (Storage) ────────────────────────────────────────────────
    function playlists() { var a = S('playlists', []); return (a && a.length) ? a : []; }
    function savePlaylists(a) { Sset('playlists', a); }
    function addPlaylist(name, url) {
        if (!/^https?:\/\//i.test(url)) { noty('Нужна ссылка http(s) на m3u'); return false; }
        var a = playlists();
        a.push({ id: uid(), name: (name || url.split('/').pop() || 'Плейлист').slice(0, 40), url: url });
        savePlaylists(a); return true;
    }
    function removePlaylist(id) {
        savePlaylists(playlists().filter(function (p) { return p.id !== id; }));
        try { delete PARSED[id]; } catch (e) {}
    }

    // ── избранное / недавние ───────────────────────────────────────────────
    function favKey(ch) { return norm(ch.title) + '|' + (ch.chno || ''); }
    function favs() { return S('fav', []); }
    function isFav(ch) { var k = favKey(ch); return favs().indexOf(k) >= 0; }
    function toggleFav(ch) {
        var k = favKey(ch), a = favs(), i = a.indexOf(k);
        if (i >= 0) a.splice(i, 1); else a.unshift(k);
        Sset('fav', a.slice(0, 500)); return i < 0;
    }
    function recent() { return S('recent', []); }
    function pushRecent(ch) {
        var a = recent().filter(function (c) { return c.url !== ch.url && norm(c.title) !== norm(ch.title); });
        a.unshift({ title: ch.title, url: ch.url, logo: ch.logo, group: ch.group, tvgId: ch.tvgId, chno: ch.chno, catchup: ch.catchup, catchupSource: ch.catchupSource, plid: ch.plid });
        Sset('recent', a.slice(0, 60));
    }

    // ── парсинг m3u ────────────────────────────────────────────────────────
    var PARSED = {};   // id → {ts, channels, tvg}
    function attr(line, name) {
        var m = line.match(new RegExp(name + '\\s*=\\s*"([^"]*)"', 'i'));
        return m ? m[1] : '';
    }
    function parseM3u(text) {
        var lines = text.split(/\r?\n/), channels = [], i = 0, chno = 0;
        var head = lines[0] || '';
        var tvg = (head.match(/(?:url-tvg|x-tvg-url)\s*=\s*"([^"]+)"/i) || [])[1] || '';
        // общий catchup из заголовка
        var hCatchup = attr(head, 'catchup'), hSrc = attr(head, 'catchup-source');
        for (; i < lines.length; i++) {
            var ln = lines[i];
            if (ln.indexOf('#EXTINF') !== 0) continue;
            var name = ln.replace(/^#EXTINF:.*?,/, '').trim();
            var logo = attr(ln, 'tvg-logo') || attr(ln, 'logo');
            var group = attr(ln, 'group-title') || 'Без группы';
            var tvgId = attr(ln, 'tvg-id');
            var num = attr(ln, 'tvg-chno') || attr(ln, 'channel-number');
            var catchup = attr(ln, 'catchup') || attr(ln, 'catchup-type') || hCatchup;
            var csrc = attr(ln, 'catchup-source') || hSrc;
            var rec = attr(ln, 'tvg-rec') || attr(ln, 'timeshift');
            // возможные #EXTGRP / #EXTVLCOPT между EXTINF и url
            var url = '';
            for (var j = i + 1; j < lines.length; j++) {
                var nx = (lines[j] || '').trim();
                if (!nx) continue;
                if (nx.charAt(0) === '#') { var g = nx.match(/^#EXTGRP:\s*(.+)$/i); if (g) group = g[1].trim(); continue; }
                url = nx; i = j; break;
            }
            if (!/^https?:|^rtsp:|^udp:/i.test(url)) continue;
            chno++;
            channels.push({
                title: name || 'Канал ' + chno, url: url, logo: logo, group: group,
                tvgId: tvgId, chno: num ? parseInt(num, 10) : chno,
                catchup: catchup ? catchup.toLowerCase() : (rec && parseInt(rec, 10) > 0 ? 'auto' : ''),
                catchupSource: csrc
            });
        }
        return { channels: channels, tvg: tvg };
    }
    function loadPlaylist(pl, done) {
        var cached = PARSED[pl.id];
        if (cached && Date.now() - cached.ts < 6 * 3600e3) { done(cached); return; }
        httpText(pl.url, function (text) {
            if (!text || text.toUpperCase().indexOf('#EXTM3U') < 0) { done(null); return; }
            var p = parseM3u(text);
            var obj = { ts: Date.now(), channels: p.channels, tvg: p.tvg };
            PARSED[pl.id] = obj;
            done(obj);
        });
    }
    function groupsOf(channels) {
        var order = [], map = {}, adult = S('adult', false);
        channels.forEach(function (c) {
            if (!adult && ADULT_RE.test(c.group)) return;
            if (!map[c.group]) { map[c.group] = []; order.push(c.group); }
            map[c.group].push(c);
        });
        return order.map(function (g) { return { name: g, list: map[g] }; });
    }

    // ── облачный EPG (Сейчас/Далее) ────────────────────────────────────────
    var EPG = {};   // tvgId → {ts, now, next}
    function idFromUrl(url) { var m = (url || '').split('?')[0].match(/\/(ch\d+)\/[^/]+$/); return m ? m[1] : ''; }
    function epgFor(ch, cb) {
        if (!S('epg', true)) { cb(null); return; }
        var id = ch.tvgId || idFromUrl(ch.url);
        if (!id) { cb(null); return; }
        var c = EPG[id];
        if (c && Date.now() - c.ts < 30 * 60e3) { cb(c); return; }
        var safe = id.replace(/[^A-Za-z0-9._-]/g, '_');
        httpText(REMOTE_EPG + '/' + safe + '.json', function (body) {
            if (!body) { cb(null); return; }
            try {
                var p = JSON.parse(body).p || [], now = Date.now() / 1000, cur = null, nxt = null;
                for (var i = 0; i < p.length; i++) {
                    if (p[i].s <= now && p[i].e > now) { cur = p[i]; nxt = p[i + 1] || null; break; }
                    if (p[i].s > now) { nxt = p[i]; break; }
                }
                var r = { ts: Date.now(), now: cur, next: nxt };
                EPG[id] = r; cb(r);
            } catch (e) { cb(null); }
        });
    }
    function hm(sec) { var d = new Date(sec * 1000); return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2); }

    // ── передача канала плееру ─────────────────────────────────────────────
    function playChannel(ch, siblings) {
        pushRecent(ch);
        var list = (siblings || [ch]).map(function (c) {
            return { title: c.title, url: c.url, tv: true, iptv: true, logo: c.logo,
                     tvg_id: c.tvgId, catchup: c.catchup, catchup_source: c.catchupSource };
        });
        var video = {
            title: ch.title, url: ch.url, tv: true, iptv: true, logo: ch.logo,
            tvg_id: ch.tvgId, catchup: ch.catchup, catchup_source: ch.catchupSource,
            playlist: list
        };
        Lampa.Player.play(video);
        try { Lampa.Player.playlist(list); } catch (e) {}
    }

    // ── компонент каталога ─────────────────────────────────────────────────
    function component(object) {
        var self = this;
        var scroll = new Lampa.Scroll({ mask: true, over: true, step: 300 });
        var html = $('<div class="lmtv"></div>');
        var body = $('<div class="lmtv__body"></div>');
        var info = $('<div class="lmtv__info"></div>');
        var last = false;
        var mode = object.mode || 'home';

        scroll.append(body);
        html.append(info);
        html.append(scroll.render());

        this.create = function () { return this.render(); };
        this.render = function () { return html; };

        // У объекта активности НЕТ метода active() — только у глобального Lampa.Activity.
        function activeNow() {
            try { return Lampa.Activity.active().activity === self.activity; } catch (e) { return true; }
        }
        function loader(on) { try { if (self.activity && self.activity.loader) self.activity.loader(on); } catch (e) {} }

        this.build = function () {
            body.empty();
            try {
                if (mode === 'home') buildHome();
                else if (mode === 'groups') buildGroups();
                else buildChannels();
            } catch (e) { noty('lmtv: ' + (e && e.message ? e.message : e)); }
        };

        // — экран «Дом»: избранное, недавние, поиск, плейлисты, управление —
        function buildHome() {
            html.removeClass('lmtv--grid');
            info.html('<div class="lmtv__title">' + esc(TITLE) + '</div><div class="lmtv__sub">Выбери источник или добавь плейлист</div>');
            var items = [];
            items.push(row('★ Избранное', favs().length + ' каналов', function () { open({ mode: 'channels', src: 'fav', title: 'Избранное' }); }));
            items.push(row('🕓 Недавние', recent().length + ' каналов', function () { open({ mode: 'channels', src: 'recent', title: 'Недавние' }); }));
            items.push(row('🔍 Поиск канала', 'по всем плейлистам', function () { searchPrompt(); }));
            var pls = playlists();
            pls.forEach(function (pl) {
                items.push(row('📺 ' + pl.name, pl.url.replace(/^https?:\/\//, '').slice(0, 46), function () {
                    open({ mode: 'groups', plid: pl.id, title: pl.name, url: pl.url });
                }, function () { confirmDelete(pl); }));
            });
            items.push(row('＋ Добавить плейлист', 'вставить ссылку на m3u', function () { addPrompt(); }));
            if (pls.length) items.push(row('⚙ Настройки', 'EPG, родительский фильтр', function () { try { Lampa.Settings.create('main'); } catch (e) {} }));
            items.forEach(function (el) { body.append(el); });
        }

        function buildGroups() {
            var pl = find(playlists(), object.plid);
            info.html('<div class="lmtv__title">' + esc(object.title || 'Плейлист') + '</div><div class="lmtv__sub">Загрузка…</div>');
            if (!pl) { info.find('.lmtv__sub').text('Плейлист не найден'); return; }
            loader(true);
            loadPlaylist(pl, function (data) {
                loader(false);
                if (!data) { info.find('.lmtv__sub').text('Не удалось загрузить m3u'); return; }
                var gs = groupsOf(data.channels);
                info.find('.lmtv__sub').text(data.channels.length + ' каналов · ' + gs.length + ' групп');
                body.empty();
                gs.forEach(function (g) {
                    body.append(groupCard(g, function () {
                        open({ mode: 'channels', plid: pl.id, group: g.name, title: g.name, url: pl.url });
                    }));
                });
                if (activeNow()) refocus();
            });
        }

        function buildChannels() {
            html.addClass('lmtv--grid');
            var title = object.title || 'Каналы';
            if (object.src === 'fav') { collectFav(function (l) { renderChannels(l, title); }); return; }
            if (object.src === 'recent') { renderChannels(recent(), title); return; }
            if (object.src === 'search') { searchAll(object.query, function (l) { renderChannels(l, title + ' · «' + object.query + '»'); }); return; }
            // группа: берём из кэша плейлиста (loadPlaylist кэширует на 6 часов)
            var pl = find(playlists(), object.plid);
            if (!pl) { renderChannels([], title); return; }
            loader(true);
            loadPlaylist(pl, function (data) {
                loader(false);
                var l = (data ? data.channels : []).filter(function (c) { return c.group === object.group; });
                renderChannels(l, title);
            });
        }

        function collectFav(cb) {
            var keys = favs(), out = [], pend = 0, seen = {};
            recent().forEach(function (c) { if (keys.indexOf(favKey(c)) >= 0 && !seen[favKey(c)]) { seen[favKey(c)] = 1; out.push(c); } });
            var pls = playlists();
            if (!pls.length) { cb(out); return; }
            pls.forEach(function (pl) {
                pend++;
                loadPlaylist(pl, function (data) {
                    (data ? data.channels : []).forEach(function (c) {
                        var k = favKey(c);
                        if (keys.indexOf(k) >= 0 && !seen[k]) { seen[k] = 1; c.plid = pl.id; out.push(c); }
                    });
                    if (--pend === 0) cb(out);
                });
            });
        }

        function renderChannels(list, title) {
            info.html('<div class="lmtv__title">' + esc(title) + '</div><div class="lmtv__sub">' + list.length + ' каналов · OK — смотреть, «меню» — в избранное</div>');
            body.empty();
            if (!list.length) { body.append($('<div class="lmtv__empty">Пусто</div>')); return; }
            list.forEach(function (ch) { body.append(card(ch, list)); });
            if (activeNow()) refocus();
        }

        // — карточка группы (плитка) —
        function groupCard(g, onEnter) {
            var logos = '';
            for (var i = 0, n = 0; i < g.list.length && n < 4; i++) {
                if (g.list[i].logo) { logos += '<img loading="lazy" src="' + esc(g.list[i].logo) + '"/>'; n++; }
            }
            var el = $(
                '<div class="lmtv__gcard selector">' +
                    '<div class="lmtv__gname">' + esc(g.name) + '</div>' +
                    '<div class="lmtv__gcount">' + g.list.length + ' каналов</div>' +
                    '<div class="lmtv__glogos">' + logos + '</div>' +
                '</div>'
            );
            el.find('img').each(function () { this.onerror = function () { $(this).remove(); }; });
            el.on('hover:enter', onEnter);
            el.on('hover:focus', function () { last = el[0]; scroll.update(el, true); });
            return el;
        }

        // — строка списка —
        function row(title, sub, onEnter, onLong) {
            var el = $('<div class="lmtv__row selector"><div class="lmtv__row-t">' + esc(title) + '</div><div class="lmtv__row-s">' + esc(sub) + '</div></div>');
            el.on('hover:enter', onEnter);
            el.on('hover:focus', function () { last = el[0]; scroll.update(el, true); });
            if (onLong) el.on('hover:long', onLong);
            return el;
        }

        // — карточка канала —
        function card(ch, siblings) {
            var el = $(
                '<div class="lmtv__card selector">' +
                    '<div class="lmtv__logo">' + (ch.logo ? '<img loading="lazy" src="' + esc(ch.logo) + '"/>' : '<span>' + esc((ch.title || '?').slice(0, 2).toUpperCase()) + '</span>') + '</div>' +
                    '<div class="lmtv__meta">' +
                        '<div class="lmtv__num">' + (ch.chno || '') + (isFav(ch) ? ' ★' : '') + (ch.catchup ? ' ⏪' : '') + '</div>' +
                        '<div class="lmtv__name">' + esc(ch.title) + '</div>' +
                        '<div class="lmtv__epg"></div>' +
                    '</div>' +
                '</div>'
            );
            var img = el.find('img')[0];
            if (img) img.onerror = function () { $(img).replaceWith('<span>' + esc((ch.title || '?').slice(0, 2).toUpperCase()) + '</span>'); };
            el.on('hover:enter', function () { playChannel(ch, siblings); });
            el.on('hover:long', function () {
                var on = toggleFav(ch);
                el.find('.lmtv__num').html((ch.chno || '') + (on ? ' ★' : '') + (ch.catchup ? ' ⏪' : ''));
                noty(on ? '★ В избранном' : 'Убрано из избранного');
            });
            el.on('hover:focus', function () {
                last = el[0]; scroll.update(el, true);
                // Дебаунс: при быстрой прокрутке не дёргаем EPG на каждый фокус.
                if (self._epgT) clearTimeout(self._epgT);
                self._epgT = setTimeout(function () { if (last === el[0]) epgLoad(); }, 350);
                function epgLoad() { epgFor(ch, function (r) {
                    var t = '';
                    if (r && r.now) t = 'Сейчас ' + hm(r.now.s) + ' · ' + r.now.t + (r.next ? '  →  ' + hm(r.next.s) + ' ' + r.next.t : '');
                    el.find('.lmtv__epg').text(t);
                    info.find('.lmtv__sub').text(t || (ch.group || ''));
                }); }
            });
            return el;
        }

        function find(arr, id) { for (var i = 0; i < arr.length; i++) if (arr[i].id === id) return arr[i]; return null; }
        function open(params) { params.component = PLUGIN; Lampa.Activity.push(params); }
        function refocus() {
            Lampa.Controller.collectionSet(scroll.render());
            Lampa.Controller.collectionFocus(last || false, scroll.render());
        }

        // — добавление плейлиста / поиск через клавиатуру Lampa —
        function addPrompt() {
            keyboard('Ссылка на m3u (можно «Название|ссылка»)', '', function (val) {
                if (!val) return;
                var name = '', url = val;
                var bar = val.indexOf('|');
                if (bar > 0) { name = val.slice(0, bar).trim(); url = val.slice(bar + 1).trim(); }
                if (addPlaylist(name, url)) { noty('Плейлист добавлен'); self.build(); refocus(); }
            });
        }
        function searchPrompt() { keyboard('Поиск канала', '', function (val) { if (val) open({ mode: 'channels', src: 'search', query: val, title: 'Поиск' }); }); }
        function searchAll(query, cb) {
            var q = norm(query), out = [], pend = 0, pls = playlists();
            if (!pls.length) { cb(out); return; }
            pls.forEach(function (pl) {
                pend++;
                loadPlaylist(pl, function (data) {
                    (data ? data.channels : []).forEach(function (c) { if (norm(c.title).indexOf(q) >= 0) { c.plid = pl.id; out.push(c); } });
                    if (--pend === 0) cb(out.slice(0, 300));
                });
            });
        }
        function keyboard(placeholder, value, cb) {
            // Штатный ввод Lampa (тот же, что в поиске/настройках).
            try {
                Lampa.Input.edit({ title: placeholder, value: value || '', free: true, nosave: true }, function (v) {
                    cb((v || '').trim());
                    Lampa.Controller.toggle('content');
                });
            } catch (e) { noty('Ввод недоступен на этом устройстве'); }
        }
        function confirmDelete(pl) {
            try {
                Lampa.Select.show({
                    title: 'Удалить плейлист?',
                    items: [{ title: 'Удалить «' + pl.name + '»', del: true }, { title: 'Отмена' }],
                    onSelect: function (a) { if (a.del) { removePlaylist(pl.id); noty('Удалён'); self.build(); refocus(); } },
                    onBack: function () { Lampa.Controller.toggle('content'); }
                });
            } catch (e) { removePlaylist(pl.id); self.build(); refocus(); }
        }

        // — жизненный цикл компонента —
        this.start = function () {
            if (Lampa.Activity.active().activity !== this.activity) return;
            Lampa.Background.immediately && Lampa.Background.immediately('');
            this.build();
            Lampa.Controller.add('content', {
                toggle: function () { refocus(); },
                up: function () { if (Navigator.canmove('up')) Navigator.move('up'); else Lampa.Controller.toggle('head'); },
                down: function () { if (Navigator.canmove('down')) Navigator.move('down'); },
                left: function () { if (Navigator.canmove('left')) Navigator.move('left'); else Lampa.Controller.toggle('menu'); },
                right: function () { if (Navigator.canmove('right')) Navigator.move('right'); },
                back: this.back
            });
            Lampa.Controller.toggle('content');
        };
        this.pause = function () {};
        this.stop = function () {};
        this.back = function () { Lampa.Activity.backward(); };
        this.destroy = function () { scroll.destroy(); html.remove(); body = html = scroll = null; };
    }

    // ── меню + настройки + загрузка ────────────────────────────────────────
    function addSettings() {
        try {
            Lampa.SettingsApi.addComponent({ component: PLUGIN, name: TITLE, icon: '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M21 3H3a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h5l-1 3h10l-1-3h5a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm0 13H3V5h18z"/></svg>' });
            Lampa.SettingsApi.addParam({ component: PLUGIN, param: { name: PLUGIN + '_epg', type: 'trigger', 'default': true }, field: { name: 'Программа на карточках', description: 'Показывать «Сейчас/Далее» при выборе канала' } });
            Lampa.SettingsApi.addParam({ component: PLUGIN, param: { name: PLUGIN + '_adult', type: 'trigger', 'default': false }, field: { name: 'Показывать 18+', description: 'Группы для взрослых скрыты по умолчанию' } });
        } catch (e) {}
    }

    function addMenu() {
        try {
            var icon = '<svg width="27" height="27" viewBox="0 0 24 24" fill="currentColor"><path d="M21 3H3a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h5l-1 3h10l-1-3h5a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm0 13H3V5h18z"/></svg>';
            var item = $('<li class="menu__item selector"><div class="menu__ico">' + icon + '</div><div class="menu__text">' + TITLE + '</div></li>');
            item.on('hover:enter', function () { Lampa.Activity.push({ url: '', title: TITLE, component: PLUGIN, mode: 'home', page: 1 }); });
            $('.menu .menu__list').eq(0).append(item);
        } catch (e) {}
    }

    var booted = false;
    function boot() {
        if (booted) return;
        if (!window.Lampa || !Lampa.Component) { setTimeout(boot, 300); return; }
        booted = true;
        Lampa.Component.add(PLUGIN, component);
        addSettings();
        addStyle();
        // пункт меню — с ретраем: список мог ещё не отрисоваться
        var tries = 0;
        (function tryMenu() {
            if ($('.menu .menu__list').length) { addMenu(); return; }
            if (++tries < 30) setTimeout(tryMenu, 500);
        })();
    }

    function addStyle() {
        var css =
        '.lmtv{padding:1.5em 0 3em}' +
        '.lmtv__info{padding:0 1.5em 1em}' +
        '.lmtv__title{font-size:2em;font-weight:600}' +
        '.lmtv__sub{color:rgba(255,255,255,.65);margin-top:.25em;font-size:1.15em;min-height:1.3em}' +
        '.lmtv__body{display:flex;flex-wrap:wrap;gap:.8em;padding:0 1.5em}' +
        '.lmtv__row{width:100%;padding:1.2em 1.4em;border-radius:.8em;background:rgba(255,255,255,.05);display:flex;justify-content:space-between;align-items:center}' +
        '.lmtv__row-t{font-size:1.35em}.lmtv__row-s{color:rgba(255,255,255,.5);font-size:1.05em;margin-left:1em;text-align:right}' +
        '.lmtv--grid .lmtv__body{gap:1em}' +
        '.lmtv__card{width:15.5em;border-radius:.8em;background:rgba(255,255,255,.05);overflow:hidden;display:flex;flex-direction:column}' +
        '.lmtv__logo{height:7.2em;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,.35)}' +
        '.lmtv__logo img{max-width:80%;max-height:80%;object-fit:contain}' +
        '.lmtv__logo span{font-size:2em;font-weight:700;color:rgba(255,255,255,.7)}' +
        '.lmtv__meta{padding:.6em .7em}' +
        '.lmtv__num{font-size:.95em;color:#F6B44C;font-weight:600;min-height:1em}' +
        '.lmtv__name{font-size:1.2em;margin-top:.1em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
        '.lmtv__epg{font-size:.95em;color:rgba(255,255,255,.55);margin-top:.25em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-height:1em}' +
        '.lmtv__gcard{width:17em;min-height:8.6em;padding:1.1em 1.2em;border-radius:.9em;background:rgba(255,255,255,.06);display:flex;flex-direction:column}' +
        '.lmtv__gname{font-size:1.3em;font-weight:600;line-height:1.2;max-height:2.4em;overflow:hidden}' +
        '.lmtv__gcount{color:rgba(255,255,255,.55);font-size:1em;margin-top:.3em}' +
        '.lmtv__glogos{display:flex;gap:.5em;margin-top:auto;padding-top:.7em;align-items:center}' +
        '.lmtv__glogos img{width:2.2em;height:2.2em;object-fit:contain;background:rgba(0,0,0,.35);border-radius:.4em;padding:.15em}' +
        '.lmtv__gcard.selector.focus{transform:scale(1.04)}' +
        '.lmtv__empty{padding:2em;color:rgba(255,255,255,.5)}' +
        '.lmtv .selector.focus,.lmtv .selector:focus{background:rgba(255,255,255,.16);box-shadow:0 0 0 .18em #F6B44C}' +
        '.lmtv__card.selector.focus{transform:scale(1.04)}';
        try { $('<style>' + css + '</style>').appendTo('head'); } catch (e) {}
    }

    if (window.Lampa && Lampa.Listener) Lampa.Listener.follow('app', function (e) { if (e.type === 'ready') boot(); });
    boot();
})();
