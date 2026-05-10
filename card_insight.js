(function () {
  'use strict';

  function injectStyles() {
    if (document.getElementById('card-insight-styles')) return;
    var css = [
      '.ci-body{padding:0}',
      '.ci-loading{padding:3em 1em;text-align:center;opacity:0.7;font-size:1.2em}',
      '.ci-error{padding:1.5em 1em;text-align:center;color:#ff7e7e;opacity:0.85}',
      '.ci-hero{height:11em;margin:0 0 1em;border-radius:0.4em;overflow:hidden;position:relative;background:#111 center/cover no-repeat}',
      '.ci-hero__overlay{position:absolute;inset:0;background:linear-gradient(180deg,rgba(0,0,0,0.05) 40%,rgba(0,0,0,0.65) 100%)}',
      '.ci-tagline{font-style:italic;margin:0 0 0.8em;opacity:0.85;font-size:1.15em;padding:0 0.2em}',
      '.ci-meta{display:flex;flex-wrap:wrap;gap:0.5em 1.1em;margin-bottom:1em;opacity:0.78;font-size:1.05em;padding:0 0.2em}',
      '.ci-meta__item{white-space:nowrap}',
      '.ci-meta__item--rating{color:#ffd54f;font-weight:700}',
      '.ci-overview{line-height:1.55;margin:0 0 1.8em;font-size:1.12em;padding:0 0.2em}',
      '.ci-section{margin-bottom:1.8em}',
      '.ci-section-title{font-size:1.4em;font-weight:700;margin:0 0 0.7em;padding:0 0.2em;display:flex;align-items:center;gap:0.5em}',
      '.ci-section-title__bar{display:inline-block;width:0.25em;height:1em;background:#ffa726;border-radius:0.1em}',
      '.ci-facts{display:grid;grid-template-columns:repeat(auto-fill,minmax(20em,1fr));gap:0.55em}',
      '.ci-fact{display:flex;gap:0.7em;padding:0.7em 0.9em;background:rgba(255,255,255,0.05);border-radius:0.4em;align-items:center;border:0.06em solid rgba(255,255,255,0.06)}',
      '.ci-fact__icon{font-size:1.4em;flex-shrink:0;line-height:1}',
      '.ci-fact__text{font-size:1em;line-height:1.4}',
      '.ci-fact__label{opacity:0.6;margin-right:0.3em}',
      '.ci-cast{display:grid;grid-template-columns:repeat(auto-fill,minmax(8em,1fr));gap:0.8em}',
      '.ci-actor{cursor:pointer;border-radius:0.4em;overflow:hidden;background:rgba(255,255,255,0.05);transition:transform 0.15s,background 0.15s}',
      '.ci-actor.focus,.ci-actor:hover{transform:scale(1.05);background:rgba(255,255,255,0.18);box-shadow:0 0 0 0.18em #ffa726}',
      '.ci-actor__photo{position:relative;padding-top:130%;background:#222 center/cover no-repeat}',
      '.ci-actor__no-photo{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:2.2em;opacity:0.3}',
      '.ci-actor__name{padding:0.4em 0.55em 0;font-weight:600;font-size:0.95em;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.5em}',
      '.ci-actor__role{padding:0.15em 0.55em 0.55em;opacity:0.6;font-size:0.82em;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;min-height:2.4em}',
      '.ci-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(13em,1fr));gap:1em}',
      '.ci-card{cursor:pointer;border-radius:0.4em;overflow:hidden;background:rgba(255,255,255,0.05);transition:transform 0.15s,background 0.15s;position:relative}',
      '.ci-card.focus,.ci-card:hover{transform:scale(1.04);background:rgba(255,255,255,0.18);box-shadow:0 0 0 0.2em #ffa726}',
      '.ci-card__poster{position:relative;padding-top:150%;background:#222 center/cover no-repeat}',
      '.ci-card__no-poster{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;font-size:2.5em;opacity:0.25}',
      '.ci-card__rating{position:absolute;top:0.4em;right:0.4em;background:rgba(0,0,0,0.78);padding:0.18em 0.5em;border-radius:0.25em;font-size:0.9em}',
      '.ci-card__info{padding:0.6em 0.7em 0.8em}',
      '.ci-card__title{font-weight:600;font-size:1em;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;line-height:1.3;min-height:2.6em}',
      '.ci-card__year{opacity:0.55;font-size:0.85em;margin-top:0.3em}',
      '.full-start__button.view--ci svg{margin-right:0.4em;vertical-align:middle;fill:currentColor}'
    ].join('\n');

    var style = document.createElement('style');
    style.id = 'card-insight-styles';
    style.textContent = css;
    document.head.appendChild(style);
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

  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c];
    });
  }

  function formatMoney(n) {
    if (!n || n < 100) return '';
    if (n >= 1e9) return '$' + (n / 1e9).toFixed(2) + ' млрд';
    if (n >= 1e6) return '$' + (n / 1e6).toFixed(1) + ' млн';
    return '$' + n.toLocaleString();
  }

  // Используем Lampa.Network для надежности
  function fetchFullData(method, id, callback) {
    var network = new Lampa.Network();
    var url = Lampa.TMDB.api(method + '/' + id + '?api_key=' + Lampa.TMDB.key() + '&language=ru&append_to_response=recommendations,similar,credits,keywords,external_ids');
    
    network.silent(url, function (data) {
      callback(null, data);
    }, function (err) {
      callback(err);
    });
  }

  function openModal(movie, method) {
    var $body = $('<div class="ci-body"><div class="ci-loading">Загрузка данных TMDB...</div></div>');

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
        $body.html('<div class="ci-error">Ошибка загрузки. Проверьте сеть или API ключ.</div>');
        return;
      }

      $body.empty();

      // Backdrop
      if (data.backdrop_path) {
        var bImg = Lampa.TMDB.image('t/p/w1280' + data.backdrop_path);
        $body.append('<div class="ci-hero" style="background-image:url(' + bImg + ')"><div class="ci-hero__overlay"></div></div>');
      }

      // Meta & Overview
      var $info = $('<div></div>');
      if (data.tagline) $info.append('<div class="ci-tagline">«' + escapeHtml(data.tagline) + '»</div>');
      
      var rating = data.vote_average ? '<span class="ci-meta__item ci-meta__item--rating">★ ' + data.vote_average.toFixed(1) + '</span>' : '';
      var year = (data.release_date || data.first_air_date || '').substring(0, 4);
      
      $info.append('<div class="ci-meta">' + rating + '<span>' + year + '</span><span>' + (data.runtime || 0) + ' мин.</span></div>');
      $info.append('<div class="ci-overview">' + escapeHtml(data.overview || 'Описание отсутствует.') + '</div>');
      $body.append($info);

      // Cast
      if (data.credits && data.credits.cast && data.credits.cast.length) {
        $body.append('<div class="ci-section-title"><span class="ci-section-title__bar"></span>В ролях</div>');
        var $castList = $('<div class="ci-cast"></div>');
        data.credits.cast.slice(0, 12).forEach(function(actor) {
          var photo = actor.profile_path ? Lampa.TMDB.image('t/p/w185' + actor.profile_path) : '';
          var $actor = $('<div class="ci-actor selector">' +
            '<div class="ci-actor__photo" ' + (photo ? 'style="background-image:url(' + photo + ')"' : '') + '></div>' +
            '<div class="ci-actor__name">' + escapeHtml(actor.name) + '</div>' +
            '</div>');
          
          $actor.on('hover:enter', function() {
              Lampa.Modal.close();
              Lampa.Activity.push({
                  url: 'person/' + actor.id,
                  title: actor.name,
                  component: 'actor',
                  id: actor.id,
                  source: 'tmdb'
              });
          });
          $castList.append($actor);
        });
        $body.append($castList);
      }

      Lampa.Controller.toggle('modal');
    });
  }

  function injectButton() {
    Lampa.Listener.follow('full', function (e) {
      if (e.type === 'complite') {
        var movie = e.data.movie || e.data;
        var method = e.data.method || (movie.first_air_date ? 'tv' : 'movie');
        
        var render = e.object.activity.render();
        var timer = setInterval(function() {
          var container = render.find('.full-start-new__buttons, .full-start__buttons');
          if (container.length) {
            clearInterval(timer);
            if (container.find('.view--ci').length) return;

            var $btn = $('<div class="full-start__button selector view--ci">' +
              '<svg width="24" height="24" viewBox="0 0 24 24"><path d="M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z" fill="currentColor"/></svg>' +
              '<span>Инфо</span></div>');

            $btn.on('hover:enter', function () {
              openModal(movie, method);
            });

            container.append($btn);
          }
        }, 200);
        
        setTimeout(function() { clearInterval(timer); }, 5000);
      }
    });
  }

  function initialize() {
    if (window.cardInsightInstalled) return;
    window.cardInsightInstalled = true;
    injectStyles();
    injectButton();
  }

  if (window.appready) initialize();
  else Lampa.Listener.follow('app', function (e) {
    if (e.type === 'ready') initialize();
  });
})();
