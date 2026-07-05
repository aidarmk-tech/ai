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
                 act.object && act.object.movie, act.object && act.object.card,
                 act.data && act.data.movie, act.data && act.data.card,
                 act.params && act.params.movie, act.params && act.params.card,
                 act.params && act.params.object,
                 act.params && act.params.object && act.params.object.movie].forEach(function(x){ candidates.push(x); });
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

        // Выбираем кандидата с наибольшим баллом (score); балансерные карточки получают штраф
        var best = null, bestScore = -1;
        candidates.forEach(function(c) {
            var u = unwrap(c);
            if (!u) return;
            var s = cardScore(u);
            if (s > bestScore) { best = u; bestScore = s; }
        });
        if (best && bestScore >= 0) return best;
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
        // (IPTV channel lists are now kept — the player uses them as a channel list)
        return { list: list, pos: pos };
    }

    function isIptvList(list) {
        return !!(list && list[0] && (list[0].tv || list[0].iptv));
    }

    // TMDB-карточка ИМЕННО открытой активности (не из стека — там мог остаться
    // старый фильм). Есть карточка → пользователь запускает кино, а не ТВ-канал.
    function activeFilmCard() {
        try {
            var act = Lampa.Activity.active && Lampa.Activity.active();
            if (!act) return null;
            var slots = [act.movie, act.card,
                         act.object && act.object.movie, act.object && act.object.card,
                         act.params && act.params.movie, act.params && act.params.card];
            for (var i = 0; i < slots.length; i++) {
                var m = slots[i];
                if (m && (m.id || m.tmdb_id) && isTmdbCard(m)) return m;
            }
        } catch (_) {}
        return null;
    }

    // m3u-источник IPTV (в нём url-tvg для XMLTV и tvg-id каналов) — для EPG.
    function iptvSource() {
        try {
            var act = Lampa.Activity.active && Lampa.Activity.active();
            if (act && typeof act.url === 'string' && /^https?:/.test(act.url)) return act.url;
        } catch (_) {}
        return '';
    }

    // Группировка EPG-заголовков по каналам (по имени карточки-предка) — на
    // странице категории в DOM отрисованы блоки многих каналов сразу.
    function groupEpgByCard(titles) {
        function clean(e) { return e ? (e.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
        function norm(s) { return ('' + (s || '')).toLowerCase().replace(/[^a-zа-я0-9]/g, ''); }
        function cardOf(el) {
            var best = el.parentElement || el, n = best;
            for (var k = 0; n && k < 10; k++, n = n.parentElement) {
                if (n.querySelectorAll && n.querySelectorAll('.js-epgTitle').length === 1) best = n;
                else break;
            }
            return best;
        }
        function nameOf(cardEl) {
            try {
                var cl = cardEl.cloneNode(true);
                var eps = cl.querySelectorAll('[class*="epg" i], .js-epgTitle, .js-epgTime, .js-epgDesc, .js-epgProgress');
                for (var i = eps.length - 1; i >= 0; i--) if (eps[i].parentNode) eps[i].parentNode.removeChild(eps[i]);
                return clean(cl).replace(/\b\d{1,2}:\d{2}\b/g, '').replace(/\s+/g, ' ').trim().slice(0, 60);
            } catch (_) { return ''; }
        }
        var groups = [], byName = {};
        for (var i2 = 0; i2 < titles.length; i2++) {
            var nm = norm(nameOf(cardOf(titles[i2])));
            if (!nm) continue;
            var g = byName[nm];
            if (!g) { g = { name: nm, titles: [] }; byName[nm] = g; groups.push(g); }
            g.titles.push(titles[i2]);
        }
        return groups;
    }

    // Считываем программу прямо из отрисованного Lampa EPG-блока запускаемого
    // канала: расписание — это список строк `.js-epgTitle` со временем. Берём
    // «Сейчас» (строка с активным прогрессом, иначе первая) и «Далее» — следующую,
    // каждую со временем начала. Готовый текст уезжает в плеер (без XMLTV).
    function scrapeEpg(channelName) {
        try {
            function clean(e) { return e ? (e.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
            // строка-расписание вокруг заголовка программы (где есть и время, и прогресс)
            function rowOf(titleEl) {
                var n = titleEl;
                for (var k = 0; k < 4 && n.parentElement; k++) {
                    n = n.parentElement;
                    if (n.querySelector('.js-epgTime') || n.querySelector('.js-epgProgress') ||
                        /\b\d{1,2}:\d{2}\b/.test(n.textContent || '')) return n;
                }
                return titleEl.parentElement || titleEl;
            }
            function timeOf(row) {
                var t = row.querySelector && row.querySelector('.js-epgTime');
                var v = t ? clean(t) : (row.textContent || '');
                var m = v.match(/\b\d{1,2}:\d{2}\b/);
                return m ? m[0] : '';
            }
            // активный прогресс (0<width<100%) — признак текущей передачи
            function activePct(row) {
                var p = row.querySelector && row.querySelector('.js-epgProgress');
                if (!p) return NaN;
                var w = (p.getAttribute('style') || '') + ' ' + ((p.style && p.style.width) || '');
                var m = w.match(/width\s*:\s*([\d.]+)%/i) || w.match(/([\d.]+)%/);
                return m ? parseFloat(m[1]) : NaN;
            }

            var titles = Array.prototype.slice.call(document.querySelectorAll('.js-epgTitle'));
            // На странице категории в DOM блоки ВСЕХ каналов — не берём чужое
            // расписание: группируем заголовки по карточкам и выбираем карточку
            // запускаемого канала по имени. Иначе у любого канала оказывалась
            // программа первого канала категории.
            if (titles.length && channelName) {
                var grouped = groupEpgByCard(titles);
                if (grouped.length > 1) {
                    var normQ = ('' + channelName).toLowerCase().replace(/[^a-zа-я0-9]/g, '');
                    var best = null, bestDiff = 1e9;
                    for (var gi = 0; gi < grouped.length; gi++) {
                        var gn = grouped[gi].name;
                        if (!gn || !normQ) continue;
                        if (gn === normQ || gn.indexOf(normQ) >= 0 || normQ.indexOf(gn) >= 0) {
                            var diff = Math.abs(gn.length - normQ.length);
                            if (diff < bestDiff) { bestDiff = diff; best = grouped[gi]; }
                        }
                    }
                    if (best) titles = best.titles;
                    else return null;   // канал не нашли — лучше ничего, чем чужая программа
                }
            }
            if (titles.length) {
                var nowIdx = -1;
                for (var i = 0; i < titles.length; i++) {
                    var pct = activePct(rowOf(titles[i]));
                    if (!isNaN(pct) && pct > 0 && pct < 100) { nowIdx = i; break; }
                }
                if (nowIdx < 0) nowIdx = 0;   // Lampa обычно режет прошлое — первая = текущая
                function entry(idx) {
                    if (idx < 0 || idx >= titles.length) return null;
                    var r = rowOf(titles[idx]);
                    var t = clean(titles[idx]);
                    return t ? { t: t, tm: timeOf(r), row: r } : null;
                }
                var now = entry(nowIdx), nxt = entry(nowIdx + 1);
                if (now) {
                    var s = 'Сейчас' + (now.tm ? '  ' + now.tm : '') + '  ' + now.t;
                    var d = now.row.querySelector && now.row.querySelector('.js-epgDesc');
                    if (d) { var dt = clean(d); if (dt) s += '\n' + dt.slice(0, 200); }
                    if (nxt) s += '\n\nДалее' + (nxt.tm ? '  ' + nxt.tm : '') + '  ' + nxt.t;
                    // …и ещё несколько следующих передач — расписание на панель плеера.
                    for (var q = nowIdx + 2; q <= nowIdx + 6; q++) {
                        var e2 = entry(q);
                        if (!e2) break;
                        s += '\n' + (e2.tm ? e2.tm + '  ' : '') + e2.t.slice(0, 80);
                    }
                    return s;
                }
            }

            // Скрейп пуст — вернём null (иначе диагностика уедет в плеер как «программа»).
            // Плеер и так тянет полноценный гид из облака по tvg-id.
            try {
                if (window.console) console.log('[lmnp] scrapeEpg: js-epgTitle=' + (titles ? titles.length : 0));
            } catch (_) {}
            return null;
        } catch (e) { try { if (window.console) console.log('[lmnp] scrapeEpg err', e); } catch (_) {} return null; }
    }

    // Карта «канал → текущая программа» по отрисованным карточкам Lampa.
    // Уезжает в X-Lmnp-EpgMap: плеер берёт из неё программу при переключении
    // канала уже внутри себя (DOM Lampa в этот момент недоступен).
    //
    // Группируем БЕЗ привязки к именам классов: карточка канала — наименьший
    // предок заголовка программы, который содержит ровно один `.js-epgTitle`
    // (как только предок охватывает >1 заголовка — это уже список, не карточка).
    // Поэтому значение `карта=N` в диагностике плеера само показывает, что в DOM:
    //   N>1  → список каналов с текущей программой у каждого (вариант А работает);
    //   N=1  → расписание одного выбранного канала (карты других каналов нет).
    function scrapeEpgMap() {
        try {
            function clean(e) { return e ? (e.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
            function norm(s) { return ('' + (s || '')).toLowerCase().replace(/[^a-zа-я0-9]/g, ''); }
            var titles = document.querySelectorAll('.js-epgTitle');
            if (!titles.length) return null;

            // наименьший предок, всё ещё охватывающий ровно один заголовок программы
            function cardOf(el) {
                var best = el.parentElement || el, n = best;
                for (var k = 0; n && k < 10; k++, n = n.parentElement) {
                    if (n.querySelectorAll && n.querySelectorAll('.js-epgTitle').length === 1) best = n;
                    else break;
                }
                return best;
            }
            // имя канала = текст карточки без её EPG-блоков (класс-агностично)
            function nameOf(cardEl) {
                try {
                    var cl = cardEl.cloneNode(true);
                    var eps = cl.querySelectorAll('[class*="epg" i], .js-epgTitle, .js-epgTime, .js-epgDesc, .js-epgProgress');
                    for (var i = eps.length - 1; i >= 0; i--) if (eps[i].parentNode) eps[i].parentNode.removeChild(eps[i]);
                    return clean(cl).replace(/\b\d{1,2}:\d{2}\b/g, '').replace(/\s+/g, ' ').trim().slice(0, 60);
                } catch (_) { return ''; }
            }
            function timeOf(el) {
                var n = el;
                for (var k = 0; k < 3 && n; k++, n = n.parentElement) {
                    var m = (n.textContent || '').match(/\b\d{1,2}:\d{2}\b/);
                    if (m) return m[0];
                }
                return '';
            }

            // группируем заголовки по карточкам (по ссылке на DOM-узел)
            var cards = [], groups = [];
            for (var i = 0; i < titles.length; i++) {
                var c = cardOf(titles[i]);
                var idx = -1;
                for (var q = 0; q < cards.length; q++) if (cards[q] === c) { idx = q; break; }
                if (idx < 0) { cards.push(c); groups.push([titles[i]]); }
                else groups[idx].push(titles[i]);
            }

            var ch = {}, size = 0, count = 0;
            for (var j = 0; j < cards.length; j++) {
                var key = norm(nameOf(cards[j]));
                if (!key || ch[key]) continue;
                var t0 = clean(groups[j][0]);
                if (!t0) continue;
                var s = 'Сейчас';
                var tm0 = timeOf(groups[j][0]);
                if (tm0) s += '  ' + tm0;
                s += '  ' + t0.slice(0, 80);
                if (groups[j][1]) {
                    var t1 = clean(groups[j][1]);
                    if (t1) {
                        var tm1 = timeOf(groups[j][1]);
                        s += '\nДалее' + (tm1 ? '  ' + tm1 : '') + '  ' + t1.slice(0, 80);
                    }
                }
                // ещё до 3 следующих передач — расписание для панели плеера
                for (var w2 = 2; w2 <= 4 && groups[j][w2]; w2++) {
                    var tw = clean(groups[j][w2]);
                    if (!tw) break;
                    var tmw = timeOf(groups[j][w2]);
                    s += '\n' + (tmw ? tmw + '  ' : '') + tw.slice(0, 80);
                }
                ch[key] = s;
                count++;
                size += key.length + s.length + 8;
                if (size > 100000) break;   // потолок размера заголовка
            }
            return count ? { ts: Date.now(), ch: ch } : null;
        } catch (e) { return null; }
    }

    // Полный плейлист (все серии/каналы) для заголовка X-Lmnp-Pl — без лимита title.
    // Записи без строкового URL включаются (u:''), чтобы список был полным; играбельные
    // подсветятся, остальные затемнятся в плеере.
    function buildFullPlaylist(list, pos) {
        if (!list || list.length < 2) return null;
        var CAP = 500;   // окно вокруг pos; ограничивает размер intent-заголовка (binder ~500КБ)
        // Окно ВОКРУГ текущего элемента, а не первые N — иначе при pos>=CAP
        // играющий канал вообще не попадёт в список (и подсветится первый).
        var p0 = pos || 0;
        var start = 0, end = list.length;
        if (list.length > CAP) {
            start = Math.max(0, p0 - Math.floor(CAP / 2));
            end = Math.min(list.length, start + CAP);
            start = Math.max(0, end - CAP);
        }
        var items = [], seen = {}, pi = 0;
        for (var i = start; i < end; i++) {
            var it = list[i] || {};
            var url = (typeof it.url === 'string') ? it.url
                    : (typeof it.file === 'string') ? it.file : '';
            // Дубли (один поток дважды — варианты качества и т.п.) не плодим.
            if (url && seen[url] !== undefined) {
                if (i === pos) pi = seen[url];
                continue;
            }
            // Логотип канала (tvg-logo) — разные балансеры кладут его по-разному.
            var logo = it.logo || it.tvg_logo || it.img || it.icon ||
                       (it.tvg && it.tvg.logo) || '';
            logo = (typeof logo === 'string' && /^https?:\/\//.test(logo)) ? logo : '';
            if (url) seen[url] = items.length;
            if (i === pos) pi = items.length;
            items.push({
                u: url || '',
                e: it.episode || (i + 1),
                s: it.season || null,
                t: (it.title || it.name || '').toString().slice(0, 60) || null,
                l: logo || null,
            });
        }
        if (items.length < 2) return null;
        return { items: items, pi: Math.min(Math.max(0, pi), items.length - 1) };
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

    // ── Субтитры ──────────────────────────────────────────────────────────
    // Lampa передаёт сабы как [{label, url}] в video.subtitles; балансеры — в
    // file.subtitles / data.subtitles. Кладём их в JSON как [{url,label,enable}],
    // нативный парсер читает поле `subtitles`.
    function getSubtitles(data, file, card) {
        var out = [];
        [ (file && file.subtitles), (data && data.subtitles), (card && card.subtitles) ]
            .forEach(function (arr) {
                if (!Array.isArray(arr)) return;
                arr.forEach(function (s) {
                    if (!s) return;
                    var url = (typeof s === 'string') ? s
                            : (typeof s.url === 'string') ? s.url
                            : (typeof s.link === 'string') ? s.link : '';
                    if (!url || !/^https?:|^file:|^content:/.test(url)) return;
                    if (out.some(function (e) { return e.url === url; })) return;  // дедуп
                    out.push({
                        url:    url,
                        label:  (s && (s.label || s.name)) || null,
                        enable: !!(s && (s.default || s.enable)),
                    });
                });
            });
        return out;
    }

    // ── Компактный плейлист-окно для title-конверта ───────────────────────
    // Кладём URL серий вокруг текущей (у кого ссылка уже строковая), чтобы
    // плеер мог сам переключать серии и делать автопереход. Окно ограничено,
    // т.к. длина title ограничена (иначе обрежется и сломает base64).
    function buildCompactPlaylist(list, pos) {
        if (!list || list.length < 2) return null;
        var WINDOW = 12, BEFORE = 2;
        var start = Math.max(0, (pos || 0) - BEFORE);
        var end   = Math.min(list.length, start + WINDOW);
        var items = [];
        for (var i = start; i < end; i++) {
            var it = list[i] || {};
            var url = (typeof it.url === 'string') ? it.url
                    : (typeof it.file === 'string') ? it.file : '';
            if (!url || !/^https?:|^rtsp:|^udp:/.test(url)) continue;
            items.push({
                u: url,
                e: it.episode || (i + 1),
                s: it.season || null,
                t: (it.title || it.name || '').toString().slice(0, 40) || null,
            });
        }
        if (items.length < 2) return null;
        return { items: items, pi: Math.max(0, (pos || 0) - start) };
    }

    // Резолвим «ленивые» URL серий в окне (balancer'ы хранят url как функцию,
    // которая дёргает сеть и подставляет строку). Дожидаемся, затем callback.
    function resolveWindow(list, pos, cb) {
        if (!list || list.length < 2) { cb(); return; }
        var BEFORE = 2, WINDOW = 12;
        var start = Math.max(0, (pos || 0) - BEFORE);
        var end   = Math.min(list.length, start + WINDOW);
        var pending = 0, done = false;
        function finish() { if (!done) { done = true; clearTimeout(timer); cb(); } }
        var timer = setTimeout(finish, 8000);
        for (var i = start; i < end; i++) {
            var it = list[i];
            if (it && typeof it.url === 'function') {
                pending++;
                (function (cell) {
                    try { cell.url(function () { if (--pending <= 0) finish(); }); }
                    catch (e) { if (--pending <= 0) finish(); }
                })(it);
            }
        }
        if (pending === 0) finish();
    }

    function compactMeta(cardData) {
        return {
            title:          cardData.title || null,
            original_title: cardData.original_title || null,
            overview:       cardData.overview ? String(cardData.overview).slice(0, 600) : null,
            year:           cardData.release_year || null,
            rating:         cardData.rating || null,
            poster:         cardData.poster_url || null,
            backdrop:       cardData.backdrop_url || null,
            tmdb_id:        cardData.tmdb_id || null,
            season:         cardData.season_number || null,
            episode:        cardData.episode_number || null,
            // Таймлайн Lampa — запасная точка «продолжить», когда URL балансера
            // сменился между сеансами и наша сохранёнка по URL не нашлась.
            timeline_time:     cardData.timeline_time || null,
            timeline_duration: cardData.timeline_duration || null,
        };
    }
    function cleanMeta(meta) {
        Object.keys(meta).forEach(function (k) {
            if (meta[k] === null || meta[k] === undefined || meta[k] === '') delete meta[k];
        });
    }

    // ── Список эпизодов ───────────────────────────────────────────────────
    function buildEpisodes(playlist, idx) {
        if (!playlist || playlist.length < 2) return null;
        try {
            var start = Math.max(0, (idx || 0) - 25);
            var eps = playlist.slice(start, start + 50).map(function (item, i) {
                // Балансеры (online_mod и др.) хранят URL как функцию для ленивой загрузки.
                // Такие URL нельзя сериализовать в JSON — передаём только строковые URL.
                var url = (typeof item.url === 'string') ? item.url
                        : (typeof item.file === 'string') ? item.file : '';
                return {
                    title:   item.title || item.name || ('Серия ' + (start + i + 1)),
                    url:     url,
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

        // If c.title looks like a translator/balancer label (has voice_name / translate,
        // or the title IS the voice_name), use original_title as a last resort.
        var rawTitle = c.title || c.name || '';
        if (rawTitle && (c.voice_name === rawTitle || c.translate === rawTitle)) {
            rawTitle = c.original_title || c.original_name || rawTitle;
        }
        var card = {
            title:          rawTitle,
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
        // Субтитры — массивом (нативный парсер читает поле `subtitles`).
        var subs = getSubtitles(fileData, epgData, c);
        if (subs.length) card.subtitles = subs;
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
    var _playGen     = 0;   // счётчик поколений play() — против гонки отложенного запуска
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
            if (!enabled()) return;                       // тумблер «Нативный плеер»
            // Внешний плеер: Lampa (приложение) сама запустит APK, метаданные уедут
            // через title-конверт (hookPlayerPlay). Свой intent не нужен — иначе двойной запуск.
            if (externalPlayerSelected()) return;
            if (Date.now() - _lastLaunch < 6000) return;

            // Сканируем всё доступное состояние Lampa прямо в момент запуска
            try { if (Lampa.Player.card)  saveTmdbCard({card: Lampa.Player.card}); } catch (_) {}
            try { if (Lampa.Player.movie) saveTmdbCard({card: Lampa.Player.movie}); } catch (_) {}
            try { if (Lampa.Player.film)  saveTmdbCard({card: Lampa.Player.film}); } catch (_) {}
            // Текущая/родительская активность (разные форки Lampa используют разные имена)
            ['current','parent','prev','last'].forEach(function(k) {
                try { var x = Lampa.Activity[k]; if (x && typeof x === 'function') saveTmdbCard(x()); else if (x) saveTmdbCard(x); } catch(_e) {}
            });
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
                    // Балансеры хранят оригинальный объект фильма в act.object или act.object.movie
                    try { if (act.object) saveTmdbCard(act.object); } catch(_e) {}
                    try { if (act.params && act.params.object) saveTmdbCard(act.params.object); } catch(_e) {}
                    // Некоторые форки используют act.card напрямую как TMDB карточку
                    try { if (act.card && typeof act.card === 'object') saveTmdbCard({movie: act.card}); } catch(_e) {}
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
                    else if (video.playlist && video.playlist.length > 1) { list = video.playlist; pos = 0; }
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
