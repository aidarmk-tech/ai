/*!
 * Apple TV Theme для Lampa
 *
 * Современное «тёплое» оформление в духе tvOS: матовые панели с размытием,
 * крупные скруглённые карточки с мягким подъёмом и бликом в фокусе, чёткая
 * типографика (системный SF-шрифт), аккуратные тени и акцентные цвета.
 *
 * Настройки → Apple TV тема:
 *   • Включить тему
 *   • Акцентный цвет (7 системных оттенков)
 *   • Фон (тёмный / чёрный OLED / светлый)
 *   • Скругление углов
 *   • Размытие панелей (можно отключить на слабых ТВ-боксах)
 *   • Анимация фокуса (подъём+блик / только подъём / минимум)
 *
 * Чистый CSS-инжект, никаких внешних запросов. Отключение мгновенно
 * возвращает стандартный вид.
 */
(function () {
  'use strict';

  var STYLE_ID = 'appletv-theme';

  var SK = {
    enabled: 'appletv_enabled',
    accent:  'appletv_accent',
    bg:      'appletv_bg',
    radius:  'appletv_radius',
    blur:    'appletv_blur',
    focus:   'appletv_focus',
    menudim: 'appletv_menudim',
    head:    'appletv_head'
  };

  // Системные акценты Apple
  var ACCENTS = {
    blue:     { name: 'Синий',       c: '#0a84ff' },
    purple:   { name: 'Фиолетовый',  c: '#bf5af2' },
    pink:     { name: 'Розовый',     c: '#ff375f' },
    orange:   { name: 'Оранжевый',   c: '#ff9f0a' },
    green:    { name: 'Зелёный',     c: '#30d158' },
    teal:     { name: 'Бирюзовый',   c: '#40c8e0' },
    graphite: { name: 'Графит',      c: '#a1a1aa' }
  };

  var BGS = {
    dark:  { name: 'Тёмный',        bg: '#0b0b0f', bg2: '#16161d', surf: 'rgba(255,255,255,0.07)', surf2: 'rgba(255,255,255,0.13)', line: 'rgba(255,255,255,0.10)', txt: '#f5f5f7', dim: 'rgba(235,235,245,0.62)' },
    black: { name: 'Чёрный (OLED)', bg: '#000000', bg2: '#0a0a0c', surf: 'rgba(255,255,255,0.06)', surf2: 'rgba(255,255,255,0.12)', line: 'rgba(255,255,255,0.08)', txt: '#f5f5f7', dim: 'rgba(235,235,245,0.55)' },
    light: { name: 'Светлый',       bg: '#eef0f4', bg2: '#ffffff', surf: 'rgba(0,0,0,0.05)',       surf2: 'rgba(0,0,0,0.10)',       line: 'rgba(0,0,0,0.10)',       txt: '#1c1c1e', dim: 'rgba(60,60,67,0.60)' }
  };

  var RADII = {
    round:  { name: 'Округлое', sm: '0.7em',  md: '1.1em',  lg: '1.5em' },
    soft:   { name: 'Мягкое',   sm: '0.5em',  md: '0.8em',  lg: '1.1em' },
    square: { name: 'Строгое',  sm: '0.25em', md: '0.35em', lg: '0.5em' }
  };

  var FOCUS = {
    full:  { name: 'Подъём + блик' },
    lift:  { name: 'Только подъём' },
    min:   { name: 'Минимум' }
  };

  // ─── helpers ──────────────────────────────────────────────────────────────
  function get(key, def) {
    try { var v = Lampa.Storage.get(key, def); return (v === undefined || v === null) ? def : v; }
    catch (e) { return def; }
  }
  function hexToRgba(hex, a) {
    hex = (hex || '#0a84ff').replace('#', '');
    if (hex.length === 3) hex = hex.replace(/(.)/g, '$1$1');
    var n = parseInt(hex, 16);
    var r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    return 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
  }

  // ─── CSS ──────────────────────────────────────────────────────────────────
  function buildCSS() {
    var ac    = ACCENTS[get(SK.accent, 'blue')] || ACCENTS.blue;
    var bg    = BGS[get(SK.bg, 'dark')]          || BGS.dark;
    var rad   = RADII[get(SK.radius, 'round')]   || RADII.round;
    var focus = get(SK.focus, 'full');
    var blur  = get(SK.blur, true);

    var A   = ac.c;
    var Ag  = hexToRgba(A, 0.45);   // glow
    var Ag2 = hexToRgba(A, 0.22);   // soft glow
    var Aln = hexToRgba(A, 0.85);   // ring line
    var blurPanel = blur ? 'saturate(180%) blur(22px)' : 'none';
    var blurSoft  = blur ? 'saturate(160%) blur(14px)' : 'none';

    var liftScale = focus === 'min' ? '1.04' : '1.10';
    var liftY     = focus === 'min' ? '-0.15em' : '-0.45em';
    var focusShadow = focus === 'min'
      ? '0 0.4em 1.2em rgba(0,0,0,0.45)'
      : '0 1.4em 2.8em rgba(0,0,0,0.6), 0 0 0 0.18em ' + Aln + ', 0 0 2.2em ' + Ag2;

    var css = [];

    /* ── базовый шрифт и фон ───────────────────────────────────────────── */
    css.push(
      'body,.body{font-family:-apple-system,BlinkMacSystemFont,"SF Pro Display","SF Pro Text","Segoe UI",system-ui,Roboto,"Helvetica Neue",Arial,sans-serif !important;' +
      '-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility;letter-spacing:0.01em}',

      '.wrap,.wrap__content,body{background:radial-gradient(120% 80% at 50% -10%,' + bg.bg2 + ' 0%,' + bg.bg + ' 60%) fixed,' + bg.bg + ' !important;color:' + bg.txt + '}'
    );

    /* ── скрытие резкой рамки фокуса по умолчанию ──────────────────────── */
    css.push(
      '.card.focus .card__view::after,.card.hover .card__view::after{border:0 !important}'
    );

    /* ── боковое меню ──────────────────────────────────────────────────── */
    css.push(
      '.menu{background:transparent !important}',
      '.menu__list{padding:0.5em 0.6em}',
      '.menu__item{position:relative;border-radius:' + rad.md + ' !important;padding:0.62em 1em 0.62em 1.15em !important;margin:0.14em 0 !important;' +
        'transition:background .25s cubic-bezier(.2,.7,.2,1),transform .25s cubic-bezier(.2,.7,.2,1),box-shadow .25s;color:' + bg.txt + '}',
      '.menu__item .menu__text{font-weight:600;letter-spacing:0.01em;transition:transform .25s}',
      /* вертикальный индикатор активного/выбранного раздела */
      '.menu__item::before{content:"";position:absolute;left:0.05em;top:50%;width:0.26em;height:0;border-radius:1em;background:' + A + ';' +
        'box-shadow:0 0 0.8em ' + Ag + ';transform:translateY(-50%);transition:height .25s cubic-bezier(.2,.7,.2,1);opacity:0}',
      '.menu__item.focus::before,.menu__item.active::before{height:1.25em;opacity:1}',
      '.menu__item.focus,.menu__item:hover{background:' + bg.surf2 + ' !important;-webkit-backdrop-filter:' + blurSoft + ';backdrop-filter:' + blurSoft + ';' +
        'box-shadow:0 0 0 0.1em ' + Aln + ',0 0.6em 1.4em rgba(0,0,0,0.4) !important;transform:translateX(0.18em)}',
      '.menu__item.focus .menu__text{transform:translateX(0.12em)}',
      '.menu__item.active{background:' + bg.surf + ' !important}',
      '.menu__item.active .menu__ico,.menu__item.focus .menu__ico{color:' + A + ';transform:scale(1.12)}',
      '.menu__ico svg,.menu__ico{transition:color .2s,transform .25s cubic-bezier(.2,.7,.2,1)}',
      '.menu__split{opacity:0.3}'
    );

    /* ── tvOS: затемнение контента при открытом меню (опционально) ──────
       Только лёгкий скрим-градиент. НЕ трогаем transform/filter контейнеров
       контента, чтобы не «контейнить» position:fixed всплывающие окна. */
    if (get(SK.menudim, false)) {
      css.push(
        'body.atv-menu-active .scroll__body{transition:opacity .35s ease;opacity:0.55}'
      );
    }

    /* ── плавное появление экранов ─────────────────────────────────────── */
    css.push(
      '.activity--active{animation:atvScreen .35s cubic-bezier(.2,.7,.2,1)}',
      '@keyframes atvScreen{0%{opacity:0}100%{opacity:1}}'
    );

    /* ── верхняя панель ────────────────────────────────────────────────── */
    css.push(
      '.head{background:' + (blur ? hexToRgba(bg.bg, 0.55) : bg.bg) + ' !important;-webkit-backdrop-filter:' + blurPanel + ';backdrop-filter:' + blurPanel + ';border-bottom:1px solid ' + bg.line + ' !important}',
      '.head__action,.head__split{border-radius:' + rad.sm + ' !important;transition:background .2s,box-shadow .2s,color .2s}',
      '.head__action.focus,.head__action:hover{background:' + bg.surf2 + ' !important;box-shadow:0 0 0 0.12em ' + Aln + ';color:' + A + '}'
    );

    /* ── строки-полки и заголовки ─────────────────────────────────────── */
    css.push(
      '.items-line__title,.category-full__title,.scroll__title{font-weight:800 !important;letter-spacing:0.015em;opacity:0.95}',
      '.items-line{margin-bottom:1.2em}'
    );

    /* ── КАРТОЧКИ ──────────────────────────────────────────────────────── */
    css.push(
      '.card{padding:0.55em !important}',
      '.card__view{border-radius:' + rad.lg + ' !important;overflow:hidden;box-shadow:0 0.5em 1.1em rgba(0,0,0,0.35);' +
        'transition:transform .28s cubic-bezier(.2,.7,.2,1),box-shadow .28s cubic-bezier(.2,.7,.2,1) !important;will-change:transform;position:relative}',
      '.card__img{transition:transform .4s cubic-bezier(.2,.7,.2,1)}',
      '.card.focus .card__view{transform:translateY(' + liftY + ') scale(' + liftScale + ');box-shadow:' + focusShadow + ' !important;z-index:5}',
      (focus !== 'min' ? '.card.focus .card__img{transform:scale(1.06)}' : ''),

      /* мета-плашки */
      '.card__type,.card__quality,.card__vote,.card__age,.card__marker>span{border-radius:0.5em !important;font-weight:700;letter-spacing:0.02em;-webkit-backdrop-filter:blur(6px);backdrop-filter:blur(6px)}',
      '.card__vote{background:' + hexToRgba(A, 0.92) + ' !important;color:#fff}',
      '.card__type{background:rgba(0,0,0,0.55) !important}',
      '.card__title{font-weight:600;opacity:0.92;padding-top:0.55em;letter-spacing:0.01em}',
      '.card__age{opacity:0.8}'
    );

    /* блик-«шайн» по карточке при фокусе */
    if (focus === 'full') {
      css.push(
        '.card__view::before{content:"";position:absolute;inset:0;z-index:3;pointer-events:none;opacity:0;background:linear-gradient(115deg,transparent 30%,rgba(255,255,255,0.22) 48%,transparent 62%);transform:translateX(-120%)}',
        '.card.focus .card__view::before{animation:atvShine .85s cubic-bezier(.2,.7,.2,1) forwards}',
        '@keyframes atvShine{0%{opacity:0;transform:translateX(-120%)}25%{opacity:1}100%{opacity:0;transform:translateX(120%)}}'
      );
    }

    /* ── СТРАНИЦА ФИЛЬМА ───────────────────────────────────────────────── */
    css.push(
      '.full-start__background,.full-start-new__background{animation:atvKen 28s ease-in-out infinite alternate;transform-origin:center}',
      '@keyframes atvKen{0%{transform:scale(1.04)}100%{transform:scale(1.13)}}',
      '.full-start__title,.full-start-new__title{font-weight:900 !important;letter-spacing:-0.01em;line-height:1.04;text-shadow:0 0.12em 0.6em rgba(0,0,0,0.55)}',
      '.full-start__tags,.full-start-new__rate-line{opacity:0.9}',
      '.full-descr__text,.full-start__descr,.full-start-new__descr{line-height:1.6 !important;opacity:0.85}',

      /* кнопки на карточке */
      '.full-start__button,.full-start-new__button,.simple-button{border-radius:' + rad.md + ' !important;background:' + bg.surf + ' !important;-webkit-backdrop-filter:' + blurSoft + ';backdrop-filter:' + blurSoft + ';' +
        'border:1px solid ' + bg.line + ' !important;font-weight:700;transition:transform .2s,background .2s,box-shadow .2s,color .2s}',
      '.full-start__button.focus,.full-start-new__button.focus,.simple-button.focus{background:' + A + ' !important;color:#fff !important;border-color:' + A + ' !important;transform:translateY(-0.12em) scale(1.03);box-shadow:0 0.8em 1.6em ' + Ag2 + ',0 0 0 0.16em ' + Aln + ' !important}',
      '.full-start__button.focus *,.full-start-new__button.focus *{color:#fff !important}'
    );

    /* ── общий фокус для прочих selector ───────────────────────────────── */
    css.push(
      '.selector{transition:box-shadow .2s,background .2s,transform .2s}',
      '.full-person.focus,.full-review.focus,.tag-count.focus,.extensions__item.focus,.online-prestige.focus,.torrent-item.focus{box-shadow:0 0 0 0.14em ' + Aln + ',0 0.6em 1.4em rgba(0,0,0,0.4) !important;border-radius:' + rad.md + ' !important}'
    );

    /* ── НАСТРОЙКИ / папки ─────────────────────────────────────────────── */
    css.push(
      '.settings-folder,.settings__content,.settings-param{border-radius:' + rad.md + ' !important}',
      '.settings-folder.focus,.settings-param.focus,.settings-param-title.focus{background:' + bg.surf2 + ' !important;-webkit-backdrop-filter:' + blurSoft + ';backdrop-filter:' + blurSoft + ';box-shadow:0 0 0 0.12em ' + Aln + ' !important}',
      '.settings-param__name{font-weight:600}',
      '.settings-param>div:last-child,.settings-param__value{color:' + A + '}'
    );

    /* ── SELECTBOX / модалки / меню выбора ─────────────────────────────────
       ВАЖНО: НЕ переопределяем фон/позиционирование самих окон и не вешаем
       backdrop-filter — чтобы гарантированно не сломать/не спрятать всплывашки.
       Только мягкая подсветка пунктов и скругление содержимого. */
    css.push(
      '.selectbox__content,.modal__content{border-radius:' + rad.lg + ' !important}',
      '.selectbox-item,.selectbox__content .selector{border-radius:' + rad.sm + ' !important;transition:background .15s,box-shadow .15s}',
      '.selectbox-item.focus,.selectbox__content .selector.focus{background:' + bg.surf2 + ' !important;box-shadow:0 0 0 0.12em ' + Aln + ' !important}',
      '.selectbox-item__checkbox.active{background:' + A + ' !important;border-color:' + A + ' !important}'
    );

    /* ── ПОИСК / клавиатура ────────────────────────────────────────────── */
    css.push(
      '.search-source.active,.search-source.focus{color:' + A + ' !important}',
      '.simple-keyboard{border-radius:' + rad.md + ' !important;background:' + bg.bg2 + ' !important;-webkit-backdrop-filter:' + blurPanel + ';backdrop-filter:' + blurPanel + '}',
      '.simple-keyboard .hg-button{border-radius:' + rad.sm + ' !important;background:' + bg.surf + ' !important;transition:background .12s,box-shadow .12s,transform .12s;color:' + bg.txt + '}',
      '.simple-keyboard .hg-button.focus,.simple-keyboard .hg-button.hovered{background:' + A + ' !important;color:#fff !important;transform:scale(1.08);box-shadow:0 0.4em 1em ' + Ag2 + ' !important;z-index:2}'
    );

    /* ── ПЛЕЕР ─────────────────────────────────────────────────────────── */
    css.push(
      '.player-panel{background:linear-gradient(0deg,' + hexToRgba(bg.bg, 0.92) + ' 0%,transparent 100%) !important}',
      '.player-panel__position .scroll__body>div,.player-panel .button.focus,.player-panel .selector.focus{box-shadow:0 0 0 0.14em ' + Aln + ' !important;border-radius:' + rad.sm + '}',
      '.player-panel__line>div:first-child,.time-line>div:first-child{background:' + A + ' !important}',
      '.player-info{border-radius:' + rad.md + '}'
    );

    /* ── уведомления / прочее ──────────────────────────────────────────── */
    css.push(
      '.noty{border-radius:' + rad.md + ' !important;background:' + bg.bg2 + ' !important;-webkit-backdrop-filter:' + blurPanel + ';backdrop-filter:' + blurPanel + ';border:1px solid ' + bg.line + ';box-shadow:0 1em 2.5em rgba(0,0,0,0.5) !important}',
      '.navigation-tabs{background:' + (blur ? hexToRgba(bg.bg, 0.6) : bg.bg) + ' !important;-webkit-backdrop-filter:' + blurPanel + ';backdrop-filter:' + blurPanel + ';border-top:1px solid ' + bg.line + '}',
      '.navigation-tabs__item.focus,.navigation-tabs__item.active{color:' + A + ' !important}'
    );

    /* ── сборка верхних иконок ─────────────────────────────────────────── */
    if (get(SK.head, true)) {
      css.push(
        '.head .atv-head-collapsible{display:none !important}',
        'body.atv-head-open .head .atv-head-collapsible{display:flex !important}',
        '.atv-head-toggle{display:flex !important;align-items:center;justify-content:center}',
        'body.atv-head-open .atv-head-toggle{background:' + bg.surf2 + ' !important;color:' + A + ' !important;box-shadow:0 0 0 0.12em ' + Aln + '}'
      );
    }

    /* ── скроллбары ────────────────────────────────────────────────────── */
    css.push(
      '::-webkit-scrollbar{width:0.5em;height:0.5em}',
      '::-webkit-scrollbar-thumb{background:' + bg.surf2 + ';border-radius:1em}',
      '::-webkit-scrollbar-track{background:transparent}'
    );

    return css.join('\n');
  }

  // ─── apply / remove ───────────────────────────────────────────────────────
  function apply() {
    var enabled = get(SK.enabled, true);
    var el = document.getElementById(STYLE_ID);

    if (!enabled) { if (el) el.remove(); unsetupHead(); return; }

    if (!el) {
      el = document.createElement('style');
      el.id = STYLE_ID;
      document.head.appendChild(el);
    }
    el.textContent = buildCSS();
    setupHead();
  }

  // ─── Настройки ──────────────────────────────────────────────────────────────
  function mapNames(obj) {
    var out = {};
    Object.keys(obj).forEach(function (k) { out[k] = obj[k].name; });
    return out;
  }

  function injectSettings() {
    if (!window.Lampa || !Lampa.SettingsApi) return;
    try {
      Lampa.SettingsApi.addComponent({
        component: 'appletv_theme',
        name: 'Apple TV тема',
        icon: '<svg viewBox="0 0 24 24" width="24" height="24"><path fill="currentColor" d="M3 5h18a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-7l1 2h2v2H6v-2h2l1-2H3a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zm1 2v8h16V7H4z"/></svg>'
      });

      Lampa.SettingsApi.addParam({
        component: 'appletv_theme',
        param: { name: SK.enabled, type: 'trigger', default: true },
        field: { name: 'Включить тему', description: 'Современное оформление в стиле Apple TV. Отключение мгновенно возвращает стандартный вид.' },
        onChange: function () { setTimeout(apply, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'appletv_theme',
        param: { name: SK.accent, type: 'select', values: mapNames(ACCENTS), default: 'blue' },
        field: { name: 'Акцентный цвет', description: 'Цвет выделения, фокуса и активных элементов.' },
        onChange: function () { setTimeout(apply, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'appletv_theme',
        param: { name: SK.bg, type: 'select', values: mapNames(BGS), default: 'dark' },
        field: { name: 'Фон', description: 'Тёмный, чёрный для OLED-экранов или светлый.' },
        onChange: function () { setTimeout(apply, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'appletv_theme',
        param: { name: SK.radius, type: 'select', values: mapNames(RADII), default: 'round' },
        field: { name: 'Скругление углов', description: 'Насколько круглые карточки, кнопки и панели.' },
        onChange: function () { setTimeout(apply, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'appletv_theme',
        param: { name: SK.focus, type: 'select', values: mapNames(FOCUS), default: 'full' },
        field: { name: 'Анимация фокуса', description: 'Подъём+блик — самый эффектный. Минимум — для слабых устройств.' },
        onChange: function () { setTimeout(apply, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'appletv_theme',
        param: { name: SK.blur, type: 'trigger', default: true },
        field: { name: 'Размытие панелей', description: 'Матовое стекло на меню и клавиатуре. Отключите, если интерфейс подтормаживает на ТВ-боксе.' },
        onChange: function () { setTimeout(apply, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'appletv_theme',
        param: { name: SK.head, type: 'trigger', default: true },
        field: { name: 'Сборка иконок сверху', description: 'Прячет лишние иконки в верхней панели и добавляет кнопку-переключатель «•••» — по нажатию они разворачиваются.' },
        onChange: function () { setTimeout(function () { apply(); setupHead(); }, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'appletv_theme',
        param: { name: SK.menudim, type: 'trigger', default: false },
        field: { name: 'Затемнять контент при открытом меню', description: 'Эффект как в tvOS. По умолчанию выключено для совместимости.' },
        onChange: function () { setTimeout(apply, 30); }
      });
    } catch (e) {}
  }

  // ─── слежение за фокусом меню (для затемнения контента) ─────────────────────
  var menuWatchStarted = false;
  function watchMenu() {
    if (menuWatchStarted) return;
    var menu = document.querySelector('.menu');
    if (!menu) return setTimeout(watchMenu, 600);
    menuWatchStarted = true;

    var raf = window.requestAnimationFrame || function (cb) { setTimeout(cb, 32); };
    var scheduled = false;
    function update() {
      scheduled = false;
      // меню «активно», когда внутри него есть элемент в фокусе
      var active = !!menu.querySelector('.menu__item.focus') || menu.classList.contains('focus');
      document.body.classList.toggle('atv-menu-active', active);
    }
    function schedule() { if (!scheduled) { scheduled = true; raf(update); } }

    try {
      var mo = new MutationObserver(schedule);
      mo.observe(menu, { subtree: true, attributes: true, attributeFilter: ['class'] });
    } catch (e) {}
    update();
  }

  // ─── СБОРКА ВЕРХНИХ ИКОНОК ──────────────────────────────────────────────────
  var headApplying = false;
  function setupHead() {
    if (headApplying) return;
    try {
      if (!get(SK.enabled, true) || !get(SK.head, true)) { unsetupHead(); return; }
      var head = document.querySelector('.head');
      if (!head) return;
      if (head.querySelector('.atv-head-toggle')) return; // уже собрано

      var actions = Array.prototype.slice.call(head.querySelectorAll('.head__action'))
        .filter(function (a) { return !a.classList.contains('atv-head-toggle'); });
      if (actions.length < 2) return; // нечего сворачивать

      headApplying = true;
      var container = actions[0].parentNode;

      // прячем существующие иконки
      actions.forEach(function (a) { a.classList.add('atv-head-collapsible'); });

      // кнопка-переключатель «•••»
      var toggle = document.createElement('div');
      toggle.className = 'head__action selector atv-head-toggle';
      toggle.title = 'Показать иконки';
      toggle.innerHTML = '<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">' +
        '<circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg>';
      container.insertBefore(toggle, container.firstChild);

      function toggleOpen() { document.body.classList.toggle('atv-head-open'); }
      if (window.$) {
        try { $(toggle).on('hover:enter', toggleOpen); } catch (e) {}
      }
      toggle.addEventListener('click', toggleOpen);

      headApplying = false;
    } catch (e) { headApplying = false; }
  }

  function unsetupHead() {
    try {
      document.body.classList.remove('atv-head-open');
      var head = document.querySelector('.head');
      if (!head) return;
      Array.prototype.forEach.call(head.querySelectorAll('.atv-head-collapsible'),
        function (a) { a.classList.remove('atv-head-collapsible'); });
      var t = head.querySelector('.atv-head-toggle');
      if (t) t.remove();
    } catch (e) {}
  }

  // переустанавливаем сборку, если Lampa перерисовала шапку
  var headWatchStarted = false;
  function watchHead() {
    if (headWatchStarted) return;
    var head = document.querySelector('.head');
    if (!head) return setTimeout(watchHead, 600);
    headWatchStarted = true;
    try {
      var mo = new MutationObserver(function () {
        if (!headApplying) setTimeout(setupHead, 50);
      });
      mo.observe(head, { childList: true, subtree: true });
    } catch (e) {}
  }

  // закрывать выпавшие иконки при смене экрана
  function bindHeadAutoClose() {
    if (!window.Lampa || !Lampa.Listener) return;
    try {
      Lampa.Listener.follow('activity', function () {
        document.body.classList.remove('atv-head-open');
      });
    } catch (e) {}
  }

  // ─── init ───────────────────────────────────────────────────────────────────
  function init() {
    if (!window.Lampa || !Lampa.Storage) return setTimeout(init, 400);
    apply();
    if (Lampa.SettingsApi) injectSettings();
    else setTimeout(injectSettings, 800);
    watchMenu();
    setTimeout(setupHead, 800);
    watchHead();
    bindHeadAutoClose();
  }

  if (window.appready) init();
  else if (window.Lampa && Lampa.Listener) Lampa.Listener.follow('app', function (e) { if (e.type === 'ready') init(); });
  else setTimeout(init, 500);
})();
