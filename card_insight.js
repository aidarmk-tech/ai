  function openModal(movie, method) {
    // 1. Создаем компонент скролла Lampa
    var scroll = new Lampa.Scroll({
      mask: true,
      over: true,
      touch: true
    });

    var $body = $('<div class="ci-body"><div class="ci-loading">Загрузка…</div></div>');
    
    // Добавляем наш контейнер в скролл
    scroll.append($body);

    Lampa.Modal.open({
      title: movie.title || movie.name || 'Подробнее',
      html: scroll.render(), // Рендерим именно скролл
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
      if (recs.length) {
        $body.append(renderRecsSection(recs.slice(0, 30), method, 'Похожие'));
      }

      // 2. Регистрируем контроллер модалки специально для пульта
      Lampa.Controller.add('modal', {
        toggle: function () {
          Lampa.Controller.collectionSet(scroll.render());
          Lampa.Controller.context(scroll);
        },
        up: function () {
          // Если фокус не перепрыгнул на селектор выше, просто скроллим
          scroll.step(-200);
        },
        down: function () {
          // Скроллим вниз при нажатии пульта
          scroll.step(200);
        },
        back: function () {
          Lampa.Modal.close();
          Lampa.Controller.toggle('full_start');
        }
      });

      // Включаем контроллер
      Lampa.Controller.toggle('modal');
      
      // Обновляем геометрию скролла после загрузки данных
      scroll.update();
    });
  }
