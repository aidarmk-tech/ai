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

      /* Hero */
      '.ci-hero{height:11em;margin:0 0 1em;border-radius:0.4em;overflow:hidden;position:relative;background:#111 center/cover no-repeat}',
      '.ci-hero__overlay{position:absolute;inset:0;background:linear-gradient(180deg,rgba(0,0,0,0.05) 40%,rgba(0,0,0,0.65) 100%)}',

      /* Текст */
      '.ci-tagline{font-style:italic;margin:0 0 0.8em;opacity:0.85;font-size:1.15em;padding:0 0.2em}',
      '.ci-meta{display:flex;flex-wrap:wrap;gap:0.5em 1.1em;margin-bottom:1em;opacity:0.78;font-size:1.05em;padding:0 0.2em}',
      '.ci-meta__item--rating{color:#ffd54f;font-weight:700}',
      '.ci-overview{line-height:1.55;margin:0 0 1.8em;font-size:1.12em;padding:0 0.2em}',

      /* Секции */
      '.ci-section{margin-bottom:1.8em}',
      '.ci-section-title{font-size:1.4em;font-weight:700;margin:0 0 0.7em;padding:0 0.2em;display:flex;align-items:center;gap:0.5em}',
      '.ci-section-title__bar{display:inline-block;width:0.25em;height:1em;background:#ffa726;border-radius:0.1em}',

      /* Факты */
      '.ci-facts{display:grid;grid-template-columns:repeat(auto-fill,minmax(20em,1fr));gap:0.55em}',
      '.ci-fact{display:flex;gap:0.7em;padding:0.7em 0.9em;background:rgba(255,255,255,0.05);border-radius:0.4em;align-items:center;border:0.06em solid rgba(255,255,255,0.06)}',
      '.ci-fact__icon{font-size:1.4em;line-height:1}',
      '.ci-fact__label{opacity:0.6;margin-right:0.3em}',

      /* Актёры */
      '.ci-cast{display:grid;grid-template-columns:repeat(auto-fill,minmax(8em,1fr));gap:0.8em}',
      '.ci-actor{cursor:pointer;border-radius:0.4em;overflow:hidden;background:rgba(255,255,255,0.05);transition:transform 0.15s}',
      '.ci-actor.focus{transform:scale(1.05);background:rgba(255,255,255,0.18);box-shadow:0 0 0 0.18em #ffa726}',
      '.ci-actor__photo{position:relative;padding-top:130%;background:#222 center/cover no-repeat}',
      '.ci-actor__name{padding:0.4em 0.55em 0;font-weight:600;font-size:0.95em;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.5em}',
      '.ci-actor__role{padding:0.15em 0.55em 0.55em;opacity:0.6;font-size:0.82em;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.4em}',

      /* Рекомендации */
      '.ci-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(13em,1fr));gap:1em}',
      '.ci-card{cursor:pointer;border-radius:0.4em;overflow:hidden;background:rgba(255,255,255,0.05);transition:transform 0.15s;position:relative}',
      '.ci-card.focus{transform:scale(1.04);background:rgba(255,255,255,0.18);box-shadow:0 0 0 0.2em #ffa726}',
      '.ci-card__poster{position:relative;padding-top:150%;background:#222 center/cover no-repeat}',
      '.ci-card__rating{position:absolute;top:0.4em;right:0.4em;background:rgba(0,0,0,0.78);padding:0.18em 0.5em;border-radius:0.25em;font-size:0.9em}',

      '.full-start__button.view--ci svg{margin-right:0.4em;vertical-align:middle;fill:currentColor}'
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

  function formatMoney(n) {
    if (!n || n < 100) return '';
    if (n >= 1e9) return '$' + (n / 1e9).toFixed(2) + ' млрд';
    if (n >= 1e6) return '$' + (n / 1e6).toFixed(1) + ' млн';
    return '$' + Math.round(n / 1e3) + ' тыс';
  }

  // ====================================================
  // СЕТЬ (Safe Reguest)
  // ====================================================
  function fetchFullData(method, id, callback) {
    try {
      var network = new Lampa.Reguest();
      var url = Lampa.TMDB.api(method + '/' + id + 
                '?api_key=' + Lampa.TMDB.key() + 
                '&language=ru&append_to_response=recommendations,similar,credits,keywords');

      network.silent(url, function (data) {
        callback(null, data);
      }, function (err) {
        callback(err || new Error('Network error'));
      });
    } catch (e) {
      callback(e);
    }
  }

  // ====================================================
  // ФУНКЦИИ РЕНДЕРА (Твои оригинальные)
  // ====================================================
  function buildFacts(data) {
    var facts = [];
    var crew = (data.credits && data.credits.crew) || [];
    var findCrew = function(jobs) {
      return crew.filter(function (p) { return jobs.indexOf(p.job) > -1; })
                 .map(function (p) { return p.name; })
                 .filter(function (n, i, a) { return a.indexOf(n) === i; });
    };

    var directors = findCrew(['Director']);
    if (directors.length) facts.push({ icon: '🎬', label: 'Режиссёр', text: directors.join(', ') });
    
    if (data.budget > 1000) facts.push({ icon: '💰', label: 'Бюджет', text: formatMoney(data.budget) });
    if (data.revenue > 1000) facts.push({ icon: '📈', label: 'Сборы', text: formatMoney(data.revenue) });

    var kws = (data.keywords && (data.keywords.keywords || data.keywords.results)) || [];
    if (kws.length) facts.push({ icon: '🏷️', label: 'Темы', text: kws.slice(0, 5).map(function(k){ return k.name; }).join(', ') });

    return facts;
  }

  // ====================================================
  // МОДАЛКА (Фикс для ATV)
  // ====================================================
  function openModal(movie, method) {
    var scroll = new Lampa.Scroll({ mask: true, over: true });
    var $body = $('<div class="ci-body"><div class="ci-loading">Загрузка данных...</div></div>');
    
    scroll.append($body);

    Lampa.Modal.open({
      title: movie.title || movie.name || 'Подробнее',
      html: scroll.render(),
      size: 'large',
      mask: true,
      onBack: function () {
        Lampa.Modal.close();
        Lampa.Controller.toggle('full_start');
      }
    });

    fetchFullData(method, movie.id, function (err, data) {
      if (err || !data) {
        $body.html('<div class="ci-error">Ошибка загрузки TMDB</div>');
        return;
      }

      $body.empty();

      // Backdrop
      if (data.backdrop_path) {
        var bImg = Lampa.TMDB.image('t/p/w1280' + data.backdrop_path);
        $body.append('<div class="ci-hero" style="background-image:url(\'' + bImg + '\')"><div class="ci-hero__overlay"></div></div>');
      }

      // Meta & Overview
      var year = (data.release_date || data.first_air_date || '').substring(0, 4);
      var htmlInfo = '';
      if (data.tagline) htmlInfo += '<div class="ci-tagline">«' + escapeHtml(data.tagline) + '»</div>';
      htmlInfo += '<div class="ci-meta">' + 
                  (data.vote_average ? '<span class="ci-meta__item ci-meta__item--rating">★ ' + data.vote_average.toFixed(1) + '</span>' : '') +
                  '<span>' + year + '</span><span>' + (data.runtime || 0) + ' мин.</span></div>';
      htmlInfo += '<div class="ci-overview">' + escapeHtml(data.overview || 'Описание отсутствует.') + '</div>';
      $body.append(htmlInfo);

      // Facts
      var facts = buildFacts(data);
      if (facts.length) {
        var $fSec = $('<div class="ci-section"><div class="ci-section-title"><span class="ci-section-title__bar"></span>Интересные факты</div><div class="ci-facts"></div></div>');
        facts.forEach(function(f) {
          $fSec.find('.ci-facts').append('<div class="ci-fact"><div class="ci-fact__icon">'+f.icon+'</div><div class="ci-fact__text"><span class="ci-fact__label">'+f.label+':</span> '+escapeHtml(f.text)+'</div></div>');
        });
        $body.append($fSec);
      }

      // Cast
      if (data.credits && data.credits.cast.length) {
        var $cSec = $('<div class="ci-section"><div class="ci-section-title"><span class="ci-section-title__bar"></span>В ролях</div><div class="ci-cast"></div></div>');
        data.credits.cast.slice(0, 10).forEach(function(p) {
          var img = p.profile_path ? Lampa.TMDB.image('t/p/w185' + p.profile_path) : '';
          var $a = $('<div class="ci-actor selector"><div class="ci-actor__photo" '+(img ? 'style="background-image:url('+img+')"' : '')+'></div><div class="ci-actor__name">'+escapeHtml(p.name)+'</div></div>');
          $a.on('hover:enter', function() {
            Lampa.Modal.close();
            Lampa.Activity.push({ url: 'person/' + p.id, title: p.name, component: 'actor', id: p.id, source: 'tmdb' });
          });
          $cSec.find('.ci-cast').append($a);
        });
        $body.append($cSec);
      }

      // КОНТРОЛЛЕР ДЛЯ ПУЛЬТА
      Lampa.Controller.add('ci_modal', {
        toggle: function () {
          Lampa.Controller.collectionSet(scroll.render());
          Lampa.Controller.context(scroll);
        },
        up: function () { scroll.step(-200); },
        down: function () { scroll.step(200); },
        back: function () {
          Lampa.Modal.close();
          Lampa.Controller.toggle('full_start');
        }
      });

      Lampa.Controller.toggle('ci_modal');
      scroll.update();
    });
  }

  // ====================================================
  // КНОПКА
  // ====================================================
  function injectButton() {
    Lampa.Listener.follow('full', function (e) {
      if (e.type === 'complite') {
        var movie = e.data.movie || e.data;
        var method = (movie.first_air_date || movie.name) ? 'tv' : 'movie';
        var render = e.object.activity.render();
        
        var timer = setInterval(function() {
          var container = render.find('.full-start-new__buttons, .full-start__buttons');
          if (container.length) {
            clearInterval(timer);
            if (container.find('.view--ci').length) return;

            var $btn = $('<div class="full-start__button selector view--ci"><svg width="24" height="24" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2"/><path d="M12 8v4M12 16h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg><span>Инфо</span></div>');
            $btn.on('hover:enter', function () { openModal(movie, method); });
            container.append($btn);
          }
        }, 200);
      }
    });
  }

  function start() {
    if (window.ci_ready) return;
    window.ci_ready = true;
    injectStyles();
    injectButton();
  }

  if (window.appready) start();
  else Lampa.Listener.follow('app', function (e) { if (e.type === 'ready') start(); });

})();
