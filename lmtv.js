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
    function groupsOf(channels, plid) {
        var order = [], map = {}, adultCnt = 0;
        var showAdult = S('adult', false) || sessionAdult;
        var hidden = S('hidden_' + (plid || ''), []);
        var pinned = S('pinned_' + (plid || ''), []);
        channels.forEach(function (c) {
            if (ADULT_RE.test(c.group)) { if (!showAdult) { adultCnt++; return; } }
            if (!map[c.group]) { map[c.group] = []; order.push(c.group); }
            map[c.group].push(c);
        });
        var visible = [], hid = 0;
        order.forEach(function (g) { if (hidden.indexOf(g) >= 0) hid++; else visible.push(g); });
        // закреплённые — в начало, сохраняя их взаимный порядок
        visible.sort(function (a, b) {
            var pa = pinned.indexOf(a), pb = pinned.indexOf(b);
            if (pa >= 0 && pb >= 0) return pa - pb;
            if (pa >= 0) return -1;
            if (pb >= 0) return 1;
            return 0;
        });
        return { list: visible.map(function (g) { return { name: g, list: map[g] }; }), hidden: hid, adult: adultCnt };
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

    // Полное расписание канала (для «Программа и архив»).
    var EPGF = {};
    function epgFull(ch, cb) {
        var id = ch.tvgId || idFromUrl(ch.url);
        if (!id) { cb(null); return; }
        var c = EPGF[id];
        if (c && Date.now() - c.ts < 30 * 60e3) { cb(c.p); return; }
        var safe = id.replace(/[^A-Za-z0-9._-]/g, '_');
        httpText(REMOTE_EPG + '/' + safe + '.json', function (body) {
            if (!body) { cb(null); return; }
            try { var p = JSON.parse(body).p || []; EPGF[id] = { ts: Date.now(), p: p }; cb(p); }
            catch (e) { cb(null); }
        });
    }

    var sessionAdult = false;         // 18+ разблокирован PIN-ом до перезапуска
    var plHealth = {};                // plid → true (ок) / false (не отвечает)

    // ── передача канала плееру (archive = {s,e,t} — сразу открыть запись) ──
    function playChannel(ch, siblings, archive) {
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
        if (archive && archive.s) {
            video.archive_start = archive.s;
            video.archive_end = archive.e || (archive.s + 3600);
            video.archive_title = archive.t || '';
        }
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
        // Слой Lampa: скролл получает высоту окна минус шапка — иначе контент
        // просто обрезается и фокус уезжает за экран без прокрутки.
        scroll.render().addClass('layer--wheight').data('mheight', info);
        scroll.minus(info);

        function layerUpdate() {
            try { if (Lampa.Layer && Lampa.Layer.update) Lampa.Layer.update(html); } catch (e) {}
        }

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
                else if (mode === 'sport') buildSport();
                else buildChannels();
            } catch (e) { noty('lmtv: ' + (e && e.message ? e.message : e)); }
            layerUpdate();
        };

        // — экран «Дом»: продолжить, эфир, избранное, плейлисты, управление —
        function buildHome() {
            html.removeClass('lmtv--grid');
            info.html('<div class="lmtv__title">' + esc(TITLE) + '</div><div class="lmtv__sub">Выбери источник или добавь плейлист</div>');
            var items = [];
            var lastCh = recent()[0];
            if (lastCh) items.push(row('▶ Продолжить · ' + lastCh.title, 'последний канал, одно нажатие', function () {
                playChannel(lastCh, recent());
            }));
            items.push(row('📡 Сейчас в эфире', 'что идёт на любимых каналах', function () { open({ mode: 'channels', src: 'now', title: 'Сейчас в эфире' }); }));
            items.push(row('🏆 Спорт', 'ближайшие трансляции со спорт-каналов', function () { open({ mode: 'sport', title: '🏆 Спорт' }); }));
            items.push(row('★ Избранное', favs().length + ' каналов', function () { open({ mode: 'channels', src: 'fav', title: 'Избранное' }); }));
            items.push(row('🕓 Недавние', recent().length + ' каналов', function () { open({ mode: 'channels', src: 'recent', title: 'Недавние' }); }));
            items.push(row('🔍 Поиск канала', 'по всем плейлистам', function () { searchPrompt(); }));
            var pls = playlists();
            var plRows = {};
            pls.forEach(function (pl) {
                var sub = pl.url.replace(/^https?:\/\//, '').slice(0, 46);
                if (plHealth[pl.id] === false) sub = '⚠ не отвечает · ' + sub;
                var el = row('📺 ' + pl.name, sub, function () {
                    open({ mode: 'groups', plid: pl.id, title: pl.name, url: pl.url });
                }, function () { plMenu(pl); });
                plRows[pl.id] = el;
                items.push(el);
            });
            items.push(row('＋ Добавить плейлист', 'ссылка m3u или портал Xtream', function () { addPrompt(); }));
            items.push(row('⇄ Экспорт / импорт', 'перенос плейлистов и избранного', function () { exportImport(); }));
            items.push(row('⚙ Настройки', 'EPG, сортировка, PIN 18+, автозапуск', function () { try { Lampa.Settings.create(PLUGIN); } catch (e) { try { Lampa.Settings.create('main'); } catch (e2) {} } }));
            items.forEach(function (el) { body.append(el); });
            // фоновая проверка здоровья плейлистов (через кэш — дёшево)
            pls.forEach(function (pl) {
                loadPlaylist(pl, function (data) {
                    plHealth[pl.id] = !!data;
                    var el = plRows[pl.id];
                    if (el && !data) el.find('.lmtv__row-s').text('⚠ не отвечает');
                });
            });
        }

        // — меню плейлиста (долгое нажатие) —
        function plMenu(pl) {
            try {
                Lampa.Select.show({
                    title: pl.name,
                    items: [
                        { title: '↻ Обновить сейчас', act: 'refresh' },
                        { title: '✎ Переименовать', act: 'rename' },
                        { title: '🗑 Удалить', act: 'del' },
                        { title: 'Отмена', act: '' }
                    ],
                    onSelect: function (a) {
                        Lampa.Controller.toggle('content');
                        if (a.act === 'refresh') {
                            try { delete PARSED[pl.id]; } catch (e) {}
                            loader(true);
                            loadPlaylist(pl, function (data) {
                                loader(false);
                                plHealth[pl.id] = !!data;
                                noty(data ? 'Обновлён: ' + data.channels.length + ' каналов' : '⚠ Плейлист не отвечает');
                                self.build(); refocus();
                            });
                        }
                        if (a.act === 'rename') keyboard('Название плейлиста', pl.name, function (v) {
                            if (!v) return;
                            var a2 = playlists();
                            for (var i = 0; i < a2.length; i++) if (a2[i].id === pl.id) a2[i].name = v.slice(0, 40);
                            savePlaylists(a2); self.build(); refocus();
                        });
                        if (a.act === 'del') confirmDelete(pl);
                    },
                    onBack: function () { Lampa.Controller.toggle('content'); }
                });
            } catch (e) { confirmDelete(pl); }
        }

        // — экспорт / импорт (плейлисты + избранное) строкой —
        function exportImport() {
            try {
                Lampa.Select.show({
                    title: 'Перенос настроек',
                    items: [{ title: '⬆ Экспорт (показать строку)', act: 'ex' }, { title: '⬇ Импорт (вставить строку)', act: 'im' }, { title: 'Отмена', act: '' }],
                    onSelect: function (a) {
                        Lampa.Controller.toggle('content');
                        if (a.act === 'ex') {
                            var data = JSON.stringify({ p: playlists(), f: favs() });
                            keyboard('Скопируй эту строку', data, function () {});
                        }
                        if (a.act === 'im') keyboard('Вставь строку экспорта', '', function (v) {
                            try {
                                var d = JSON.parse(v);
                                var cur = playlists(), urls = {};
                                cur.forEach(function (x) { urls[x.url] = 1; });
                                (d.p || []).forEach(function (x) { if (x && x.url && !urls[x.url]) { x.id = uid() + cur.length; cur.push(x); } });
                                savePlaylists(cur);
                                var fv = favs();
                                (d.f || []).forEach(function (k) { if (fv.indexOf(k) < 0) fv.push(k); });
                                Sset('fav', fv);
                                noty('Импортировано: плейлистов ' + cur.length + ', избранных ' + fv.length);
                                self.build(); refocus();
                            } catch (e) { noty('Не удалось разобрать строку'); }
                        });
                    },
                    onBack: function () { Lampa.Controller.toggle('content'); }
                });
            } catch (e) {}
        }

        function buildGroups() {
            var pl = find(playlists(), object.plid);
            info.html('<div class="lmtv__title">' + esc(object.title || 'Плейлист') + '</div><div class="lmtv__sub">Загрузка…</div>');
            if (!pl) { info.find('.lmtv__sub').text('Плейлист не найден'); return; }
            loader(true);
            loadPlaylist(pl, function (data) {
                loader(false);
                if (!data) { info.find('.lmtv__sub').text('Не удалось загрузить m3u'); return; }
                var gs = groupsOf(data.channels, pl.id);
                info.find('.lmtv__sub').text(data.channels.length + ' каналов · ' + gs.list.length + ' групп · долгое OK на группе — скрыть/закрепить');
                body.empty();
                // виртуальная группа: каналы с архивом
                var withArc = data.channels.filter(function (c) { return !!c.catchup && (S('adult', false) || sessionAdult || !ADULT_RE.test(c.group)); });
                if (withArc.length) body.append(groupCard({ name: '⏪ С архивом', list: withArc }, function () {
                    open({ mode: 'channels', plid: pl.id, src: 'arc', title: '⏪ С архивом', url: pl.url });
                }));
                gs.list.forEach(function (g) {
                    var el = groupCard(g, function () {
                        open({ mode: 'channels', plid: pl.id, group: g.name, title: g.name, url: pl.url });
                    });
                    el.on('hover:long', function () { groupMenu(pl, g.name); });
                    body.append(el);
                });
                if (gs.hidden) body.append(groupCard({ name: '🚫 Скрытые (' + gs.hidden + ')', list: [] }, function () { unhideMenu(pl); }));
                if (gs.adult) body.append(groupCard({ name: '🔒 Взрослые (' + gs.adult + ')', list: [] }, function () { pinPrompt(pl); }));
                layerUpdate();
                if (activeNow()) refocus();
            });
        }

        function buildChannels() {
            html.addClass('lmtv--grid');
            var title = object.title || 'Каналы';
            if (object.src === 'fav') { collectFav(function (l) { renderChannels(l, title); }); return; }
            if (object.src === 'recent') { renderChannels(recent(), title); return; }
            if (object.src === 'now') {
                collectFav(function (l) {
                    if (!l.length) l = recent();
                    renderChannels(l, title, true);
                });
                return;
            }
            if (object.src === 'search') { searchAll(object.query, function (l) { renderChannels(l, title + ' · «' + object.query + '»'); }); return; }
            // группа/архив: берём из кэша плейлиста (loadPlaylist кэширует на 6 часов)
            var pl = find(playlists(), object.plid);
            if (!pl) { renderChannels([], title); return; }
            loader(true);
            loadPlaylist(pl, function (data) {
                loader(false);
                var all = data ? data.channels : [];
                var l = object.src === 'arc'
                    ? all.filter(function (c) { return !!c.catchup && (S('adult', false) || sessionAdult || !ADULT_RE.test(c.group)); })
                    : all.filter(function (c) { return c.group === object.group; });
                renderChannels(l, title);
            });
        }

        var SPORT_RE = /спорт|sport|футбол|матч|match|eurosport|setanta|окко\s*спорт|виасат\s*спорт|нтв.?плюс|киберспорт|бокс|ufc|формула|f1/i;
        function buildSport() {
            html.addClass('lmtv--grid');
            info.html('<div class="lmtv__title">🏆 Спорт</div><div class="lmtv__sub">Собираю трансляции со спорт-каналов…</div>');
            body.empty();
            // все спорт-каналы из всех плейлистов
            var pls = playlists(), chans = [], pend = 0;
            if (!pls.length) { info.find('.lmtv__sub').text('Нет плейлистов'); return; }
            loader(true);
            pls.forEach(function (pl) {
                pend++;
                loadPlaylist(pl, function (data) {
                    (data ? data.channels : []).forEach(function (c) {
                        if (SPORT_RE.test(c.group) || SPORT_RE.test(c.title)) { c.plid = pl.id; chans.push(c); }
                    });
                    if (--pend === 0) gatherSport(chans);
                });
            });
        }
        function gatherSport(chans) {
            var now = Date.now() / 1000, events = [], pend = 0;
            if (!chans.length) { loader(false); info.find('.lmtv__sub').text('Спорт-каналы не найдены'); return; }
            // ограничим, чтобы не долбить EPG: до 40 каналов
            chans = chans.slice(0, 40);
            chans.forEach(function (ch) {
                pend++;
                epgFull(ch, function (progs) {
                    (progs || []).forEach(function (p) {
                        if (p.e > now - 3600 && p.s < now + 36 * 3600) {   // сейчас + ближайшие сутки-полтора
                            events.push({ ch: ch, t: p.t, s: p.s, e: p.e, live: p.s <= now && p.e > now, past: p.e <= now });
                        }
                    });
                    if (--pend === 0) showSport(events, chans);
                });
            });
        }
        function showSport(events, chans) {
            loader(false);
            events.sort(function (a, b) { return a.s - b.s; });
            // сначала идущие сейчас, потом будущие по времени
            events.sort(function (a, b) { return (b.live ? 1 : 0) - (a.live ? 1 : 0); });
            events = events.slice(0, 60);
            info.find('.lmtv__sub').text(events.length ? (events.length + ' трансляций · OK — смотреть') : 'Сейчас и в ближайшее время трансляций нет');
            body.empty();
            if (!events.length) { body.append($('<div class="lmtv__empty">Нет данных программы по спорт-каналам</div>')); return; }
            events.forEach(function (ev) { body.append(sportCard(ev, chans)); });
            layerUpdate();
            if (activeNow()) refocus();
        }
        function when(ev) {
            if (ev.live) return '● В ЭФИРЕ';
            var d = new Date(ev.s * 1000), now = new Date();
            var t = ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
            if (d.toDateString() === now.toDateString()) return 'Сегодня ' + t;
            var mon = ['янв','фев','мар','апр','мая','июн','июл','авг','сен','окт','ноя','дек'];
            return d.getDate() + ' ' + mon[d.getMonth()] + ' ' + t;
        }
        function sportCard(ev, chans) {
            var ch = ev.ch;
            var el = $(
                '<div class="lmtv__card lmtv__sport selector' + (ev.live ? ' lmtv__sport--live' : '') + '">' +
                    '<div class="lmtv__logo">' + (ch.logo ? '<img loading="lazy" src="' + esc(ch.logo) + '"/>' : '<span>' + esc((ch.title || '?').slice(0, 2).toUpperCase()) + '</span>') + '</div>' +
                    '<div class="lmtv__meta">' +
                        '<div class="lmtv__num">' + esc(ch.title) + '</div>' +
                        '<div class="lmtv__name">' + esc(ev.t) + '</div>' +
                        '<div class="lmtv__epg lmtv__when">' + when(ev) + '</div>' +
                    '</div>' +
                '</div>'
            );
            var img = el.find('img')[0];
            if (img) img.onerror = function () { $(img).replaceWith('<span>' + esc((ch.title || '?').slice(0, 2).toUpperCase()) + '</span>'); };
            el.on('hover:enter', function () {
                if (ev.past && ch.catchup) playChannel(ch, chans, { s: ev.s, e: ev.e, t: ev.t });
                else playChannel(ch, chans);
            });
            el.on('hover:focus', function () { last = el[0]; scroll.update(el, true); });
            return el;
        }

        function sorted(list) {
            var m = S('sort', 'default');
            if (m === 'abc') return list.slice().sort(function (a, b) { return ('' + a.title).localeCompare('' + b.title, 'ru'); });
            if (m === 'num') return list.slice().sort(function (a, b) { return (a.chno || 9e9) - (b.chno || 9e9); });
            return list;
        }

        // — меню группы: скрыть / закрепить —
        function groupMenu(pl, name) {
            try {
                var pinned = S('pinned_' + pl.id, []);
                var isPin = pinned.indexOf(name) >= 0;
                Lampa.Select.show({
                    title: name,
                    items: [
                        { title: isPin ? '📌 Открепить' : '📌 Закрепить в начало', act: 'pin' },
                        { title: '🚫 Скрыть группу', act: 'hide' },
                        { title: 'Отмена', act: '' }
                    ],
                    onSelect: function (a) {
                        Lampa.Controller.toggle('content');
                        if (a.act === 'pin') {
                            if (isPin) pinned.splice(pinned.indexOf(name), 1); else pinned.unshift(name);
                            Sset('pinned_' + pl.id, pinned); self.build(); refocus();
                        }
                        if (a.act === 'hide') {
                            var h = S('hidden_' + pl.id, []); h.push(name);
                            Sset('hidden_' + pl.id, h); self.build(); refocus();
                        }
                    },
                    onBack: function () { Lampa.Controller.toggle('content'); }
                });
            } catch (e) {}
        }
        function unhideMenu(pl) {
            var h = S('hidden_' + pl.id, []);
            try {
                Lampa.Select.show({
                    title: 'Показать группу',
                    items: h.map(function (n) { return { title: n, name: n }; }).concat([{ title: '↩ Показать все', all: true }]),
                    onSelect: function (a) {
                        Lampa.Controller.toggle('content');
                        Sset('hidden_' + pl.id, a.all ? [] : h.filter(function (n) { return n !== a.name; }));
                        self.build(); refocus();
                    },
                    onBack: function () { Lampa.Controller.toggle('content'); }
                });
            } catch (e) {}
        }
        function pinPrompt(pl) {
            keyboard('PIN (по умолчанию 0000)', '', function (v) {
                if (v === ('' + S('pin', '0000'))) { sessionAdult = true; noty('18+ разблокировано до перезапуска'); self.build(); refocus(); }
                else noty('Неверный PIN');
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

        function renderChannels(list, title, eagerEpg) {
            list = sorted(list);
            info.html('<div class="lmtv__title">' + esc(title) + '</div><div class="lmtv__sub">' + list.length + ' каналов · OK — смотреть · долгое OK — меню · цифры — номер</div>');
            body.empty();
            numMap = {};
            if (!list.length) { body.append($('<div class="lmtv__empty">Пусто</div>')); return; }
            list.forEach(function (ch) {
                var el = card(ch, list);
                if (ch.chno) numMap[ch.chno] = el;
                body.append(el);
            });
            // Программа «Сейчас» на карточках — порциями со сдвигом (плавно, без
            // фриза): сразу грузим верх списка, остальное подтянется по фокусу.
            var eagerN = eagerEpg ? 30 : 24;
            list.slice(0, eagerN).forEach(function (ch, i) {
                setTimeout(function () {
                    epgFor(ch, function (r) {
                        var el = body.children().eq(i);
                        el.find('.lmtv__epg').text(r && r.now ? ('● ' + r.now.t) : '');
                    });
                }, i * 80);
            });
            layerUpdate();
            if (activeNow()) refocus();
        }

        // — переход по номеру канала с цифровых кнопок —
        var numMap = {}, zapBuf = '', zapT = null;
        function onDigit(d) {
            zapBuf += d;
            info.find('.lmtv__sub').text('Канал № ' + zapBuf + '…');
            if (zapT) clearTimeout(zapT);
            zapT = setTimeout(function () {
                var el = numMap[parseInt(zapBuf, 10)];
                zapBuf = '';
                if (el) {
                    last = el[0];
                    Lampa.Controller.collectionFocus(el[0], scroll.render());
                    scroll.update(el, true);
                } else info.find('.lmtv__sub').text('Нет канала с таким номером');
            }, 900);
        }
        this._digits = function (e) {
            if (mode !== 'channels') return;
            if (!activeNow()) return;
            var k = e.keyCode;
            var d = -1;
            if (k >= 48 && k <= 57) d = k - 48;
            if (k >= 96 && k <= 105) d = k - 96;
            if (d >= 0) onDigit('' + d);
        };

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
            el.on('hover:long', function () { cardMenu(ch, el, siblings); });
            el.on('hover:focus', function () {
                last = el[0]; scroll.update(el, true);
                // Дебаунс: при быстрой прокрутке не дёргаем EPG на каждый фокус.
                if (self._epgT) clearTimeout(self._epgT);
                self._epgT = setTimeout(function () { if (last === el[0]) epgLoad(); }, 350);
                function epgLoad() {
                    // постер-фон, как в остальной Lampa
                    try {
                        if (ch.logo) { if (Lampa.Background.change) Lampa.Background.change(ch.logo); else if (Lampa.Background.immediately) Lampa.Background.immediately(ch.logo); }
                    } catch (e) {}
                    epgFor(ch, function (r) {
                        var t = '';
                        if (r && r.now) t = 'Сейчас ' + hm(r.now.s) + ' · ' + r.now.t + (r.next ? '  →  ' + hm(r.next.s) + ' ' + r.next.t : '');
                        el.find('.lmtv__epg').text(t);
                        info.find('.lmtv__sub').text(t || (ch.group || ''));
                    });
                }
            });
            return el;
        }

        // — меню канала: избранное + программа с архивом —
        function cardMenu(ch, el, siblings) {
            try {
                Lampa.Select.show({
                    title: ch.title,
                    items: [
                        { title: isFav(ch) ? '★ Убрать из избранного' : '☆ В избранное', act: 'fav' },
                        { title: '📅 Программа и архив', act: 'epg' },
                        { title: 'Отмена', act: '' }
                    ],
                    onSelect: function (a) {
                        Lampa.Controller.toggle('content');
                        if (a.act === 'fav') {
                            var on = toggleFav(ch);
                            el.find('.lmtv__num').html((ch.chno || '') + (on ? ' ★' : '') + (ch.catchup ? ' ⏪' : ''));
                            noty(on ? '★ В избранном' : 'Убрано из избранного');
                        }
                        if (a.act === 'epg') programmeMenu(ch, siblings);
                    },
                    onBack: function () { Lampa.Controller.toggle('content'); }
                });
            } catch (e) {}
        }

        // — расписание канала: прошлое кликабельно (архив), будущее — просмотр —
        function programmeMenu(ch, siblings) {
            loader(true);
            epgFull(ch, function (p) {
                loader(false);
                if (!p || !p.length) { noty('Программа для канала не найдена'); return; }
                var now = Date.now() / 1000, items = [];
                p.forEach(function (x) {
                    if (x.e <= now) items.push({ title: '⏪ ' + hm(x.s) + '  ' + x.t, prog: x });
                    else if (x.s <= now) items.push({ title: '● СЕЙЧАС  ' + hm(x.s) + '  ' + x.t, live: true });
                    else items.push({ title: '· ' + hm(x.s) + '  ' + x.t, future: true });
                });
                // прошлое ближе к «сейчас» — сверху видимой зоны: показываем хвостом
                if (items.length > 40) items = items.slice(items.length - 40);
                try {
                    Lampa.Select.show({
                        title: ch.title + ' · ⏪ архив',
                        items: items,
                        onSelect: function (a) {
                            Lampa.Controller.toggle('content');
                            if (a.prog) playChannel(ch, siblings, { s: a.prog.s, e: a.prog.e, t: a.prog.t });
                            else if (a.live) playChannel(ch, siblings);
                            else noty('Эта передача ещё не вышла');
                        },
                        onBack: function () { Lampa.Controller.toggle('content'); }
                    });
                } catch (e) {}
            });
        }

        function find(arr, id) { for (var i = 0; i < arr.length; i++) if (arr[i].id === id) return arr[i]; return null; }
        function open(params) { params.component = PLUGIN; Lampa.Activity.push(params); }
        function refocus() {
            Lampa.Controller.collectionSet(scroll.render());
            Lampa.Controller.collectionFocus(last || false, scroll.render());
        }

        // — добавление плейлиста / поиск через клавиатуру Lampa —
        function addPrompt() {
            try {
                Lampa.Select.show({
                    title: 'Добавить источник',
                    items: [{ title: '🔗 Ссылка на m3u', act: 'm3u' }, { title: '🌐 Портал Xtream Codes', act: 'xc' }, { title: 'Отмена', act: '' }],
                    onSelect: function (a) {
                        Lampa.Controller.toggle('content');
                        if (a.act === 'm3u') addM3u();
                        if (a.act === 'xc') addXtream();
                    },
                    onBack: function () { Lampa.Controller.toggle('content'); }
                });
            } catch (e) { addM3u(); }
        }
        function addM3u() {
            keyboard('Ссылка на m3u (можно «Название|ссылка»)', '', function (val) {
                if (!val) return;
                var name = '', url = val;
                var bar = val.indexOf('|');
                if (bar > 0) { name = val.slice(0, bar).trim(); url = val.slice(bar + 1).trim(); }
                if (addPlaylist(name, url)) { noty('Плейлист добавлен'); self.build(); refocus(); }
            });
        }
        // Xtream Codes: сервер + логин + пароль → стандартный get.php m3u
        function addXtream() {
            keyboard('Сервер (например http://host:8080)', '', function (host) {
                if (!host) return;
                if (!/^https?:\/\//i.test(host)) host = 'http://' + host;
                host = host.replace(/\/+$/, '');
                keyboard('Логин', '', function (user) {
                    if (!user) return;
                    keyboard('Пароль', '', function (pass) {
                        if (!pass) return;
                        var url = host + '/get.php?username=' + encodeURIComponent(user) + '&password=' + encodeURIComponent(pass) + '&type=m3u_plus&output=ts';
                        if (addPlaylist('Xtream · ' + host.replace(/^https?:\/\//, ''), url)) { noty('Портал добавлен'); self.build(); refocus(); }
                    });
                });
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
            // автозапуск последнего канала (настройка), один раз за сессию
            if (mode === 'home' && S('autoplay', false) && !window.__lmtvAutoplayed && recent().length) {
                window.__lmtvAutoplayed = true;
                var lc = recent()[0];
                setTimeout(function () { playChannel(lc, recent()); }, 400);
            }
            $(document).on('keydown.lmtv', this._digits);
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
        this.destroy = function () { $(document).off('keydown.lmtv', this._digits); scroll.destroy(); html.remove(); body = html = scroll = null; };
    }

    // ── меню + настройки + загрузка ────────────────────────────────────────
    function addSettings() {
        try {
            Lampa.SettingsApi.addComponent({ component: PLUGIN, name: TITLE, icon: '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M21 3H3a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h5l-1 3h10l-1-3h5a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm0 13H3V5h18z"/></svg>' });
            Lampa.SettingsApi.addParam({ component: PLUGIN, param: { name: PLUGIN + '_epg', type: 'trigger', 'default': true }, field: { name: 'Программа на карточках', description: 'Показывать «Сейчас/Далее» при выборе канала' } });
            Lampa.SettingsApi.addParam({ component: PLUGIN, param: { name: PLUGIN + '_adult', type: 'trigger', 'default': false }, field: { name: 'Показывать 18+ без PIN', description: 'Иначе группы 18+ открываются по PIN с плитки «Взрослые»' } });
            Lampa.SettingsApi.addParam({ component: PLUGIN, param: { name: PLUGIN + '_pin', type: 'input', 'default': '0000', values: '' }, field: { name: 'PIN для 18+', description: 'Код для разблокировки взрослых групп' } });
            Lampa.SettingsApi.addParam({ component: PLUGIN, param: { name: PLUGIN + '_autoplay', type: 'trigger', 'default': false }, field: { name: 'Автозапуск последнего канала', description: 'Открыл раздел — сразу играет то, что смотрел' } });
            Lampa.SettingsApi.addParam({ component: PLUGIN, param: { name: PLUGIN + '_sort', type: 'select', values: { 'default': 'Как в плейлисте', 'abc': 'По алфавиту', 'num': 'По номеру' }, 'default': 'default' }, field: { name: 'Сортировка каналов', description: 'Порядок каналов внутри группы' } });
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
        registerSource();
        addSettings();
        addStyle();
        // пункт меню — с ретраем: список мог ещё не отрисоваться
        var tries = 0;
        (function tryMenu() {
            if ($('.menu .menu__list').length) { addMenu(); return; }
            if (++tries < 30) setTimeout(tryMenu, 500);
        })();
    }

    // Регистрация IPTV как источника главного экрана: при выборе в «Выбрать»
    // сразу открывается наш каталог каналов (по образцу nmprs — main редиректит).
    function registerSource() {
        try {
            if (!(window.Lampa && Lampa.Api && Lampa.Api.sources)) return;
            if (Lampa.Api.sources[PLUGIN]) return;
            var base = Lampa.Api.sources.tmdb || Lampa.Api.sources['tmdb'] || {};
            var src = {};
            for (var k in base) { try { src[k] = base[k]; } catch (e) {} }
            // main обязан вернуть массив; затем, если выбран именно IPTV — уходим в каталог.
            src.main = function (params, oncomplete) {
                if (typeof oncomplete === 'function') oncomplete([]);
                try { if (Lampa.Storage.get('source', 'tmdb') !== PLUGIN) return; } catch (e) { return; }
                setTimeout(function () {
                    try { Lampa.Activity.replace({ url: '', title: TITLE, component: PLUGIN, mode: 'home', page: 1 }); } catch (e) {}
                }, 0);
            };
            Lampa.Api.sources[PLUGIN] = src;
            // добавить в список выбора источника (дефолт не меняем)
            try {
                var opts = {}, cur = (Lampa.Params.values && Lampa.Params.values.source) ? Lampa.Params.values.source : {};
                for (var o in cur) opts[o] = cur[o];
                opts[PLUGIN] = TITLE;
                Lampa.Params.select('source', opts, 'tmdb');
            } catch (e) {}
            try { if (Lampa.Params.values && Lampa.Params.values.start_page) Lampa.Params.values.start_page[PLUGIN] = TITLE; } catch (e) {}
            // при переключении источника на/с IPTV — пересобрать главную
            if (!window.__lmtv_source_watch) {
                window.__lmtv_source_watch = true;
                var origSet = Lampa.Storage.set;
                Lampa.Storage.set = function (key, value) {
                    var r = origSet.apply(this, arguments);
                    try {
                        if (key === 'source') {
                            var act = Lampa.Activity.active && Lampa.Activity.active();
                            if (act && act.component === 'main') {
                                if (value === PLUGIN) Lampa.Activity.replace({ url: '', title: TITLE, component: PLUGIN, mode: 'home', page: 1 });
                                else Lampa.Activity.replace({ component: 'main' });
                            }
                        }
                    } catch (e) {}
                    return r;
                };
            }
        } catch (e) {}
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
        '.lmtv__card{width:17em;border-radius:.9em;background:rgba(255,255,255,.05);overflow:hidden;display:flex;flex-direction:column}' +
        '.lmtv__logo{height:8.4em;display:flex;align-items:center;justify-content:center;background:linear-gradient(160deg,#1b2330,#0d1117)}' +
        '.lmtv__logo img{max-width:80%;max-height:80%;object-fit:contain}' +
        '.lmtv__logo span{font-size:2em;font-weight:700;color:rgba(255,255,255,.7)}' +
        '.lmtv__meta{padding:.6em .7em}' +
        '.lmtv__num{font-size:.95em;color:#F6B44C;font-weight:600;min-height:1em}' +
        '.lmtv__name{font-size:1.2em;margin-top:.1em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
        '.lmtv__epg{font-size:.95em;color:#7fd6a0;margin-top:.3em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-height:1.2em}' +
        '.lmtv__gcard{width:17em;min-height:8.6em;padding:1.1em 1.2em;border-radius:.9em;background:rgba(255,255,255,.06);display:flex;flex-direction:column}' +
        '.lmtv__gname{font-size:1.3em;font-weight:600;line-height:1.2;max-height:2.4em;overflow:hidden}' +
        '.lmtv__gcount{color:rgba(255,255,255,.55);font-size:1em;margin-top:.3em}' +
        '.lmtv__glogos{display:flex;gap:.5em;margin-top:auto;padding-top:.7em;align-items:center}' +
        '.lmtv__glogos img{width:2.2em;height:2.2em;object-fit:contain;background:rgba(0,0,0,.35);border-radius:.4em;padding:.15em}' +
        '.lmtv__gcard.selector.focus{transform:scale(1.04)}' +
        '.lmtv__when{color:#f6b44c;font-weight:600}' +
        '.lmtv__sport--live .lmtv__when{color:#ff5468}' +
        '.lmtv__sport--live{box-shadow:inset 0 0 0 .12em rgba(255,84,104,.6)}' +
        '.lmtv__sport .lmtv__name{white-space:normal;max-height:2.6em;overflow:hidden;line-height:1.25}' +
        '.lmtv__empty{padding:2em;color:rgba(255,255,255,.5)}' +
        '.lmtv .selector.focus,.lmtv .selector:focus{background:rgba(255,255,255,.16);box-shadow:0 0 0 .18em #F6B44C}' +
        '.lmtv__card.selector.focus{transform:scale(1.04)}';
        try { $('<style>' + css + '</style>').appendTo('head'); } catch (e) {}
    }

    if (window.Lampa && Lampa.Listener) Lampa.Listener.follow('app', function (e) { if (e.type === 'ready') boot(); });
    boot();
})();
