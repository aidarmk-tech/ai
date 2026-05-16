(function () {
  'use strict';

  // ─── Genre map (RU) ──────────────────────────────────────────────────────
  var GENRE_NAMES = {
    28: 'Боевик', 12: 'Приключения', 16: 'Мультфильм', 35: 'Комедия',
    80: 'Криминал', 99: 'Документальный', 18: 'Драма', 10751: 'Семейный',
    14: 'Фэнтези', 36: 'История', 27: 'Ужасы', 10402: 'Музыка',
    9648: 'Детектив', 10749: 'Мелодрама', 878: 'Фантастика',
    53: 'Триллер', 10752: 'Военный', 37: 'Вестерн'
  };

  // ─── Mood config ─────────────────────────────────────────────────────────
  var _d = new Date();
  _d.setFullYear(_d.getFullYear() - 2);
  var _freshDate = _d.toISOString().substring(0, 10);

  var MOODS = [
    {
      id: 'sad', title: 'Грустно', icon: '😔',
      params: { with_genres: '18|10749', 'vote_average.gte': 7.0, 'vote_count.gte': 300, sort_by: 'vote_average.desc' }
    },
    {
      id: 'tired', title: 'Устал', icon: '😴',
      params: { with_genres: '35|16|10751', 'with_runtime.lte': 110, 'vote_average.gte': 6.5, sort_by: 'popularity.desc' }
    },
    {
      id: 'think', title: 'Хочу думать', icon: '🧠',
      params: { with_genres: '9648|878|53', 'vote_average.gte': 7.5, 'vote_count.gte': 500, sort_by: 'vote_average.desc' }
    },
    {
      id: 'adrenaline', title: 'Адреналин', icon: '⚡',
      params: { with_genres: '28|53|80', 'vote_average.gte': 6.5, 'vote_count.gte': 200, sort_by: 'popularity.desc' }
    },
    {
      id: 'laugh', title: 'Смеяться', icon: '😂',
      params: { with_genres: '35', 'vote_average.gte': 6.5, sort_by: 'popularity.desc' }
    },
    {
      id: 'fresh', title: 'Что-то новое', icon: '✨',
      params: { 'primary_release_date.gte': _freshDate, 'vote_count.gte': 100, sort_by: 'popularity.desc' }
    }
  ];

  // ─── Helpers ─────────────────────────────────────────────────────────────
  function tmdbGet(path, cb) {
    var net = new Lampa.Reguest();
    var sep = path.indexOf('?') >= 0 ? '&' : '?';
    var url = Lampa.TMDB.api(path + sep + 'api_key=' + Lampa.TMDB.key() + '&language=ru');
    net.silent(url, function (d) { cb(null, d); }, function (e) { cb(e); });
  }

  function esc(s) {
    return String(s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // ─── Skip-list storage ───────────────────────────────────────────────────
  var _skipList = null; // module-level cache; loaded once from storage

  function loadSkipList() {
    _skipList = [];
    try {
      var raw = Lampa.Storage.get('mr_skip_list', '[]');
      var list;
      if (typeof raw === 'string') {
        list = JSON.parse(raw || '[]');
      } else {
        list = raw; // Storage already parsed the JSON
      }
      if (list && typeof list.length === 'number') _skipList = list;
    } catch (e) {}
  }

  function saveSkipList() {
    try { Lampa.Storage.set('mr_skip_list', JSON.stringify(_skipList)); } catch (e) {}
  }

  function listHas(id) {
    if (!_skipList) loadSkipList();
    for (var i = 0; i < _skipList.length; i++) { if (_skipList[i] === id) return true; }
    return false;
  }

  function addToSkipList(id) {
    if (!_skipList) loadSkipList();
    if (listHas(id)) return;
    _skipList.push(id);
    if (_skipList.length > 500) _skipList = _skipList.slice(-400);
    saveSkipList();
  }

  function isSkipped(id) { return listHas(id); }

  function isInBook(id) {
    try { var f = Lampa.Favorite.check(id); return !!(f && f.book); }
    catch (e) { return false; }
  }

  // ─── Component ───────────────────────────────────────────────────────────
  function MoodRouletteActivity(object) {
    var $root        = $('<div class="mr-root"></div>');
    var $moodScreen  = null;
    var $cardScreen  = null;
    var queue        = [];
    var currentMood  = null;
    var fetching     = false;
    var screen       = 'mood'; // 'mood' | 'card'
    var self         = this;

    // ── Mood selection screen ────────────────────────────────────────────
    function buildMoodScreen() {
      $moodScreen = $('<div class="mr-moods"></div>');
      $moodScreen.append('<h2 class="mr-moods__title">Какое настроение?</h2>');
      var $grid = $('<div class="mr-moods__grid"></div>');
      MOODS.forEach(function (mood) {
        var $btn = $(
          '<div class="mr-mood-btn selector" tabindex="0" data-mood="' + mood.id + '">' +
            '<div class="mr-mood-btn__icon">' + mood.icon + '</div>' +
            '<div class="mr-mood-btn__label">' + esc(mood.title) + '</div>' +
          '</div>'
        );
        $btn.on('hover:enter', function () { startMood(mood); });
        $grid.append($btn);
      });
      $moodScreen.append($grid);
      $root.append($moodScreen);
    }

    function showMoodScreen() {
      screen = 'mood';
      queue = [];
      currentMood = null;
      if ($cardScreen) $cardScreen.hide();
      $moodScreen.show();
      Lampa.Controller.toggle('mr_mood');
      setTimeout(function () {
        if (!$root || !$root[0]) return;
        try {
          Lampa.Controller.collectionSet($root);
          var $f = $moodScreen.find('.selector').first();
          if ($f.length) Lampa.Controller.collectionFocus($f[0], $root);
        } catch (e) {}
      }, 100);
    }

    // ── Card screen ──────────────────────────────────────────────────────
    function buildCardScreen() {
      $cardScreen = $('<div class="mr-cards"></div>');
      $root.append($cardScreen);
    }

    function showCardScreen() {
      screen = 'card';
      $moodScreen.hide();
      $cardScreen.show().html('<div class="mr-loading">🎬 Загружаем фильмы…</div>');
      Lampa.Controller.toggle('mr_card');
      setTimeout(function () {
        if (!$root || !$root[0]) return;
        try {
          Lampa.Controller.collectionSet($root);
          Lampa.Controller.collectionFocus(false, $root);
        } catch (e) {}
      }, 100);
    }

    // ── Queue / prefetch ─────────────────────────────────────────────────
    function startMood(mood) {
      currentMood = mood;
      queue = [];
      fetching = false;
      Lampa.Storage.set('mr_last_mood', mood.id);
      showCardScreen();
      refillQueue();
    }

    function refillQueue() {
      if (fetching || !currentMood || queue.length >= 5) return;
      fetching = true;
      try {
        var page  = Math.floor(Math.random() * 20) + 1;
        var src   = currentMood.params;
        var parts = ['page=' + page];
        for (var k in src) { if (src.hasOwnProperty(k)) parts.push(k + '=' + src[k]); }
        var query = parts.join('&');

        var timer = setTimeout(function () {
          if (!fetching) return;
          fetching = false;
          if (screen === 'card') showMsg('error');
        }, 12000);

        tmdbGet('discover/movie?' + query, function (err, data) {
          clearTimeout(timer);
          fetching = false;
          if (err || !data || !data.results) {
            if (screen === 'card') showMsg('error');
            return;
          }
          data.results.forEach(function (m) {
            if (!isSkipped(m.id) && !isInBook(m.id) && !queueHas(m.id)) queue.push(m);
          });
          tryShowCard();
        });
      } catch (e) {
        fetching = false;
        if (screen === 'card') showMsg('error');
      }
    }

    function queueHas(id) {
      for (var i = 0; i < queue.length; i++) if (queue[i].id === id) return true;
      return false;
    }

    function tryShowCard() {
      if (screen !== 'card' || !$cardScreen) return;
      if (queue.length === 0) {
        if (!fetching) showMsg('empty');
        return;
      }
      renderMovie(queue[0]);
      if (queue.length <= 2) refillQueue();
    }

    // ── Render card ──────────────────────────────────────────────────────
    function renderMovie(movie) {
      var poster   = movie.poster_path
        ? 'https://image.tmdb.org/t/p/w500' + movie.poster_path : '';
      var year     = (movie.release_date || '').substring(0, 4);
      var rating   = movie.vote_average ? Number(movie.vote_average).toFixed(1) : '';
      var overview = movie.overview || '';
      var genres   = (movie.genre_ids || []).slice(0, 3)
        .map(function (id) { return GENRE_NAMES[id] || ''; }).filter(Boolean).join(', ');

      $cardScreen.empty().append(
        '<div class="mr-card">' +
          '<div class="mr-card__poster"' +
            (poster ? ' style="background-image:url(\'' + poster + '\')"' : '') + '>' +
            (!poster ? '<div class="mr-card__no-poster">🎬</div>' : '') +
          '</div>' +
          '<div class="mr-card__body">' +
            '<div class="mr-card__title">' + esc(movie.title || 'Без названия') +
              (year ? ' <span class="mr-card__year">(' + year + ')</span>' : '') + '</div>' +
            (rating  ? '<div class="mr-card__rating">★ ' + rating + '</div>' : '') +
            (genres  ? '<div class="mr-card__genres">' + esc(genres) + '</div>' : '') +
            (overview ? '<div class="mr-card__overview">' + esc(overview) + '</div>' : '') +
            '<div class="mr-card__cast"></div>' +
            '<div class="mr-card__hints">' +
              '<span class="mr-hint mr-hint--skip">← Пропустить</span>' +
              '<span class="mr-hint mr-hint--detail">↑ Подробнее</span>' +
              '<span class="mr-hint mr-hint--book">→ В Книгу</span>' +
              '<span class="mr-hint mr-hint--hide">↓ Скрыть</span>' +
            '</div>' +
          '</div>' +
        '</div>'
      );

      // Load cast asynchronously — update cast div when ready
      tmdbGet('movie/' + movie.id + '/credits', function (err, data) {
        if (err || !data || !data.cast || !$cardScreen) return;
        var names = [];
        for (var i = 0; i < data.cast.length && names.length < 5; i++) {
          if (data.cast[i].name) names.push(esc(data.cast[i].name));
        }
        if (names.length) {
          $cardScreen.find('.mr-card__cast').html(
            '<span class="mr-cast__label">В ролях: </span>' + names.join(', ')
          );
        }
      });
    }

    function showMsg(type) {
      if (!$cardScreen) return;
      var isError  = type === 'error';
      var btnLabel = isError ? 'Повторить' : 'Сменить настроение';
      $cardScreen.empty().append(
        '<div class="mr-msg">' +
          '<div class="mr-msg__icon">' + (isError ? '⚠️' : '🎬') + '</div>' +
          '<div class="mr-msg__text">' +
            esc(isError ? 'Ошибка загрузки. Проверьте соединение.' : 'Все фильмы по этому настроению просмотрены') +
          '</div>' +
          '<div class="mr-msg__btn selector" tabindex="0">' + esc(btnLabel) + '</div>' +
        '</div>'
      );
      var $btn = $cardScreen.find('.selector');
      $btn.on('hover:enter', function () {
        if (isError) {
          $cardScreen.html('<div class="mr-loading">🎬 Загружаем…</div>');
          refillQueue();
        } else {
          showMoodScreen();
        }
      });
      // Re-activate mr_card controller so back/keys still work, but focus the button
      Lampa.Controller.toggle('mr_card');
      setTimeout(function () {
        try {
          Lampa.Controller.collectionSet($root);
          if ($btn.length) Lampa.Controller.collectionFocus($btn[0], $root);
        } catch (e) {}
      }, 50);
    }

    // ── Actions ──────────────────────────────────────────────────────────
    function advance(dir) {
      if (!$cardScreen) return;
      var $card = $cardScreen.find('.mr-card');
      if ($card.length) {
        $card.addClass('mr-card--out-' + dir);
        setTimeout(function () { queue.shift(); tryShowCard(); }, 180);
      } else {
        queue.shift();
        tryShowCard();
      }
    }

    function doRight() {
      var m = queue[0]; if (!m) return;
      try { Lampa.Favorite.add('book', m); Lampa.Noty.show('Добавлено в Книгу'); } catch (e) {}
      advance('right');
    }
    function doLeft() { advance('left'); }
    function doDown() {
      var m = queue[0]; if (!m) return;
      addToSkipList(m.id);
      try { Lampa.Noty.show('Скрыто навсегда'); } catch (e) {}
      advance('left');
    }
    function doUp() {
      var m = queue[0]; if (!m) return;
      Lampa.Activity.push({ url: '', component: 'full', id: m.id, method: 'movie', card: m, source: 'tmdb' });
    }

    // ── Lifecycle ────────────────────────────────────────────────────────
    this.create = function () {
      // Mood controller: delegates arrow keys to Navigator for grid navigation
      Lampa.Controller.add('mr_mood', {
        toggle: function () {
          Lampa.Controller.collectionSet($root);
          var $f = $moodScreen ? $moodScreen.find('.selector').first() : null;
          if ($f && $f.length) Lampa.Controller.collectionFocus($f[0], $root);
          else Lampa.Controller.collectionFocus(false, $root);
        },
        up: function () {
          if (typeof Navigator !== 'undefined' && Navigator.canmove && Navigator.canmove('up'))
            Navigator.move('up');
        },
        down: function () {
          if (typeof Navigator !== 'undefined' && Navigator.canmove && Navigator.canmove('down'))
            Navigator.move('down');
        },
        left: function () {
          if (typeof Navigator !== 'undefined' && Navigator.canmove && Navigator.canmove('left'))
            Navigator.move('left');
          else
            Lampa.Controller.toggle('menu');
        },
        right: function () {
          if (typeof Navigator !== 'undefined' && Navigator.canmove && Navigator.canmove('right'))
            Navigator.move('right');
        },
        back: function () { Lampa.Activity.backward(); }
      });

      // Card controller: fully manual — no Navigator, every key is a swipe/action
      Lampa.Controller.add('mr_card', {
        toggle: function () {
          Lampa.Controller.collectionSet($root);
          Lampa.Controller.collectionFocus(false, $root);
        },
        left:  function () { doLeft(); },
        right: function () { doRight(); },
        up:    function () { doUp(); },
        down:  function () { doDown(); },
        back:  function () { showMoodScreen(); }
      });

      buildMoodScreen();
      buildCardScreen();
      $cardScreen.hide();
      return this.render();
    };

    this.render = function () { return $root; };

    this.start = function () {
      if (screen === 'card') {
        Lampa.Controller.toggle('mr_card');
        setTimeout(function () {
          try { Lampa.Controller.collectionSet($root); Lampa.Controller.collectionFocus(false, $root); } catch (e) {}
        }, 100);
      } else {
        Lampa.Controller.toggle('mr_mood');
        setTimeout(function () {
          if (!$root || !$root[0] || !$moodScreen) return;
          try {
            Lampa.Controller.collectionSet($root);
            var $f = $moodScreen.find('.selector').first();
            if ($f.length) Lampa.Controller.collectionFocus($f[0], $root);
          } catch (e) {}
        }, 100);
      }
    };

    this.stop    = function () {};
    this.pause   = function () {};
    this.resume  = function () { self.start(); };

    this.destroy = function () {
      try { Lampa.Controller.remove('mr_mood'); } catch (e) {}
      try { Lampa.Controller.remove('mr_card'); } catch (e) {}
      if ($root) $root.remove();
      $root = null; $moodScreen = null; $cardScreen = null;
    };
  }

  // ─── Menu button ─────────────────────────────────────────────────────────
  function addMenuButton() {
    if ($('.menu [data-action="mood_roulette"]').length) return;
    var $btn = $(
      '<li class="menu__item selector" data-action="mood_roulette">' +
        '<div class="menu__ico">🎲</div>' +
        '<div class="menu__text">Кинорулетка</div>' +
      '</li>'
    );
    $btn.on('hover:enter', function () {
      Lampa.Activity.push({ url: '', title: 'Кинорулетка', component: 'mood_roulette', page: 1 });
    });
    $('.menu .menu__list').eq(0).append($btn);
  }

  // ─── CSS ─────────────────────────────────────────────────────────────────
  var CSS = [
    '.mr-root{position:relative;width:100%;height:100%;min-height:100%;background:#0f0f0f;display:flex;align-items:center;justify-content:center}',

    // Mood screen
    '.mr-moods{width:100%;max-width:860px;padding:40px 48px}',
    '.mr-moods__title{font-size:36px;color:#fff;text-align:center;margin:0 0 36px;font-weight:600}',
    '.mr-moods__grid{display:grid;grid-template-columns:repeat(3,1fr);gap:18px}',
    '.mr-mood-btn{display:flex;flex-direction:column;align-items:center;justify-content:center;padding:28px 16px;background:rgba(255,255,255,0.06);border-radius:14px;border:2px solid transparent;transition:background .12s,border-color .12s,transform .12s;cursor:pointer}',
    '.mr-mood-btn.focus,.mr-mood-btn:focus{background:rgba(229,160,13,0.15);border-color:#e5a00d;transform:scale(1.04);outline:none}',
    '.mr-mood-btn__icon{font-size:48px;margin-bottom:10px;line-height:1}',
    '.mr-mood-btn__label{font-size:22px;color:#fff;font-weight:500;text-align:center}',

    // Card container — fills activity height, padding on all sides
    '.mr-cards{width:100%;height:100%;display:flex;align-items:center;justify-content:center;padding:20px 40px;box-sizing:border-box}',

    // Card — horizontal layout, fills container height
    '.mr-card{display:flex;flex-direction:row;width:100%;height:100%;background:#1c1c1c;border-radius:18px;overflow:hidden;box-shadow:0 16px 50px rgba(0,0,0,0.85);animation:mr-in .2s ease}',
    '@keyframes mr-in{from{opacity:0;transform:scale(.96)}to{opacity:1;transform:none}}',
    '.mr-card--out-left{animation:mr-outl .18s ease forwards}',
    '.mr-card--out-right{animation:mr-outr .18s ease forwards}',
    '@keyframes mr-outl{to{opacity:0;transform:translateX(-60px) scale(.95)}}',
    '@keyframes mr-outr{to{opacity:0;transform:translateX(60px) scale(.95)}}',

    // Poster — left column, fills full card height
    '.mr-card__poster{width:300px;min-width:300px;height:100%;background:#1a1a1a center/cover no-repeat;flex-shrink:0}',
    '.mr-card__no-poster{width:100%;height:100%;display:flex;align-items:center;justify-content:center;font-size:64px;color:#333}',

    // Body — right column, flex column: overview grows, cast + hints stick to bottom
    '.mr-card__body{flex:1;display:flex;flex-direction:column;padding:24px 28px;overflow:hidden;min-width:0}',
    '.mr-card__title{font-size:30px;color:#fff;font-weight:600;line-height:1.3;margin-bottom:6px;flex-shrink:0}',
    '.mr-card__year{font-size:22px;color:#777;font-weight:400}',
    '.mr-card__rating{font-size:20px;color:#e5a00d;margin-bottom:4px;flex-shrink:0}',
    '.mr-card__genres{font-size:16px;color:#888;margin-bottom:8px;flex-shrink:0}',
    // Overview fills all remaining space — no line-clamp, clips naturally at boundary
    '.mr-card__overview{font-size:18px;color:#bbb;line-height:1.55;flex:1;overflow:hidden;min-height:0}',
    // Cast — single line at bottom, appears after async credits fetch
    '.mr-card__cast{font-size:15px;color:#666;padding-top:8px;margin-top:6px;border-top:1px solid rgba(255,255,255,0.06);flex-shrink:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;min-height:26px}',
    '.mr-cast__label{color:#aaa;font-weight:500}',
    '.mr-card__hints{display:flex;justify-content:space-around;padding:10px 0 0;border-top:1px solid rgba(255,255,255,0.07);margin-top:8px;flex-shrink:0}',
    '.mr-hint{font-size:14px;color:#555}',
    '.mr-hint--book{color:#e5a00d}',

    // Loading / empty / error
    '.mr-loading{font-size:24px;color:#666;text-align:center;padding:60px 40px}',
    '.mr-msg{text-align:center;padding:40px 24px;max-width:500px}',
    '.mr-msg__icon{font-size:56px;margin-bottom:16px}',
    '.mr-msg__text{font-size:22px;color:#888;margin-bottom:24px;line-height:1.4}',
    '.mr-msg__btn{display:inline-block;padding:14px 36px;background:#e5a00d;color:#000;border-radius:10px;font-size:20px;font-weight:700;cursor:pointer;transition:transform .12s}',
    '.mr-msg__btn.focus,.mr-msg__btn:focus{transform:scale(1.06);outline:3px solid #fff;outline-offset:3px}',
  ].join('');

  // ─── Bootstrap ───────────────────────────────────────────────────────────
  Lampa.Manifest.plugins = {
    type: 'video',
    version: '1.0',
    name: 'Mood Roulette',
    description: 'Кинорулетка по настроению'
  };

  $('<style>').text(CSS).appendTo('head');
  Lampa.Component.add('mood_roulette', MoodRouletteActivity);

  if (window.appready) addMenuButton();
  else Lampa.Listener.follow('app', function (e) { if (e.type === 'ready') addMenuButton(); });
})();
