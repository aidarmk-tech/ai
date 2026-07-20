/*!
 * Amber Glow — тема для Lampa
 *
 * Оболочка в стиле «midnight + amber»: глубокий сине-графитовый фон,
 * янтарно-оранжевый акцент с мягким свечением вокруг фокуса, стеклянные
 * панели с размытием, меню-«пилюли» с заливкой и glow-ореолом, скруглённые
 * карточки с янтарным кольцом фокуса и янтарные прогресс-бары.
 *
 * Настройки → Amber Glow тема:
 *   • Включить тему
 *   • Оттенок акцента (янтарь / золото / медь / мандарин)
 *   • Фон (Midnight / чёрный OLED / графит)
 *   • Свечение фокуса (яркое / мягкое / выкл)
 *   • Стиль фокуса меню (заливка-пилюля / кольцо)
 *   • Скругление углов
 *   • Размытие панелей (можно отключить на слабых ТВ-боксах)
 *
 * Чистый CSS-инжект, никаких внешних запросов. Отключение мгновенно
 * возвращает стандартный вид.
 */
(function () {
  'use strict';

  var STYLE_ID = 'amber-glow-theme';

  var SK = {
    enabled: 'amberglow_enabled',
    accent:  'amberglow_accent',
    bg:      'amberglow_bg',
    glow:    'amberglow_glow',
    menu:    'amberglow_menu',
    radius:  'amberglow_radius',
    blur:    'amberglow_blur'
  };

  // Тёплые акценты: верх/низ градиента + базовый цвет
  var ACCENTS = {
    amber:     { name: 'Янтарь',   c: '#f5a83d', hi: '#ffc46b', lo: '#ef9426' },
    gold:      { name: 'Золото',   c: '#e8b93a', hi: '#f7d468', lo: '#d9a521' },
    copper:    { name: 'Медь',     c: '#e08a4a', hi: '#f3a86e', lo: '#cf7431' },
    tangerine: { name: 'Мандарин', c: '#ff9d3c', hi: '#ffbb66', lo: '#f28418' }
  };

  var BGS = {
    midnight: { name: 'Midnight',       bg: '#0a0f17', bg2: '#0e1622', bg3: '#152134', surf: 'rgba(148,178,215,0.07)', surf2: 'rgba(148,178,215,0.13)', line: 'rgba(148,178,215,0.12)', txt: '#eef2f8', dim: 'rgba(200,214,232,0.60)' },
    black:    { name: 'Чёрный (OLED)',  bg: '#000000', bg2: '#05070c', bg3: '#0a0e16', surf: 'rgba(148,178,215,0.06)', surf2: 'rgba(148,178,215,0.12)', line: 'rgba(148,178,215,0.10)', txt: '#eef2f8', dim: 'rgba(200,214,232,0.55)' },
    graphite: { name: 'Графит',         bg: '#101216', bg2: '#171a20', bg3: '#20242c', surf: 'rgba(255,255,255,0.07)',  surf2: 'rgba(255,255,255,0.13)',  line: 'rgba(255,255,255,0.11)',  txt: '#f2f3f5', dim: 'rgba(225,228,235,0.60)' }
  };

  var GLOWS = {
    bright: { name: 'Яркое' },
    soft:   { name: 'Мягкое' },
    off:    { name: 'Выкл' }
  };

  var MENUS = {
    pill: { name: 'Заливка-пилюля' },
    ring: { name: 'Кольцо' }
  };

  var RADII = {
    round:  { name: 'Округлое', sm: '0.7em',  md: '1.1em',  lg: '1.4em' },
    soft:   { name: 'Мягкое',   sm: '0.5em',  md: '0.8em',  lg: '1.1em' },
    square: { name: 'Строгое',  sm: '0.3em',  md: '0.45em', lg: '0.6em' }
  };

  // ─── helpers ──────────────────────────────────────────────────────────────
  function get(key, def) {
    try { var v = Lampa.Storage.get(key, def); return (v === undefined || v === null) ? def : v; }
    catch (e) { return def; }
  }
  function hexToRgba(hex, a) {
    hex = (hex || '#f5a83d').replace('#', '');
    if (hex.length === 3) hex = hex.replace(/(.)/g, '$1$1');
    var n = parseInt(hex, 16);
    var r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    return 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
  }

  // ─── CSS ──────────────────────────────────────────────────────────────────
  function buildCSS() {
    var ac   = ACCENTS[get(SK.accent, 'amber')] || ACCENTS.amber;
    var bg   = BGS[get(SK.bg, 'midnight')]      || BGS.midnight;
    var rad  = RADII[get(SK.radius, 'round')]   || RADII.round;
    var glow = get(SK.glow, 'bright');
    var menuStyle = get(SK.menu, 'pill');
    var blur = get(SK.blur, true);

    var A    = ac.c;
    var Adark = '#1c1204';                 // тёмный текст на янтарной заливке
    var fill  = 'linear-gradient(180deg,' + ac.hi + ' 0%,' + ac.lo + ' 100%)';
    var Aln   = hexToRgba(A, 0.95);        // линия кольца
    var Ag60  = hexToRgba(A, 0.60);
    var Ag40  = hexToRgba(A, 0.42);
    var Ag25  = hexToRgba(A, 0.25);
    var Ag12  = hexToRgba(A, 0.12);

    // Фирменное свечение: тонкое кольцо + ореол наружу
    var haloBig  = glow === 'off' ? '' : ',0 0 1.6em 0.25em ' + (glow === 'bright' ? Ag60 : Ag40) + ',0 0 3em 0.6em ' + (glow === 'bright' ? Ag25 : Ag12);
    var haloSm   = glow === 'off' ? '' : ',0 0 1.1em 0.15em ' + (glow === 'bright' ? Ag60 : Ag40);
    var ring     = '0 0 0 0.16em ' + Aln + haloBig;
    var ringSm   = '0 0 0 0.13em ' + Aln + haloSm;
    var fillGlow = (glow === 'off' ? '0 0.5em 1.2em rgba(0,0,0,0.45)' : '0 0.5em 1.4em rgba(0,0,0,0.45),0 0 1.5em 0.3em ' + (glow === 'bright' ? Ag60 : Ag40) + ',0 0 3em 0.7em ' + (glow === 'bright' ? Ag25 : Ag12));

    var blurPanel = blur ? 'saturate(160%) blur(22px)' : 'none';
    var blurSoft  = blur ? 'saturate(150%) blur(14px)' : 'none';

    var css = [];

    /* ── шрифт и фон ───────────────────────────────────────────────────── */
    css.push(
      'body,.body{font-family:-apple-system,BlinkMacSystemFont,"SF Pro Display","Segoe UI",system-ui,Roboto,"Helvetica Neue",Arial,sans-serif !important;' +
      '-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility;letter-spacing:0.01em}',

      /* глубокий сине-графитовый фон с тёплым «рассветным» отблеском сверху */
      '.wrap,.wrap__content,body{background:' +
        'radial-gradient(90% 60% at 78% -8%,' + hexToRgba(ac.lo, 0.10) + ' 0%,transparent 55%),' +
        'radial-gradient(120% 85% at 50% -10%,' + bg.bg3 + ' 0%,' + bg.bg2 + ' 45%,' + bg.bg + ' 100%) fixed,' +
        bg.bg + ' !important;color:' + bg.txt + '}'
    );

    /* ── убираем штатную рамку фокуса карточек ─────────────────────────── */
    css.push(
      '.card.focus .card__view::after,.card.hover .card__view::after{border:0 !important}'
    );

    /* ── боковое меню: «пилюли» ────────────────────────────────────────── */
    css.push(
      '.menu{background:transparent !important}',
      '.menu__list{padding:0.5em 0.7em}',
      '.menu__item{position:relative;border-radius:2em !important;padding:0.6em 1.1em !important;margin:0.18em 0 !important;color:' + bg.txt + ';' +
        'transition:background .25s cubic-bezier(.2,.7,.2,1),color .25s,box-shadow .25s,transform .25s cubic-bezier(.2,.7,.2,1)}',
      '.menu__item .menu__text{font-weight:600;letter-spacing:0.01em}',
      '.menu__ico svg,.menu__ico{transition:color .2s,transform .25s cubic-bezier(.2,.7,.2,1)}',
      '.menu__item.active{background:' + bg.surf + ' !important}',
      '.menu__item.active .menu__ico{color:' + A + '}',
      '.menu__split{opacity:0.3}'
    );
    if (menuStyle === 'pill') {
      css.push(
        '.menu__item.focus,.menu__item:hover{background:' + fill + ' !important;color:' + Adark + ' !important;box-shadow:' + fillGlow + ' !important;transform:translateX(0.15em)}',
        '.menu__item.focus .menu__text,.menu__item:hover .menu__text{color:' + Adark + ' !important;font-weight:700}',
        '.menu__item.focus .menu__ico,.menu__item:hover .menu__ico{color:' + Adark + ' !important;transform:scale(1.1)}'
      );
    } else {
      css.push(
        '.menu__item.focus,.menu__item:hover{background:' + bg.surf2 + ' !important;box-shadow:' + ring + ' !important;transform:translateX(0.15em)}',
        '.menu__item.focus .menu__ico{color:' + A + ';transform:scale(1.1)}'
      );
    }

    /* ── плавное появление экранов ─────────────────────────────────────── */
    css.push(
      '.activity--active{animation:agScreen .35s cubic-bezier(.2,.7,.2,1)}',
      '@keyframes agScreen{0%{opacity:0}100%{opacity:1}}'
    );

    /* ── верхняя панель ────────────────────────────────────────────────── */
    css.push(
      '.head{background:' + (blur ? hexToRgba(bg.bg, 0.55) : bg.bg) + ' !important;-webkit-backdrop-filter:' + blurPanel + ';backdrop-filter:' + blurPanel + ';border-bottom:1px solid ' + bg.line + ' !important}',
      '.head__action,.head__split{border-radius:' + rad.sm + ' !important;transition:background .2s,box-shadow .2s,color .2s}',
      '.head__action.focus,.head__action:hover{background:' + fill + ' !important;color:' + Adark + ' !important;box-shadow:' + fillGlow + '}',
      '.head__action.focus *{color:' + Adark + ' !important}'
    );

    /* ── заголовки полок ───────────────────────────────────────────────── */
    css.push(
      '.items-line__title,.category-full__title,.scroll__title{font-weight:800 !important;letter-spacing:0.015em;opacity:0.95}',
      '.items-line{margin-bottom:1.2em}',
      '.items-line__more{border-radius:2em !important;transition:background .2s,box-shadow .2s,color .2s}',
      '.items-line__more.focus{background:' + bg.surf2 + ' !important;box-shadow:' + ringSm + ' !important}'
    );

    /* ── КАРТОЧКИ: янтарное кольцо-свечение ────────────────────────────── */
    css.push(
      '.card{padding:0.55em !important}',
      '.card__view{border-radius:' + rad.lg + ' !important;overflow:hidden;box-shadow:0 0.5em 1.1em rgba(0,0,0,0.35);' +
        'transition:transform .28s cubic-bezier(.2,.7,.2,1),box-shadow .28s cubic-bezier(.2,.7,.2,1) !important;will-change:transform;position:relative}',
      '.card__img{transition:transform .4s cubic-bezier(.2,.7,.2,1)}',
      '.card.focus .card__view{transform:scale(1.05);box-shadow:0 0.9em 2em rgba(0,0,0,0.55),' + ring + ' !important;z-index:5}',
      '.card.focus .card__img{transform:scale(1.05)}',
      '.card.focus .card__title{color:' + A + ';opacity:1}',

      /* мета-плашки: 4K / HDR / рейтинг */
      '.card__type,.card__quality,.card__vote,.card__age,.card__marker>span{border-radius:0.5em !important;font-weight:700;letter-spacing:0.02em;-webkit-backdrop-filter:blur(6px);backdrop-filter:blur(6px)}',
      '.card__vote{background:' + hexToRgba(A, 0.94) + ' !important;color:' + Adark + ' !important}',
      '.card__type{background:rgba(8,12,20,0.65) !important;color:' + ac.hi + ' !important}',
      '.card__quality{background:rgba(8,12,20,0.65) !important;color:' + bg.txt + '}',
      '.card__title{font-weight:600;opacity:0.92;padding-top:0.55em;letter-spacing:0.01em;transition:color .2s}',
      '.card__age{opacity:0.8}'
    );

    /* ── прогресс просмотра: янтарная полоска ──────────────────────────── */
    css.push(
      '.time-line{background:rgba(148,178,215,0.18) !important;border-radius:1em;overflow:hidden}',
      '.time-line>div:first-child{background:' + fill + ' !important;border-radius:1em}',
      '.card-watched .time-line>div:first-child{background:' + fill + ' !important}'
    );

    /* ── СТРАНИЦА ФИЛЬМА ───────────────────────────────────────────────── */
    css.push(
      '.full-start__background,.full-start-new__background{animation:agKen 28s ease-in-out infinite alternate;transform-origin:center}',
      '@keyframes agKen{0%{transform:scale(1.04)}100%{transform:scale(1.12)}}',
      '.full-start__title,.full-start-new__title{font-weight:900 !important;letter-spacing:-0.01em;line-height:1.05;text-shadow:0 0.12em 0.6em rgba(0,0,0,0.55)}',
      '.full-start__poster,.full-start-new__poster{border-radius:' + rad.lg + ' !important;overflow:hidden;box-shadow:0 1em 2.5em rgba(0,0,0,0.55)}',
      '.full-start__rate>div:first-child,.full-start-new__rate>div:first-child{color:' + A + ';font-weight:800}',
      '.full-descr__text,.full-start__descr,.full-start-new__descr{line-height:1.6 !important;opacity:0.85}',

      /* жанры-чипы */
      '.full-start__tags .tag-count,.full-start-new__details span{border-radius:2em}',

      /* кнопки: фокус = янтарная заливка с glow, остальные — стеклянные пилюли */
      '.full-start__button,.full-start-new__button,.simple-button{border-radius:2em !important;background:' + bg.surf + ' !important;-webkit-backdrop-filter:' + blurSoft + ';backdrop-filter:' + blurSoft + ';' +
        'border:1px solid ' + bg.line + ' !important;font-weight:700;transition:transform .2s,background .2s,box-shadow .2s,color .2s}',
      '.full-start__button.focus,.full-start-new__button.focus,.simple-button.focus{background:' + fill + ' !important;color:' + Adark + ' !important;border-color:transparent !important;' +
        'transform:translateY(-0.1em) scale(1.03);box-shadow:' + fillGlow + ' !important}',
      '.full-start__button.focus *,.full-start-new__button.focus *,.simple-button.focus *{color:' + Adark + ' !important;fill:' + Adark + '}'
    );

    /* ── общий фокус для прочих selector ───────────────────────────────── */
    css.push(
      '.selector{transition:box-shadow .2s,background .2s,transform .2s}',
      '.full-person.focus,.full-review.focus,.tag-count.focus,.extensions__item.focus,.online-prestige.focus,.torrent-item.focus,.register.focus,.folder.focus{' +
        'box-shadow:' + ringSm + ' !important;border-radius:' + rad.md + ' !important;background:' + bg.surf + ' !important}'
    );

    /* ── НАСТРОЙКИ: стеклянные пилюли ──────────────────────────────────── */
    css.push(
      '.settings__content,.settings-folder{border-radius:' + rad.lg + ' !important}',
      '.settings-folder,.settings-param{border-radius:1.6em !important;transition:background .2s,box-shadow .2s,color .2s}',
      '.settings-folder.focus,.settings-param.focus,.settings-param-title.focus{background:' + fill + ' !important;color:' + Adark + ' !important;' +
        '-webkit-backdrop-filter:none;backdrop-filter:none;box-shadow:' + fillGlow + ' !important}',
      '.settings-folder.focus *,.settings-param.focus *{color:' + Adark + ' !important}',
      '.settings-folder.focus svg *,.settings-param.focus svg *{fill:' + Adark + ' !important;stroke:' + Adark + ' !important}',
      '.settings-param__name{font-weight:600}',
      '.settings-param>div:last-child,.settings-param__value{color:' + A + '}',
      '.settings-param.focus .settings-param__value{color:' + Adark + ' !important}'
    );

    /* ── SELECTBOX / модалки: стеклянная панель, кольцо на пунктах ──────
       НЕ трогаем позиционирование и фон контейнера-оверлея, чтобы не
       сломать всплывашки. Только вид содержимого. */
    css.push(
      '.selectbox__content,.modal__content{border-radius:' + rad.lg + ' !important;background:' + hexToRgba(bg.bg2, blur ? 0.85 : 1) + ' !important;' +
        '-webkit-backdrop-filter:' + blurPanel + ';backdrop-filter:' + blurPanel + ';border:1px solid ' + bg.line + '}',
      '.selectbox__title,.modal__title{font-weight:800}',
      '.selectbox-item,.selectbox__content .selector{border-radius:' + rad.md + ' !important;transition:background .15s,box-shadow .15s}',
      '.selectbox-item.focus,.selectbox__content .selector.focus{background:' + bg.surf2 + ' !important;box-shadow:' + ringSm + ' !important}',
      '.selectbox-item__checkbox.active{background:' + A + ' !important;border-color:' + A + ' !important}'
    );

    /* ── ПОИСК / клавиатура ────────────────────────────────────────────── */
    css.push(
      '.search-source.active,.search-source.focus{color:' + A + ' !important}',
      '.search__input{border-radius:2em !important}',
      '.simple-keyboard{border-radius:' + rad.md + ' !important;background:' + hexToRgba(bg.bg2, blur ? 0.75 : 1) + ' !important;-webkit-backdrop-filter:' + blurPanel + ';backdrop-filter:' + blurPanel + '}',
      '.simple-keyboard .hg-button{border-radius:' + rad.sm + ' !important;background:' + bg.surf + ' !important;transition:background .12s,box-shadow .12s,transform .12s;color:' + bg.txt + '}',
      '.simple-keyboard .hg-button.focus,.simple-keyboard .hg-button.hovered{background:' + fill + ' !important;color:' + Adark + ' !important;transform:scale(1.1);box-shadow:' + fillGlow + ' !important;z-index:2}'
    );

    /* ── ПЛЕЕР ─────────────────────────────────────────────────────────── */
    css.push(
      '.player-panel{background:linear-gradient(0deg,' + hexToRgba(bg.bg, 0.92) + ' 0%,transparent 100%) !important}',
      '.player-panel .button.focus,.player-panel .selector.focus{box-shadow:' + ringSm + ' !important;border-radius:' + rad.sm + '}',
      '.player-panel__line>div:first-child{background:' + fill + ' !important}',
      '.player-info{border-radius:' + rad.md + '}'
    );

    /* ── уведомления / нижние вкладки ──────────────────────────────────── */
    css.push(
      '.noty{border-radius:' + rad.md + ' !important;background:' + hexToRgba(bg.bg2, blur ? 0.85 : 1) + ' !important;-webkit-backdrop-filter:' + blurPanel + ';backdrop-filter:' + blurPanel + ';border:1px solid ' + bg.line + ';box-shadow:0 1em 2.5em rgba(0,0,0,0.5) !important}',
      '.navigation-tabs{background:' + (blur ? hexToRgba(bg.bg, 0.6) : bg.bg) + ' !important;-webkit-backdrop-filter:' + blurPanel + ';backdrop-filter:' + blurPanel + ';border-top:1px solid ' + bg.line + '}',
      '.navigation-tabs__item.focus,.navigation-tabs__item.active{color:' + A + ' !important}'
    );

    /* ── скроллбары ────────────────────────────────────────────────────── */
    css.push(
      '::-webkit-scrollbar{width:0.5em;height:0.5em}',
      '::-webkit-scrollbar-thumb{background:' + Ag40 + ';border-radius:1em}',
      '::-webkit-scrollbar-track{background:transparent}'
    );

    return css.join('\n');
  }

  // ─── apply / remove ───────────────────────────────────────────────────────
  function apply() {
    var enabled = get(SK.enabled, true);
    var el = document.getElementById(STYLE_ID);

    if (!enabled) { if (el) el.remove(); return; }

    if (!el) {
      el = document.createElement('style');
      el.id = STYLE_ID;
      document.head.appendChild(el);
    }
    el.textContent = buildCSS();
  }

  // ─── Настройки ────────────────────────────────────────────────────────────
  function mapNames(obj) {
    var out = {};
    Object.keys(obj).forEach(function (k) { out[k] = obj[k].name; });
    return out;
  }

  function injectSettings() {
    if (!window.Lampa || !Lampa.SettingsApi) return;
    try {
      Lampa.SettingsApi.addComponent({
        component: 'amberglow_theme',
        name: 'Amber Glow тема',
        icon: '<svg viewBox="0 0 24 24" width="24" height="24"><circle cx="12" cy="12" r="5" fill="currentColor"/><path fill="currentColor" d="M12 1.5 13 5h-2l1-3.5zM12 22.5 11 19h2l-1 3.5zM1.5 12 5 11v2l-3.5-1zM22.5 12 19 13v-2l3.5 1zM4.6 4.6 7.4 6 6 7.4 4.6 4.6zM19.4 19.4 16.6 18l1.4-1.4 1.4 2.8zM19.4 4.6 18 7.4 16.6 6l2.8-1.4zM4.6 19.4 6 16.6 7.4 18l-2.8 1.4z"/></svg>'
      });

      Lampa.SettingsApi.addParam({
        component: 'amberglow_theme',
        param: { name: SK.enabled, type: 'trigger', default: true },
        field: { name: 'Включить тему', description: 'Тёмная «midnight»-оболочка с янтарным свечением фокуса. Отключение мгновенно возвращает стандартный вид.' },
        onChange: function () { setTimeout(apply, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'amberglow_theme',
        param: { name: SK.accent, type: 'select', values: mapNames(ACCENTS), default: 'amber' },
        field: { name: 'Оттенок акцента', description: 'Цвет заливки, свечения и активных элементов.' },
        onChange: function () { setTimeout(apply, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'amberglow_theme',
        param: { name: SK.bg, type: 'select', values: mapNames(BGS), default: 'midnight' },
        field: { name: 'Фон', description: 'Midnight — глубокий сине-графитовый. Чёрный — для OLED-экранов.' },
        onChange: function () { setTimeout(apply, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'amberglow_theme',
        param: { name: SK.glow, type: 'select', values: mapNames(GLOWS), default: 'bright' },
        field: { name: 'Свечение фокуса', description: 'Янтарный ореол вокруг выбранного элемента. «Выкл» — только кольцо, для слабых устройств.' },
        onChange: function () { setTimeout(apply, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'amberglow_theme',
        param: { name: SK.menu, type: 'select', values: mapNames(MENUS), default: 'pill' },
        field: { name: 'Стиль фокуса меню', description: 'Заливка-пилюля — янтарная кнопка с тёмным текстом, как на референсе. Кольцо — сдержанный вариант.' },
        onChange: function () { setTimeout(apply, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'amberglow_theme',
        param: { name: SK.radius, type: 'select', values: mapNames(RADII), default: 'round' },
        field: { name: 'Скругление углов', description: 'Насколько круглые карточки, панели и клавиши.' },
        onChange: function () { setTimeout(apply, 30); }
      });

      Lampa.SettingsApi.addParam({
        component: 'amberglow_theme',
        param: { name: SK.blur, type: 'trigger', default: true },
        field: { name: 'Размытие панелей', description: 'Матовое стекло на модалках и клавиатуре. Отключите, если интерфейс подтормаживает на ТВ-боксе.' },
        onChange: function () { setTimeout(apply, 30); }
      });
    } catch (e) {}
  }

  // ─── init ─────────────────────────────────────────────────────────────────
  function init() {
    if (!window.Lampa || !Lampa.Storage) return setTimeout(init, 400);
    apply();
    if (Lampa.SettingsApi) injectSettings();
    else setTimeout(injectSettings, 800);
  }

  if (window.appready) init();
  else if (window.Lampa && Lampa.Listener) Lampa.Listener.follow('app', function (e) { if (e.type === 'ready') init(); });
  else setTimeout(init, 500);
})();
