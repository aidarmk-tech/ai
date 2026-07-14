/*!
 * lmstatus.js — статус сериала на карточке Lampa.
 * На постере сериала рисует бейдж: последний вышедший сезон×эпизод и статус
 * (идёт/завершён). Данные из TMDB (tv/{id}). Самодостаточно — без зависимостей;
 * грузит только для видимых карточек (IntersectionObserver), кэширует. ES5.
 */
(function () {
    'use strict';
    if (window.lmstatus_ready) return;
    window.lmstatus_ready = true;

    var L = 'ru';
    function lang() { try { return Lampa.Storage.get('language', 'ru') || 'ru'; } catch (e) { return 'ru'; } }

    // ── TMDB fetch (несколько путей — как в lmmain) ─────────────────────────
    function httpJson(url, cb) {
        try {
            var x = new XMLHttpRequest(); x.open('GET', url, true); x.timeout = 15000;
            x.onreadystatechange = function () { if (x.readyState === 4) { try { cb(x.status === 200 ? JSON.parse(x.responseText) : null); } catch (e) { cb(null); } } };
            x.onerror = x.ontimeout = function () { cb(null); };
            x.send();
        } catch (e) { cb(null); }
    }
    function tmdbGet(path, cb) {
        try {
            if (Lampa.TMDB && Lampa.TMDB.get) {
                var parts = path.split('?'), params = {};
                (parts[1] || '').split('&').forEach(function (kv) { if (kv) { var q = kv.split('='); params[q[0]] = decodeURIComponent(q[1] || ''); } });
                Lampa.TMDB.get(parts[0], params, function (j) { cb(j); }, function () { tmdbReq(path, cb); });
                return;
            }
        } catch (e) {}
        tmdbReq(path, cb);
    }
    function tmdbReq(path, cb) {
        var url = '';
        try { url = Lampa.TMDB.api(path); } catch (e) {}
        try { if (url && url.indexOf('api_key=') < 0 && Lampa.TMDB.key) url += (url.indexOf('?') < 0 ? '?' : '&') + 'api_key=' + Lampa.TMDB.key(); } catch (e) {}
        if (!url) { cb(null); return; }
        try { var net = new Lampa.Reguest(); net.silent(url, function (j) { cb(j); }, function () { httpJson(url, cb); }); }
        catch (e) { httpJson(url, cb); }
    }

    // ── определение сериала + расчёт статуса ────────────────────────────────
    var cache = {};   // id → {text, cls} | 'pending' | 'none'
    function isSerial(d) {
        if (!d) return false;
        if (d.media_type === 'tv' || d.type === 'tv') return true;
        if (d.number_of_seasons || d.first_air_date || d.name && !d.title) return true;
        return false;
    }
    function pad(n) { n = '' + n; return n.length < 2 ? '0' + n : n; }
    function compute(det) {
        if (!det) return null;
        var last = det.last_episode_to_air, nxt = det.next_episode_to_air;
        var seas = det.number_of_seasons || (last ? last.season_number : 0) || 0;
        var text = '';
        if (last && last.season_number) text = last.season_number + '×' + pad(last.episode_number || 0);
        else if (seas) text = seas + ' сез';
        else return null;
        // статус: есть анонс следующей серии / on_the_air → идёт; иначе завершён
        var st = (det.status || '');
        var ongoing = !!nxt || /return|air|production/i.test(st);
        return { text: text, cls: ongoing ? 'go' : 'end', tip: (ongoing ? 'Идёт' : 'Завершён') };
    }

    function loadStatus(id, cb) {
        if (!id) { cb(null); return; }
        var c = cache[id];
        if (c === 'pending') { setTimeout(function () { loadStatus(id, cb); }, 300); return; }
        if (c && c !== 'none') { cb(c); return; }
        if (c === 'none') { cb(null); return; }
        cache[id] = 'pending';
        tmdbGet('tv/' + id + '?language=' + lang(), function (det) {
            var r = compute(det);
            cache[id] = r || 'none';
            cb(r);
        });
    }

    // ── бейдж на карточке ───────────────────────────────────────────────────
    function domOf(x) { if (!x) return null; if (x.nodeType) return x; if (x.jquery || (x[0] && x[0].nodeType)) return x[0]; return null; }
    function addBadge(cardInstance) {
        var data = cardInstance.data || cardInstance.card_data;
        if (!isSerial(data)) return;
        var id = data.id || data.tmdb_id;
        if (!id) return;
        var root = domOf(cardInstance.card || (cardInstance.render && cardInstance.render(true)));
        if (!root || root.querySelector('.lmstatus')) return;
        var view = root.querySelector('.card__view') || root;

        function fetchNow() {
            loadStatus(id, function (r) {
                if (!r || root.querySelector('.lmstatus')) return;
                var b = document.createElement('div');
                b.className = 'lmstatus lmstatus--' + r.cls;
                b.textContent = r.text;
                b.title = r.tip;
                view.appendChild(b);
            });
        }
        // грузим только когда карточка реально попала в кадр
        if (window.IntersectionObserver) {
            try {
                var io = new IntersectionObserver(function (ents) {
                    for (var i = 0; i < ents.length; i++) if (ents[i].isIntersecting) { io.disconnect(); fetchNow(); break; }
                }, { rootMargin: '200px' });
                io.observe(root);
                return;
            } catch (e) {}
        }
        fetchNow();
    }

    // ── подключение: оборачиваем построение карточки ────────────────────────
    function hookCards() {
        if (!(window.Lampa && Lampa.Card && Lampa.Card.prototype)) return false;
        var proto = Lampa.Card.prototype;
        if (proto.__lmstatus) return true;
        var names = ['build', 'create'];
        for (var i = 0; i < names.length; i++) {
            (function (name) {
                if (typeof proto[name] !== 'function') return;
                var orig = proto[name];
                proto[name] = function () {
                    var res = orig.apply(this, arguments);
                    try { addBadge(this); } catch (e) {}
                    return res;
                };
            })(names[i]);
        }
        proto.__lmstatus = true;
        return true;
    }

    function addStyle() {
        var css =
        '.lmstatus{position:absolute;top:.5em;left:.5em;z-index:5;font-size:1em;font-weight:700;' +
            'line-height:1;color:#fff;padding:.35em .5em;border-radius:.4em;font-variant-numeric:tabular-nums;' +
            'box-shadow:0 .1em .4em rgba(0,0,0,.5)}' +
        '.lmstatus--go{background:#2fbf6c}' +
        '.lmstatus--end{background:#ff9f1a;color:#141414}' +
        '.card__view{position:relative}';
        try {
            var st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);
        } catch (e) {}
    }

    function boot() {
        if (!window.Lampa) { setTimeout(boot, 300); return; }
        L = lang();
        addStyle();
        // Карточки могут быть уже определены; если нет — ждём готовности приложения.
        if (!hookCards()) {
            var tries = 0;
            var t = setInterval(function () { if (hookCards() || ++tries > 40) clearInterval(t); }, 400);
        }
    }
    if (window.Lampa && Lampa.Listener) Lampa.Listener.follow('app', function (e) { if (e.type === 'ready') boot(); });
    boot();
})();
