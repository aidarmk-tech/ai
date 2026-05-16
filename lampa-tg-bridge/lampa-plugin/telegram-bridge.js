(function () {
    'use strict';

    var API_BASE = 'https://filmkenttg.aidar-m-k.workers.dev';
    var BOT_NAME = '@Filmkent_bot';
    var pollTimer = null;

    Lampa.Template.add('tg_bridge_feed', '<div class="tg-bridge-feed"><div class="tg-bridge-feed__head"><div class="tg-bridge-feed__title"></div><div class="tg-bridge-feed__subtitle"></div></div><div class="tg-bridge-feed__list"></div></div>');
    Lampa.Template.add('tg_bridge_settings', '<div class="tg-bridge-settings"><div class="tg-bridge-settings__title">Telegram Bridge</div><div class="tg-bridge-settings__body"></div></div>');
    Lampa.Template.add('tg_bridge_link', '<div class="tg-bridge-link"><div class="tg-bridge-link__title">Подключи Telegram-чат</div><div class="tg-bridge-link__step">1. Открой <b>{bot}</b> в Telegram</div><div class="tg-bridge-link__step">2. Введи команду: <code>/link</code></div><div class="tg-bridge-link__step">3. Получи код и введи здесь:</div><div class="tg-bridge-link__input-wrap"><input class="tg-bridge-link__input" type="text" maxlength="6" placeholder="000000"></div><div class="tg-bridge-link__btn selector">Подтвердить</div><div class="tg-bridge-link__error" style="display:none"></div></div>');

    $('<style id="tg-bridge-css">').html([
        '.tg-badge{background:#e74c3c;color:#fff;border-radius:10px;font-size:11px;padding:1px 6px;margin-left:5px;display:none}',
        '.tg-bridge-feed{padding:1.2em;overflow-y:auto;max-height:100%}',
        '.tg-bridge-feed__head{margin-bottom:1em}',
        '.tg-bridge-feed__title{font-size:1.3em;font-weight:bold}',
        '.tg-bridge-feed__subtitle{opacity:.6;font-size:.85em;margin-top:.2em}',
        '.tg-bridge-feed__list{display:flex;flex-direction:column;gap:.8em}',
        '.tg-rec{display:flex;gap:.8em;background:rgba(255,255,255,.06);border-radius:8px;padding:.8em;transition:background .15s}',
        '.tg-rec.focus,.tg-rec:hover{background:rgba(255,255,255,.14)}',
        '.tg-rec__poster{width:80px;min-width:80px;height:120px;background-size:cover;background-position:center;border-radius:4px;background-color:rgba(255,255,255,.08)}',
        '.tg-rec__body{flex:1;display:flex;flex-direction:column;gap:.3em;min-width:0}',
        '.tg-rec__title{font-size:1em;font-weight:bold;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
        '.tg-rec__year{opacity:.6;font-weight:normal}',
        '.tg-rec__rating{color:#f1c40f;font-size:.85em}',
        '.tg-rec__from{opacity:.65;font-size:.82em}',
        '.tg-rec__comment{font-style:italic;opacity:.8;font-size:.85em}',
        '.tg-rec__actions{display:flex;gap:.4em;flex-wrap:wrap;margin-top:.3em}',
        '.tg-rec__btn{padding:.25em .7em;border-radius:4px;background:rgba(255,255,255,.1);font-size:.8em;cursor:pointer;transition:background .15s}',
        '.tg-rec__btn.focus,.tg-rec__btn:hover{background:#3498db}',
        '.tg-bridge-link{max-width:380px;padding:1.5em}',
        '.tg-bridge-link__title{font-size:1.2em;font-weight:bold;margin-bottom:1.2em}',
        '.tg-bridge-link__step{margin:.5em 0;line-height:1.5}',
        '.tg-bridge-link__step code{background:rgba(255,255,255,.15);padding:1px 5px;border-radius:3px}',
        '.tg-bridge-link__input-wrap{margin:1em 0}',
        '.tg-bridge-link__input{width:150px;padding:.4em;font-size:1.4em;letter-spacing:.2em;text-align:center;border-radius:4px;border:2px solid rgba(255,255,255,.3);background:rgba(0,0,0,.4);color:#fff;outline:none}',
        '.tg-bridge-link__input:focus{border-color:#3498db}',
        '.tg-bridge-link__btn{display:inline-block;margin-top:.4em;padding:.5em 1.3em;background:#3498db;border-radius:4px;cursor:pointer}',
        '.tg-bridge-link__btn.focus{background:#2980b9}',
        '.tg-bridge-link__error{color:#e74c3c;margin-top:.6em;font-size:.85em}',
        '.tg-bridge-settings{padding:1.2em;max-width:380px}',
        '.tg-bridge-settings__title{font-size:1.2em;font-weight:bold;margin-bottom:1.2em}',
        '.tg-bridge-settings__row{display:flex;justify-content:space-between;align-items:center;padding:.6em 0;border-bottom:1px solid rgba(255,255,255,.08)}',
        '.tg-bridge-settings__row:last-child{border-bottom:none}',
        '.tg-bridge-settings__btn{padding:.3em .9em;background:rgba(255,255,255,.1);border-radius:4px;cursor:pointer}',
        '.tg-bridge-settings__btn.focus{background:#e74c3c}',
        '.tg-date-header{padding:.5em 0 .2em;opacity:.45;font-size:.8em;text-transform:uppercase;letter-spacing:.06em}',
        '.tg-empty{padding:2em;text-align:center;opacity:.55;line-height:1.9}'
    ].join('')).appendTo('head');

    // ── Storage ────────────────────────────────────────────────────────────────
    function getToken()    { return Lampa.Storage.get('tg_bridge_token', ''); }
    function getChatName() { return Lampa.Storage.get('tg_bridge_chat_name', ''); }
    function getCache()    { return Lampa.Storage.get('tg_bridge_cache', []); }
    function getLastTs()   { return Lampa.Storage.get('tg_bridge_last_ts', 0); }
    function getUnread()   { return Lampa.Storage.get('tg_bridge_unread', 0); }
    function getSettings() { return Lampa.Storage.get('tg_bridge_settings', { notify: true, share_to_chat: true, poll_interval: 30 }); }

    // ── Badge ──────────────────────────────────────────────────────────────────
    function updateBadge(add) {
        var n = getUnread() + (add || 0);
        Lampa.Storage.set('tg_bridge_unread', n);
        var $b = $('.menu .tg-badge');
        if (n > 0) $b.text(n).show(); else $b.hide();
    }
    function clearBadge() {
        Lampa.Storage.set('tg_bridge_unread', 0);
        $('.menu .tg-badge').hide();
    }

    // ── Polling ────────────────────────────────────────────────────────────────
    function fetchFeed() {
        var token = getToken();
        if (!token) return;
        fetch(API_BASE + '/api/feed?token=' + encodeURIComponent(token) + '&since=' + getLastTs())
            .then(function (r) {
                if (r.status === 401) {
                    Lampa.Storage.set('tg_bridge_token', '');
                    clearBadge();
                    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
                    Lampa.Noty.show('Telegram Bridge: чат отключён');
                    return null;
                }
                return r.json();
            })
            .then(function (data) {
                if (!data || !data.items || !data.items.length) return;
                var merged = data.items.concat(getCache()).slice(0, 100);
                Lampa.Storage.set('tg_bridge_cache', merged);
                Lampa.Storage.set('tg_bridge_last_ts', data.items[0].ts);
                if (getSettings().notify) Lampa.Noty.show(data.items[0].from_name + ' рекомендует: ' + data.items[0].title);
                updateBadge(data.items.length);
            })
            .catch(function () {});
    }

    function startPolling() {
        if (!getToken()) return;
        if (pollTimer) clearInterval(pollTimer);
        fetchFeed();
        pollTimer = setInterval(fetchFeed, (getSettings().poll_interval || 30) * 1000);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    function timeAgo(ts) {
        var d = Math.floor(Date.now() / 1000) - ts;
        if (d < 60) return 'только что';
        if (d < 3600) return Math.floor(d / 60) + ' мин назад';
        if (d < 86400) return Math.floor(d / 3600) + 'ч назад';
        if (d < 172800) return 'вчера';
        var dt = new Date(ts * 1000);
        return dt.getDate() + ' ' + ['янв','фев','мар','апр','май','июн','июл','авг','сен','окт','ноя','дек'][dt.getMonth()];
    }
    function dayLabel(ts) {
        var d = new Date(ts * 1000), today = new Date(), yest = new Date();
        yest.setDate(yest.getDate() - 1);
        if (d.toDateString() === today.toDateString()) return 'Сегодня';
        if (d.toDateString() === yest.toDateString()) return 'Вчера';
        return d.getDate() + ' ' + ['января','февраля','марта','апреля','мая','июня','июля','августа','сентября','октября','ноября','декабря'][d.getMonth()];
    }

    // ── Feed Component ─────────────────────────────────────────────────────────
    function TgFeedComponent(object) {
        var el = document.createElement('div');
        el.className = 'tg-bridge-feed';

        function buildContent() {
            el.innerHTML = '';
            var cache = getCache();

            var head = document.createElement('div');
            head.className = 'tg-bridge-feed__head';
            head.innerHTML = '<div class="tg-bridge-feed__title">Рекомендации от друзей</div>' +
                             '<div class="tg-bridge-feed__subtitle">' + (getChatName() || '') + '</div>';
            el.appendChild(head);

            var list = document.createElement('div');
            list.className = 'tg-bridge-feed__list';

            if (!cache.length) {
                list.innerHTML = '<div class="tg-empty">📭 Пока пусто.<br>Попроси друзей написать /rec в чате с ботом.</div>';
                el.appendChild(list);
                return;
            }

            var lastDay = '';
            cache.forEach(function (rec) {
                var day = dayLabel(rec.ts);
                if (day !== lastDay) {
                    var dh = document.createElement('div');
                    dh.className = 'tg-date-header';
                    dh.textContent = day;
                    list.appendChild(dh);
                    lastDay = day;
                }

                var comment = rec.comment || '';
                if (!comment && rec.comments && rec.comments.length) {
                    comment = rec.comments.map(function (c) { return '«' + c.comment + '» — ' + c.from_name; }).join(' / ');
                }

                var card = document.createElement('div');
                card.className = 'tg-rec selector';
                card.dataset.id = rec.id;
                card.innerHTML =
                    '<div class="tg-rec__poster" style="background-image:url(\'' + (rec.poster || '') + '\')"></div>' +
                    '<div class="tg-rec__body">' +
                        '<div class="tg-rec__title">' + rec.title + ' <span class="tg-rec__year">(' + (rec.year || '') + ')</span></div>' +
                        (rec.vote ? '<div class="tg-rec__rating">★ ' + rec.vote + '</div>' : '') +
                        '<div class="tg-rec__from">👤 ' + rec.from_name + ' · ' + timeAgo(rec.ts) + '</div>' +
                        (comment ? '<div class="tg-rec__comment">«' + comment + '»</div>' : '') +
                        '<div class="tg-rec__actions">' +
                            '<div class="tg-rec__btn selector" data-action="open">Открыть</div>' +
                            '<div class="tg-rec__btn selector" data-action="book">В книгу</div>' +
                            '<div class="tg-rec__btn selector" data-action="hide">Скрыть</div>' +
                        '</div>' +
                    '</div>';

                (function (r) {
                    card.addEventListener('mouseenter', function () { card.classList.add('focus'); });
                    card.addEventListener('mouseleave', function () { card.classList.remove('focus'); });
                    card.querySelector('[data-action="open"]').addEventListener('click', function () { openMovie(r); });
                    card.querySelector('[data-action="book"]').addEventListener('click', function () { addToBook(r); });
                    card.querySelector('[data-action="hide"]').addEventListener('click', function () { hideRec(r.id, card); });
                    $(card).on('hover:enter', function () {
                        var a = card.querySelector('.tg-rec__btn.focus');
                        if (a) a.click();
                        else openMovie(r);
                    });
                }(rec));

                list.appendChild(card);
            });

            el.appendChild(list);
        }

        // Lampa calls render() to get the root element for appending to the slide
        this.render = function () { return el; };

        // Lampa calls create() to initialize after render()
        this.create = function () {
            if (!getToken()) {
                setTimeout(function () {
                    Lampa.Activity.replace({ component: 'tg_bridge_settings' });
                }, 0);
                return el;
            }
            clearBadge();
            buildContent();
            this.activity.loader(false);
            return el;
        };

        this.start = function () {
            Lampa.Controller.add('tg_bridge_feed', {
                toggle: function () {
                    Lampa.Controller.collectionSet($(el));
                    Lampa.Controller.collectionFocus(false, $(el));
                },
                up:    function () { Navigator.move('up'); },
                down:  function () { Navigator.move('down'); },
                left:  function () { Navigator.move('left'); },
                right: function () { Navigator.move('right'); },
                back:  function () { Lampa.Activity.backward(); }
            });
            Lampa.Controller.toggle('tg_bridge_feed');
        };

        this.pause   = function () {};
        this.stop    = function () {};
        this.destroy = function () { el.innerHTML = ''; };
    }

    // ── Settings Component ─────────────────────────────────────────────────────
    function TgSettingsComponent(object) {
        var el = document.createElement('div');
        el.className = 'tg-bridge-settings';

        function build() {
            el.innerHTML = '<div class="tg-bridge-settings__title">Telegram Bridge</div>';
            var body = document.createElement('div');
            body.className = 'tg-bridge-settings__body';
            var token = getToken();
            if (!token) buildLinkForm(body);
            else buildLinked(body, token);
            el.appendChild(body);
        }

        function buildLinkForm(body) {
            var wrap = document.createElement('div');
            wrap.className = 'tg-bridge-link';
            wrap.innerHTML =
                '<div class="tg-bridge-link__title">Подключи Telegram-чат</div>' +
                '<div class="tg-bridge-link__step">1. Открой <b>' + BOT_NAME + '</b> в Telegram</div>' +
                '<div class="tg-bridge-link__step">2. Введи команду: <code>/link</code></div>' +
                '<div class="tg-bridge-link__step">3. Получи 6-значный код и введи ниже:</div>' +
                '<div class="tg-bridge-link__input-wrap"><input class="tg-bridge-link__input" type="text" maxlength="6" placeholder="000000"></div>' +
                '<div class="tg-bridge-link__btn selector">Подтвердить</div>' +
                '<div class="tg-bridge-link__error" style="display:none"></div>';

            var btn = wrap.querySelector('.tg-bridge-link__btn');
            var inp = wrap.querySelector('.tg-bridge-link__input');
            var err = wrap.querySelector('.tg-bridge-link__error');

            function submit() {
                var code = inp.value.trim();
                if (!/^\d{6}$/.test(code)) { err.textContent = 'Введи 6-значный код.'; err.style.display = ''; return; }
                btn.textContent = 'Проверяю...';
                err.style.display = 'none';
                fetch(API_BASE + '/api/link/' + code, { method: 'POST' })
                    .then(function (r) { return r.json(); })
                    .then(function (data) {
                        if (data.token) {
                            Lampa.Storage.set('tg_bridge_token', data.token);
                            Lampa.Storage.set('tg_bridge_chat_name', data.chat_name);
                            Lampa.Noty.show('✅ Подключено к «' + data.chat_name + '»');
                            startPolling();
                            Lampa.Activity.replace({ component: 'tg_bridge_feed' });
                        } else {
                            err.textContent = 'Неверный или просроченный код.';
                            err.style.display = '';
                            btn.textContent = 'Подтвердить';
                        }
                    })
                    .catch(function () {
                        err.textContent = 'Ошибка соединения.';
                        err.style.display = '';
                        btn.textContent = 'Подтвердить';
                    });
            }

            btn.addEventListener('click', submit);
            inp.addEventListener('keydown', function (e) { if (e.key === 'Enter') submit(); });
            $(btn).on('hover:enter', submit);
            $(btn).on('hover:focus', function () { btn.classList.add('focus'); });
            $(btn).on('hover:blur',  function () { btn.classList.remove('focus'); });

            body.appendChild(wrap);
        }

        function row(label, val) {
            var d = document.createElement('div');
            d.className = 'tg-bridge-settings__row';
            d.innerHTML = '<span>' + label + '</span><span>' + val + '</span>';
            return d;
        }

        function toggleRow(label, key) {
            var d = document.createElement('div');
            d.className = 'tg-bridge-settings__row';
            var s = getSettings();
            var btn = document.createElement('span');
            btn.className = 'tg-bridge-settings__btn selector';
            btn.textContent = s[key] ? 'Вкл' : 'Выкл';
            d.innerHTML = '<span>' + label + '</span>';
            d.appendChild(btn);
            function toggle() {
                var cur = getSettings(); cur[key] = !cur[key];
                Lampa.Storage.set('tg_bridge_settings', cur);
                btn.textContent = cur[key] ? 'Вкл' : 'Выкл';
            }
            btn.addEventListener('click', toggle);
            $(btn).on('hover:enter', toggle);
            $(btn).on('hover:focus', function () { btn.classList.add('focus'); });
            $(btn).on('hover:blur',  function () { btn.classList.remove('focus'); });
            return d;
        }

        function buildLinked(body, token) {
            body.appendChild(row('Чат:', '«' + getChatName() + '» ✓'));
            body.appendChild(toggleRow('Уведомления о новых', 'notify'));
            body.appendChild(toggleRow('Сообщать в чат при «В книгу»', 'share_to_chat'));

            var unlinkRow = document.createElement('div');
            unlinkRow.className = 'tg-bridge-settings__row';
            var unlinkBtn = document.createElement('span');
            unlinkBtn.className = 'tg-bridge-settings__btn selector';
            unlinkBtn.textContent = 'Отвязать';
            unlinkRow.innerHTML = '<span>Lampa</span>';
            unlinkRow.appendChild(unlinkBtn);

            function doUnlink() {
                fetch(API_BASE + '/api/unlink', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: token }) }).catch(function () {});
                ['tg_bridge_token','tg_bridge_chat_name','tg_bridge_cache','tg_bridge_last_ts'].forEach(function (k) { Lampa.Storage.set(k, k.endsWith('ts') ? 0 : ''); });
                Lampa.Storage.set('tg_bridge_cache', []);
                clearBadge();
                if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
                Lampa.Noty.show('Lampa отвязана.');
                Lampa.Activity.replace({ component: 'tg_bridge_settings' });
            }
            unlinkBtn.addEventListener('click', doUnlink);
            $(unlinkBtn).on('hover:enter', doUnlink);
            $(unlinkBtn).on('hover:focus', function () { unlinkBtn.classList.add('focus'); });
            $(unlinkBtn).on('hover:blur',  function () { unlinkBtn.classList.remove('focus'); });
            body.appendChild(unlinkRow);
        }

        // Lampa calls render() to get the root element
        this.render = function () { return el; };

        // Lampa calls create() to initialize
        this.create = function () {
            build();
            this.activity.loader(false);
            return el;
        };

        this.start = function () {
            Lampa.Controller.add('tg_bridge_settings', {
                toggle: function () { Lampa.Controller.collectionSet($(el)); Lampa.Controller.collectionFocus(false, $(el)); },
                up:    function () { Navigator.move('up'); },
                down:  function () { Navigator.move('down'); },
                left:  function () { Navigator.move('left'); },
                right: function () { Navigator.move('right'); },
                back:  function () { Lampa.Activity.backward(); }
            });
            Lampa.Controller.toggle('tg_bridge_settings');
        };

        this.pause   = function () {};
        this.stop    = function () {};
        this.destroy = function () { el.innerHTML = ''; };
    }

    // ── Actions ────────────────────────────────────────────────────────────────
    function openMovie(rec) {
        Lampa.Activity.push({ url: '', title: rec.title, component: 'full', id: rec.tmdb_id, method: rec.media_type === 'tv' ? 'tv' : 'movie', from_tg_bridge: rec });
    }
    function addToBook(rec) {
        Lampa.Favorite.add('book', { id: rec.tmdb_id, title: rec.title, original_title: rec.title, release_date: rec.year ? rec.year + '-01-01' : '', poster_path: rec.poster ? rec.poster.replace('https://image.tmdb.org/t/p/w500', '') : '', vote_average: parseFloat(rec.vote) || 0 });
        Lampa.Noty.show('«' + rec.title + '» добавлено в книгу');
        if (getSettings().share_to_chat) {
            fetch(API_BASE + '/api/share', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: getToken(), type: 'added_to_book', tmdb_id: rec.tmdb_id, title: rec.title, user_name: Lampa.Storage.get('name', 'Пользователь') }) }).catch(function () {});
        }
    }
    function hideRec(id, cardEl) {
        Lampa.Storage.set('tg_bridge_cache', getCache().filter(function (r) { return r.id !== id; }));
        if (cardEl) cardEl.remove();
        Lampa.Noty.show('Скрыто');
    }

    // ── Menu button ────────────────────────────────────────────────────────────
    function addMenuButton() {
        var $item = $('<li class="menu__item selector" data-action="tg_bridge"><div class="menu__ico">✈</div><div class="menu__text">От друзей <span class="tg-badge"></span></div></li>');
        $item.on('hover:enter', function () {
            Lampa.Activity.push({ url: '', title: 'Рекомендации от друзей', component: 'tg_bridge_feed', page: 1 });
        });
        $('.menu .menu__list').eq(0).append($item);
        updateBadge(0);
    }

    // ── Inject rec source into movie card ──────────────────────────────────────
    Lampa.Listener.follow('full', function (e) {
        if (e.type === 'complite' && e.object && e.object.from_tg_bridge) {
            var rec = e.object.from_tg_bridge;
            setTimeout(function () {
                var $d = $('.full-start__description');
                if ($d.length && !$d.find('.tg-rec-source').length) {
                    $d.append('<div class="tg-rec-source" style="margin-top:.8em;opacity:.75;font-size:.88em;">💬 Рекомендация от <b>' + rec.from_name + '</b>' + (rec.comment ? ': «' + rec.comment + '»' : '') + '</div>');
                }
            }, 600);
        }
    });

    // ── Bootstrap ──────────────────────────────────────────────────────────────
    Lampa.Component.add('tg_bridge_feed', TgFeedComponent);
    Lampa.Component.add('tg_bridge_settings', TgSettingsComponent);

    Lampa.Listener.follow('app', function (e) {
        if (e.type === 'ready') {
            addMenuButton();
            if (getToken()) startPolling();
        }
    });

})();
