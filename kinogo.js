/*!
 * КиноГо — Российские шоу для Lampa
 * Каталог go2.kinogo.cc, воспроизведение через встроенный iframe-плеер.
 * Работает в bylampa и других форках с отключённым CORS в WebView.
 */
(function () {
  'use strict';

  var BASE      = 'https://go2.kinogo.cc';
  var RU_SHOWS  = BASE + '/tv-shou/country-tv-shou-rossiya/';
  var PN        = 'kinogo';

  // ─── СТИЛИ ────────────────────────────────────────────────────────────────
  function injectStyles() {
    if (document.getElementById('kg-styles')) return;
    var css = [
      /* Корень (каталог / страница шоу) */
      '.kg-root{width:100%;height:100%;background:#0d0d0d;color:#fff;overflow-y:auto;overflow-x:hidden;-webkit-overflow-scrolling:touch}',
      '.kg-page{padding:1.5em 1.5em 6em}',

      /* Состояния */
      '.kg-loading{padding:4em 1em;text-align:center;font-size:1.3em;opacity:0.55}',
      '.kg-error{padding:3em 1em;text-align:center;font-size:1.1em;color:#ff7e7e;line-height:1.6;opacity:0.9}',

      /* Заголовок каталога */
      '.kg-catalog-title{font-size:1.8em;font-weight:800;margin-bottom:1em;color:#fff;display:flex;align-items:center;gap:0.5em}',

      /* Сетка карточек */
      '.kg-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(11em,1fr));gap:1em}',

      /* Карточка */
      '.kg-card{border-radius:0.5em;overflow:hidden;background:#1c1c1c;cursor:pointer;outline:none;transition:transform 0.15s,box-shadow 0.15s;position:relative}',
      '.kg-card.focus{transform:scale(1.06);box-shadow:0 0 0 0.2em #e53935,0 0 1.5em rgba(229,57,53,0.4)}',
      '.kg-card__poster{padding-top:145%;background:#2a2a2a center/cover no-repeat;position:relative}',
      '.kg-card__no-poster{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:2.5em;opacity:0.2}',
      '.kg-card__title{padding:0.5em 0.6em 0.7em;font-size:0.88em;font-weight:600;line-height:1.35;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.5em}',

      /* «Ещё» */
      '.kg-more{margin-top:1.5em;text-align:center;padding:0.9em 2em;background:#222;border-radius:0.4em;font-size:1.1em;font-weight:600;cursor:pointer;outline:none;color:#e53935;transition:background 0.15s}',
      '.kg-more.focus{background:#333;box-shadow:0 0 0 0.18em #e53935}',

      /* Страница шоу */
      '.kg-hero{height:18em;background:#111 center/cover no-repeat;position:relative;border-radius:0.5em;overflow:hidden;margin-bottom:1.2em}',
      '.kg-hero__overlay{position:absolute;inset:0;background:linear-gradient(180deg,rgba(13,13,13,0) 30%,#0d0d0d 100%)}',
      '.kg-show-title{font-size:2em;font-weight:900;line-height:1.1;margin-bottom:1.2em}',
      '.kg-show-desc{font-size:1.05em;line-height:1.6;opacity:0.75;margin-bottom:1.5em;max-width:60em}',

      /* Кнопка «Смотреть» */
      '.kg-play-btn{display:inline-flex;align-items:center;gap:0.5em;padding:0.7em 1.8em;background:#e53935;border-radius:0.4em;font-size:1.2em;font-weight:700;cursor:pointer;outline:none;transition:background 0.15s,transform 0.12s;color:#fff}',
      '.kg-play-btn.focus{background:#c62828;transform:scale(1.04);box-shadow:0 0 0 0.2em rgba(229,57,53,0.5)}',

      /* Плеер — полноэкранный оверлей */
      '.kg-player-root{position:fixed;inset:0;z-index:99999;background:#000;display:flex;flex-direction:column}',
      '.kg-player-bar{display:flex;align-items:center;gap:1em;padding:0.5em 1em;background:rgba(0,0,0,0.85);flex-shrink:0;min-height:3em}',
      '.kg-back-btn{padding:0.4em 1.1em;border-radius:0.35em;background:rgba(255,255,255,0.1);font-size:1.05em;font-weight:600;cursor:pointer;outline:none;white-space:nowrap;color:#fff;transition:background 0.15s}',
      '.kg-back-btn.focus{background:#e53935;box-shadow:0 0 0 0.18em rgba(229,57,53,0.6)}',
      '.kg-player-title{font-size:1em;opacity:0.65;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      '.kg-player-frame{flex:1;border:none;width:100%;display:block}',

      /* Меню */
      '.menu__item[data-action="kinogo"] .menu__ico{font-size:1.4em}'
    ].join('\n');

    var el = document.createElement('style');
    el.id = 'kg-styles';
    el.textContent = css;
    document.head.appendChild(el);
  }

  // ─── УТИЛИТЫ ──────────────────────────────────────────────────────────────
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  // ─── FETCH С ТАЙМАУТОМ ────────────────────────────────────────────────────
  function httpGet(url, callback) {
    var done = false;
    var tid  = setTimeout(function () {
      if (!done) { done = true; callback(new Error('timeout'), null); }
    }, 18000);

    fetch(url, {
      headers: {
        'Accept': 'text/html,application/xhtml+xml',
        'Accept-Language': 'ru-RU,ru;q=0.9'
      }
    })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        if (done) return;
        done = true; clearTimeout(tid);
        callback(null, html);
      })
      .catch(function (e) {
        if (done) return;
        done = true; clearTimeout(tid);
        callback(e, null);
      });
  }

  // ─── ПАРСЕР КАТАЛОГА ──────────────────────────────────────────────────────
  function parseCatalog(html) {
    var doc   = new DOMParser().parseFromString(html, 'text/html');
    var items = [];

    var selectors = [
      '.short-story', '.shortstory', '.short',
      'article.item', '.movie-item', '.card-item', '.post-item'
    ];
    var cards = [];
    for (var si = 0; si < selectors.length && !cards.length; si++) {
      cards = Array.from(doc.querySelectorAll(selectors[si]));
    }

    if (!cards.length) {
      var links = Array.from(doc.querySelectorAll('a[href]')).filter(function (a) {
        var h = a.getAttribute('href') || '';
        return h.includes('.html') && a.querySelector('img');
      });
      links.forEach(function (a) {
        var wrapper = a.closest('div') || a;
        if (cards.indexOf(wrapper) === -1) cards.push(wrapper);
      });
    }

    cards.forEach(function (card) {
      var linkEl  = card.querySelector('a[href]') || (card.tagName === 'A' ? card : null);
      var imgEl   = card.querySelector('img');
      var titleEl = card.querySelector('h2 a, h3 a, .short-title a, .title a')
                 || card.querySelector('h2, h3, .title, .name');

      if (!linkEl) return;

      var href = linkEl.getAttribute('href') || '';
      if (!href || /javascript:|^#/.test(href)) return;
      if (!href.startsWith('http')) href = BASE + (href.startsWith('/') ? '' : '/') + href.replace(/^\//, '');
      if (!href.startsWith(BASE)) return;

      var poster = '';
      if (imgEl) {
        poster = imgEl.getAttribute('data-src') || imgEl.getAttribute('src') || '';
        if (poster && poster.startsWith('//')) poster = 'https:' + poster;
        if (poster && !poster.startsWith('http')) poster = BASE + (poster.startsWith('/') ? '' : '/') + poster.replace(/^\//, '');
      }

      var title = '';
      if (titleEl)             title = titleEl.textContent.trim();
      if (!title && linkEl.title) title = linkEl.title.trim();
      if (!title)              title = linkEl.textContent.trim().substring(0, 80);
      title = title.replace(/\s+/g, ' ').trim();
      if (!title || title.length < 2) return;

      if (!items.find(function (i) { return i.url === href; })) {
        items.push({ title: title, url: href, poster: poster });
      }
    });

    var nextEl  = doc.querySelector('a[rel="next"], .nextpostslink, .pnext a, .next-page a, .navigation .next');
    var nextUrl = null;
    if (nextEl) {
      var nh = nextEl.getAttribute('href') || '';
      if (nh && !/javascript:|^#/.test(nh)) {
        nextUrl = nh.startsWith('http') ? nh : BASE + nh;
      }
    }
    if (!nextUrl) {
      var pageLinks = Array.from(doc.querySelectorAll('.pagination a, .pages a, [class*="pag"] a'));
      pageLinks.forEach(function (a) {
        var h = a.getAttribute('href') || '';
        if (h.includes('page/2') || h.includes('?page=2')) {
          nextUrl = h.startsWith('http') ? h : BASE + h;
        }
      });
    }

    return { items: items, nextUrl: nextUrl };
  }

  // ─── ПАРСЕР URL ПЛЕЕРА СО СТРАНИЦЫ ШОУ ───────────────────────────────────
  function parsePlayerUrl(html) {
    var doc = new DOMParser().parseFromString(html, 'text/html');

    var iframes = Array.from(doc.querySelectorAll('iframe'));
    for (var i = 0; i < iframes.length; i++) {
      var src = iframes[i].getAttribute('src') || iframes[i].getAttribute('data-src') || '';
      if (src && src.length > 12 && !/about:|javascript:|#/.test(src)) {
        if (src.startsWith('//')) src = 'https:' + src;
        return src;
      }
    }

    var scripts = Array.from(doc.querySelectorAll('script:not([src])'));
    var playerHosts = ['kodik', 'video', 'iframe', 'player', 'embed', 'voidboost', 'hdvb', 'videocdn', 'bazon'];
    var urlRe = /['"]((?:https?:)?\/\/[^\s'"<>]{15,})['"/g;

    for (var s = 0; s < scripts.length; s++) {
      var code = scripts[s].textContent;
      var m;
      urlRe.lastIndex = 0;
      while ((m = urlRe.exec(code)) !== null) {
        var u = m[1];
        var hostMatch = playerHosts.some(function (h) { return u.indexOf(h) !== -1; });
        if (hostMatch) {
          if (u.startsWith('//')) u = 'https:' + u;
          return u;
        }
      }
    }

    var rawRe = /['"](//[a-z0-9-]+\.[a-z]{2,}\/[^'"<>\s]{10,})['"/gi;
    var rawM  = rawRe.exec(html);
    if (rawM) return 'https:' + rawM[1];

    return null;
  }

  // ─── ПАРСЕР ОПИСАНИЯ ──────────────────────────────────────────────────────
  function parseDescription(html) {
    var doc    = new DOMParser().parseFromString(html, 'text/html');
    var descEl = doc.querySelector('.full-story, .fullstory, .movie-description, .description, [class*="desc"], [itemprop="description"]');
    if (!descEl) return '';
    var text = descEl.textContent.replace(/\s+/g, ' ').trim();
    return text.substring(0, 500) + (text.length > 500 ? '…' : '');
  }

  // ─── РЕНДЕР КАРТОЧКИ ──────────────────────────────────────────────────────
  function renderCard(item) {
    var $card = $(
      '<div class="kg-card selector" tabindex="0">' +
        '<div class="kg-card__poster"' +
          (item.poster ? ' style="background-image:url(\'' + esc(item.poster) + '\')"' : '') + '>' +
          (!item.poster ? '<div class="kg-card__no-poster">📺</div>' : '') +
        '</div>' +
        '<div class="kg-card__title">' + esc(item.title) + '</div>' +
      '</div>'
    );
    $card.on('hover:enter', function () {
      Lampa.Activity.push({
        component: PN + '_show',
        title:     item.title,
        url:       item.url,
        poster:    item.poster
      });
    });
    return $card;
  }

  // ─── SCROLL HELPER ────────────────────────────────────────────────────────
  function scrollTo($root, el) {
    if (!el || !$root || !$root[0]) return;
    var raf = window.requestAnimationFrame || function (cb) { setTimeout(cb, 16); };
    raf(function () {
      if (!$root || !$root[0]) return;
      var root  = $root[0];
      var viewH = root.clientHeight;
      if (viewH > 0) {
        var top = 0, node = el;
        while (node && node !== root) { top += node.offsetTop; node = node.offsetParent; }
        var bottom = top + el.offsetHeight;
        var margin = 80;
        if (top - margin < root.scrollTop) root.scrollTop = Math.max(0, top - margin);
        else if (bottom + margin > root.scrollTop + viewH) root.scrollTop = bottom + margin - viewH;
      } else {
        try { el.scrollIntoView({ behavior: 'auto', block: 'nearest' }); } catch (e) {}
      }
    });
  }

  // ─── СТАНДАРТНЫЙ КОНТРОЛЛЕР ───────────────────────────────────────────────
  function makeController($root, lastFocused, back) {
    return {
      toggle: function () {
        Lampa.Controller.collectionSet($root);
        var target = (lastFocused[0] && $root[0] && $.contains($root[0], lastFocused[0]))
          ? lastFocused[0] : $root.find('.selector').first()[0];
        if (target) { Lampa.Controller.collectionFocus(target, $root); scrollTo($root, target); }
      },
      update: function () { Lampa.Controller.collectionSet($root); },
      left:  function () {
        if (typeof Navigator !== 'undefined' && Navigator.canmove && Navigator.canmove('left')) Navigator.move('left');
        else Lampa.Controller.toggle('menu');
      },
      right: function () { if (typeof Navigator !== 'undefined' && Navigator.move) Navigator.move('right'); },
      up:    function () {
        if (typeof Navigator !== 'undefined' && Navigator.canmove && Navigator.canmove('up')) Navigator.move('up');
        else Lampa.Controller.toggle('head');
      },
      down:  function () { if (typeof Navigator !== 'undefined' && Navigator.move) Navigator.move('down'); },
      back:  back
    };
  }

  // ─── КАТАЛОГ ──────────────────────────────────────────────────────────────
  function KingoCatalogActivity(object) {
    var $root       = $('<div class="kg-root"><div class="kg-page"></div></div>');
    var $page       = $root.find('.kg-page');
    var lastFocused = [null];
    var self        = this;
    var currentNext = null;

    this.create = function () { this.load(object.url || RU_SHOWS); return this.render(); };
    this.render = function () { return $root; };

    this.load = function (url) {
      $page.html('<div class="kg-loading">📡 Загружаем российские шоу...</div>');
      httpGet(url, function (err, html) {
        if (!$page) return;
        if (err || !html) {
          $page.html('<div class="kg-error">Не удалось загрузить список шоу.<br>Проверьте интернет-соединение или попробуйте позже.</div>');
          return;
        }

        var result = parseCatalog(html);
        $page.empty();
        $page.append('<div class="kg-catalog-title">🇷🇺 Российские шоу</div>');

        if (!result.items.length) {
          $page.html('<div class="kg-error">Не удалось распознать список шоу.<br>Возможно, сайт изменил структуру страницы.</div>');
          return;
        }

        var $grid = $('<div class="kg-grid"></div>');
        result.items.forEach(function (item) { $grid.append(renderCard(item)); });
        $page.append($grid);
        currentNext = result.nextUrl;

        if (currentNext) {
          var $more = $('<div class="kg-more selector" tabindex="0">⬇ Загрузить ещё</div>');
          $more.on('hover:enter', function () {
            if (!currentNext) return;
            var loadingNext = currentNext;
            currentNext = null;
            $more.text('⏳ Загружаем...');

            httpGet(loadingNext, function (err2, html2) {
              if (!$page) return;
              if (err2 || !html2) { $more.text('Ошибка — попробуйте ещё раз'); currentNext = loadingNext; return; }
              var r2 = parseCatalog(html2);
              r2.items.forEach(function (item) { $grid.append(renderCard(item)); });
              currentNext = r2.nextUrl;
              if (currentNext) $more.text('⬇ Загрузить ещё');
              else $more.remove();
              try { Lampa.Controller.collectionSet($root); } catch (e) {}
            });
          });
          $page.append($more);
        }

        try { Lampa.Controller.toggle('content'); } catch (e) {}
      });
    };

    $root.on('hover:focus', '.selector', function () {
      lastFocused[0] = this; scrollTo($root, this);
    });

    this.start = function () {
      var back = this.back;
      function setH() { if ($root && $root[0]) { var h = window.innerHeight || screen.height; if (h > 100) $root[0].style.height = h + 'px'; } }
      setH(); setTimeout(setH, 300);
      Lampa.Controller.add('content', makeController($root, lastFocused, back));
      Lampa.Controller.toggle('content');
    };

    this.pause = this.stop = function () {};
    this.back    = function () { Lampa.Activity.backward(); };
    this.destroy = function () { if ($root) $root.remove(); $root = null; $page = null; };
  }

  // ─── СТРАНИЦА ШОУ ─────────────────────────────────────────────────────────
  function KinogoShowActivity(object) {
    var $root       = $('<div class="kg-root"><div class="kg-page"></div></div>');
    var $page       = $root.find('.kg-page');
    var lastFocused = [null];
    var loaded      = false;

    this.create  = function () { this.load(); return this.render(); };
    this.render  = function () { return $root; };

    this.load = function () {
      $page.html('<div class="kg-loading">📡 Загружаем страницу шоу...</div>');
      httpGet(object.url, function (err, html) {
        if (loaded || !$page) return;
        loaded = true;

        if (err || !html) {
          $page.html('<div class="kg-error">Не удалось загрузить страницу шоу.<br>' + esc(object.url) + '</div>');
          return;
        }

        var playerUrl = parsePlayerUrl(html);
        var desc      = parseDescription(html);

        $page.empty();

        if (object.poster) {
          $page.append(
            '<div class="kg-hero" style="background-image:url(\'' + esc(object.poster) + '\')">' +
              '<div class="kg-hero__overlay"></div>' +
            '</div>'
          );
        }

        $page.append('<div class="kg-show-title">' + esc(object.title || '') + '</div>');
        if (desc) $page.append('<div class="kg-show-desc">' + esc(desc) + '</div>');

        if (playerUrl) {
          var $btn = $('<div class="kg-play-btn selector" tabindex="0">▶ Смотреть</div>');
          $btn.on('hover:enter', function () {
            Lampa.Activity.push({
              component: PN + '_player',
              title:     object.title,
              url:       playerUrl
            });
          });
          $page.append($btn);
        } else {
          $page.append('<div class="kg-error">Не удалось найти плеер на странице.<br>Попробуйте открыть сайт вручную: ' + esc(object.url) + '</div>');
        }

        try { Lampa.Controller.toggle('content'); } catch (e) {}
      });
    };

    $root.on('hover:focus', '.selector', function () {
      lastFocused[0] = this; scrollTo($root, this);
    });

    this.start = function () {
      var back = this.back;
      function setH() { if ($root && $root[0]) { var h = window.innerHeight || screen.height; if (h > 100) $root[0].style.height = h + 'px'; } }
      setH(); setTimeout(setH, 300);
      Lampa.Controller.add('content', makeController($root, lastFocused, back));
      Lampa.Controller.toggle('content');
    };

    this.pause = this.stop = function () {};
    this.back    = function () { Lampa.Activity.backward(); };
    this.destroy = function () { if ($root) $root.remove(); $root = null; $page = null; };
  }

  // ─── IFRAME ПЛЕЕР ─────────────────────────────────────────────────────────
  function KinogoPlayerActivity(object) {
    var $root   = $('<div class="kg-player-root"></div>');
    var $iframe = null;

    this.create = function () { return this.render(); };

    this.render = function () {
      $root.append(
        '<div class="kg-player-bar">' +
          '<div class="kg-back-btn selector" tabindex="0">← Назад</div>' +
          '<div class="kg-player-title">' + esc(object.title || '') + '</div>' +
        '</div>'
      );
      $iframe = $('<iframe class="kg-player-frame"' +
        ' src="' + esc(object.url) + '"' +
        ' allowfullscreen' +
        ' allow="autoplay; fullscreen; picture-in-picture"' +
        ' scrolling="yes"' +
      '></iframe>');
      $root.append($iframe);

      $root.find('.kg-back-btn').on('hover:enter', function () {
        Lampa.Activity.backward();
      });

      return $root;
    };

    this.start = function () {
      var back = this.back;
      Lampa.Controller.add('content', {
        toggle: function () {
          Lampa.Controller.collectionSet($root);
          var btn = $root.find('.kg-back-btn')[0];
          if (btn) Lampa.Controller.collectionFocus(btn, $root);
        },
        update: function () {},
        left:  function () {},
        right: function () {},
        up:    function () {},
        down:  function () {},
        back:  back
      });
      Lampa.Controller.toggle('content');
    };

    this.pause = this.stop = function () {};
    this.back    = function () { Lampa.Activity.backward(); };
    this.destroy = function () {
      if ($iframe) { $iframe.attr('src', 'about:blank'); $iframe = null; }
      if ($root)   { $root.remove(); $root = null; }
    };
  }

  // ─── МЕНЮ ─────────────────────────────────────────────────────────────────
  function injectMenu() {
    var tries = 0;
    var check = setInterval(function () {
      if (++tries > 60) { clearInterval(check); return; }
      var $menu = $('.menu__list');
      if (!$menu.length) return;
      clearInterval(check);

      if ($menu.find('[data-action="kinogo"]').length) return;

      var $item = $(
        '<li class="menu__item selector" tabindex="0" data-action="kinogo">' +
          '<div class="menu__ico">🇷🇺</div>' +
          '<div class="menu__text">РФ Шоу</div>' +
        '</li>'
      );
      $item.on('hover:enter', function () {
        Lampa.Activity.push({
          component: PN + '_catalog',
          title:     'Российские шоу',
          url:       RU_SHOWS
        });
      });

      var $settings = $menu.find('[data-action="settings"]');
      if ($settings.length) $settings.before($item);
      else $menu.append($item);
    }, 300);
  }

  // ─── ИНИЦИАЛИЗАЦИЯ ────────────────────────────────────────────────────────
  function init() {
    if (!window.Lampa || !Lampa.Component || !Lampa.Listener) return setTimeout(init, 500);
    injectStyles();
    Lampa.Component.add(PN + '_catalog', KingoCatalogActivity);
    Lampa.Component.add(PN + '_show',    KinogoShowActivity);
    Lampa.Component.add(PN + '_player',  KinogoPlayerActivity);
    injectMenu();
  }

  if (window.appready) init();
  else Lampa.Listener.follow('app', function (e) { if (e.type === 'ready') init(); });
})();
