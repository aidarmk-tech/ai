/*!
 * Card Insight for Lampa — v4 (no Lampa.Scroll)
 * Кнопка "Подробнее" на карточке: бэкдроп, описание, факты,
 * актёры и рекомендации TMDB.
 *
 * v4: Использует CSS-скролл + native scrollIntoView вместо Lampa.Scroll.
 *     Это совместимо с любым форком Lampa (bylampa, lampa-lite и т.д.).
 *     Никаких API-ключей — встроенный Lampa.TMDB.
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
      '.ci-root{width:100%;background:#1a0033;color:#fff;overflow-y:auto;overflow-x:hidden;-webkit-overflow-scrolling:touch}',
      '.ci-page{padding:1.5em 2em 6em}',
      '.ci-loading{padding:4em 1em;text-align:center;font-size:2em;color:#ffff00;font-weight:bold}',
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
      '.ci-overview{line-height:1.6;margin:0 0 2em;font-size:1.2em;padding:0.3em 0.4em;max-width:65em;border-radius:0.4em;outline:none}',
      '.ci-overview.focus{background:rgba(255,255,255,0.06)}',

      /* Заголовки секций */
      '.ci-section{margin-bottom:2em}',
      '.ci-section-title{font-size:1.6em;font-weight:700;margin:0 0 0.8em;padding:0 0.2em;display:flex;align-items:center;gap:0.55em}',
      '.ci-section-title__bar{display:inline-block;width:0.28em;height:1em;background:#ffa726;border-radius:0.1em}',

      /* Факты */
      '.ci-facts{display:grid;grid-template-columns:repeat(auto-fill,minmax(22em,1fr));gap:0.6em}',
      '.ci-fact{display:flex;gap:0.8em;padding:0.85em 1em;background:rgba(255,255,255,0.05);border-radius:0.4em;align-items:center;border:0.06em solid rgba(255,255,255,0.06);outline:none}',
      '.ci-fact.focus{background:rgba(255,255,255,0.18);box-shadow:0 0 0 0.2em #ffa726}',
      '.ci-fact__icon{font-size:1.5em;flex-shrink:0;line-height:1}',
      '.ci-fact__text{font-size:1.1em;line-height:1.4}',
      '.ci-fact__label{opacity:0.6;margin-right:0.3em}',

      /* Актёры */
      '.ci-cast{display:grid;grid-template-columns:repeat(auto-fill,minmax(9em,1fr));gap:0.9em}',
      '.ci-actor{cursor:pointer;border-radius:0.4em;overflow:hidden;background:rgba(255,255,255,0.05);outline:none;transition:transform 0.15s,background 0.15s}',
      '.ci-actor.focus{transform:scale(1.05);background:rgba(255,255,255,0.2);box-shadow:0 0 0 0.2em #ffa726}',
      '.ci-actor__photo{position:relative;padding-top:130%;background:#222 center/cover no-repeat}',
      '.ci-actor__no-photo{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:2.4em;opacity:0.3}',
      '.ci-actor__name{padding:0.5em 0.6em 0;font-weight:600;font-size:1em;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.6em}',
      '.ci-actor__role{padding:0.18em 0.6em 0.6em;opacity:0.6;font-size:0.88em;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.5em}',

      /* Карточки рекомендаций */
      '.ci-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(14em,1fr));gap:1.1em}',
      '.ci-card{cursor:pointer;border-radius:0.4em;overflow:hidden;background:rgba(255,255,255,0.05);outline:none;transition:transform 0.15s,background 0.15s;position:relative}',
      '.ci-card.focus{transform:scale(1.05);background:rgba(255,255,255,0.2);box-shadow:0 0 0 0.22em #ffa726}',
      '.ci-card__poster{position:relative;padding-top:150%;background:#222 center/cover no-repeat}',
      '.ci-card__no-poster{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:2.6em;opacity:0.25}',
      '.ci-card__rating{position:absolute;top:0.4em;right:0.4em;background:rgba(0,0,0,0.78);padding:0.2em 0.55em;border-radius:0.25em;font-size:0.95em}',
      '.ci-card__info{padding:0.65em 0.75em 0.85em}',
      '.ci-card__title{font-weight:600;font-size:1.05em;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;line-height:1.3;min-height:2.7em}',
      '.ci-card__year{opacity:0.55;font-size:0.9em;margin-top:0.3em}',

      /* Коллекция — текущий фильм */
      '.ci-card--current{opacity:0.72}',
      '.ci-card__current-badge{position:absolute;top:0.4em;left:0.4em;background:rgba(255,166,38,0.9);padding:0.15em 0.5em;border-radius:0.2em;font-size:0.85em;font-weight:700;color:#1a0033;z-index:1}',

      /* За кулисами */
      '.ci-trivia{display:flex;flex-direction:column;gap:0.7em}',
      '.ci-trivia__item{padding:0.85em 1em 0.85em 1.2em;background:rgba(255,255,255,0.05);border-radius:0.4em;line-height:1.55;font-size:1.1em;border-left:0.25em solid #ffa726;outline:none}',
      '.ci-trivia__item.focus{background:rgba(255,255,255,0.15);box-shadow:0 0 0 0.2em #ffa726}',

      /* Кнопка в карточке */
      '.full-start__button.view--ci{color:#ffa726}',
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
  // РЕНДЕРИНГ
  // ====================================================
  function renderHero(data) {
    if (!data.backdrop_path) return null;
    var url = Lampa.TMDB.image('t/p/w1280' + data.backdrop_path);
    var title = data.title || data.name || '';
    return $(
      '<div class="ci-hero" style="background-image:url(\'' + url + '\')">'+
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
    $wrap.append('<div class="ci-overview selector" tabindex="0">' + escapeHtml(overview) + '</div>');
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

  function renderCastSection(credits) {
    if (!credits || !credits.cast || !credits.cast.length) return null;
    var cast = credits.cast.slice(0, 20);
    var $sec = $('<div class="ci-section"></div>');
    $sec.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>В ролях</div>');
    var $row = $('<div class="ci-cast"></div>');

    cast.forEach(function (person) {
      var photo = person.profile_path ? Lampa.TMDB.image('t/p/w185' + person.profile_path) : '';
      var $card = $(
        '<div class="ci-actor selector" tabindex="0">' +
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

  function renderRecsSection(items, defaultMethod, sectionTitle) {
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
        '<div class="ci-card selector" tabindex="0">' +
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

  function renderCollectionSection(colData, currentId) {
    if (!colData || !colData.parts || !colData.parts.length) return null;
    var parts = colData.parts.slice().sort(function (a, b) {
      var da = a.release_date || '';
      var db = b.release_date || '';
      return da < db ? -1 : da > db ? 1 : 0;
    });
    var $sec = $('<div class="ci-section"></div>');
    var colName = colData.name ? escapeHtml(colData.name) : 'Коллекция';
    $sec.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>' + colName + '</div>');
    var $grid = $('<div class="ci-grid"></div>');
    parts.forEach(function (item) {
      var isCurrent = item.id === currentId;
      var posterUrl = item.poster_path ? Lampa.TMDB.image('t/p/w200' + item.poster_path) : '';
      var year = (item.release_date || '').substring(0, 4);
      var title = item.title || item.name || 'Без названия';
      var rating = item.vote_average && item.vote_average > 0 ? Number(item.vote_average).toFixed(1) : '';
      var $card = $(
        '<div class="ci-card' + (isCurrent ? ' ci-card--current' : '') + ' selector" tabindex="0">' +
          '<div class="ci-card__poster"' +
            (posterUrl ? ' style="background-image:url(\'' + posterUrl + '\')"' : '') + '>' +
            (!posterUrl ? '<div class="ci-card__no-poster">🎬</div>' : '') +
            (rating ? '<div class="ci-card__rating">★ ' + rating + '</div>' : '') +
            (isCurrent ? '<div class="ci-card__current-badge">▶ Сейчас</div>' : '') +
          '</div>' +
          '<div class="ci-card__info">' +
            '<div class="ci-card__title">' + escapeHtml(title) + '</div>' +
            (year ? '<div class="ci-card__year">' + year + '</div>' : '') +
          '</div>' +
        '</div>'
      );
      if (!isCurrent) {
        $card.on('hover:enter', function () {
          Lampa.Activity.push({
            url: '', component: 'full', id: item.id, method: 'movie',
            card: item, source: 'tmdb'
          });
        });
      }
      $grid.append($card);
    });
    $sec.append($grid);
    return $sec;
  }

  function parseAIResponse(text) {
    var facts = [];
    text.split('\n').forEach(function (line) {
      line = line.replace(/^[\s•\-\*]+/, '').replace(/^\d+[\.\)\]]\s*/, '').trim();
      if (line.length >= 40 && line.length <= 700 && facts.indexOf(line) === -1) facts.push(line);
    });
    return facts.slice(0, 8);
  }

  function fetchAITrivia(movieInfo, callback) {
    var apiKey = '';
    try { apiKey = Lampa.Storage.get('ci_openrouter_key', ''); } catch (e) {}
    if (!apiKey) { callback(null, []); return; }

    var model = AI_DEFAULT_MODEL;
    try { model = Lampa.Storage.get('ci_openrouter_model', model) || model; } catch (e) {}

    // Build a precise identification block so the AI writes about THIS specific film
    var lines = [];
    lines.push('Фильм: «' + movieInfo.title + '»' + (movieInfo.year ? ' (' + movieInfo.year + ')' : ''));
    if (movieInfo.originalTitle && movieInfo.originalTitle !== movieInfo.title)
      lines.push('Оригинальное название: «' + movieInfo.originalTitle + '»');
    if (movieInfo.director)
      lines.push('Режиссёр: ' + movieInfo.director);
    if (movieInfo.cast && movieInfo.cast.length)
      lines.push('В главных ролях: ' + movieInfo.cast.join(', '));
    if (movieInfo.genres && movieInfo.genres.length)
      lines.push('Жанр: ' + movieInfo.genres.slice(0, 3).join(', '));
    if (movieInfo.overview)
      lines.push('Краткое описание: ' + movieInfo.overview.substring(0, 200));

    var context = lines.join('\n');

    var prompt =
      context + '\n\n' +
      'Напиши от 3 до 6 интересных фактов о производстве именно этого фильма (столько, сколько знаешь): ' +
      'съёмочный процесс, история кастинга, курьёзы на площадке, ' +
      'решения режиссёра, реакция актёров, необычные детали создания. ' +
      'Каждый факт — отдельная строка, начинается с «•». Без заголовков и вступлений. Только на русском.';

    var xhr = new XMLHttpRequest();
    xhr.open('POST', 'https://openrouter.ai/api/v1/chat/completions', true);
    xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.setRequestHeader('Authorization', 'Bearer ' + apiKey);
    xhr.setRequestHeader('HTTP-Referer', 'https://lampa.mx');
    xhr.timeout = 25000;
    xhr.onload = function () {
      if (xhr.status !== 200) { callback(null, []); return; }
      try {
        var resp = JSON.parse(xhr.responseText);
        var content = resp.choices && resp.choices[0] && resp.choices[0].message && resp.choices[0].message.content;
        callback(null, content ? parseAIResponse(content) : []);
      } catch (e) { callback(null, []); }
    };
    xhr.onerror = xhr.ontimeout = function () { callback(null, []); };
    xhr.send(JSON.stringify({
      model: model,
      messages: [{ role: 'user', content: prompt }],
      max_tokens: 900,
      temperature: 0.6
    }));
  }

  function renderWikiTriviaSection(facts) {
    if (!facts || !facts.length) return null;
    var $sec = $('<div class="ci-section"></div>');
    $sec.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>За кулисами</div>');
    var $list = $('<div class="ci-trivia"></div>');
    facts.forEach(function (f) {
      $list.append('<div class="ci-trivia__item selector" tabindex="0">' + escapeHtml(f) + '</div>');
    });
    $sec.append($list);
    return $sec;
  }

  // ====================================================
  // СТАТУС ПРОДОЛЖЕНИЯ (из данных TMDB)
  // ====================================================
  function renderContinuationSection(data, method) {
    var items = [];

    if (method === 'tv') {
      var statusMap = {
        'Returning Series': { icon: '🔄', text: 'Сериал продолжается' },
        'In Production':    { icon: '🎬', text: 'Сезон снимается' },
        'Post Production':  { icon: '✂️', text: 'Постпродакшн нового сезона' },
        'Planned':          { icon: '📋', text: 'Сезон запланирован' },
        'Ended':            { icon: '🏁', text: 'Сериал завершён' },
        'Canceled':         { icon: '❌', text: 'Сериал отменён' },
        'Cancelled':        { icon: '❌', text: 'Сериал отменён' }
      };
      if (statusMap[data.status]) items.push(statusMap[data.status]);

      if (data.next_episode_to_air) {
        var ep = data.next_episode_to_air;
        var epDate = '';
        if (ep.air_date) {
          try {
            var d = new Date(ep.air_date);
            epDate = d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });
          } catch (e) { epDate = ep.air_date; }
        }
        var epText = 'Сезон ' + ep.season_number + ', эпизод ' + ep.episode_number;
        if (ep.name) epText += ' — «' + ep.name + '»';
        if (epDate) epText += ' (' + epDate + ')';
        items.push({ icon: '📅', label: 'Следующий эпизод', text: epText });
      }

      if (data.number_of_seasons) {
        var s = data.number_of_seasons;
        var sLabel = s === 1 ? 'сезон' : s < 5 ? 'сезона' : 'сезонов';
        var e = data.number_of_episodes || 0;
        var eLabel = e === 1 ? 'серия' : e < 5 ? 'серии' : 'серий';
        items.push({ icon: '🎞️', label: 'Всего', text: s + ' ' + sLabel + (e ? ', ' + e + ' ' + eLabel : '') });
      }
    } else {
      if (data.status === 'Post Production') items.push({ icon: '✂️', text: 'Фильм в постпродакшне' });
      else if (data.status === 'In Production') items.push({ icon: '🎬', text: 'Фильм снимается' });
    }

    if (!items.length) return null;
    var $sec = $('<div class="ci-section"></div>');
    $sec.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>Статус выхода</div>');
    var $list = $('<div class="ci-facts"></div>');
    items.forEach(function (f) {
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

  // ====================================================
  // НОВОСТИ И СЛУХИ (Google News RSS)
  // ====================================================
  function fetchNewsRSS(title, callback) {
    var query = encodeURIComponent('"' + title + '" продолжение сезон');
    var url = 'https://news.google.com/rss/search?q=' + query + '&hl=ru&gl=RU&ceid=RU:ru';
    var done = false;
    var tid = setTimeout(function () { if (!done) { done = true; callback(null, []); } }, 8000);

    fetch(url)
      .then(function (r) { return r.text(); })
      .then(function (xml) {
        if (done) return;
        done = true;
        clearTimeout(tid);
        var items = [];
        try {
          var doc = (new DOMParser()).parseFromString(xml, 'text/xml');
          var nodes = doc.querySelectorAll('item');
          for (var i = 0; i < nodes.length && items.length < 5; i++) {
            var tEl = nodes[i].querySelector('title');
            var dEl = nodes[i].querySelector('pubDate');
            if (!tEl) continue;
            var t = tEl.textContent.replace(/\s*-\s*[^-–—]+$/, '').trim();
            if (t.length > 15) {
              items.push({
                text: t,
                date: dEl ? dEl.textContent.substring(5, 17) : ''
              });
            }
          }
        } catch (e) {}
        callback(null, items);
      })
      .catch(function () { if (!done) { done = true; clearTimeout(tid); callback(null, []); } });
  }

  function renderNewsSection(items) {
    if (!items || !items.length) return null;
    var $sec = $('<div class="ci-section"></div>');
    $sec.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>Новости и слухи</div>');
    var $list = $('<div class="ci-trivia"></div>');
    items.forEach(function (item) {
      var date = item.date ? '<span style="opacity:0.45;font-size:0.82em;float:right;padding-left:0.5em">' + item.date + '</span>' : '';
      $list.append('<div class="ci-trivia__item selector" tabindex="0">' + date + escapeHtml(item.text) + '</div>');
    });
    $sec.append($list);
    return $sec;
  }

  // ====================================================
  // КОМПОНЕНТ ACTIVITY
  // ====================================================
  function CardInsightActivity(object) {
    var network = new Lampa.Reguest();
    var $root = $('<div class="ci-root"><div class="ci-page"><div class="ci-loading">Загрузка...</div></div></div>');
    var $page = $root.find('.ci-page');
    var loaded = false;
    var contentReady = false;
    var lastFocused = null;
    var self = this;

    this.create = function () {
      this.load();
      return this.render();
    };

    this.render = function () {
      return $root;
    };

    this.load = function () {
      fetchFullData(object.method, object.id, function (err, data) {
        if (loaded) return;
        loaded = true;
        if (!$page) return;
        if (err || !data) {
          $page.html('<div class="ci-error">Не удалось загрузить TMDB: ' + (err && err.message ? err.message : 'нет данных') + '</div>');
          return;
        }
        self.build(data);
      });
    };

    this.build = function (data) {
      $page.empty();

      if (!window.Lampa || !Lampa.SettingsApi) $page.append(renderAISettingsSection());

      var $hero = renderHero(data);
      if ($hero) $page.append($hero);

      $page.append(renderOverview(data));

      var $facts = renderFactsSection(buildFacts(data));
      if ($facts) $page.append($facts);

      // Статус выхода (из TMDB, синхронно)
      var $cont = renderContinuationSection(data, object.method);
      if ($cont) $page.append($cont);

      // Placeholder for AI trivia (filled async)
      var $triviaPlaceholder = $('<div></div>');
      $page.append($triviaPlaceholder);

      var $cast = renderCastSection(data.credits);
      if ($cast) $page.append($cast);

      // Placeholder for news (filled async)
      var $newsPlaceholder = $('<div></div>');
      $page.append($newsPlaceholder);

      // Placeholder for collection/franchise (filled async)
      var $colPlaceholder = $('<div></div>');
      $page.append($colPlaceholder);

      var recs = (data.recommendations && data.recommendations.results) || [];
      var recTitle = 'Похожие';
      if (!recs.length) {
        recs = (data.similar && data.similar.results) || [];
        recTitle = 'Похожие (по жанру)';
      }
      var $recs = renderRecsSection(recs.slice(0, 30), object.method, recTitle);
      if ($recs) $page.append($recs);

      contentReady = true;

      // Async: collection parts
      if (data.belongs_to_collection && data.belongs_to_collection.id) {
        var colNet = new Lampa.Reguest();
        var colUrl = Lampa.TMDB.api(
          'collection/' + data.belongs_to_collection.id +
          '?api_key=' + Lampa.TMDB.key() + '&language=ru'
        );
        colNet.silent(colUrl, function (colData) {
          if (!$page) return;
          var $col = renderCollectionSection(colData, data.id);
          if ($col) $colPlaceholder.replaceWith($col);
          else $colPlaceholder.remove();
          try { Lampa.Controller.collectionSet($root); } catch (e) {}
        }, function () { $colPlaceholder.remove(); });
      } else {
        $colPlaceholder.remove();
      }

      // Async: новости Google News RSS
      var newsTitle = data.title || data.name || '';
      fetchNewsRSS(newsTitle, function (err, newsItems) {
        if (!$page) return;
        var $news = renderNewsSection(newsItems);
        if ($news) $newsPlaceholder.replaceWith($news);
        else $newsPlaceholder.remove();
        try { Lampa.Controller.collectionSet($root); } catch (e) {}
      });

      // Async: AI trivia via OpenRouter (only if API key configured)
      var crew = (data.credits && data.credits.crew) || [];
      var directorObj = null;
      for (var ci = 0; ci < crew.length; ci++) {
        if (crew[ci].job === 'Director') { directorObj = crew[ci]; break; }
      }
      var topCast = (data.credits && data.credits.cast || []).slice(0, 4).map(function (a) { return a.name; });
      var movieInfo = {
        title:         data.title || data.name || '',
        originalTitle: data.original_title || data.original_name || '',
        year:          (data.release_date || data.first_air_date || '').substring(0, 4),
        genres:        (data.genres || []).map(function (g) { return g.name; }),
        director:      directorObj ? directorObj.name : '',
        cast:          topCast,
        overview:      data.overview || ''
      };
      fetchAITrivia(movieInfo, function (err, triviaFacts) {
        if (!$page) return;
        var $trivia = renderWikiTriviaSection(triviaFacts);
        if ($trivia) $triviaPlaceholder.replaceWith($trivia);
        else $triviaPlaceholder.remove();
        try { Lampa.Controller.collectionSet($root); } catch (e) {}
      });

      setTimeout(function () {
        if (!$root) return;
        try { Lampa.Controller.toggle('content'); } catch (e) {}
      }, 150);
    };

    function scrollToEl(el) {
      if (!el || !$root || !$root[0]) return;
      var raf = window.requestAnimationFrame || function (cb) { return setTimeout(cb, 16); };
      raf(function () {
        if (!$root || !$root[0]) return;
        var rootEl = $root[0];
        var viewH  = rootEl.clientHeight;
        if (viewH > 0) {
          var elTop = 0, node = el;
          while (node && node !== rootEl) { elTop += node.offsetTop; node = node.offsetParent; }
          var elBottom = elTop + el.offsetHeight;
          var margin = 80;
          if (elTop - margin < rootEl.scrollTop) {
            rootEl.scrollTop = Math.max(0, elTop - margin);
          } else if (elBottom + margin > rootEl.scrollTop + viewH) {
            rootEl.scrollTop = elBottom + margin - viewH;
          }
        } else {
          try { el.scrollIntoView({ behavior: 'auto', block: 'nearest' }); }
          catch (e) { try { el.scrollIntoView(false); } catch (er) {} }
        }
      });
    }

    function scrollToFocus() {
      if (!$root) return;
      var focused = $root.find('.selector.focus')[0] || lastFocused;
      if (focused) scrollToEl(focused);
    }

    $root.on('hover:focus', '.selector', function () {
      lastFocused = this;
      scrollToEl(this);
    });

    this.start = function () {
      var back = this.back;
      function applyHeight() {
        if (!$root || !$root[0]) return;
        var h = window.innerHeight || document.documentElement.clientHeight
              || screen.availHeight || screen.height || 0;
        if (h > 100) $root[0].style.height = h + 'px';
      }
      applyHeight();
      setTimeout(applyHeight, 300);

      Lampa.Controller.add('content', {
        toggle: function () {
          Lampa.Controller.collectionSet($root);
          if (contentReady) {
            var target = (lastFocused && $root[0] && $.contains($root[0], lastFocused))
              ? lastFocused
              : $root.find('.selector').first()[0];
            if (target) {
              Lampa.Controller.collectionFocus(target, $root);
              scrollToEl(target);
            }
          }
        },
        update: function () {
          Lampa.Controller.collectionSet($root);
        },
        left: function () {
          if (typeof Navigator !== 'undefined' && Navigator.canmove && Navigator.canmove('left')) {
            Navigator.move('left');
            setTimeout(scrollToFocus, 50);
          } else {
            Lampa.Controller.toggle('menu');
          }
        },
        right: function () {
          if (typeof Navigator !== 'undefined' && Navigator.move) {
            Navigator.move('right');
            setTimeout(scrollToFocus, 50);
          }
        },
        up: function () {
          if (typeof Navigator !== 'undefined' && Navigator.canmove && Navigator.canmove('up')) {
            Navigator.move('up');
            setTimeout(scrollToFocus, 50);
          } else {
            Lampa.Controller.toggle('head');
          }
        },
        down: function () {
          if (typeof Navigator !== 'undefined' && Navigator.move) {
            Navigator.move('down');
            setTimeout(scrollToFocus, 50);
          }
        },
        back: back
      });
      Lampa.Controller.toggle('content');
    };

    this.pause   = function () {};
    this.stop    = function () {};
    this.back    = function () { Lampa.Activity.backward(); };
    this.destroy = function () {
      network.clear();
      if ($root) $root.remove();
      $root = null;
      $page = null;
    };
  }

  var AI_MODELS = {
    // Бесплатные
    'meta-llama/llama-3.3-70b-instruct:free': '[FREE] Llama 3.3 70B',
    'deepseek/deepseek-chat-v3-0324:free':    '[FREE] DeepSeek V3',
    // Дёшево и качественно
    'deepseek/deepseek-chat':                 'DeepSeek V3 ★ лучший за цену',
    'qwen/qwen-2.5-72b-instruct':             'Qwen 2.5 72B',
    'google/gemini-2.5-flash':                'Gemini 2.5 Flash',
    'openai/gpt-4o-mini':                     'GPT-4o Mini',
    'anthropic/claude-3.5-haiku':             'Claude 3.5 Haiku',
    'mistralai/mistral-large-2411':           'Mistral Large',
    // Рассуждение
    'deepseek/deepseek-r1':                   'DeepSeek R1 (думает)',
    // Премиум
    'z-ai/glm-5.2':                          'zai37o',
    'google/gemini-2.5-pro':                  'Gemini 2.5 Pro',
    'anthropic/claude-sonnet-4':              'Claude Sonnet 4'
  };

  var AI_DEFAULT_MODEL = 'meta-llama/llama-3.3-70b-instruct:free';

  function getAIKey()   { try { return Lampa.Storage.get('ci_openrouter_key', '') || ''; } catch(e) { return ''; } }
  function getAIModel() { try { return Lampa.Storage.get('ci_openrouter_model', AI_DEFAULT_MODEL) || AI_DEFAULT_MODEL; } catch(e) { return AI_DEFAULT_MODEL; } }
  function setAIKey(v)  { try { Lampa.Storage.set('ci_openrouter_key', v); } catch(e) {} }
  function setAIModel(v){ try { Lampa.Storage.set('ci_openrouter_model', v); } catch(e) {} }

  // ====================================================
  // НАСТРОЙКИ ЧЕРЕЗ Lampa.SettingsApi
  // ====================================================
  function registerSettings() {
    if (!Lampa.SettingsApi) return;

    Lampa.SettingsApi.addComponent({
      component: 'ai_info',
      name: 'AI Инфо',
      icon: '<svg width="64" height="64" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2"/><path d="M12 8v4M12 16h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>'
    });

    Lampa.SettingsApi.addParam({
      component: 'ai_info',
      param: { name: 'ci_openrouter_key', type: 'input', values: '', default: '' },
      field: {
        name: 'OpenRouter API ключ',
        description: 'sk-or-v1-… — получить на openrouter.ai/keys'
      }
    });

    Lampa.SettingsApi.addParam({
      component: 'ai_info',
      param: { name: 'ci_openrouter_model', type: 'select', values: AI_MODELS, default: AI_DEFAULT_MODEL },
      field: {
        name: 'Модель AI',
        description: 'Модель OpenRouter для генерации фактов о фильме'
      }
    });
  }

  // Fallback-панель внутри плагина — только если SettingsApi недоступен
  function renderAISettingsSection() {
    var key   = getAIKey();
    var model = getAIModel();
    var modelLabel = AI_MODELS[model] || model;

    var $sec = $('<div class="ci-section"></div>');
    $sec.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>Настройки AI Инфо</div>');
    var $list = $('<div class="ci-facts"></div>');

    // Строка — ключ
    var keyText = key ? '●●●●●●' + key.slice(-4) : 'Не задан — нажмите для ввода';
    var $keyRow = $('<div class="ci-fact selector" tabindex="0"><div class="ci-fact__icon">🔑</div><div class="ci-fact__text"><span class="ci-fact__label">API ключ:</span> ' + escapeHtml(keyText) + '</div></div>');
    $keyRow.on('hover:enter', function () {
      var newKey = window.prompt('OpenRouter API ключ (openrouter.ai):', key);
      if (newKey !== null) {
        setAIKey(newKey.trim());
        var disp = newKey.trim() ? '●●●●●●' + newKey.trim().slice(-4) : 'Не задан';
        $keyRow.find('.ci-fact__text').html('<span class="ci-fact__label">API ключ:</span> ' + escapeHtml(disp));
      }
    });
    $list.append($keyRow);

    // Строка — модель (перебор по клику)
    var modelKeys = Object.keys(AI_MODELS);
    var $modelRow = $('<div class="ci-fact selector" tabindex="0"><div class="ci-fact__icon">🤖</div><div class="ci-fact__text"><span class="ci-fact__label">Модель:</span> ' + escapeHtml(modelLabel) + '</div></div>');
    $modelRow.on('hover:enter', function () {
      var cur = getAIModel();
      var idx = modelKeys.indexOf(cur);
      var next = modelKeys[(idx + 1) % modelKeys.length];
      setAIModel(next);
      $modelRow.find('.ci-fact__text').html('<span class="ci-fact__label">Модель:</span> ' + escapeHtml(AI_MODELS[next]));
    });
    $list.append($modelRow);

    $sec.append($list);
    return $sec;
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
        '<circle cx="12" cy="12" r="9" stroke="#ffa726" stroke-width="2"/>' +
        '<path d="M12 8v4M12 16h.01" stroke="#ffa726" stroke-width="2" stroke-linecap="round"/>' +
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
    registerSettings();
    injectButton();
  }

  if (window.appready) initialize();
  else Lampa.Listener.follow('app', function (e) {
    if (e.type === 'ready') initialize();
  });
})();
