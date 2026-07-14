/*!
 * lmmain.js — «Мои подборки» для Lampa.
 * Свой раздел с рядами фильмов, которые тянутся с ТВОЕГО сервера (Lampac/CUB)
 * по настраиваемым ссылкам (TMDB-совместимый JSON). Карточки открывают штатную
 * карточку фильма Lampa — там Lampac уже даёт онлайн-источники.
 * Отдельный пункт меню — стандартную главную не трогаем. ES5, тест на устройстве.
 */
(function () {
    'use strict';
    if (window.lmmain_ready) return;
    window.lmmain_ready = true;

    var PLUGIN = 'lmmain';
    var TITLE = 'Мои подборки';

    function S(k, d) { try { return Lampa.Storage.get(PLUGIN + '_' + k, d); } catch (e) { return d; } }
    function Sset(k, v) { try { Lampa.Storage.set(PLUGIN + '_' + k, v); } catch (e) {} }
    function noty(t) { try { Lampa.Noty.show(t); } catch (e) {} }
    function esc(s) { return ('' + (s == null ? '' : s)).replace(/[<>&"]/g, function (c) { return ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' })[c]; }); }
    function uid() { return 'r' + Math.abs(('' + Date.now() + Math.round((performance && performance.now ? performance.now() : 0))).split('').reduce(function (a, c) { return ((a << 5) - a) + c.charCodeAt(0) | 0; }, 0)); }

    function httpJson(url, done) {
        try {
            var x = new XMLHttpRequest();
            x.open('GET', url, true); x.timeout = 20000;
            x.onreadystatechange = function () {
                if (x.readyState === 4) {
                    if (x.status !== 200) { done(null); return; }
                    try { done(JSON.parse(x.responseText)); } catch (e) { done(null); }
                }
            };
            x.onerror = x.ontimeout = function () { done(null); };
            x.send();
        } catch (e) { done(null); }
    }

    // Язык интерфейса для TMDB-запросов.
    function lang() { try { return Lampa.Storage.get('language', 'ru') || 'ru'; } catch (e) { return 'ru'; } }

    function today() { try { return new Date().toISOString().slice(0, 10); } catch (e) { return '2030-01-01'; } }

    // Готовые подборки из TMDB (ключ/прокси уже настроены в Lampa) — работают
    // без сервера. Сервер Lampac подключается сам при открытии карточки фильма.
    // «Новинки» = сортировка по дате выхода + отсечка будущих релизов и мусора.
    function defaults() {
        var L = lang(), T = today();
        function movie(q) { return 'discover/movie?language=' + L + '&' + q; }
        function tv(q) { return 'discover/tv?language=' + L + '&' + q; }
        var newM = 'sort_by=primary_release_date.desc&primary_release_date.lte=' + T + '&vote_count.gte=20';
        var newT = 'sort_by=first_air_date.desc&first_air_date.lte=' + T + '&vote_count.gte=5';
        return [
            { id: 'd_trend', title: '🔥 В тренде за неделю', tmdb: true, path: 'trending/all/week?language=' + L },
            { id: 'd_newm',  title: '🎬 Новинки кино',       tmdb: true, path: movie(newM) },
            { id: 'd_newt',  title: '📺 Новинки сериалов',   tmdb: true, path: tv(newT) },
            { id: 'd_rut',   title: '🇷🇺 Русские сериалы — новинки', tmdb: true, path: tv('with_original_language=ru&' + newT.replace('vote_count.gte=5', 'vote_count.gte=1')) },
            { id: 'd_rum',   title: '🇷🇺 Русские фильмы — новинки', tmdb: true, path: movie('with_original_language=ru&' + newM.replace('vote_count.gte=20', 'vote_count.gte=3')) },
            { id: 'd_forn',  title: '🌍 Зарубежные новинки', tmdb: true, path: movie('with_original_language=en&' + newM.replace('vote_count.gte=20', 'vote_count.gte=50')) },
            { id: 'd_g_com', title: '😂 Комедии',            tmdb: true, path: movie('with_genres=35&sort_by=popularity.desc&vote_count.gte=100') },
            { id: 'd_g_act', title: '💥 Боевики',            tmdb: true, path: movie('with_genres=28&sort_by=popularity.desc&vote_count.gte=100') },
            { id: 'd_g_sci', title: '🚀 Фантастика',         tmdb: true, path: movie('with_genres=878&sort_by=popularity.desc&vote_count.gte=100') },
            { id: 'd_g_hor', title: '👻 Ужасы',              tmdb: true, path: movie('with_genres=27&sort_by=popularity.desc&vote_count.gte=80') },
            { id: 'd_g_rom', title: '❤️ Мелодрамы',          tmdb: true, path: movie('with_genres=10749&sort_by=popularity.desc&vote_count.gte=80') },
            { id: 'd_g_det', title: '🕵️ Детективы',          tmdb: true, path: movie('with_genres=9648&sort_by=popularity.desc&vote_count.gte=80') },
            { id: 'd_g_cart',title: '🧸 Мультфильмы',        tmdb: true, path: movie('with_genres=16&sort_by=popularity.desc&vote_count.gte=100') },
            { id: 'd_anime', title: '🌸 Аниме',              tmdb: true, path: tv('with_genres=16&with_original_language=ja&sort_by=popularity.desc') },
            { id: 'd_toptv', title: '⭐ Популярные сериалы',  tmdb: true, path: 'tv/popular?language=' + L }
        ];
    }

    // ── ряды (Storage) ──────────────────────────────────────────────────────
    var SEEDVER = 2;   // бампни, чтобы обновить набор дефолтных рядов у всех
    function rows() {
        if (S('seedver', 0) !== SEEDVER) {
            // сохраняем свои (серверные) ряды пользователя, дефолты обновляем
            var custom = (S('rows', []) || []).filter(function (r) { return r && !r.tmdb; });
            Sset('rows', defaults().concat(custom));
            Sset('seedver', SEEDVER);
        }
        var a = S('rows', []); return a && a.length ? a : defaults();
    }
    function saveRows(a) { Sset('rows', a); }

    // Загрузка списка ряда: TMDB — через сеть Lampa (ключ+прокси), иначе прямой JSON.
    function fetchList(r, cb) {
        if (r.tmdb) { tmdbGet(r.path, cb); return; }
        httpJson(r.url, function (j) { cb(extractList(j)); });
    }
    // Несколько путей к TMDB — в разных сборках Lampa доступны разные.
    function tmdbGet(path, cb) {
        // 1) Lampa.TMDB.get(url, params, ok, err) — сам добавляет ключ и прокси
        try {
            if (Lampa.TMDB && Lampa.TMDB.get) {
                var parts = path.split('?'), params = {};
                (parts[1] || '').split('&').forEach(function (kv) { if (kv) { var q = kv.split('='); params[q[0]] = decodeURIComponent(q[1] || ''); } });
                Lampa.TMDB.get(parts[0], params, function (j) { cb(extractList(j)); }, function () { tmdbReguest(path, cb); });
                return;
            }
        } catch (e) {}
        tmdbReguest(path, cb);
    }
    function tmdbReguest(path, cb) {
        var url = '';
        try { url = Lampa.TMDB.api(path); } catch (e) {}
        // ключ, если сборка не подставляет его сама
        try { if (url && url.indexOf('api_key=') < 0 && Lampa.TMDB.key) url += (url.indexOf('?') < 0 ? '?' : '&') + 'api_key=' + Lampa.TMDB.key(); } catch (e) {}
        if (!url) { cb(null); return; }
        try {
            var net = new Lampa.Reguest();
            net.silent(url, function (j) { cb(extractList(j)); }, function () { httpJson(url, function (j) { cb(extractList(j)); }); });
        } catch (e) { httpJson(url, function (j) { cb(extractList(j)); }); }
    }
    function tmdbDiag() {
        var t = window.Lampa && Lampa.TMDB;
        return 'TMDB.get=' + (t && Lampa.TMDB.get ? '1' : '0') + ' api=' + (t && Lampa.TMDB.api ? '1' : '0') + ' key=' + (t && Lampa.TMDB.key ? '1' : '0') + ' Req=' + (window.Lampa && Lampa.Reguest ? '1' : '0');
    }

    // TMDB-совместимый разбор: {results:[...]} | {items:[...]} | [...]
    function extractList(data) {
        if (!data) return [];
        if (Array.isArray(data)) return data;
        return data.results || data.items || data.list || data.data || [];
    }
    function cardTitle(c) { return c.title || c.name || c.original_title || c.original_name || 'Без названия'; }
    function cardMethod(c) { return (c.name || c.first_air_date || c.media_type === 'tv') ? 'tv' : 'movie'; }
    function cardYear(c) { var d = c.release_date || c.first_air_date || ''; return d ? ('' + d).slice(0, 4) : ''; }
    function poster(c) {
        var p = c.poster_path || c.poster || c.img || c.image || '';
        if (!p) return '';
        if (/^https?:/i.test(p)) return p;
        try { if (Lampa.TMDB && Lampa.TMDB.image) return Lampa.TMDB.image('t/p/w300' + p); } catch (e) {}
        return 'https://image.tmdb.org/t/p/w300' + p;
    }

    // Открыть штатную карточку фильма Lampa (дальше Lampac даёт источники).
    function openCard(c) {
        try {
            Lampa.Activity.push({
                url: '', component: 'full',
                id: c.id || c.tmdb_id, method: cardMethod(c),
                card: c, source: c.source || 'tmdb'
            });
        } catch (e) { noty('Не удалось открыть карточку'); }
    }

    // Собрать данные всех рядов (параллельно) → массив {title, results}.
    function buildData(done) {
        var rs = rows(), out = new Array(rs.length), pend = rs.length, finished = false;
        if (!pend) { done([]); return; }
        var timer = setTimeout(function () { if (!finished) { finished = true; done(pack(out)); } }, 12000);
        rs.forEach(function (r, i) {
            fetchList(r, function (list) {
                if (list && list.length) {
                    // nomore: прячем кнопку «Ещё» — её пагинация не совпадает с нашим
                    // путём к TMDB и открывала пустой экран. В ряду и так 30 карточек.
                    out[i] = { title: r.title, results: list.slice(0, 30), source: 'tmdb',
                               nomore: true, cardClass: false };
                }
                if (--pend === 0 && !finished) { finished = true; clearTimeout(timer); done(pack(out)); }
            });
        });
        function pack(a) { var o = []; for (var k = 0; k < a.length; k++) if (a[k]) o.push(a[k]); return o; }
    }

    // ── нативный компонент: горизонтальные ленты (как на главной Lampa) ─────
    function component(object) {
        // Нет нативного класса лент → откат на свой рендер.
        if (!(window.Lampa && Lampa.InteractionMain)) return legacyComponent(object);
        var comp;
        try { comp = new Lampa.InteractionMain(object); } catch (e) { return legacyComponent(object); }
        comp.create = function () {
            var self = this;
            try { this.activity.loader(true); } catch (e) {}
            buildData(function (data) {
                try {
                    if (!data.length) { self.empty(); }
                    else { self.build(data); }
                } catch (e) { try { self.empty(); } catch (e2) {} }
                try { self.activity.loader(false); } catch (e) {}
            });
            return this.render();
        };
        return comp;
    }

    // ── запасной компонент (если нет Lampa.InteractionMain) ─────────────────
    function legacyComponent(object) {
        var self = this;
        var scroll = new Lampa.Scroll({ mask: true, over: true, step: 300 });
        var html = $('<div class="lmmain"></div>');
        var body = $('<div class="lmmain__body"></div>');
        var info = $('<div class="lmmain__info"></div>');
        var last = false;

        scroll.append(body);
        html.append(info);
        html.append(scroll.render());
        scroll.render().addClass('layer--wheight').data('mheight', info);
        scroll.minus(info);

        function layerUpdate() { try { if (Lampa.Layer && Lampa.Layer.update) Lampa.Layer.update(html); } catch (e) {} }
        function activeNow() { try { return Lampa.Activity.active().activity === self.activity; } catch (e) { return true; } }
        function loader(on) { try { if (self.activity && self.activity.loader) self.activity.loader(on); } catch (e) {} }
        function refocus() {
            Lampa.Controller.collectionSet(scroll.render());
            Lampa.Controller.collectionFocus(last || false, scroll.render());
        }

        this.create = function () { return this.render(); };
        this.render = function () { return html; };

        this.build = function () {
            body.empty();
            var rs = rows();
            info.html('<div class="lmmain__title">' + esc(TITLE) + '</div><div class="lmmain__sub">' +
                'Долгое OK на заголовке — управление · снизу можно добавить свой ряд' + '</div>');
            // секции: заголовок + сетка постеров; данные догружаются по очереди
            rs.forEach(function (r, idx) {
                var sec = $('<div class="lmmain__sec" data-idx="' + idx + '">' +
                    '<div class="lmmain__sechead selector"><span>' + esc(r.title) + '</span><i>обновить</i></div>' +
                    '<div class="lmmain__cards"><div class="lmmain__load">Загрузка…</div></div></div>');
                sec.find('.lmmain__sechead').on('hover:enter', function () { openRowMenu(r); })
                    .on('hover:focus', function () { last = this; scroll.update($(this), true); });
                body.append(sec);
                fillSection(sec, r);
            });
            body.append(rowManage());
            layerUpdate();
            if (activeNow()) refocus();
        };

        function fillSection(sec, r) {
            fetchList(r, function (all) {
                var list = (all || []).slice(0, 30);
                var wrap = sec.find('.lmmain__cards').empty();
                if (!list.length) { wrap.append('<div class="lmmain__load">Пусто · ' + (r.tmdb ? tmdbDiag() : 'сервер не ответил') + '</div>'); layerUpdate(); return; }
                list.forEach(function (c) { wrap.append(cardEl(c)); });
                layerUpdate();
                if (activeNow()) refocus();
            });
        }

        function cardEl(c) {
            var p = poster(c), y = cardYear(c), r = c.vote_average ? (Math.round(c.vote_average * 10) / 10) : '';
            var el = $(
                '<div class="lmmain__card selector">' +
                    '<div class="lmmain__poster">' + (p ? '<img loading="lazy" src="' + esc(p) + '"/>' : '<span>' + esc(cardTitle(c).slice(0, 2)) + '</span>') + '</div>' +
                    (r ? '<div class="lmmain__rate">' + r + '</div>' : '') +
                    '<div class="lmmain__cname">' + esc(cardTitle(c)) + '</div>' +
                    (y ? '<div class="lmmain__cyear">' + y + '</div>' : '') +
                '</div>'
            );
            var img = el.find('img')[0];
            if (img) img.onerror = function () { $(img).replaceWith('<span>' + esc(cardTitle(c).slice(0, 2)) + '</span>'); };
            el.on('hover:enter', function () { openCard(c); });
            el.on('hover:focus', function () {
                last = el[0]; scroll.update(el, true);
                try {
                    var bg = c.backdrop_path ? (/^https?:/.test(c.backdrop_path) ? c.backdrop_path : (Lampa.TMDB && Lampa.TMDB.image ? Lampa.TMDB.image('t/p/w1280' + c.backdrop_path) : '')) : p;
                    if (bg && Lampa.Background && Lampa.Background.change) Lampa.Background.change(bg);
                } catch (e) {}
            });
            return el;
        }

        // — управление рядами —
        function rowManage() {
            var el = $('<div class="lmmain__manage selector">＋ Добавить ряд · управление</div>');
            el.on('hover:enter', function () { addRow(); })
                .on('hover:focus', function () { last = this; scroll.update($(this), true); });
            return el;
        }
        function addRow() {
            keyboard('Название ряда', '', function (title) {
                if (!title) return;
                keyboard('URL списка (TMDB-совместимый JSON)', S('lastbase', ''), function (url) {
                    if (!/^https?:\/\//i.test(url)) { noty('Нужна ссылка http(s)'); return; }
                    var a = rows(); a.push({ id: uid(), title: title.slice(0, 40), url: url, tmdb: false });
                    saveRows(a);
                    try { Sset('lastbase', url.replace(/[^/]*$/, '')); } catch (e) {}
                    noty('Ряд добавлен'); self.build(); refocus();
                });
            });
        }
        function openRowMenu(r) {
            try {
                Lampa.Select.show({
                    title: r.title,
                    items: [{ title: '↻ Обновить', act: 'refresh' }, { title: '✎ Переименовать', act: 'rename' },
                            { title: '⬆ Выше', act: 'up' }, { title: '⬇ Ниже', act: 'down' },
                            { title: '🗑 Удалить', act: 'del' }, { title: 'Отмена', act: '' }],
                    onSelect: function (a) {
                        Lampa.Controller.toggle('content');
                        var arr = rows(), i = idxOf(arr, r.id);
                        if (a.act === 'refresh') { self.build(); refocus(); }
                        if (a.act === 'rename') keyboard('Название ряда', r.title, function (v) { if (v) { arr[i].title = v.slice(0, 40); saveRows(arr); self.build(); refocus(); } });
                        if (a.act === 'up' && i > 0) { arr.splice(i - 1, 0, arr.splice(i, 1)[0]); saveRows(arr); self.build(); refocus(); }
                        if (a.act === 'down' && i < arr.length - 1) { arr.splice(i + 1, 0, arr.splice(i, 1)[0]); saveRows(arr); self.build(); refocus(); }
                        if (a.act === 'del') { arr.splice(i, 1); saveRows(arr); noty('Удалён'); self.build(); refocus(); }
                    },
                    onBack: function () { Lampa.Controller.toggle('content'); }
                });
            } catch (e) {}
        }
        function idxOf(arr, id) { for (var i = 0; i < arr.length; i++) if (arr[i].id === id) return i; return -1; }
        function keyboard(ph, val, cb) {
            try { Lampa.Input.edit({ title: ph, value: val || '', free: true, nosave: true }, function (v) { cb((v || '').trim()); Lampa.Controller.toggle('content'); }); }
            catch (e) { noty('Ввод недоступен'); }
        }

        this.start = function () {
            if (Lampa.Activity.active().activity !== this.activity) return;
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

    // ── меню + стиль + загрузка ─────────────────────────────────────────────
    function addMenu() {
        try {
            var icon = '<svg width="27" height="27" viewBox="0 0 24 24" fill="currentColor"><path d="M4 4h16v12H4zM2 18h20v2H2zm7-9l5 3-5 3z"/></svg>';
            var item = $('<li class="menu__item selector"><div class="menu__ico">' + icon + '</div><div class="menu__text">' + TITLE + '</div></li>');
            item.on('hover:enter', function () { Lampa.Activity.push({ url: '', title: TITLE, component: PLUGIN, page: 1 }); });
            $('.menu .menu__list').eq(0).append(item);
        } catch (e) {}
    }
    function addStyle() {
        var css =
        '.lmmain{padding:1.5em 0 3em}' +
        '.lmmain__info{padding:0 1.5em 1em}.lmmain__title{font-size:2em;font-weight:600}' +
        '.lmmain__sub{color:rgba(255,255,255,.6);margin-top:.25em;font-size:1.1em}' +
        '.lmmain__body{padding:0 1.5em}' +
        '.lmmain__sec{margin-bottom:1.6em}' +
        '.lmmain__sechead{display:inline-flex;align-items:baseline;gap:.7em;font-size:1.4em;font-weight:600;padding:.3em .5em;border-radius:.5em;margin-bottom:.4em}' +
        '.lmmain__sechead i{font-size:.6em;color:rgba(255,255,255,.4);font-style:normal}' +
        '.lmmain__cards{display:flex;flex-wrap:wrap;gap:1em}' +
        '.lmmain__load{color:rgba(255,255,255,.4);padding:1em}' +
        '.lmmain__card{width:11em;border-radius:.6em;overflow:hidden;position:relative}' +
        '.lmmain__poster{height:16em;background:rgba(255,255,255,.06);display:flex;align-items:center;justify-content:center;border-radius:.6em;overflow:hidden}' +
        '.lmmain__poster img{width:100%;height:100%;object-fit:cover}' +
        '.lmmain__poster span{font-size:2.4em;font-weight:700;color:rgba(255,255,255,.6)}' +
        '.lmmain__rate{position:absolute;top:.5em;left:.5em;background:rgba(0,0,0,.7);color:#F6B44C;font-weight:700;font-size:.85em;padding:.15em .5em;border-radius:.4em}' +
        '.lmmain__cname{font-size:1.02em;margin-top:.4em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
        '.lmmain__cyear{font-size:.85em;color:rgba(255,255,255,.5)}' +
        '.lmmain__manage{padding:1em 1.2em;border-radius:.7em;background:rgba(255,255,255,.05);font-size:1.15em;margin-top:.5em}' +
        '.lmmain .selector.focus{box-shadow:0 0 0 .18em #F6B44C}' +
        '.lmmain__card.selector.focus{transform:scale(1.05)}' +
        '.lmmain__sechead.selector.focus,.lmmain__manage.selector.focus{background:rgba(255,255,255,.16)}';
        try { $('<style>' + css + '</style>').appendTo('head'); } catch (e) {}
    }

    // Регистрация источника главного экрана (появляется в списке «Выбрать»).
    // Наследуем всё от штатного TMDB-источника, переопределяем только main —
    // так menu/full/категории/поиск работают штатно, а на главной наши ряды.
    function registerSource() {
        try {
            if (!(window.Lampa && Lampa.Api && Lampa.Api.sources)) return false;
            if (Lampa.Api.sources[PLUGIN]) return true;
            var base = Lampa.Api.sources.tmdb || Lampa.Api.sources['tmdb'];
            if (!base) return false;
            var src = {};
            for (var k in base) { try { src[k] = base[k]; } catch (e) {} }
            src.main = function (params, oncomplete, onerror) {
                buildData(function (data) {
                    try { oncomplete({ results: data, collection: true, source: PLUGIN }); }
                    catch (e) { if (onerror) onerror(e); }
                });
            };
            // Заголовок в переключателе (в разных сборках берётся по-разному).
            src.title = TITLE;
            Lampa.Api.sources[PLUGIN] = src;
            // Попытка добавить в список выбора источника, если механизм доступен.
            try {
                if (Lampa.Params && Lampa.Params.select && typeof Lampa.Storage.get('source') !== 'undefined') {
                    // не трогаем текущий выбор — просто регистрируем возможность
                }
            } catch (e) {}
            return true;
        } catch (e) { return false; }
    }

    var booted = false;
    function boot() {
        if (booted) return;
        if (!window.Lampa || !Lampa.Component) { setTimeout(boot, 300); return; }
        booted = true;
        Lampa.Component.add(PLUGIN, component);
        registerSource();
        addStyle();
        var tries = 0;
        (function tryMenu() { if ($('.menu .menu__list').length) { addMenu(); return; } if (++tries < 30) setTimeout(tryMenu, 500); })();
    }
    if (window.Lampa && Lampa.Listener) Lampa.Listener.follow('app', function (e) { if (e.type === 'ready') boot(); });
    boot();
})();
