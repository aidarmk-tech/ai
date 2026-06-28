/*!
 * Киноконг (Kinokong) — источник фильмов и сериалов для Lampa
 *
 * Делает две вещи:
 *   1) Отдельный плагин: пункт «Киноконг» в меню → каталог (Фильмы / Сериалы /
 *      Мультфильмы / Новинки) + поиск, воспроизведение через встроенный iframe-плеер.
 *   2) Встраивается в карточку фильма: кнопка «Киноконг» рядом с «Смотреть онлайн»,
 *      ищет по названию текущего фильма и сразу открывает найденное.
 *
 * Сайт DLE-движковый, домены часто меняются — поэтому есть авто-подбор рабочего
 * зеркала и ручная настройка домена в:  Настройки → Киноконг → Домен.
 *
 * ВАЖНО: kinokong.fm стоит за Cloudflare anti-bot. Прямой fetch получает
 * страницу-заглушку «Just a moment...», а не контент. Для работы нужен прокси,
 * проходящий Cloudflare — задаётся в Настройки → Киноконг → Прокси.
 *
 * Работает в bylampa и других форках с отключённым CORS в WebView.
 */
(function () {
  'use strict';

  var PN = 'kinokong';

  // Кандидаты-зеркала. Пробуются по порядку, первое рабочее кэшируется.
  // ВНИМАНИЕ: сайт за Cloudflare — без прокси (см. настройку «Прокси») прямой
  // fetch вернёт страницу-заглушку «Just a moment...», а не контент.
  var MIRRORS = [
    'https://kinokong.fm',
    'https://kinokong.pro',
    'https://kinokong.la',
    'https://kinokong.uno',
    'https://kinokong.show',
    'https://kinokong.bet',
    'https://kinokong.cc',
    'https://kinokong.org'
  ];

  // Категории каталога (пути DLE). Если на конкретном зеркале путь иной —
  // парсер всё равно подхватит карточки с любой страницы со списком.
  var CATEGORIES = [
    { id: 'home',    title: 'Новинки',     icon: '🔥', path: '/' },
    { id: 'films',   title: 'Фильмы',      icon: '🎬', path: '/films/' },
    { id: 'serials', title: 'Сериалы',     icon: '📺', path: '/serialy/' },
    { id: 'cartoon', title: 'Мультфильмы', icon: '🧸', path: '/multfilmy/' }
  ];

  var BASE = MIRRORS[0];       // текущий рабочий домен (обновляется ensureBase)
  var baseResolved = false;    // уже подбирали зеркало в этой сессии?

  // ─── СТИЛИ ────────────────────────────────────────────────────────────────
  function injectStyles() {
    if (document.getElementById('kk-styles')) return;
    var css = [
      '.kk-root{width:100%;height:100%;background:#0d0d0d;color:#fff;overflow-y:auto;overflow-x:hidden;-webkit-overflow-scrolling:touch}',
      '.kk-page{padding:1.5em 1.5em 6em}',

      '.kk-loading{padding:4em 1em;text-align:center;font-size:1.3em;opacity:0.55}',
      '.kk-error{padding:3em 1em;text-align:center;font-size:1.1em;color:#ff7e7e;line-height:1.6;opacity:0.9}',

      '.kk-catalog-title{font-size:1.8em;font-weight:800;margin-bottom:0.8em;color:#fff;display:flex;align-items:center;gap:0.5em}',

      /* Поиск */
      '.kk-search-bar{display:flex;gap:0.7em;margin-bottom:1.3em}',
      '.kk-search-input{flex:1;background:#1c1c1c;border:0.12em solid #333;border-radius:0.45em;padding:0.7em 1em;font-size:1.15em;color:#fff;outline:none}',
      '.kk-search-input.focus{border-color:#ff9800;box-shadow:0 0 0 0.12em rgba(255,152,0,0.35)}',
      '.kk-search-go{padding:0.7em 1.4em;background:#ff9800;color:#0d0d0d;border-radius:0.45em;font-weight:700;font-size:1.1em;cursor:pointer;outline:none;white-space:nowrap}',
      '.kk-search-go.focus{background:#ffb74d;box-shadow:0 0 0 0.18em rgba(255,152,0,0.5)}',

      /* Сетка карточек */
      '.kk-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(11em,1fr));gap:1em}',

      '.kk-card{border-radius:0.5em;overflow:hidden;background:#1c1c1c;cursor:pointer;outline:none;transition:transform 0.15s,box-shadow 0.15s;position:relative}',
      '.kk-card.focus{transform:scale(1.06);box-shadow:0 0 0 0.2em #ff9800,0 0 1.5em rgba(255,152,0,0.4)}',
      '.kk-card__poster{padding-top:145%;background:#2a2a2a center/cover no-repeat;position:relative}',
      '.kk-card__no-poster{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:2.5em;opacity:0.2}',
      '.kk-card__badge{position:absolute;top:0.4em;left:0.4em;background:rgba(0,0,0,0.7);color:#ffb74d;font-size:0.75em;font-weight:700;padding:0.15em 0.5em;border-radius:0.3em}',
      '.kk-card__title{padding:0.5em 0.6em 0.7em;font-size:0.88em;font-weight:600;line-height:1.35;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.5em}',

      '.kk-more{margin-top:1.5em;text-align:center;padding:0.9em 2em;background:#222;border-radius:0.4em;font-size:1.1em;font-weight:600;cursor:pointer;outline:none;color:#ff9800;transition:background 0.15s}',
      '.kk-more.focus{background:#333;box-shadow:0 0 0 0.18em #ff9800}',

      /* Страница тайтла */
      '.kk-hero{height:18em;background:#111 center/cover no-repeat;position:relative;border-radius:0.5em;overflow:hidden;margin-bottom:1.2em}',
      '.kk-hero__overlay{position:absolute;inset:0;background:linear-gradient(180deg,rgba(13,13,13,0) 30%,#0d0d0d 100%)}',
      '.kk-show-title{font-size:2em;font-weight:900;line-height:1.1;margin-bottom:1.2em}',
      '.kk-show-desc{font-size:1.05em;line-height:1.6;opacity:0.75;margin-bottom:1.5em;max-width:60em}',

      '.kk-play-btn{display:inline-flex;align-items:center;gap:0.5em;padding:0.7em 1.8em;background:#ff9800;border-radius:0.4em;font-size:1.2em;font-weight:700;cursor:pointer;outline:none;transition:background 0.15s,transform 0.12s;color:#0d0d0d}',
      '.kk-play-btn.focus{background:#ffb74d;transform:scale(1.04);box-shadow:0 0 0 0.2em rgba(255,152,0,0.5)}',

      /* Плеер */
      '.kk-player-root{position:fixed;inset:0;z-index:99999;background:#000;display:flex;flex-direction:column}',
      '.kk-player-bar{display:flex;align-items:center;gap:1em;padding:0.5em 1em;background:rgba(0,0,0,0.85);flex-shrink:0;min-height:3em}',
      '.kk-back-btn{padding:0.4em 1.1em;border-radius:0.35em;background:rgba(255,255,255,0.1);font-size:1.05em;font-weight:600;cursor:pointer;outline:none;white-space:nowrap;color:#fff;transition:background 0.15s}',
      '.kk-back-btn.focus{background:#ff9800;color:#0d0d0d;box-shadow:0 0 0 0.18em rgba(255,152,0,0.6)}',
      '.kk-player-title{font-size:1em;opacity:0.65;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      '.kk-player-frame{flex:1;border:none;width:100%;display:block}',

      /* Кнопка в карточке фильма */
      '.full-start__button.view--kinokong .kk-btn-ico,.full-start-new__button.view--kinokong .kk-btn-ico{margin-right:0.4em}'
    ].join('\n');

    var el = document.createElement('style');
    el.id = 'kk-styles';
    el.textContent = css;
    document.head.appendChild(el);
  }

  // ─── НАСТРОЙКИ (домен) ────────────────────────────────────────────────────
  function getDomainOverride() {
    try {
      var v = Lampa.Storage.get('kinokong_domain', '');
      return (v || '').trim();
    } catch (e) { return ''; }
  }
  function normalizeDomain(d) {
    d = (d || '').trim().replace(/\/+$/, '');
    if (!d) return '';
    if (!/^https?:\/\//.test(d)) d = 'https://' + d;
    return d;
  }
  function getProxy() {
    try { return (Lampa.Storage.get('kinokong_proxy', '') || '').trim(); }
    catch (e) { return ''; }
  }
  function injectSettings() {
    if (!window.Lampa || !Lampa.SettingsApi) return;
    try {
      Lampa.SettingsApi.addComponent({
        component: 'kinokong',
        name: 'Киноконг',
        icon: '<svg viewBox="0 0 24 24" width="24" height="24"><path fill="currentColor" d="M4 4h16v12H4z M2 18h20v2H2z"/></svg>'
      });
      Lampa.SettingsApi.addParam({
        component: 'kinokong',
        param: { name: 'kinokong_domain', type: 'input', values: '', default: '' },
        field: {
          name: 'Домен',
          description: 'Адрес зеркала Киноконга, напр. kinokong.fm. Пусто — авто-подбор.'
        },
        onChange: function (value) {
          baseResolved = false;
          var d = normalizeDomain(value);
          if (d) { BASE = d; baseResolved = true; }
          Lampa.Settings.update && Lampa.Settings.update();
        }
      });
      Lampa.SettingsApi.addParam({
        component: 'kinokong',
        param: { name: 'kinokong_proxy', type: 'input', values: '', default: '' },
        field: {
          name: 'Прокси (обход Cloudflare)',
          description: 'Сайт за Cloudflare — без прокси контент не загрузится. Укажите CORS/CF-прокси. Поддерживается шаблон {url}, напр. https://my-proxy/?url={url}. Пусто — прямой запрос.'
        },
        onChange: function () {
          baseResolved = false;
          try { Lampa.Storage.set('kinokong_base', ''); } catch (e) {}
          Lampa.Settings.update && Lampa.Settings.update();
        }
      });
    } catch (e) {}
  }

  // ─── ДЕТЕКТ CLOUDFLARE / ЗАГЛУШКИ ─────────────────────────────────────────
  function isChallenge(html) {
    if (!html) return false;
    return /__cf_chl|challenge-platform|cf-browser-verification|Just a moment|Enable JavaScript and cookies/i.test(html)
        && !/short-story|shortstory|b-content__inline_item/i.test(html);
  }
  function cfMessage() {
    return '<div class="kk-error">🛡️ Сайт защищён Cloudflare и блокирует прямые запросы.<br><br>' +
      'Чтобы загрузить контент, укажите прокси в:<br>Настройки → Киноконг → «Прокси (обход Cloudflare)».<br><br>' +
      'Без прокси этот источник работать не может.</div>';
  }

  // ─── ПОДБОР РАБОЧЕГО ЗЕРКАЛА ──────────────────────────────────────────────
  function looksLikeSite(html) {
    if (!html || html.length < 800) return false;
    if (isChallenge(html)) return false;
    if (/kinokong/i.test(html)) return true;
    try { return parseCatalog(html).items.length > 0; } catch (e) { return false; }
  }

  function ensureBase(callback) {
    // 1) Ручной домен из настроек — приоритет.
    var override = normalizeDomain(getDomainOverride());
    if (override) { BASE = override; baseResolved = true; return callback(BASE); }

    // 2) Уже подобрали в этой сессии.
    if (baseResolved) return callback(BASE);

    // 3) Кэш из прошлого запуска.
    var cached = '';
    try { cached = normalizeDomain(Lampa.Storage.get('kinokong_base', '')); } catch (e) {}

    var candidates = [];
    if (cached) candidates.push(cached);
    MIRRORS.forEach(function (m) { if (candidates.indexOf(m) === -1) candidates.push(m); });

    var i = 0;
    (function tryNext() {
      if (i >= candidates.length) {           // ничего не ответило — берём первый
        BASE = candidates[0] || MIRRORS[0];
        baseResolved = true;
        return callback(BASE);
      }
      var url = candidates[i++];
      httpGet(url + '/', function (err, html) {
        if (!err && looksLikeSite(html)) {
          BASE = url; baseResolved = true;
          try { Lampa.Storage.set('kinokong_base', url); } catch (e) {}
          return callback(BASE);
        }
        tryNext();
      }, 9000);
    })();
  }

  // ─── УТИЛИТЫ ──────────────────────────────────────────────────────────────
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function absUrl(href, base) {
    base = base || BASE;
    if (!href) return '';
    if (href.startsWith('//')) return 'https:' + href;
    if (href.startsWith('http')) return href;
    return base + (href.startsWith('/') ? '' : '/') + href.replace(/^\//, '');
  }

  // ─── HTTP ─────────────────────────────────────────────────────────────────
  function httpGet(url, callback, timeoutMs) {
    request(url, null, callback, timeoutMs);
  }
  function httpPost(url, body, callback, timeoutMs) {
    request(url, body, callback, timeoutMs);
  }
  function applyProxy(url) {
    var proxy = getProxy();
    if (!proxy) return url;
    if (proxy.indexOf('{url}') !== -1) return proxy.replace('{url}', encodeURIComponent(url));
    // Прокси-префикс: если кончается на = — добавляем encoded, иначе сырой URL.
    return /[=?]$/.test(proxy) ? proxy + encodeURIComponent(url) : proxy + url;
  }
  function request(url, postBody, callback, timeoutMs) {
    var done = false;
    var tid  = setTimeout(function () {
      if (!done) { done = true; callback(new Error('timeout'), null); }
    }, timeoutMs || 18000);

    var opts = {
      headers: {
        'Accept': 'text/html,application/xhtml+xml',
        'Accept-Language': 'ru-RU,ru;q=0.9'
      }
    };
    if (postBody != null) {
      opts.method = 'POST';
      opts.headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
      opts.body = postBody;
    }

    fetch(applyProxy(url), opts)
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

  // ─── ПАРСЕР КАТАЛОГА (DLE, общий) ─────────────────────────────────────────
  function parseCatalog(html) {
    var doc   = new DOMParser().parseFromString(html, 'text/html');
    var items = [];

    var selectors = [
      '.short-story', '.shortstory', '.short', '.sect-item',
      'article.item', '.movie-item', '.card-item', '.post-item', '.b-content__inline_item'
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
      var titleEl = card.querySelector('h2 a, h3 a, .short-title a, .title a, .sect-item-title a')
                 || card.querySelector('h2, h3, .title, .name, .short-title');

      if (!linkEl) return;

      var href = absUrl(linkEl.getAttribute('href') || '');
      if (!href || /javascript:|^#/.test(href)) return;
      if (!href.startsWith(BASE)) return;

      var poster = '';
      if (imgEl) {
        poster = imgEl.getAttribute('data-src') || imgEl.getAttribute('src') || '';
        poster = absUrl(poster);
      }

      var title = '';
      if (titleEl)                title = titleEl.textContent.trim();
      if (!title && linkEl.title) title = linkEl.title.trim();
      if (!title)                 title = linkEl.textContent.trim().substring(0, 80);
      title = title.replace(/\s+/g, ' ').trim();
      if (!title || title.length < 2) return;

      // год, если есть
      var badge = '';
      var yearM = (card.textContent || '').match(/\b(19\d{2}|20\d{2})\b/);
      if (yearM) badge = yearM[1];

      if (!items.find(function (i) { return i.url === href; })) {
        items.push({ title: title, url: href, poster: poster, badge: badge });
      }
    });

    var nextEl  = doc.querySelector('a[rel="next"], .nextpostslink, .pnext a, .next-page a, .navigation .next');
    var nextUrl = null;
    if (nextEl) {
      var nh = nextEl.getAttribute('href') || '';
      if (nh && !/javascript:|^#/.test(nh)) nextUrl = absUrl(nh);
    }
    if (!nextUrl) {
      var pageLinks = Array.from(doc.querySelectorAll('.pagination a, .navigation a, .pages a, [class*="pag"] a'));
      pageLinks.forEach(function (a) {
        var h = a.getAttribute('href') || '';
        if (/page\/2\b|[?&]page=2\b|cstart=2\b/.test(h)) nextUrl = absUrl(h);
      });
    }

    return { items: items, nextUrl: nextUrl };
  }

  // ─── ПАРСЕР URL ПЛЕЕРА ────────────────────────────────────────────────────
  function parsePlayerUrl(html) {
    var doc = new DOMParser().parseFromString(html, 'text/html');

    var iframes = Array.from(doc.querySelectorAll('iframe'));
    for (var i = 0; i < iframes.length; i++) {
      var src = iframes[i].getAttribute('src') || iframes[i].getAttribute('data-src') || '';
      if (src && src.length > 12 && !/about:|javascript:|#$/.test(src)) {
        return absUrl(src);
      }
    }

    var scripts = Array.from(doc.querySelectorAll('script:not([src])'));
    var playerHosts = ['kodik', 'aezofeb', 'collaps', 'video', 'iframe', 'player', 'embed', 'voidboost', 'hdvb', 'videocdn', 'bazon', 'alloha', 'ashdi', 'tortuga'];
    var urlRe = new RegExp('[\'"]' + '((?:https?:)?//[^\\s\'"<>]{15,})' + '[\'"]', 'g');

    for (var s = 0; s < scripts.length; s++) {
      var code = scripts[s].textContent;
      var m;
      urlRe.lastIndex = 0;
      while ((m = urlRe.exec(code)) !== null) {
        var u = m[1];
        if (playerHosts.some(function (h) { return u.indexOf(h) !== -1; })) {
          return absUrl(u);
        }
      }
    }

    var rawRe = new RegExp('[\'"]' + '(//[a-z0-9-]+\\.[a-z]{2,}/[^\'"<>\\s]{10,})' + '[\'"]', 'gi');
    var rawM  = rawRe.exec(html);
    if (rawM) return 'https:' + rawM[1];

    return null;
  }

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
      '<div class="kk-card selector" tabindex="0">' +
        '<div class="kk-card__poster"' +
          (item.poster ? ' style="background-image:url(\'' + esc(item.poster) + '\')"' : '') + '>' +
          (item.badge ? '<div class="kk-card__badge">' + esc(item.badge) + '</div>' : '') +
          (!item.poster ? '<div class="kk-card__no-poster">🎬</div>' : '') +
        '</div>' +
        '<div class="kk-card__title">' + esc(item.title) + '</div>' +
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

  // ─── SCROLL / КОНТРОЛЛЕР (как в kinogo) ───────────────────────────────────
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

  function fitHeight($root) {
    function setH() {
      if ($root && $root[0]) { var h = window.innerHeight || screen.height; if (h > 100) $root[0].style.height = h + 'px'; }
    }
    setH(); setTimeout(setH, 300);
  }

  // ─── ОБЩАЯ ЛОГИКА «СЕТКА + ЕЩЁ» ───────────────────────────────────────────
  function renderGrid($page, result, emptyText) {
    if (!result.items.length) {
      $page.append('<div class="kk-error">' + esc(emptyText || 'Ничего не найдено.') + '</div>');
      return null;
    }
    var $grid = $('<div class="kk-grid"></div>');
    result.items.forEach(function (item) { $grid.append(renderCard(item)); });
    $page.append($grid);
    return $grid;
  }

  function appendMore($page, $grid, $root, firstNext) {
    var currentNext = firstNext;
    if (!currentNext) return;
    var $more = $('<div class="kk-more selector" tabindex="0">⬇ Загрузить ещё</div>');
    $more.on('hover:enter', function () {
      if (!currentNext) return;
      var loadingNext = currentNext;
      currentNext = null;
      $more.text('⏳ Загружаем...');
      httpGet(loadingNext, function (err2, html2) {
        if (!$page || !$page.length) return;
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

  // ─── КАТАЛОГ ──────────────────────────────────────────────────────────────
  function KinokongCatalogActivity(object) {
    var $root       = $('<div class="kk-root"><div class="kk-page"></div></div>');
    var $page       = $root.find('.kk-page');
    var lastFocused = [null];

    this.create = function () { this.load(); return this.render(); };
    this.render = function () { return $root; };

    this.load = function () {
      $page.html('<div class="kk-loading">📡 Загружаем Киноконг...</div>');
      ensureBase(function (base) {
        var url = object.url || (base + (object.path || '/'));
        httpGet(url, function (err, html) {
          if (!$page || !$page.length) return;
          if (err || !html) {
            $page.html('<div class="kk-error">Не удалось загрузить список.<br>Проверьте интернет или смените домен в настройках.</div>');
            return;
          }
          if (isChallenge(html)) { $page.html(cfMessage()); return; }
          var result = parseCatalog(html);
          $page.empty();
          $page.append('<div class="kk-catalog-title">' + esc((object.icon ? object.icon + ' ' : '🦍 ') + (object.title || 'Киноконг')) + '</div>');
          var $grid = renderGrid($page, result, 'Не удалось распознать список.\nВозможно, сайт изменил структуру или сменил домен.');
          if ($grid) appendMore($page, $grid, $root, result.nextUrl);
          try { Lampa.Controller.toggle('content'); } catch (e) {}
        });
      });
    };

    $root.on('hover:focus', '.selector', function () { lastFocused[0] = this; scrollTo($root, this); });

    this.start = function () {
      fitHeight($root);
      Lampa.Controller.add('content', makeController($root, lastFocused, this.back));
      Lampa.Controller.toggle('content');
    };
    this.pause = this.stop = function () {};
    this.back    = function () { Lampa.Activity.backward(); };
    this.destroy = function () { if ($root) $root.remove(); $root = null; $page = null; };
  }

  // ─── ПОИСК ────────────────────────────────────────────────────────────────
  function doSearch(query, callback) {
    ensureBase(function (base) {
      var q = encodeURIComponent(query);
      // DLE-поиск — POST. На многих зеркалах работает и GET, держим фолбэк.
      httpPost(base + '/index.php?do=search', 'do=search&subaction=search&search_start=0&full_search=0&result_from=1&story=' + q, function (err, html) {
        if (!err && html && parseCatalog(html).items.length) return callback(null, html);
        httpGet(base + '/index.php?do=search&subaction=search&story=' + q, function (e2, h2) {
          callback(e2, h2);
        });
      });
    });
  }

  function KinokongSearchActivity(object) {
    var $root       = $('<div class="kk-root"><div class="kk-page"></div></div>');
    var $page       = $root.find('.kk-page');
    var lastFocused = [null];
    var query       = object.query || '';

    this.create = function () {
      this.build();
      if (query) this.run(query);
      return this.render();
    };
    this.render = function () { return $root; };

    this.build = function () {
      $page.empty();
      $page.append('<div class="kk-catalog-title">🔎 Поиск · Киноконг</div>');
      var $bar = $(
        '<div class="kk-search-bar">' +
          '<input class="kk-search-input selector" type="text" placeholder="Название фильма или сериала" value="' + esc(query) + '">' +
          '<div class="kk-search-go selector" tabindex="0">Найти</div>' +
        '</div>'
      );
      $page.append($bar);
      $page.append('<div class="kk-results"></div>');

      var self = this;
      var $input = $bar.find('.kk-search-input');
      var $go    = $bar.find('.kk-search-go');

      function fire() {
        var v = ($input.val() || '').trim();
        if (v.length < 2) { Lampa.Noty && Lampa.Noty.show('Введите минимум 2 символа'); return; }
        self.run(v);
      }
      // OK на поле → открыть системную клавиатуру (TV/WebView) либо просто фокус.
      $input.on('hover:enter', function () { try { $input[0].focus(); } catch (e) {} });
      $input.on('keydown', function (e) { if (e.keyCode === 13) fire(); });
      $go.on('hover:enter', fire);
    };

    this.run = function (q) {
      query = q;
      var $results = $page.find('.kk-results');
      $results.html('<div class="kk-loading">📡 Ищем «' + esc(q) + '»...</div>');
      doSearch(q, function (err, html) {
        if (!$results || !$results.length) return;
        if (err || !html) { $results.html('<div class="kk-error">Поиск не удался. Попробуйте позже или смените домен.</div>'); return; }
        if (isChallenge(html)) { $results.html(cfMessage()); return; }
        var result = parseCatalog(html);
        $results.empty();
        var $grid = renderGrid($results, result, 'По запросу «' + q + '» ничего не найдено.');
        if ($grid) appendMore($results, $grid, $root, result.nextUrl);
        try { Lampa.Controller.collectionSet($root); } catch (e) {}
      });
    };

    $root.on('hover:focus', '.selector', function () { lastFocused[0] = this; scrollTo($root, this); });

    this.start = function () {
      fitHeight($root);
      Lampa.Controller.add('content', makeController($root, lastFocused, this.back));
      Lampa.Controller.toggle('content');
    };
    this.pause = this.stop = function () {};
    this.back    = function () { Lampa.Activity.backward(); };
    this.destroy = function () { if ($root) $root.remove(); $root = null; $page = null; };
  }

  // ─── СТРАНИЦА ТАЙТЛА ──────────────────────────────────────────────────────
  function KinokongShowActivity(object) {
    var $root       = $('<div class="kk-root"><div class="kk-page"></div></div>');
    var $page       = $root.find('.kk-page');
    var lastFocused = [null];
    var loaded      = false;

    this.create  = function () { this.load(); return this.render(); };
    this.render  = function () { return $root; };

    this.load = function () {
      $page.html('<div class="kk-loading">📡 Загружаем страницу...</div>');
      httpGet(object.url, function (err, html) {
        if (loaded || !$page || !$page.length) return;
        loaded = true;

        if (err || !html) {
          $page.html('<div class="kk-error">Не удалось загрузить страницу.<br>' + esc(object.url) + '</div>');
          return;
        }
        if (isChallenge(html)) { $page.html(cfMessage()); return; }

        var playerUrl = parsePlayerUrl(html);
        var desc      = parseDescription(html);

        $page.empty();
        if (object.poster) {
          $page.append('<div class="kk-hero" style="background-image:url(\'' + esc(object.poster) + '\')"><div class="kk-hero__overlay"></div></div>');
        }
        $page.append('<div class="kk-show-title">' + esc(object.title || '') + '</div>');
        if (desc) $page.append('<div class="kk-show-desc">' + esc(desc) + '</div>');

        if (playerUrl) {
          var $btn = $('<div class="kk-play-btn selector" tabindex="0">▶ Смотреть</div>');
          $btn.on('hover:enter', function () {
            Lampa.Activity.push({ component: PN + '_player', title: object.title, url: playerUrl });
          });
          $page.append($btn);
        } else {
          $page.append('<div class="kk-error">Не удалось найти плеер на странице.<br>Откройте сайт вручную: ' + esc(object.url) + '</div>');
        }

        try { Lampa.Controller.toggle('content'); } catch (e) {}
      });
    };

    $root.on('hover:focus', '.selector', function () { lastFocused[0] = this; scrollTo($root, this); });

    this.start = function () {
      fitHeight($root);
      Lampa.Controller.add('content', makeController($root, lastFocused, this.back));
      Lampa.Controller.toggle('content');
    };
    this.pause = this.stop = function () {};
    this.back    = function () { Lampa.Activity.backward(); };
    this.destroy = function () { if ($root) $root.remove(); $root = null; $page = null; };
  }

  // ─── IFRAME-ПЛЕЕР ─────────────────────────────────────────────────────────
  function KinokongPlayerActivity(object) {
    var $root   = $('<div class="kk-player-root"></div>');
    var $iframe = null;

    this.create = function () { return this.render(); };
    this.render = function () {
      $root.append(
        '<div class="kk-player-bar">' +
          '<div class="kk-back-btn selector" tabindex="0">← Назад</div>' +
          '<div class="kk-player-title">' + esc(object.title || '') + '</div>' +
        '</div>'
      );
      $iframe = $('<iframe class="kk-player-frame" src="' + esc(object.url) + '" allowfullscreen ' +
        'allow="autoplay; fullscreen; picture-in-picture" scrolling="yes"></iframe>');
      $root.append($iframe);
      $root.find('.kk-back-btn').on('hover:enter', function () { Lampa.Activity.backward(); });
      return $root;
    };

    this.start = function () {
      var back = this.back;
      Lampa.Controller.add('content', {
        toggle: function () {
          Lampa.Controller.collectionSet($root);
          var btn = $root.find('.kk-back-btn')[0];
          if (btn) Lampa.Controller.collectionFocus(btn, $root);
        },
        update: function () {}, left: function () {}, right: function () {},
        up: function () {}, down: function () {}, back: back
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
  function openMenu() {
    var items = CATEGORIES.map(function (c) {
      return { title: c.icon + ' ' + c.title, category: c };
    });
    items.push({ title: '🔎 Поиск', search: true });

    Lampa.Select.show({
      title: 'Киноконг',
      items: items,
      onSelect: function (item) {
        Lampa.Controller.toggle('menu');
        if (item.search) {
          Lampa.Activity.push({ component: PN + '_search', title: 'Поиск · Киноконг' });
        } else {
          var c = item.category;
          Lampa.Activity.push({ component: PN + '_catalog', title: c.title, icon: c.icon, path: c.path });
        }
      },
      onBack: function () { Lampa.Controller.toggle('menu'); }
    });
  }

  function injectMenu() {
    var tries = 0;
    var check = setInterval(function () {
      if (++tries > 60) { clearInterval(check); return; }
      var $menu = $('.menu .menu__list').eq(0);
      if (!$menu.length) $menu = $('.menu__list').eq(0);
      if (!$menu.length) return;
      clearInterval(check);

      if ($menu.find('[data-action="kinokong"]').length) return;

      var $item = $(
        '<li class="menu__item selector" tabindex="0" data-action="kinokong">' +
          '<div class="menu__ico">🦍</div>' +
          '<div class="menu__text">Киноконг</div>' +
        '</li>'
      );
      $item.on('hover:enter', openMenu);

      var $settings = $menu.find('[data-action="settings"]');
      if ($settings.length) $settings.before($item);
      else $menu.append($item);
    }, 300);
  }

  // ─── КНОПКА В КАРТОЧКЕ ФИЛЬМА ─────────────────────────────────────────────
  function addCardButton(e) {
    try {
      var movie  = e.data && e.data.movie ? e.data.movie : (e.object ? e.object.card || e.object.movie : null);
      var render = e.object && e.object.activity ? e.object.activity.render() : null;
      if (!movie || !render) return;
      if (render.find('.view--kinokong').length) return;

      var title = movie.title || movie.name || movie.original_title || movie.original_name || '';
      if (!title) return;

      var $btn = $(
        '<div class="full-start__button selector view--kinokong" style="background:#ff9800;color:#0d0d0d">' +
          '<span class="kk-btn-ico">🦍</span><span>Киноконг</span>' +
        '</div>'
      );
      $btn.on('hover:enter', function () {
        Lampa.Activity.push({ component: PN + '_search', title: 'Киноконг · ' + title, query: title });
      });

      // Новый макет карточки / старый — пробуем оба контейнера кнопок.
      var $cont = render.find('.full-start-new__buttons');
      if (!$cont.length) $cont = render.find('.full-start__buttons');
      if ($cont.length) $cont.append($btn);
      else {
        var $online = render.find('.view--online, .button--play').eq(0);
        if ($online.length) $online.after($btn);
      }
    } catch (err) {}
  }

  // ─── ИНИЦИАЛИЗАЦИЯ ────────────────────────────────────────────────────────
  function init() {
    if (!window.Lampa || !Lampa.Component || !Lampa.Listener) return setTimeout(init, 500);

    injectStyles();
    injectSettings();

    Lampa.Component.add(PN + '_catalog', KinokongCatalogActivity);
    Lampa.Component.add(PN + '_search',  KinokongSearchActivity);
    Lampa.Component.add(PN + '_show',    KinokongShowActivity);
    Lampa.Component.add(PN + '_player',  KinokongPlayerActivity);

    injectMenu();

    // Кнопка в карточке фильма. Lampa исторически шлёт type 'complite'.
    Lampa.Listener.follow('full', function (e) {
      if (e.type === 'complite' || e.type === 'complete') addCardButton(e);
    });
  }

  if (window.appready) init();
  else if (window.Lampa && Lampa.Listener) Lampa.Listener.follow('app', function (e) { if (e.type === 'ready') init(); });
  else setTimeout(init, 500);
})();
