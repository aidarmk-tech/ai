(function () {
    'use strict';

    var API_BASE = 'https://filmkenttg.workers.dev';
    var BOT_NAME = '@Filmkent_bot';
    var POLL_INTERVAL = 30000;
    var pollTimer = null;

    Lampa.Template.add('tg_bridge_feed', [
        '<div class="tg-bridge-feed">',
        '  <div class="tg-bridge-feed__head">',
        '    <div class="tg-bridge-feed__title"></div>',
        '    <div class="tg-bridge-feed__subtitle"></div>',
        '  </div>',
        '  <div class="tg-bridge-feed__list"></div>',
        '</div>'
    ].join(''));

    Lampa.Template.add('tg_bridge_card', [
        '<div class="tg-rec selector" data-id="{id}">',
        '  <div class="tg-rec__poster" style="background-image:url({poster})"></div>',
        '  <div class="tg-rec__body">',
        '    <div class="tg-rec__title">{title} <span class="tg-rec__year">({year})</span></div>',
        '    <div class="tg-rec__rating">★ {vote}</div>',
        '    <div class="tg-rec__from">',
        '      <span class="tg-rec__from-name">{from_name}</span>',
        '      <span class="tg-rec__time">{time_ago}</span>',
        '    </div>',
        '    <div class="tg-rec__comment">{comment}</div>',
        '    <div class="tg-rec__actions">',
        '      <div class="tg-rec__btn selector" data-action="open">Открыть</div>',
        '      <div class="tg-rec__btn selector" data-action="book">В книгу</div>',
        '      <div class="tg-rec__btn selector" data-action="hide">Скрыть</div>',
        '    </div>',
        '  </div>',
        '</div>'
    ].join(''));

    Lampa.Template.add('tg_bridge_settings', [
        '<div class="tg-bridge-settings">',
        '  <div class="tg-bridge-settings__title">Telegram Bridge</div>',
        '  <div class="tg-bridge-settings__body"></div>',
        '</div>'
    ].join(''));

    Lampa.Template.add('tg_bridge_link', [
        '<div class="tg-bridge-link">',
        '  <div class="tg-bridge-link__title">Подключи Telegram-чат</div>',
        '  <div class="tg-bridge-link__step">1. Открой <b>{bot}</b> в Telegram</div>',
        '  <div class="tg-bridge-link__step">2. Отправь команду: <code>/link</code></div>',
        '  <div class="tg-bridge-link__step">3. Получишь 6-значный код. Введи его ниже:</div>',
        '  <div class="tg-bridge-link__input-wrap">',
        '    <input class="tg-bridge-link__input selector" type="text" maxlength="6" placeholder="000000">',
        '  </div>',
        '  <div class="tg-bridge-link__btn selector">Подтвердить</div>',
        '  <div class="tg-bridge-link__error" style="display:none"></div>',
        '</div>'
    ].join(''));

    var css = [
        '.tg-badge{background:#e74c3c;color:#fff;border-radius:10px;font-size:11px;padding:1px 6px;margin-left:5px;display:none}',
        '.tg-bridge-feed{padding:1.5em}',
        '.tg-bridge-feed__head{margin-bottom:1em}',
        '.tg-bridge-feed__title{font-size:1.4em;font-weight:bold}',
        '.tg-bridge-feed__subtitle{opacity:.6;font-size:.9em;margin-top:.3em}',
        '.tg-bridge-feed__list{display:flex;flex-direction:column;gap:1em}',
        '.tg-rec{display:flex;gap:1em;background:rgba(255,255,255,.05);border-radius:8px;padding:1em;transition:background .2s}',
        '.tg-rec.focus{background:rgba(255,255,255,.15)}',
        '.tg-rec__poster{width:90px;min-width:90px;height:135px;background-size:cover;background-position:center;border-radius:4px;background-color:rgba(255,255,255,.1)}',
        '.tg-rec__body{flex:1;display:flex;flex-direction:column;gap:.4em}',
        '.tg-rec__title{font-size:1.1em;font-weight:bold}',
        '.tg-rec__year{opacity:.6;font-weight:normal}',
        '.tg-rec__rating{color:#f1c40f;font-size:.9em}',
        '.tg-rec__from{display:flex;gap:.5em;opacity:.7;font-size:.85em;align-items:center}',
        '.tg-rec__comment{font-style:italic;opacity:.85;font-size:.9em}',
        '.tg-rec__actions{display:flex;gap:.5em;margin-top:.4em}',
        '.tg-rec__btn{padding:.3em .8em;border-radius:4px;background:rgba(255,255,255,.1);font-size:.85em;cursor:pointer;transition:background .2s}',
        '.tg-rec__btn.focus{background:#3498db}',
        '.tg-bridge-link{max-width:400px;padding:2em}',
        '.tg-bridge-link__title{font-size:1.3em;font-weight:bold;margin-bottom:1.5em}',
        '.tg-bridge-link__step{margin:.6em 0;line-height:1.5}',
        '.tg-bridge-link__step code{background:rgba(255,255,255,.15);padding:2px 6px;border-radius:3px}',
        '.tg-bridge-link__input-wrap{margin:1.2em 0}',
        '.tg-bridge-link__input{width:160px;padding:.5em;font-size:1.4em;letter-spacing:.2em;text-align:center;border-radius:4px;border:2px solid rgba(255,255,255,.3);background:rgba(0,0,0,.4);color:#fff}',
        '.tg-bridge-link__input:focus{border-color:#3498db;outline:none}',
        '.tg-bridge-link__btn{display:inline-block;margin-top:.5em;padding:.6em 1.5em;background:#3498db;border-radius:4px;cursor:pointer}',
        '.tg-bridge-link__btn.focus{background:#2980b9}',
        '.tg-bridge-link__error{color:#e74c3c;margin-top:.8em;font-size:.9em}',
        '.tg-bridge-settings{padding:1.5em;max-width:400px}',
        '.tg-bridge-settings__title{font-size:1.3em;font-weight:bold;margin-bottom:1.5em}',
        '.tg-bridge-settings__row{display:flex;justify-content:space-between;align-items:center;padding:.7em 0;border-bottom:1px solid rgba(255,255,255,.1)}',
        '.tg-bridge-settings__row:last-child{border-bottom:none}',
        '.tg-bridge-settings__btn{padding:.4em 1em;background:rgba(255,255,255,.1);border-radius:4px;cursor:pointer}',
        '.tg-bridge-settings__btn.focus{background:#e74c3c}',
        '.tg-date-header{padding:.6em 0 .3em;opacity:.5;font-size:.85em;text-transform:uppercase;letter-spacing:.05em}',
        '.tg-empty{padding:2em;text-align:center;opacity:.6;line-height:1.8}'
    ].join('\n');

    $('<style>').html(css).appendTo('head');

    function getToken() { return Lampa.Storage.get('tg_bridge_token', ''); }
    function getChatName() { return Lampa.Storage.get('tg_bridge_chat_name', ''); }
    function getCache() { return Lampa.Storage.get('tg_bridge_cache', []); }
    function getLastTs() { return Lampa.Storage.get('tg_bridge_last_ts', 0); }
    function getUnread() { return Lampa.Storage.get('tg_bridge_unread', 0); }
    function getSettings() {
        return Lampa.Storage.get('tg_bridge_settings', { notify: true, share_to_chat: true, poll_interval: 30 });
    }

    function updateBadge(add) {
        var current = getUnread();
        var next = add ? current + add : current;
        Lampa.Storage.set('tg_bridge_unread', next);
        var $badge = $('.menu .tg-badge');
        if (next > 0) $badge.text(next).show();
        else $badge.hide();
    }

    function clearBadge() {
        Lampa.Storage.set('tg_bridge_unread', 0);
        $('.menu .tg-badge').hide();
    }

    function fetchFeed() {
        var token = getToken();
        if (!token) return;
        var since = getLastTs();

        fetch(API_BASE + '/api/feed?token=' + encodeURIComponent(token) + '&since=' + since)
            .then(function (r) {
                if (r.status === 401) {
                    Lampa.Storage.set('tg_bridge_token', '');
                    Lampa.Storage.set('tg_bridge_chat_name', '');
                    clearBadge();
                    Lampa.Noty.show('Telegram Bridge: чат отключён. Переподключитесь.');
                    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
                    return null;
                }
                return r.json();
            })
            .then(function (data) {
                if (!data || !data.items || data.items.length === 0) return;
                var cached = getCache();
                var merged = data.items.concat(cached).slice(0, 100);
                Lampa.Storage.set('tg_bridge_cache', merged);
                Lampa.Storage.set('tg_bridge_last_ts', data.items[0].ts);
                var settings = getSettings();
                if (settings.notify) {
                    var first = data.items[0];
                    Lampa.Noty.show(first.from_name + ' рекомендует: ' + first.title);
                }
                updateBadge(data.items.length);
            })
            .catch(function () {});
    }

    function startPolling() {
        if (!getToken()) return;
        if (pollTimer) clearInterval(pollTimer);
        var settings = getSettings();
        var interval = (settings.poll_interval || 30) * 1000;
        fetchFeed();
        pollTimer = setInterval(fetchFeed, interval);
    }

    function timeAgo(ts) {
        var diff = Math.floor(Date.now() / 1000) - ts;
        if (diff < 60) return 'только что';
        if (diff < 3600) return Math.floor(diff / 60) + ' мин назад';
        if (diff < 86400) return Math.floor(diff / 3600) + 'ч назад';
        if (diff < 172800) return 'вчера';
        var d = new Date(ts * 1000);
        return d.getDate() + ' ' + ['янв','фев','мар','апр','май','июн','июл','авг','сен','окт','ноя','дек'][d.getMonth()];
    }

    function dayLabel(ts) {
        var d = new Date(ts * 1000);
        var today = new Date();
        var yest = new Date(); yest.setDate(yest.getDate() - 1);
        if (d.toDateString() === today.toDateString()) return 'Сегодня';
        if (d.toDateString() === yest.toDateString()) return 'Вчера';
        return d.getDate() + ' ' + ['января','февраля','марта','апреля','мая','июня','июля','августа','сентября','октября','ноября','декабря'][d.getMonth()];
    }

    function TgFeedComponent(object) {
        var comp = Lampa.Template.get('tg_bridge_feed', {});
        var scroll = new Lampa.Scroll({ mask: true, over: true });

        this.create = function () {
            if (!getToken()) { Lampa.Activity.replace({ component: 'tg_bridge_settings' }); return; }
            clearBadge();
            this.render();
            this.activity.loader(false);
            return comp;
        };

        this.render = function () {
            var cache = getCache();
            var $list = comp.find('.tg-bridge-feed__list');
            $list.empty();
            comp.find('.tg-bridge-feed__title').text('Рекомендации от друзей');
            comp.find('.tg-bridge-feed__subtitle').text(getChatName() || '');

            if (cache.length === 0) {
                $list.append('<div class="tg-empty">📭 Пока пусто.<br>Попроси друзей написать /rec в чате.</div>');
                return;
            }

            var lastDay = '';
            cache.forEach(function (rec) {
                var day = dayLabel(rec.ts);
                if (day !== lastDay) {
                    $list.append('<div class="tg-date-header">' + day + '</div>');
                    lastDay = day;
                }
                var comment = rec.comment
                    ? '«' + rec.comment + '»'
                    : (rec.comments && rec.comments.length
                        ? rec.comments.map(function (c) { return '«' + c.comment + '» — ' + c.from_name; }).join('<br>')
                        : '');

                var card = Lampa.Template.get('tg_bridge_card', {
                    id: rec.id, poster: rec.poster || '', title: rec.title,
                    year: rec.year || '', vote: rec.vote || '—',
                    from_name: rec.from_name, time_ago: timeAgo(rec.ts), comment: comment
                });

                if (!rec.poster) card.find('.tg-rec__poster').css('background-color', 'rgba(255,255,255,.08)');
                if (!comment) card.find('.tg-rec__comment').hide();

                card.on('hover:focus', function () { card.addClass('focus'); });
                card.on('hover:blur', function () { card.removeClass('focus'); });
                card.find('[data-action="open"]').on('hover:enter', function () { openMovie(rec); });
                card.find('[data-action="book"]').on('hover:enter', function () { addToBook(rec); });
                card.find('[data-action="hide"]').on('hover:enter', function () { hideRec(rec.id); });
                $list.append(card);
            });

            scroll.render().find('.scroll__body').append($list);
        };

        this.start = function () {
            Lampa.Controller.add('tg_bridge_feed', {
                toggle: function () { Lampa.Controller.collectionSet(scroll.render()); Lampa.Controller.collectionFocus(false, scroll.render()); },
                up: function () { Navigator.move('up'); },
                down: function () { Navigator.move('down'); },
                left: function () { Navigator.move('left'); },
                right: function () { Navigator.move('right'); },
                back: function () { Lampa.Activity.backward(); }
            });
            Lampa.Controller.toggle('tg_bridge_feed');
        };

        this.pause = function () {};
        this.stop = function () {};
        this.destroy = function () { scroll.destroy(); };
    }

    function TgSettingsComponent(object) {
        var comp = Lampa.Template.get('tg_bridge_settings', {});

        this.create = function () {
            var $body = comp.find('.tg-bridge-settings__body');
            $body.empty();
            var token = getToken();
            if (!token) renderLinkForm($body);
            else renderLinked($body, token);
            this.activity.loader(false);
            return comp;
        };

        function renderLinkForm($body) {
            var form = Lampa.Template.get('tg_bridge_link', { bot: BOT_NAME });
            form.find('.tg-bridge-link__btn').on('hover:enter click', function () {
                var code = form.find('.tg-bridge-link__input').val().trim();
                if (!/^\d{6}$/.test(code)) { form.find('.tg-bridge-link__error').text('Введи 6-значный код.').show(); return; }
                var $err = form.find('.tg-bridge-link__error');
                var $btn = form.find('.tg-bridge-link__btn');
                $btn.text('Проверяю...');
                $err.hide();
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
                            $err.text('Неверный или просроченный код.').show();
                            $btn.text('Подтвердить');
                        }
                    })
                    .catch(function () { $err.text('Ошибка соединения.').show(); $btn.text('Подтвердить'); });
            });
            form.find('.tg-bridge-link__btn').on('hover:focus', function () { $(this).addClass('focus'); });
            form.find('.tg-bridge-link__btn').on('hover:blur', function () { $(this).removeClass('focus'); });
            $body.append(form);
        }

        function renderLinked($body, token) {
            function row(label, value) {
                return $('<div class="tg-bridge-settings__row"><span>' + label + '</span><span>' + value + '</span></div>');
            }
            function toggleRow(label, key) {
                var s = getSettings();
                var $row = $('<div class="tg-bridge-settings__row"></div>');
                var $toggle = $('<span class="tg-bridge-settings__btn selector">' + (s[key] ? 'Вкл' : 'Выкл') + '</span>');
                $toggle.on('hover:enter', function () {
                    var cur = getSettings(); cur[key] = !cur[key];
                    Lampa.Storage.set('tg_bridge_settings', cur);
                    $toggle.text(cur[key] ? 'Вкл' : 'Выкл');
                });
                $toggle.on('hover:focus', function () { $toggle.addClass('focus'); });
                $toggle.on('hover:blur', function () { $toggle.removeClass('focus'); });
                $row.append($('<span>' + label + '</span>')).append($toggle);
                return $row;
            }
            $body.append(row('Подключённый чат:', '«' + (getChatName() || '—') + '» ✓'));
            $body.append(toggleRow('Уведомлять о новых', 'notify'));
            $body.append(toggleRow('Отправлять в чат при «В книгу»', 'share_to_chat'));
            var $unlinkRow = $('<div class="tg-bridge-settings__row"></div>');
            var $unlinkBtn = $('<span class="tg-bridge-settings__btn selector">Отвязать</span>');
            $unlinkBtn.on('hover:enter', function () {
                fetch(API_BASE + '/api/unlink', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: token }) }).catch(function () {});
                Lampa.Storage.set('tg_bridge_token', ''); Lampa.Storage.set('tg_bridge_chat_name', '');
                Lampa.Storage.set('tg_bridge_cache', []); Lampa.Storage.set('tg_bridge_last_ts', 0);
                clearBadge(); if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
                Lampa.Noty.show('Lampa отвязана.');
                Lampa.Activity.replace({ component: 'tg_bridge_settings' });
            });
            $unlinkBtn.on('hover:focus', function () { $unlinkBtn.addClass('focus'); });
            $unlinkBtn.on('hover:blur', function () { $unlinkBtn.removeClass('focus'); });
            $unlinkRow.append($('<span>Lampa</span>')).append($unlinkBtn);
            $body.append($unlinkRow);
        }

        this.start = function () {
            Lampa.Controller.add('tg_bridge_settings', {
                toggle: function () { Lampa.Controller.collectionSet(comp); Lampa.Controller.collectionFocus(false, comp); },
                up: function () { Navigator.move('up'); },
                down: function () { Navigator.move('down'); },
                left: function () { Navigator.move('left'); },
                right: function () { Navigator.move('right'); },
                back: function () { Lampa.Activity.backward(); }
            });
            Lampa.Controller.toggle('tg_bridge_settings');
        };

        this.pause = function () {};
        this.stop = function () {};
        this.destroy = function () {};
    }

    function openMovie(rec) {
        Lampa.Activity.push({ url: '', title: rec.title, component: 'full', id: rec.tmdb_id, method: rec.media_type === 'tv' ? 'tv' : 'movie', from_tg_bridge: rec });
    }

    function addToBook(rec) {
        var card = { id: rec.tmdb_id, title: rec.title, original_title: rec.title, release_date: rec.year ? rec.year + '-01-01' : '', poster_path: rec.poster ? rec.poster.replace('https://image.tmdb.org/t/p/w500', '') : '', vote_average: parseFloat(rec.vote) || 0 };
        Lampa.Favorite.add('book', card);
        Lampa.Noty.show('«' + rec.title + '» добавлено в книгу');
        var settings = getSettings();
        if (settings.share_to_chat) {
            fetch(API_BASE + '/api/share', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: getToken(), type: 'added_to_book', tmdb_id: rec.tmdb_id, title: rec.title, user_name: Lampa.Storage.get('name', 'Пользователь') }) }).catch(function () {});
        }
    }

    function hideRec(id) {
        Lampa.Storage.set('tg_bridge_cache', getCache().filter(function (r) { return r.id !== id; }));
        Lampa.Noty.show('Рекомендация скрыта');
        $('[data-id="' + id + '"]').fadeOut(200, function () { $(this).remove(); });
    }

    function addMenuButton() {
        var $item = $('<li class="menu__item selector" data-action="tg_bridge"><div class="menu__ico">✈</div><div class="menu__text">От друзей <span class="tg-badge"></span></div></li>');
        $item.on('hover:enter', function () { Lampa.Activity.push({ url: '', title: 'Рекомендации от друзей', component: 'tg_bridge_feed', page: 1 }); });
        $('.menu .menu__list').eq(0).append($item);
        updateBadge(0);
    }

    Lampa.Listener.follow('full', function (e) {
        if (e.type === 'complite' && e.object && e.object.from_tg_bridge) {
            var rec = e.object.from_tg_bridge;
            var block = $('<div class="tg-rec-source" style="margin-top:1em;opacity:.8;font-size:.9em;">💬 Рекомендация от <b>' + rec.from_name + '</b>' + (rec.comment ? ': «' + rec.comment + '»' : '') + '</div>');
            setTimeout(function () { var $d = $('.full-start__description'); if ($d.length && !$d.find('.tg-rec-source').length) $d.append(block); }, 500);
        }
    });

    Lampa.Component.add('tg_bridge_feed', TgFeedComponent);
    Lampa.Component.add('tg_bridge_settings', TgSettingsComponent);

    Lampa.Listener.follow('app', function (e) {
        if (e.type === 'ready') { addMenuButton(); if (getToken()) startPolling(); }
    });

})();
