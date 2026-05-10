/*!
 * Card Insight for Lampa — v3
 * Кнопка "Подробнее" на карточке: бэкдроп, описание, факты,
 * актёры и рекомендации TMDB.
 *
 * v3: Полноэкранная Activity вместо модалки (правильный TV-скролл).
 *     Фокус сам прокручивает страницу через Lampa.Controller.
 * Никаких API-ключей — встроенный Lampa.TMDB.
 */
(function () {
  'use strict';

  var COMPONENT_NAME = 'card_insight_full';

  // ====================================================
  // СТИЛИ
  // ====================================================
  function injectStyles() {
    if (document.getElementById('card-insight-styles')) return;
    var css = [
      /* Корневой контейнер */
      '.ci-page{padding:1.5em}',
      '.ci-loading{padding:4em 1em;text-align:center;opacity:0.7;font-size:1.3em}',
      '.ci-error{padding:2em 1em;text-align:center;color:#ff7e7e;opacity:0.85;font-size:1.2em}',

      /* Hero — широкий бэкдроп */
      '.ci-hero{height:18em;margin:0 0 1.3em;border-radius:0.5em;overflow:hidden;position:relative;background:#111 center/cover no-repeat}',
      '.ci-hero__overlay{position:absolute;inset:0;background:linear-gradient(180deg,rgba(0,0,0,0.05) 40%,rgba(0,0,0,0.7) 100%)}',
      '.ci-hero__title{position:absolute;left:1.2em;right:1.2em;bottom:1em;font-size:2.4em;font-weight:800;line-height:1.1;text-shadow:0 0.1em 0.5em rgba(0,0,0,0.8)}',

      /* Тэглайн и описание */
      '.ci-tagline{font-style:italic;margin:0 0 0.8em;opacity:0.85;font-size:1.3em;padding:0 0.2em}',
      '.ci-meta{display:flex;flex-wrap:wrap;gap:0.5em 1.3em;margin-bottom:1.2em;opacity:0.8;font-size:1.15em;padding:0 0.2em}',
      '.ci-meta__item{white-space:nowrap}',
      '.ci-meta__item--rating{color:#ffd54f;font-weight:700}',
      '.ci-overview{line-height:1.6;margin:0 0 2em;font-size:1.2em;padding:0 0.2em;max-width:65em}',

      /* Заголовки секций */
      '.ci-section{margin-bottom:2em}',
      '.ci-section-title{font-size:1.6em;font-weight:700;margin:0 0 0.8em;padding:0 0.2em;display:flex;align-items:center;gap:0.55em}',
      '.ci-section-title__bar{display:inline-block;width:0.28em;height:1em;background:#ffa726;border-radius:0.1em}',

      /* Факты */
      '.ci-facts{display:grid;grid-template-columns:repeat(auto-fill,minmax(22em,1fr));gap:0.6em}',
      '.ci-fact{display:flex;gap:0.8em;padding:0.85em 1em;background:rgba(255,255,255,0.05);border-radius:0.4em;align-items:center;border:0.06em solid rgba(255,255,255,0.06)}',
      '.ci-fact__icon{font-size:1.5em;flex-shrink:0;line-height:1}',
      '.ci-fact__text{font-size:1.1em;line-height:1.4}',
      '.ci-fact__label{opacity:0.6;margin-right:0.3em}',

      /* Актёры */
      '.ci-cast{display:grid;grid-template-columns:repeat(auto-fill,minmax(9em,1fr));gap:0.9em}',
      '.ci-actor{cursor:pointer;border-radius:0.4em;overflow:hidden;background:rgba(255,255,255,0.05);transition:transform 0.15s,background 0.15s}',
      '.ci-actor.focus{transform:scale(1.05);background:rgba(255,255,255,0.2);box-shadow:0 0 0 0.2em #ffa726}',
      '.ci-actor__photo{position:relative;padding-top:130%;background:#222 center/cover no-repeat}',
      '.ci-actor__no-photo{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:2.4em;opacity:0.3}',
      '.ci-actor__name{padding:0.5em 0.6em 0;font-weight:600;font-size:1em;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.6em}',
      '.ci-actor__role{padding:0.18em 0.6em 0.6em;opacity:0.6;font-size:0.88em;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.5em}',

      /* Карточки рекомендаций */
      '.ci-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(14em,1fr));gap:1.1em}',
      '.ci-card{cursor:pointer;border-radius:0.4em;overflow:hidden;background:rgba(255,255,255,0.05);transition:transform 0.15s,background 0.15s;position:relative}',
      '.ci-card.focus{transform:scale(1.05);background:rgba(255,255,255,0.2);box-shadow:0 0 0 0.22em #ffa726}',
      '.ci-card__poster{position:relative;padding-top:150%;background:#222 center/cover no-repeat}',
      '.ci-card__no-poster{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:2.6em;opacity:0.25}',
      '.ci-card__rating{position:absolute;top:0.4em;right:0.4em;background:rgba(0,0,0,0.78);padding:0.2em 0.55em;border-radius:0.25em;font-size:0.95em}',
      '.ci-card__info{padding:0.65em 0.75em 0.85em}',
      '.ci-card__title{font-weight:600;font-size:1.05em;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;line-height:1.3;min-height:2.7em}',
      '.ci-card__year{opacity:0.55;font-size:0.9em;margin-top:0.3em}',

      /* Кнопка в карточке */
      '.full-start__button.view--ci svg{margin-right:0.4em;vertical-align:middle}',

      /* Невидимые "якоря" фокуса для текстовых блоков —
         пульт по ним проходит и тащит скролл, но визуально не видны */
      '.ci-focus-anchor{outline:none}',
      '.ci-focus-anchor.focus{outline:none}',
      '.ci-overview-block.focus{background:rgba(255,255,255,0.04);border-radius:0.4em}',
      '.ci-fact.focus{background:rgba(255,255,255,0.15);box-shadow:0 0 0 0.15em #ffa726}'
    ].join('\n');

    var style = document.createElement('style');
    style.id = 'card-insight-styles';
    style.textContent = css;
    document.head.appendChild(style);
  }

  // ====================================================
  // УТИЛИТЫ
  // ====================================================
  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c];
    });
  }

  function formatNum(n) {
    if (n == null) return '';
    return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  }

  function formatMoney(n) {
    if (!n || n < 100) return '';
    if (n >= 1e9) return '$' + (n / 1e9).toFixed(2).replace(/\.?0+$/, '') + ' млрд';
    if (n >= 1e6) return '$' + (n / 1e6).toFixed(1).replace(/\.0$/, '') + ' млн';
    if (n >= 1e3) return '$' + Math.round(n / 1e3) + ' тыс';
    return '$' + n;
  }

  var LANG_NAMES = {
    en: 'английский', fr: 'французский', de: 'немецкий', es: 'испанский',
    it: 'итальянский', ja: 'японский', ko: 'корейский', zh: 'китайский',
    cn: 'китайский', tr: 'турецкий', kk: 'казахский', uk: 'украинский',
    pl: 'польский', pt: 'португальский', sv: 'шведский', nl: 'нидерландский',
    da: 'датский', fi: 'финский', no: 'норвежский', cs: 'чешский',
    hi: 'хинди', ar: 'арабский', th: 'тайский', vi: 'вьетнамский',
    he: 'иврит', el: 'греческий', hu: 'венгерский', ro: 'румынский',
    ru: 'русский'
  };

  // ====================================================
  // TMDB API
  // ====================================================
  function fetchFullData(method, id, callback) {
    var network = new Lampa.Reguest();
    var url = Lampa.TMDB.api(
      method + '/' + id +
      '?api_key=' + Lampa.TMDB.key() +
      '&language=ru' +
      '&append_to_response=recommendations,similar,credits,keywords'
    );
    network.silent(url,
      function (data) { callback(null, data); },
      function (err) { callback(err || new Error('Ошибка сети')); }
    );
  }

  // ====================================================
  // ФАКТЫ
  // ====================================================
  function buildFacts(data) {
    var facts = [];
    var crew = (data.credits && data.credits.crew) || [];

    function findCrew(jobs) {
      var jobSet = {};
      jobs.forEach(function (j) { jobSet[j] = true; });
      return crew
        .filter(function (p) { return jobSet[p.job]; })
        .map(function (p) { return p.name; })
        .filter(function (n, i, arr) { return arr.indexOf(n) === i; });
    }

    var directors = findCrew(['Director']);
    if (directors.length) facts.push({ icon: '🎬', label: 'Режиссёр', text: directors.join(', ') });

    var writers = findCrew(['Screenplay', 'Writer', 'Story']);
    if (writers.length) facts.push({ icon: '✍️', label: 'Сценарий', text: writers.slice(0, 3).join(', ') });

    var composers = findCrew(['Original Music Composer']);
    if (composers.length) facts.push({ icon: '🎵', label: 'Композитор', text: composers.join(', ') });

    var dop = findCrew(['Director of Photography']);
    if (dop.length) facts.push({ icon: '🎥', label: 'Оператор', text: dop[0] });

    if (data.budget && data.budget > 1000 && data.revenue && data.revenue > 1000) {
      var ratio = data.revenue / data.budget;
      var verdict = ratio >= 5 ? 'мега-хит'
        : ratio >= 2 ? 'окупился ×' + ratio.toFixed(1)
        : ratio >= 1 ? 'окупился' : 'не окупился';
      facts.push({ icon: '💰', text: formatMoney(data.budget) + ' → ' + formatMoney(data.revenue) + ' (' + verdict + ')' });
    } else if (data.budget && data.budget > 1000) {
      facts.push({ icon: '💰', label: 'Бюджет', text: formatMoney(data.budget) });
    } else if (data.revenue && data.revenue > 1000) {
      facts.push({ icon: '💰', label: 'Сборы', text: formatMoney(data.revenue) });
    }

    if (data.vote_average >= 7.5 && data.vote_count >= 100) {
      facts.push({ icon: '⭐', text: 'Высокий рейтинг ' + data.vote_average.toFixed(1) + '/10 (' + formatNum(data.vote_count) + ' оценок)' });
    } else if (data.vote_count >= 5000) {
      facts.push({ icon: '🔥', text: 'Очень популярный — ' + formatNum(data.vote_count) + ' оценок на TMDB' });
    }

    if (data.belongs_to_collection && data.belongs_to_collection.name)
      facts.push({ icon: '🔗', label: 'Серия', text: data.belongs_to_collection.name });

    if (data.created_by && data.created_by.length)
      facts.push({ icon: '🎬', label: 'Создатель', text: data.created_by.map(function (p) { return p.name; }).join(', ') });

    if (data.networks && data.networks.length)
      facts.push({ icon: '📺', label: 'Канал', text: data.networks.map(function (n) { return n.name; }).join(', ') });

    if (data.status === 'Ended') facts.push({ icon: '🏁', text: 'Сериал завершён' });
    else if (data.status === 'Returning Series') facts.push({ icon: '🔄', text: 'Сериал продолжается' });
    else if (data.status === 'Canceled' || data.status === 'Cancelled') facts.push({ icon: '❌', text: 'Сериал закрыт' });

    var kws = (data.keywords && (data.keywords.keywords || data.keywords.results)) || [];
    if (kws.length)
      facts.push({ icon: '🏷️', label: 'Темы', text: kws.slice(0, 5).map(function (k) { return k.name; }).join(', ') });

    if (data.production_countries && data.production_countries.length > 1)
      facts.push({ icon: '🌍', label: 'Производство', text: data.production_countries.map(function (c) { return c.name; }).join(', ') });

    if (data.original_language && data.original_language !== 'ru')
      facts.push({ icon: '🗣️', label: 'Язык оригинала', text: LANG_NAMES[data.original_language] || data.original_language });

    var origTitle = data.original_title || data.original_name;
    var rusTitle = data.title || data.name;
    if (origTitle && rusTitle && origTitle !== rusTitle)
      facts.push({ icon: '📝', label: 'Оригинальное название', text: origTitle });

    return facts;
  }

  // ====================================================
  // РЕНДЕРИНГ СЕКЦИЙ
  // ====================================================
  function renderHero(data) {
    if (!data.backdrop_path) return null;
    var url = Lampa.TMDB.image('t/p/w1280' + data.backdrop_path);
    var title = data.title || data.name || '';
    return $(
      '<div class="ci-hero" style="background-image:url(\'' + url + '\')">' +
        '<div class="ci-hero__overlay"></div>' +
        (title ? '<div class="ci-hero__title">' + escapeHtml(title) + '</div>' : '') +
      '</div>'
    );
  }

  function renderOverview(data) {
    var $wrap = $('<div></div>');
    if (data.tagline)
      $wrap.append('<div class="ci-tagline">«' + escapeHtml(data.tagline) + '»</div>');

    var meta = [];
    if (data.vote_average && data.vote_average > 0)
      meta.push({ cls: 'ci-meta__item ci-meta__item--rating', text: '★ ' + Number(data.vote_average).toFixed(1) });
    var year = (data.release_date || data.first_air_date || '').substring(0, 4);
    if (year) meta.push({ text: year });
    if (data.runtime) meta.push({ text: data.runtime + ' мин' });
    if (data.episode_run_time && data.episode_run_time[0])
      meta.push({ text: '~' + data.episode_run_time[0] + ' мин/серия' });
    if (data.number_of_seasons) meta.push({ text: 'сезонов: ' + data.number_of_seasons });
    if (data.number_of_episodes) meta.push({ text: 'серий: ' + data.number_of_episodes });
    if (data.genres && data.genres.length)
      meta.push({ text: data.genres.map(function (g) { return g.name; }).join(', ') });

    if (meta.length) {
      var $meta = $('<div class="ci-meta"></div>');
      meta.forEach(function (m) {
        $meta.append('<div class="' + (m.cls || 'ci-meta__item') + '">' + escapeHtml(m.text) + '</div>');
      });
      $wrap.append($meta);
    }

    var overview = data.overview && data.overview.trim() ? data.overview : 'Описание отсутствует.';
    $wrap.append(
      '<div class="ci-overview ci-overview-block selector ci-focus-anchor" tabindex="0">' +
        escapeHtml(overview) +
      '</div>'
    );
    return $wrap;
  }

  function renderFactsSection(facts) {
    if (!facts.length) return null;
    var $sec = $('<div class="ci-section"></div>');
    $sec.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>Интересные факты</div>');
    var $list = $('<div class="ci-facts"></div>');
    facts.forEach(function (f) {
      var label = f.label ? '<span class="ci-fact__label">' + escapeHtml(f.label) + ':</span> ' : '';
      $list.append(
        '<div class="ci-fact selector" tabindex="0">' +
          '<div class="ci-fact__icon">' + f.icon + '</div>' +
          '<div class="ci-fact__text">' + label + escapeHtml(f.text) + '</div>' +
        '</div>'
      );
    });
    $sec.append($list);
    return $sec;
  }

  function renderCastSection(credits, onSelect) {
    if (!credits || !credits.cast || !credits.cast.length) return null;
    var cast = credits.cast.slice(0, 20);
    var $sec = $('<div class="ci-section"></div>');
    $sec.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>В ролях</div>');
    var $row = $('<div class="ci-cast"></div>');

    cast.forEach(function (person) {
      var photo = person.profile_path ? Lampa.TMDB.image('t/p/w185' + person.profile_path) : '';
      var $card = $(
        '<div class="ci-actor selector">' +
          '<div class="ci-actor__photo"' +
            (photo ? ' style="background-image:url(\'' + photo + '\')"' : '') + '>' +
            (!photo ? '<div class="ci-actor__no-photo">👤</div>' : '') +
          '</div>' +
          '<div class="ci-actor__name">' + escapeHtml(person.name) + '</div>' +
          (person.character
            ? '<div class="ci-actor__role">' + escapeHtml(person.character) + '</div>'
            : '<div class="ci-actor__role">&nbsp;</div>') +
        '</div>'
      );
      $card.on('hover:enter', function () {
        if (onSelect) onSelect();
        try {
          Lampa.Activity.push({
            url: 'person/' + person.id, title: person.name,
            component: 'actor', id: person.id, source: 'tmdb'
          });
        } catch (e) { Lampa.Noty.show(person.name); }
      });
      $row.append($card);
    });
    $sec.append($row);
    return $sec;
  }

  function renderRecsSection(items, defaultMethod, sectionTitle, onSelect) {
    if (!items.length) return null;
    var $sec = $('<div class="ci-section"></div>');
    $sec.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>' + escapeHtml(sectionTitle) + '</div>');
    var $grid = $('<div class="ci-grid"></div>');
    items.forEach(function (item) {
      var posterUrl = item.poster_path ? Lampa.TMDB.image('t/p/w200' + item.poster_path) : '';
      var year = (item.release_date || item.first_air_date || '').substring(0, 4);
      var title = item.title || item.name || 'Без названия';
      var rating = item.vote_average && item.vote_average > 0
        ? Number(item.vote_average).toFixed(1) : '';
      var method = item.media_type === 'tv' || item.media_type === 'movie'
        ? item.media_type
        : (item.first_air_date || item.name ? 'tv' : defaultMethod);

      var $card = $(
        '<div class="ci-card selector">' +
          '<div class="ci-card__poster"' +
            (posterUrl ? ' style="background-image:url(\'' + posterUrl + '\')"' : '') + '>' +
            (!posterUrl ? '<div class="ci-card__no-poster">🎬</div>' : '') +
            (rating ? '<div class="ci-card__rating">★ ' + rating + '</div>' : '') +
          '</div>' +
          '<div class="ci-card__info">' +
            '<div class="ci-card__title">' + escapeHtml(title) + '</div>' +
            (year ? '<div class="ci-card__year">' + year + '</div>' : '') +
          '</div>' +
        '</div>'
      );
      $card.on('hover:enter', function () {
        if (onSelect) onSelect();
        Lampa.Activity.push({
          url: '', component: 'full', id: item.id, method: method,
          card: item, source: 'tmdb'
        });
      });
      $grid.append($card);
    });
    $sec.append($grid);
    return $sec;
  }

  // ====================================================
  // КОМПОНЕНТ ACTIVITY (правильный TV-скролл)
  // ====================================================
  function CardInsightActivity(object) {
    var network = new Lampa.Reguest();
    var scroll = new Lampa.Scroll({ mask: true, over: true, step: 250 });
    var $body = $('<div class="ci-page"><div class="ci-loading">Загрузка…</div></div>');
    var loaded = false;
    var contentReady = false;
    var lastFocused = null;
    var self = this;

    this.create = function () {
      scroll.body().append($body);
      this.load();
      return this.render();
    };

    this.render = function () {
      return scroll.render();
    };

    // Загрузка данных вынесена в отдельный метод — стандартный паттерн Lampa
    this.load = function () {
      fetchFullData(object.method, object.id, function (err, data) {
        if (loaded) return;
        loaded = true;
        if (!$body) return;

        if (err || !data) {
          $body.html('<div class="ci-error">Не удалось загрузить данные TMDB</div>');
          return;
        }

        self.build(data);
      });
    };

    this.build = function (data) {
      $body.empty();

      var $hero = renderHero(data);
      if ($hero) $body.append($hero);

      $body.append(renderOverview(data));

      var $facts = renderFactsSection(buildFacts(data));
      if ($facts) $body.append($facts);

      var $cast = renderCastSection(data.credits);
      if ($cast) $body.append($cast);

      var recs = (data.recommendations && data.recommendations.results) || [];
      var recTitle = 'Похожие';
      if (!recs.length) {
        recs = (data.similar && data.similar.results) || [];
        recTitle = 'Похожие (по жанру)';
      }
      var $recs = renderRecsSection(recs.slice(0, 30), object.method, recTitle);
      if ($recs) $body.append($recs);

      $body.find('.selector').on('hover:focus', function (e) {
        lastFocused = e.currentTarget;
        scroll.update($(e.currentTarget), true);
      });

      contentReady = true;

      // ── ГЛАВНОЕ: имитируем то, что делает пользователь руками ──
      // (head → content переключение). Это гарантированно
      // пересобирает коллекцию Navigator после рендера контента.
      try {
        if (object.activity && object.activity.toggle) {
          // Стандартный путь Lampa — есть в большинстве форков
          object.activity.toggle();
        }
        // Дополнительный фолбэк для bylampa: имитируем уход и возврат
        setTimeout(function () {
          if (Lampa.Controller && Lampa.Controller.enabled().name === 'content') {
            Lampa.Controller.collectionSet(scroll.render());
            var first = scroll.render().find('.selector').first()[0];
            if (first) Lampa.Controller.collectionFocus(first, scroll.render());
          } else {
            Lampa.Controller.toggle('content');
          }
        }, 50);
      } catch (er) {
        Lampa.Controller.toggle('content');
      }
    };

    this.start = function () {
      Lampa.Controller.add('content', {
        toggle: function () {
          Lampa.Controller.collectionSet(scroll.render());
          if (contentReady) {
            var target = (lastFocused && scroll.render()[0] && $.contains(scroll.render()[0], lastFocused))
              ? lastFocused
              : scroll.render().find('.selector').first()[0];
            if (target) Lampa.Controller.collectionFocus(target, scroll.render());
          }
        },
        update: function () {
          Lampa.Controller.collectionSet(scroll.render());
        },
        left: function () {
          if (typeof Navigator !== 'undefined' && Navigator.canmove && Navigator.canmove('left')) {
            Navigator.move('left');
          } else {
            Lampa.Controller.toggle('menu');
          }
        },
        right: function () {
          if (typeof Navigator !== 'undefined' && Navigator.move) Navigator.move('right');
        },
        up: function () {
          if (typeof Navigator !== 'undefined' && Navigator.canmove && Navigator.canmove('up')) {
            Navigator.move('up');
          } else {
            Lampa.Controller.toggle('head');
          }
        },
        down: function () {
          if (typeof Navigator !== 'undefined' && Navigator.move) Navigator.move('down');
        },
        back: this.back
      });
      Lampa.Controller.toggle('content');
    };

    this.pause = function () {};
    this.stop = function () {};
    this.back = function () { Lampa.Activity.backward(); };
    this.destroy = function () {
      network.clear();
      scroll.destroy();
      if ($body) $body.remove();
      $body = null;
    };
  }

  // ====================================================
  // РЕГИСТРАЦИЯ КОМПОНЕНТА
  // ====================================================
  function registerComponent() {
    Lampa.Component.add(COMPONENT_NAME, CardInsightActivity);
  }

  // ====================================================
  // КНОПКА НА КАРТОЧКЕ
  // ====================================================
  function appendButton($buttons, movie, method) {
    if (!$buttons || !$buttons.length) return;
    if ($buttons.find('.view--ci').length) return;

    var svgIcon =
      '<svg width="36" height="36" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">' +
        '<circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2"/>' +
        '<path d="M12 8v4M12 16h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>' +
      '</svg>';

    var $btn = $(
      '<div class="full-start__button selector view--ci">' +
        svgIcon + '<span>Подробнее</span>' +
      '</div>'
    );
    $btn.on('hover:enter', function () {
      Lampa.Activity.push({
        url:       '',
        title:     'Подробнее: ' + (movie.title || movie.name || ''),
        component: COMPONENT_NAME,
        id:        movie.id,
        method:    method,
        source:    'tmdb'
      });
    });
    $buttons.append($btn);
  }

  function injectButton() {
    Lampa.Listener.follow('full', function (e) {
      if (e.type !== 'complite') return;
      var data = e.data;
      if (!data) return;
      var movie = data.movie || data;
      if (!movie || !movie.id) return;

      var method = (e.object && e.object.method) || data.method || movie.method ||
                   (movie.first_air_date || movie.name ? 'tv' : 'movie');

      var $render = e.object.activity.render();
      var attempts = 0;
      function tryAppend() {
        var $buttons = $render.find('.full-start-new__buttons, .full-start__buttons').first();
        if ($buttons.length) appendButton($buttons, movie, method);
        else if (attempts++ < 5) setTimeout(tryAppend, 200);
      }
      tryAppend();
    });
  }

  // ====================================================
  // ИНИЦИАЛИЗАЦИЯ
  // ====================================================
  function initialize() {
    if (!window.Lampa || !Lampa.Listener || !Lampa.Component) return setTimeout(initialize, 500);
    injectStyles();
    registerComponent();
    injectButton();
    console.log('[Card Insight v3] готов');
  }

  if (window.appready) initialize();
  else Lampa.Listener.follow('app', function (e) {
    if (e.type === 'ready') initialize();
  });
})();
