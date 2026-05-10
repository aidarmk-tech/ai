/*!
 * AI Search for Lampa — OpenRouter Edition
 * Поиск фильмов/сериалов через AI + TMDB
 *
 * - Один API-ключ OpenRouter → доступ ко всем моделям
 * - До 3 моделей-слотов в порядке приоритета (fallback)
 * - 12 пресетов + поле "свой ID модели" для любой другой
 * - Все настройки в Настройки → AI Поиск
 */
(function () {
  'use strict';

  // ====================================================
  // ПРЕСЕТЫ МОДЕЛЕЙ (все через OpenRouter)
  // jsonMode — поддерживает ли response_format: json_object
  // ====================================================
  var PRESETS = {
    'none': { name: '— не использовать —', _none: true },

    // ─── Лучшее по цене/качеству ───
    'ds_v3': {
      name: 'DeepSeek V3 ★ дёшево, отличный русский',
      model: 'deepseek/deepseek-chat', jsonMode: true
    },
    'qwen_25_72': {
      name: 'Qwen 2.5 72B — хороший русский',
      model: 'qwen/qwen-2.5-72b-instruct', jsonMode: true
    },
    'gem_25_flash': {
      name: 'Gemini 2.5 Flash — быстрая',
      model: 'google/gemini-2.5-flash', jsonMode: true
    },
    'gpt_4o_mini': {
      name: 'GPT-4o-mini — дёшево',
      model: 'openai/gpt-4o-mini', jsonMode: true
    },
    'claude_haiku': {
      name: 'Claude 3.5 Haiku — быстрая',
      model: 'anthropic/claude-3.5-haiku', jsonMode: false
    },
    'mistral_large': {
      name: 'Mistral Large',
      model: 'mistralai/mistral-large-2411', jsonMode: true
    },

    // ─── Reasoning ───
    'ds_r1': {
      name: 'DeepSeek R1 — умнее, медленнее',
      model: 'deepseek/deepseek-r1', jsonMode: false
    },

    // ─── Премиум ───
    'gpt_4o': {
      name: 'GPT-4o — премиум',
      model: 'openai/gpt-4o', jsonMode: true
    },
    'gem_25_pro': {
      name: 'Gemini 2.5 Pro — премиум',
      model: 'google/gemini-2.5-pro', jsonMode: true
    },
    'claude_sonnet_4': {
      name: 'Claude Sonnet 4 — премиум',
      model: 'anthropic/claude-sonnet-4', jsonMode: false
    },

    // ─── Бесплатные (нужен баланс на OpenRouter, лимиты) ───
    'llama_33_70_free': {
      name: '[FREE] Llama 3.3 70B',
      model: 'meta-llama/llama-3.3-70b-instruct:free', jsonMode: false
    },
    'ds_v3_free': {
      name: '[FREE] DeepSeek V3',
      model: 'deepseek/deepseek-chat-v3-0324:free', jsonMode: false
    },

    // ─── Своя модель ───
    'custom': { name: '✎ Свой ID модели (поле ниже)', _custom: true }
  };

  var PRESET_ORDER = [
    'none',
    'ds_v3', 'qwen_25_72', 'gem_25_flash', 'gpt_4o_mini', 'claude_haiku', 'mistral_large',
    'ds_r1',
    'gpt_4o', 'gem_25_pro', 'claude_sonnet_4',
    'llama_33_70_free', 'ds_v3_free',
    'custom'
  ];

  function buildPresetValues() {
    var v = {};
    for (var i = 0; i < PRESET_ORDER.length; i++) {
      var k = PRESET_ORDER[i];
      if (PRESETS[k]) v[k] = PRESETS[k].name;
    }
    return v;
  }

  // ====================================================
  // КОНСТАНТЫ
  // ====================================================
  var OR_BASE_URL = 'https://openrouter.ai/api/v1';

  var STORAGE = {
    api_key: 'ai_search_or_key',

    slot1: 'ai_search_slot1',
    slot2: 'ai_search_slot2',
    slot3: 'ai_search_slot3',

    custom_model:    'ai_search_custom_model',
    custom_jsonmode: 'ai_search_custom_jsonmode',

    result_count: 'ai_search_result_count',

    cache_ai:   'ai_search_cache',
    cache_tmdb: 'ai_search_tmdb_cache'
  };

  var RESULT_COUNT_MIN = 1;
  var RESULT_COUNT_MAX = 20;
  var RESULT_COUNT_DEFAULT = 10;

  function getResultCount() {
    var raw = parseInt(Lampa.Storage.get(STORAGE.result_count, RESULT_COUNT_DEFAULT), 10);
    if (!raw || isNaN(raw)) raw = RESULT_COUNT_DEFAULT;
    if (raw < RESULT_COUNT_MIN) raw = RESULT_COUNT_MIN;
    if (raw > RESULT_COUNT_MAX) raw = RESULT_COUNT_MAX;
    return raw;
  }

  // ====================================================
  // ЛОГГЕР
  // ====================================================
  var logger = {
    log:   function () { console.log.apply(console,   ['[AI Поиск]'].concat([].slice.call(arguments))); },
    warn:  function () { console.warn.apply(console,  ['[AI Поиск]'].concat([].slice.call(arguments))); },
    error: function () { console.error.apply(console, ['[AI Поиск]'].concat([].slice.call(arguments))); }
  };

  // ====================================================
  // СБОРКА АКТИВНЫХ МОДЕЛЕЙ
  // ====================================================
  function getApiKey() {
    return (Lampa.Storage.get(STORAGE.api_key, '') || '').trim();
  }

  function resolveSlot(slotKey) {
    var presetId = Lampa.Storage.get(slotKey, 'none');
    if (!presetId || presetId === 'none') return null;

    var preset = PRESETS[presetId];
    if (!preset || preset._none) return null;

    if (preset._custom) {
      var modelId = (Lampa.Storage.get(STORAGE.custom_model, '') || '').trim();
      var jsonMd  = Lampa.Storage.get(STORAGE.custom_jsonmode, 'no') === 'yes';
      if (!modelId) return null;
      return { name: 'Custom: ' + modelId, model: modelId, jsonMode: jsonMd };
    }

    return { name: preset.name, model: preset.model, jsonMode: !!preset.jsonMode };
  }

  function buildModels() {
    var key = getApiKey();
    if (!key) return [];

    var slots = [STORAGE.slot1, STORAGE.slot2, STORAGE.slot3];
    var list = [], seen = {};
    for (var i = 0; i < slots.length; i++) {
      var m = resolveSlot(slots[i]);
      if (!m) continue;
      if (seen[m.model]) continue;
      seen[m.model] = true;
      m.apiKey = key;
      list.push(m);
    }
    return list;
  }

  // ====================================================
  // КЭШ И УТИЛИТЫ
  // ====================================================
  function hashString(str) {
    var h = 0;
    for (var i = 0; i < str.length; i++) h = ((h << 5) - h + str.charCodeAt(i)) | 0;
    return Math.abs(h).toString(36);
  }

  function getFromCache(name, key) {
    try {
      var c = Lampa.Storage.get(name) || {};
      var entry = c[key];
      if (entry && Date.now() - entry.timestamp < 86400000) return entry.data;
    } catch (e) { logger.error('Cache read:', e); }
    return null;
  }

  function setToCache(name, key, data) {
    try {
      var c = Lampa.Storage.get(name) || {};
      c[key] = { data: data, timestamp: Date.now() };
      Lampa.Storage.set(name, c);
    } catch (e) { logger.error('Cache write:', e); }
  }

  function parseJsonFromResponse(text) {
    if (!text || typeof text !== 'string') return null;
    text = text.trim();

    var cb = text.indexOf('```');
    if (cb !== -1) {
      var s = cb + 3;
      if (text.substr(s, 4).toLowerCase() === 'json') s += 4;
      while (s < text.length && /\s/.test(text[s])) s++;
      var e = text.indexOf('```', s);
      if (e !== -1) {
        try { return JSON.parse(text.substring(s, e).trim()); } catch (_) {}
      }
    }

    var depth = 0, start = -1;
    for (var i = 0; i < text.length; i++) {
      if (text[i] === '{') { if (start === -1) start = i; depth++; }
      else if (text[i] === '}') {
        depth--;
        if (depth === 0 && start !== -1) {
          try { return JSON.parse(text.substring(start, i + 1)); } catch (_) {}
          start = -1;
        }
      }
    }
    return null;
  }

  function extractRecommendations(parsed) {
    var out = [];
    if (!parsed) return out;
    var items = parsed.recommendations || parsed.movies || parsed.items || parsed.results || [];
    if (!Array.isArray(items)) return out;
    var max = getResultCount();
    for (var i = 0; i < items.length && out.length < max; i++) {
      var it = items[i];
      if (!it || typeof it !== 'object') continue;
      var rec = {
        title: ((it.title || it.name || it.film || '') + '').trim(),
        year:  parseInt(it.year || it.release_year || it.date || '0', 10) || null
      };
      if (rec.title) out.push(rec);
    }
    return out;
  }

  // ====================================================
  // AI ЗАПРОС: AbortController + защита от двойного callback
  // ====================================================
  function queryAIOnce(model, query, callback) {
    var done = false;
    var controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
    var timeoutId;

    function finish(err, data) {
      if (done) return;
      done = true;
      if (timeoutId) clearTimeout(timeoutId);
      if (controller) { try { controller.abort(); } catch (_) {} }
      callback(err, data);
    }

    timeoutId = setTimeout(function () {
      logger.warn('Таймаут:', model.name);
      finish(new Error('Таймаут ' + model.name));
    }, 25000);

    var n = getResultCount();
    var prompt =
      'Запрос пользователя: "' + query + '"\n' +
      'Предложи ровно ' + n + ' фильмов или сериалов, подходящих под запрос.\n' +
      'Формат JSON: {"recommendations":[{"title":"Название","year":2023}]}\n' +
      'Отвечай ТОЛЬКО валидным JSON, без пояснений.';

    var body = {
      model: model.model,
      messages: [
        { role: 'system', content: 'Ты киноэксперт. Отвечаешь ТОЛЬКО валидным JSON.' },
        { role: 'user',   content: prompt }
      ],
      temperature: 0.6,
      max_tokens: 2000
    };
    if (model.jsonMode) body.response_format = { type: 'json_object' };

    var headers = {
      'Authorization': 'Bearer ' + model.apiKey,
      'Content-Type':  'application/json',
      'HTTP-Referer':  (window.location && window.location.origin) || 'https://lampa.app',
      'X-Title':       'Lampa AI Search'
    };

    var fetchOpts = { method: 'POST', headers: headers, body: JSON.stringify(body) };
    if (controller) fetchOpts.signal = controller.signal;

    logger.log('Запрос →', model.model);

    fetch(OR_BASE_URL + '/chat/completions', fetchOpts)
      .then(function (res) {
        return res.text().then(function (text) {
          return { ok: res.ok, status: res.status, text: text };
        });
      })
      .then(function (r) {
        if (!r.ok) {
          var msg = 'HTTP ' + r.status;
          if      (r.status === 401) msg = 'Неверный OpenRouter ключ';
          else if (r.status === 402) msg = 'Недостаточно средств на OpenRouter';
          else if (r.status === 429) msg = 'Лимит запросов: ' + model.model;
          else if (r.status === 404) msg = 'Модель не найдена: ' + model.model;
          else if (r.status >= 500)  msg = 'OpenRouter недоступен';
          logger.error(model.model, '→', r.status, r.text.substring(0, 200));
          return finish(new Error(msg));
        }
        var data = null;
        try { data = JSON.parse(r.text); } catch (_) {}

        var content =
          (data && data.choices && data.choices[0] && data.choices[0].message
            && data.choices[0].message.content) || '';
        if (!content) return finish(new Error('Пустой ответ ' + model.model));

        var parsed = parseJsonFromResponse(content);
        if (!parsed) return finish(new Error('Невалидный JSON от ' + model.model));

        var recs = extractRecommendations(parsed);
        if (!recs.length) return finish(new Error('Нет рекомендаций'));

        finish(null, { recommendations: recs });
      })
      .catch(function (err) {
        if (done) return;
        if (err && err.name === 'AbortError') return;
        logger.warn('Сеть/ошибка', model.model + ':', err && err.message);
        finish(err || new Error('Сетевая ошибка'));
      });
  }

  function queryAI(query, callback) {
    var models = buildModels();
    if (!models.length) {
      var hint = getApiKey() ?
        'Не выбрано ни одной модели. Откройте Настройки → AI Поиск' :
        'Нет OpenRouter ключа. Откройте Настройки → AI Поиск';
      Lampa.Noty.show(hint);
      return callback(new Error('not configured'));
    }

    var MAX_RETRIES = 2;
    var modelIdx = 0, retry = 0;

    function attempt() {
      if (modelIdx >= models.length) {
        Lampa.Noty.show('Все модели недоступны');
        return callback(new Error('all failed'));
      }
      var m = models[modelIdx];
      var cacheKey = 'q_' + hashString(query + '|' + m.model);
      var cached = getFromCache(STORAGE.cache_ai, cacheKey);
      if (cached) {
        logger.log('Кэш:', m.model);
        return callback(null, cached);
      }
      logger.log('Попытка ' + (retry + 1) + '/' + MAX_RETRIES + ' → ' + m.model);

      queryAIOnce(m, query, function (err, data) {
        if (!err) {
          setToCache(STORAGE.cache_ai, cacheKey, data);
          return callback(null, data);
        }
        retry++;
        if (retry >= MAX_RETRIES) {
          if (modelIdx + 1 < models.length) Lampa.Noty.show(m.model + ' недоступна, пробую другую…');
          modelIdx++;
          retry = 0;
        }
        setTimeout(attempt, 1000);
      });
    }
    attempt();
  }

  // ====================================================
  // TMDB ПОИСК
  // ====================================================
  function fetchTmdbData(recommendations, callback) {
    var results = [], processed = 0;
    var limit = Math.min(recommendations.length, getResultCount());
    if (!limit) return callback([]);
    var request = new Lampa.Reguest();

    function next() {
      processed++;
      if (processed >= limit) callback(results);
    }

    function processItem(item) {
      if (!item || !item.title) return next();
      var key = 'tmdb_' + hashString(item.title + (item.year || ''));
      var cached = getFromCache(STORAGE.cache_tmdb, key);
      if (cached) { results.push(cached); return next(); }

      var url = Lampa.TMDB.api(
        'search/multi?query=' + encodeURIComponent(item.title) +
        '&api_key=' + Lampa.TMDB.key() + '&language=ru'
      );

      request.silent(url, function (data) {
        if (data && data.results && data.results.length) {
          var best = data.results[0];
          if (item.year) {
            for (var i = 0; i < data.results.length; i++) {
              var r = data.results[i];
              var y = (r.release_date || r.first_air_date || '').substring(0, 4);
              if (y && parseInt(y, 10) === parseInt(item.year, 10)) { best = r; break; }
            }
          }
          if (best.media_type === 'movie' || best.media_type === 'tv') {
            var movie = {
              id: best.id,
              title: best.title || best.name,
              original_title: best.original_title || best.original_name,
              overview: best.overview || '',
              poster_path: best.poster_path,
              backdrop_path: best.backdrop_path,
              vote_average: best.vote_average || 0,
              release_date: best.release_date || best.first_air_date,
              type: best.media_type === 'tv' ? 'tv' : 'movie'
            };
            results.push(movie);
            setToCache(STORAGE.cache_tmdb, key, movie);
          }
        }
        next();
      }, next);
    }
    for (var i = 0; i < limit; i++) processItem(recommendations[i]);
  }

  // ====================================================
  // ИСТОЧНИК ПОИСКА
  // ====================================================
  var AiSearchSource = {
    title: 'AI Поиск',
    search: function (params, oncomplite) {
      var query = decodeURIComponent(params.query || '').trim();
      if (!query) return oncomplite([]);
      Lampa.Noty.show('AI ищет: ' + query);

      queryAI(query, function (err, aiResult) {
        if (err) return oncomplite([]);
        fetchTmdbData(aiResult.recommendations, function (tmdbResults) {
          if (!tmdbResults.length) {
            Lampa.Noty.show('TMDB ничего не нашёл по AI-рекомендациям');
            return oncomplite([]);
          }
          var results = tmdbResults.map(function (it) {
            return {
              id: it.id, title: it.title, name: it.title,
              poster_path: it.poster_path ? Lampa.TMDB.image('t/p/w200' + it.poster_path) : '',
              release_year: (it.release_date || '').substring(0, 4),
              vote_average: it.vote_average,
              type: it.type, source: 'tmdb', method: it.type
            };
          });
          oncomplite([{
            title: 'AI Рекомендации (' + results.length + ')',
            results: results, total: results.length
          }]);
        });
      });
    },
    onSelect: function (params, close) {
      close();
      if (params.element) {
        Lampa.Activity.push({
          url: '', title: params.element.type === 'tv' ? 'Сериал' : 'Фильм',
          component: 'full', id: params.element.id,
          method: params.element.type, source: 'tmdb'
        });
      }
    },
    params: { save: true, lazy: true }
  };

  // ====================================================
  // НАСТРОЙКИ ПЛАГИНА
  // ====================================================
  function addSettings() {
    if (!Lampa.SettingsApi) {
      logger.warn('Lampa.SettingsApi отсутствует');
      return;
    }

    Lampa.SettingsApi.addComponent({
      component: 'ai_search',
      name: 'AI Поиск',
      icon: '<svg width="64" height="64" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>'
    });

    var presetValues = buildPresetValues();

    // --- Ключ ---
    Lampa.SettingsApi.addParam({
      component: 'ai_search',
      param: { name: STORAGE.api_key, type: 'input', values: '', default: '' },
      field: {
        name: 'OpenRouter API Key',
        description: 'sk-or-v1-… — получить на openrouter.ai/keys'
      }
    });

    // --- Слоты ---
    Lampa.SettingsApi.addParam({
      component: 'ai_search',
      param: { name: STORAGE.slot1, type: 'select', values: presetValues, default: 'ds_v3' },
      field: { name: 'Модель №1 (основная)', description: 'Используется первой' }
    });
    Lampa.SettingsApi.addParam({
      component: 'ai_search',
      param: { name: STORAGE.slot2, type: 'select', values: presetValues, default: 'gem_25_flash' },
      field: { name: 'Модель №2 (резерв)', description: 'Если №1 не отвечает' }
    });
    Lampa.SettingsApi.addParam({
      component: 'ai_search',
      param: { name: STORAGE.slot3, type: 'select', values: presetValues, default: 'none' },
      field: { name: 'Модель №3 (резерв)', description: 'Последняя попытка' }
    });

    // --- Своя модель ---
    Lampa.SettingsApi.addParam({
      component: 'ai_search',
      param: { name: STORAGE.custom_model, type: 'input', values: '', default: '' },
      field: {
        name: 'Свой ID модели',
        description: 'Любая модель с openrouter.ai/models. Пример: x-ai/grok-3, qwen/qwen3-235b-a22b'
      }
    });
    Lampa.SettingsApi.addParam({
      component: 'ai_search',
      param: {
        name: STORAGE.custom_jsonmode, type: 'select',
        values: { 'no': 'Нет', 'yes': 'Да' }, default: 'no'
      },
      field: {
        name: 'Своя модель: JSON-режим',
        description: 'response_format: json_object — включать только если модель поддерживает'
      }
    });

    // --- Количество результатов ---
    Lampa.SettingsApi.addParam({
      component: 'ai_search',
      param: { name: STORAGE.result_count, type: 'input', values: '', default: String(RESULT_COUNT_DEFAULT) },
      field: {
        name: 'Сколько фильмов искать',
        description: 'От ' + RESULT_COUNT_MIN + ' до ' + RESULT_COUNT_MAX + '. Больше — дольше ждать. По умолчанию: ' + RESULT_COUNT_DEFAULT
      },
      onChange: function () {
        var v = parseInt(Lampa.Storage.get(STORAGE.result_count, RESULT_COUNT_DEFAULT), 10);
        if (!v || isNaN(v) || v < RESULT_COUNT_MIN) v = RESULT_COUNT_MIN;
        if (v > RESULT_COUNT_MAX) v = RESULT_COUNT_MAX;
        Lampa.Storage.set(STORAGE.result_count, v);
        Lampa.Noty.show('Количество фильмов: ' + v);
      }
    });

    // --- Очистка кэша ---
    Lampa.SettingsApi.addParam({
      component: 'ai_search',
      param: { name: 'ai_search_clear_cache', type: 'button' },
      field: { name: 'Очистить кэш', description: 'Удалить все сохранённые AI- и TMDB-результаты' },
      onChange: function () {
        Lampa.Storage.set(STORAGE.cache_ai, {});
        Lampa.Storage.set(STORAGE.cache_tmdb, {});
        Lampa.Noty.show('Кэш очищен');
      }
    });
  }

  // ====================================================
  // ИНИЦИАЛИЗАЦИЯ
  // ====================================================
  function initialize() {
    if (!window.Lampa || !Lampa.Storage) return setTimeout(initialize, 500);

    addSettings();

    if (Lampa.Search && typeof Lampa.Search.addSource === 'function') {
      Lampa.Search.addSource(AiSearchSource);
    } else if (Lampa.SearchSource && typeof Lampa.SearchSource.add === 'function') {
      Lampa.SearchSource.add('ai_search', AiSearchSource);
    } else {
      logger.warn('Не нашёл API регистрации источника поиска');
    }

    logger.log('AI Search Plugin готов (OpenRouter). Настройки → AI Поиск');
  }

  if (window.appready) initialize();
  else Lampa.Listener.follow('app', function (e) { if (e.type === 'ready') initialize(); });
})();
