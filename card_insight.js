/*!
 * Card Insight for Lampa — v2
 * Кнопка "Подробнее" на карточке: бэкдроп, описание, факты,
 * актёры и рекомендации TMDB.
 * Никаких API-ключей — используется встроенный Lampa.TMDB.
 */
(function () {
  'use strict';

  // ====================================================
  // СТИЛИ
  // ====================================================
  function injectStyles() {
    if (document.getElementById('card-insight-styles')) return;
    var css = [
      '.ci-body{padding:0}',
      '.ci-loading{padding:3em 1em;text-align:center;opacity:0.7;font-size:1.2em}',
      '.ci-error{padding:1.5em 1em;text-align:center;color:#ff7e7e;opacity:0.85}',

      /* Hero — слим-бэкдроп */
      '.ci-hero{height:11em;margin:0 0 1em;border-radius:0.4em;overflow:hidden;position:relative;background:#111 center/cover no-repeat}',
      '.ci-hero__overlay{position:absolute;inset:0;background:linear-gradient(180deg,rgba(0,0,0,0.05) 40%,rgba(0,0,0,0.65) 100%)}',

      /* Тэглайн и описание */
      '.ci-tagline{font-style:italic;margin:0 0 0.8em;opacity:0.85;font-size:1.15em;padding:0 0.2em}',
      '.ci-meta{display:flex;flex-wrap:wrap;gap:0.5em 1.1em;margin-bottom:1em;opacity:0.78;font-size:1.05em;padding:0 0.2em}',
      '.ci-meta__item{white-space:nowrap}',
      '.ci-meta__item--rating{color:#ffd54f;font-weight:700}',
      '.ci-overview{line-height:1.55;margin:0 0 1.8em;font-size:1.12em;padding:0 0.2em}',

      /* Заголовки секций */
      '.ci-section{margin-bottom:1.8em}',
      '.ci-section-title{font-size:1.4em;font-weight:700;margin:0 0 0.7em;padding:0 0.2em;display:flex;align-items:center;gap:0.5em}',
      '.ci-section-title__bar{display:inline-block;width:0.25em;height:1em;background:#ffa726;border-radius:0.1em}',

      /* Факты */
      '.ci-facts{display:grid;grid-template-columns:repeat(auto-fill,minmax(20em,1fr));gap:0.55em}',
      '.ci-fact{display:flex;gap:0.7em;padding:0.7em 0.9em;background:rgba(255,255,255,0.05);border-radius:0.4em;align-items:center;border:0.06em solid rgba(255,255,255,0.06)}',
      '.ci-fact__icon{font-size:1.4em;flex-shrink:0;line-height:1}',
      '.ci-fact__text{font-size:1em;line-height:1.4}',
      '.ci-fact__label{opacity:0.6;margin-right:0.3em}',

      /* Актёры */
      '.ci-cast{display:grid;grid-template-columns:repeat(auto-fill,minmax(8em,1fr));gap:0.8em}',
      '.ci-actor{cursor:pointer;border-radius:0.4em;overflow:hidden;background:rgba(255,255,255,0.05);transition:transform 0.15s,background 0.15s}',
      '.ci-actor.focus,.ci-actor:hover{transform:scale(1.05);background:rgba(255,255,255,0.18);box-shadow:0 0 0 0.18em #ffa726}',
      '.ci-actor__photo{position:relative;padding-top:130%;background:#222 center/cover no-repeat}',
      '.ci-actor__no-photo{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:2.2em;opacity:0.3}',
      '.ci-actor__name{padding:0.4em 0.55em 0;font-weight:600;font-size:0.95em;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.5em}',
      '.ci-actor__role{padding:0.15em 0.55em 0.55em;opacity:0.6;font-size:0.82em;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.4em}',

      /* Карточки рекомендаций */
      '.ci-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(13em,1fr));gap:1em}',
      '.ci-card{cursor:pointer;border-radius:0.4em;overflow:hidden;background:rgba(255,255,255,0.05);transition:transform 0.15s,background 0.15s;position:relative}',
      '.ci-card.focus,.ci-card:hover{transform:scale(1.04);background:rgba(255,255,255,0.18);box-shadow:0 0 0 0.2em #ffa726}',
      '.ci-card__poster{position:relative;padding-top:150%;background:#222 center/cover no-repeat}',
      '.ci-card__no-poster{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:2.5em;opacity:0.25}',
      '.ci-card__rating{position:absolute;top:0.4em;right:0.4em;background:rgba(0,0,0,0.78);padding:0.18em 0.5em;border-radius:0.25em;font-size:0.9em}',
      '.ci-card__info{padding:0.6em 0.7em 0.8em}',
      '.ci-card__title{font-weight:600;font-size:1em;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;line-height:1.3;min-height:2.6em}',
      '.ci-card__year{opacity:0.55;font-size:0.85em;margin-top:0.3em}',

      /* Кнопка в карточке */
      '.full-start__button.view--ci svg{margin-right:0.4em;vertical-align:middle}'
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
      '&append_to_response=recommendations,similar,credits,keywords,external_ids'
    );
    network.silent(url,
      function (data) { callback(null, data); },
      function (err) { callback(err || new Error('Ошибка сети')); }
    );
  }

  // ====================================================
  // HERO (бэкдроп-полоса)
  // ====================================================
  function renderHero(data) {
    if (!data.backdrop_path) return null;
    var url = Lampa.TMDB.image('t/p/w1280' + data.backdrop_path);
    return $(
      '<div class="ci-hero" style="background-image:url(\'' + url + '\')">' +
        '<div class="ci-hero__overlay"></div>' +
      '</div>'
    );
  }

  // ====================================================
  // ОПИСАНИЕ + МЕТА
  // ====================================================
  function renderOverview(data) {
    var $wrap = $('<div></div>');

    if (data.tagline) {
      $wrap.append('<div class="ci-tagline">«' + escapeHtml(data.tagline) + '»</div>');
    }

    var meta = [];
    if (data.vote_average && data.vote_average > 0) {
      meta.push({ cls: 'ci-meta__item ci-meta__item--rating',
        text: '★ ' + Number(data.vote_average).toFixed(1) });
    }
    var year = (data.release_date || data.first_air_date || '').substring(0, 4);
    if (year) meta.push({ text: year });
    if (data.runtime) meta.push({ text: data.runtime + ' мин' });
    if (data.episode_run_time && data.episode_run_time[0])
      meta.push({ text: '~' + data.episode_run_time[0] + ' мин/серия' });
    if (data.number_of_seasons)
      meta.push({ text: 'сезонов: ' + data.number_of_seasons });
    if (data.number_of_episodes)
      meta.push({ text: 'серий: ' + data.number_of_episodes });
    if (data.genres && data.genres.length)
      meta.push({ text: data.genres.map(function (g) { return g.name; }).join(', ') });

    if (meta.length) {
      var $meta = $('<div class="ci-meta"></div>');
      meta.forEach(function (m) {
        $meta.append('<div class="' + (m.cls || 'ci-meta__item') + '">' + escapeHtml(m.text) + '</div>');
      });
      $wrap.append($meta);
    }

    var overview = data.overview && data.overview.trim()
      ? data.overview : 'Описание отсутствует.';
    $wrap.append('<div class="ci-overview">' + escapeHtml(overview) + '</div>');

    return $wrap;
  }

  // ====================================================
  // ФАКТЫ — собираются из TMDB-данных
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
    if (directors.length)
      facts.push({ icon: '🎬', label: 'Режиссёр', text: directors.join(', ') });

    var writers = findCrew(['Screenplay', 'Writer', 'Story']);
    if (writers.length)
      facts.push({ icon: '✍️', label: 'Сценарий', text: writers.slice(0, 3).join(', ') });

    var composers = findCrew(['Original Music Composer']);
    if (composers.length)
      facts.push({ icon: '🎵', label: 'Композитор', text: composers.join(', ') });

    var dop = findCrew(['Director of Photography']);
    if (dop.length)
      facts.push({ icon: '🎥', label: 'Оператор', text: dop[0] });

    // Бюджет / сборы
    if (data.budget && data.budget > 1000 && data.revenue && data.revenue > 1000) {
      var ratio = data.revenue / data.budget;
      var verdict;
      if (ratio >= 5) verdict = 'мега-хит';
      else if (ratio >= 2) verdict = 'окупился ×' + ratio.toFixed(1);
      else if (ratio >= 1) verdict = 'окупился';
      else verdict = 'не окупился';
      facts.push({
        icon: '💰',
        text: formatMoney(data.budget) + ' → ' + formatMoney(data.revenue) + ' (' + verdict + ')'
      });
    } else if (data.budget && data.budget > 1000) {
      facts.push({ icon: '💰', label: 'Бюджет', text: formatMoney(data.budget) });
    } else if (data.revenue && data.revenue > 1000) {
      facts.push({ icon: '💰', label: 'Сборы', text: formatMoney(data.revenue) });
    }

    // Рейтинг
    if (data.vote_average >= 7.5 && data.vote_count >= 100) {
      facts.push({
        icon: '⭐',
        text: 'Высокий рейтинг ' + data.vote_average.toFixed(1) + '/10 (' + formatNum(data.vote_count) + ' оценок)'
      });
    } else if (data.vote_count >= 5000) {
      facts.push({ icon: '🔥', text: 'Очень популярный — ' + formatNum(data.vote_count) + ' оценок на TMDB' });
    }

    // Часть франшизы
    if (data.belongs_to_collection && data.belongs_to_collection.name) {
      facts.push({ icon: '🔗', label: 'Серия', text: data.belongs_to_collection.name });
    }

    // Сериальные факты
    if (data.created_by && data.created_by.length) {
      facts.push({
        icon: '🎬', label: 'Создатель',
        text: data.created_by.map(function (p) { return p.name; }).join(', ')
      });
    }
    if (data.networks && data.networks.length) {
      facts.push({
        icon: '📺', label: 'Канал',
        text: data.networks.map(function (n) { return n.name; }).join(', ')
      });
    }
    if (data.status === 'Ended')
      facts.push({ icon: '🏁', text: 'Сериал завершён' });
    else if (data.status === 'Returning Series')
      facts.push({ icon: '🔄', text: 'Сериал продолжается' });
    else if (data.status === 'Canceled' || data.status === 'Cancelled')
      facts.push({ icon: '❌', text: 'Сериал закрыт' });

    // Темы из keywords
    var kws = (data.keywords && (data.keywords.keywords || data.keywords.results)) || [];
    if (kws.length) {
      facts.push({
        icon: '🏷️', label: 'Темы',
        text: kws.slice(0, 5).map(function (k) { return k.name; }).join(', ')
      });
    }

    // Производство
    if (data.production_countries && data.production_countries.length > 1) {
      facts.push({
        icon: '🌍', label: 'Производство',
        text: data.production_countries.map(function (c) { return c.name; }).join(', ')
      });
    }

    // Язык оригинала
    if (data.original_language && data.original_language !== 'ru') {
      facts.push({
        icon: '🗣️', label: 'Язык оригинала',
        text: LANG_NAMES[data.original_language] || data.original_language
      });
    }

    // Оригинальное название отличается
    var origTitle = data.original_title || data.original_name;
    var rusTitle = data.title || data.name;
    if (origTitle && rusTitle && origTitle !== rusTitle) {
      facts.push({ icon: '📝', label: 'Оригинальное название', text: origTitle });
    }

    return facts;
  }

  function renderFactsSection(facts) {
    if (!facts.length) return null;
    var $sec = $('<div class="ci-section"></div>');
    $sec.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>Интересные факты</div>');
    var $list = $('<div class="ci-facts"></div>');
    facts.forEach(function (f) {
      var label = f.label ? '<span class="ci-fact__label">' + escapeHtml(f.label) + ':</span> ' : '';
      $list.append(
        '<div class="ci-fact">' +
          '<div class="ci-fact__icon">' + f.icon + '</div>' +
          '<div class="ci-fact__text">' + label + escapeHtml(f.text) + '</div>' +
        '</div>'
      );
    });
    $sec.append($list);
    return $sec;
  }

  // ====================================================
  // АКТЁРЫ
  // ====================================================
  function renderCastSection(credits) {
    if (!credits || !credits.cast || !credits.cast.length) return null;
    var cast = credits.cast.slice(0, 20);

    var $sec = $('<div class="ci-section"></div>');
    $sec.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>В ролях</div>');
    var $row = $('<div class="ci-cast"></div>');

    cast.forEach(function (person) {
      var photo = person.profile_path
        ? Lampa.TMDB.image('t/p/w185' + person.profile_path) : '';
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
        Lampa.Modal.close();
        Lampa.Controller.toggle('content');
        try {
          Lampa.Activity.push({
            url: 'person/' + person.id,
            title: person.name,
            component: 'actor',
            id: person.id,
            source: 'tmdb'
          });
        } catch (e) {
          Lampa.Noty.show(person.name);
        }
      });
      $row.append($card);
    });

    $sec.append($row);
    return $sec;
  }

  // ====================================================
  // РЕКОМЕНДАЦИИ
  // ====================================================
  function renderRecCard(item, defaultMethod) {
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
      Lampa.Modal.close();
      Lampa.Controller.toggle('content');
      Lampa.Activity.push({
        url: '', component: 'full', id: item.id, method: method,
        card: item, source: 'tmdb'
      });
    });
    return $card;
  }

  function renderRecsSection(items, defaultMethod, sectionTitle) {
    var $sec = $('<div class="ci-section"></div>');
    $sec.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>' + escapeHtml(sectionTitle) + '</div>');
    var $grid = $('<div class="ci-grid"></div>');
    items.forEach(function (item) { $grid.append(renderRecCard(item, defaultMethod)); });
    $sec.append($grid);
    return $sec;
  }

  // ====================================================
  // МОДАЛКА
  // ====================================================
  function openModal(movie, method) {
    var $body = $('<div class="ci-body"><div class="ci-loading">Загрузка…</div></div>');

    Lampa.Modal.open({
      title: movie.title || movie.name || 'Подробнее',
      html: $body,
      size: 'large',
      mask: true,
      onBack: function () {
        Lampa.Modal.close();
        Lampa.Controller.toggle('full_start');
      }
    });

    fetchFullData(method, movie.id, function (err, data) {
      if (err || !data) {
        $body.html('<div class="ci-error">Не удалось загрузить данные TMDB</div>');
        return;
      }

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
      if (recs.length) {
        $body.append(renderRecsSection(recs.slice(0, 30), method, recTitle));
      }

      // Перенастроить контроллер на новые .selector
      try { Lampa.Controller.toggle('modal'); } catch (e) {}
    });
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
    $btn.on('hover:enter', function () { openModal(movie, method); });
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
    if (!window.Lampa || !Lampa.Listener) return setTimeout(initialize, 500);
    injectStyles();
    injectButton();
    console.log('[Card Insight v2] готов');
  }

  if (window.appready) initialize();
  else Lampa.Listener.follow('app', function (e) {
    if (e.type === 'ready') initialize();
  });
})();
