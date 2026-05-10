/*!
 * Card Insight for Lampa
 * Кнопка "Похожие" на карточке фильма/сериала.
 * Открывает модалку с описанием и рекомендациями TMDB.
 *
 * Никаких API-ключей не требуется — используется встроенный TMDB Lampa.
 */
(function () {
  'use strict';

  // ====================================================
  // СТИЛИ
  // ====================================================
  function injectStyles() {
    if (document.getElementById('card-insight-styles')) return;
    var style = document.createElement('style');
    style.id = 'card-insight-styles';
    style.textContent = [
      '.ci-body{padding:1em 0}',
      '.ci-loading{padding:3em 1em;text-align:center;opacity:0.7;font-size:1.2em}',
      '.ci-error{padding:2em 1em;text-align:center;color:#ff7e7e;opacity:0.8}',
      '.ci-meta{display:flex;flex-wrap:wrap;gap:0.8em 1.2em;margin-bottom:1em;opacity:0.75;font-size:1.05em}',
      '.ci-meta__item{white-space:nowrap}',
      '.ci-tagline{font-style:italic;margin-bottom:1em;opacity:0.8;font-size:1.15em}',
      '.ci-overview{line-height:1.55;margin-bottom:2em;font-size:1.15em}',
      '.ci-section-title{font-size:1.5em;font-weight:bold;margin:1.5em 0 0.8em}',
      '.ci-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(13em,1fr));gap:1em}',
      '.ci-card{cursor:pointer;border-radius:0.4em;overflow:hidden;background:rgba(255,255,255,0.05);transition:transform 0.15s,background 0.15s;position:relative}',
      '.ci-card.focus,.ci-card:hover{transform:scale(1.04);background:rgba(255,255,255,0.18);box-shadow:0 0 0 0.2em #ffa726}',
      '.ci-card__poster{position:relative;padding-top:150%;background:#222 center/cover no-repeat}',
      '.ci-card__no-poster{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:2.5em;opacity:0.25}',
      '.ci-card__rating{position:absolute;top:0.4em;right:0.4em;background:rgba(0,0,0,0.78);padding:0.18em 0.5em;border-radius:0.25em;font-size:0.9em}',
      '.ci-card__info{padding:0.6em 0.7em 0.8em}',
      '.ci-card__title{font-weight:600;font-size:1em;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;line-height:1.3;min-height:2.6em}',
      '.ci-card__year{opacity:0.55;font-size:0.85em;margin-top:0.3em}',
      '.full-start__button.view--ci svg{margin-right:0.4em;vertical-align:middle}'
    ].join('\n');
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

  // ====================================================
  // TMDB API
  // ====================================================
  function fetchFullData(method, id, callback) {
    var network = new Lampa.Reguest();
    var url = Lampa.TMDB.api(
      method + '/' + id +
      '?api_key=' + Lampa.TMDB.key() +
      '&language=ru' +
      '&append_to_response=recommendations,similar'
    );
    network.silent(url,
      function (data) { callback(null, data); },
      function (err) { callback(err || new Error('Ошибка сети')); }
    );
  }

  // ====================================================
  // РЕНДЕРИНГ — ОПИСАНИЕ
  // ====================================================
  function renderMeta(data) {
    var $wrap = $('<div></div>');

    if (data.tagline) {
      $wrap.append('<div class="ci-tagline">«' + escapeHtml(data.tagline) + '»</div>');
    }

    var meta = [];
    if (data.vote_average && data.vote_average > 0)
      meta.push('★ ' + Number(data.vote_average).toFixed(1));
    var year = (data.release_date || data.first_air_date || '').substring(0, 4);
    if (year) meta.push(year);
    if (data.runtime) meta.push(data.runtime + ' мин');
    if (data.episode_run_time && data.episode_run_time[0])
      meta.push('~' + data.episode_run_time[0] + ' мин/серия');
    if (data.number_of_seasons)
      meta.push('сезонов: ' + data.number_of_seasons);
    if (data.number_of_episodes)
      meta.push('серий: ' + data.number_of_episodes);
    if (data.genres && data.genres.length)
      meta.push(data.genres.map(function (g) { return g.name; }).join(', '));
    if (data.production_countries && data.production_countries.length)
      meta.push(data.production_countries.map(function (c) { return c.iso_3166_1; }).join(', '));

    if (meta.length) {
      var $meta = $('<div class="ci-meta"></div>');
      meta.forEach(function (m) {
        $meta.append('<div class="ci-meta__item">' + escapeHtml(m) + '</div>');
      });
      $wrap.append($meta);
    }

    var overview = data.overview && data.overview.trim()
      ? data.overview
      : 'Описание отсутствует.';
    $wrap.append('<div class="ci-overview">' + escapeHtml(overview) + '</div>');

    return $wrap;
  }

  // ====================================================
  // РЕНДЕРИНГ — КАРТОЧКА РЕКОМЕНДАЦИИ
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

    var html =
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
      '</div>';

    var $card = $(html);
    $card.on('hover:enter', function () {
      Lampa.Modal.close();
      Lampa.Controller.toggle('content');
      Lampa.Activity.push({
        url: '',
        component: 'full',
        id: item.id,
        method: method,
        card: item,
        source: 'tmdb'
      });
    });

    return $card;
  }

  function renderRecsSection(items, defaultMethod, sectionTitle) {
    var $sec = $('<div></div>');
    $sec.append('<div class="ci-section-title">' + escapeHtml(sectionTitle) + '</div>');
    var $grid = $('<div class="ci-grid"></div>');
    items.forEach(function (item) {
      $grid.append(renderRecCard(item, defaultMethod));
    });
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
      $body.append(renderMeta(data));

      var recs = (data.recommendations && data.recommendations.results) || [];
      var fallbackTitle = 'Похожие';
      if (!recs.length) {
        recs = (data.similar && data.similar.results) || [];
        fallbackTitle = 'Похожие (по жанру)';
      }

      if (recs.length) {
        $body.append(renderRecsSection(recs.slice(0, 30), method, fallbackTitle));
      } else {
        $body.append('<div class="ci-section-title">Похожие</div>');
        $body.append('<div class="ci-error">TMDB не нашёл похожих</div>');
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
        '<path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>' +
      '</svg>';

    var $btn = $(
      '<div class="full-start__button selector view--ci">' +
        svgIcon + '<span>Похожие</span>' +
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

      var method = (e.object && e.object.method) ||
                   data.method ||
                   movie.method ||
                   (movie.first_air_date || movie.name ? 'tv' : 'movie');

      var $render = e.object.activity.render();

      // Пробуем сразу, если контейнера ещё нет — ждём пару тактов
      var attempts = 0;
      function tryAppend() {
        var $buttons = $render.find('.full-start-new__buttons, .full-start__buttons').first();
        if ($buttons.length) {
          appendButton($buttons, movie, method);
        } else if (attempts < 5) {
          attempts++;
          setTimeout(tryAppend, 200);
        }
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
    console.log('[Card Insight] готов');
  }

  if (window.appready) initialize();
  else Lampa.Listener.follow('app', function (e) {
    if (e.type === 'ready') initialize();
  });
})();
