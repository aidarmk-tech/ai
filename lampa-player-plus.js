/**
 * Lampa Player Plus
 * ---------------------------------------------------------------------------
 * Встроенный плеер для Lampa на базе hls.js.
 *
 * Возможности:
 *   - Движок hls.js вместо нативного HLS в WebView (убирает зависания на ATV)
 *   - Адаптивный буфер с авто-подстройкой под слабое железо
 *   - Сохранение позиции просмотра и продолжение с того же места
 *   - Автопереход к следующей серии с таймером-оверлеем
 *   - Запоминание выбранной аудиодорожки и субтитров на весь сериал
 *   - Скорость воспроизведения 0.5x–2x
 *   - Перемотка пультом ±10/30 сек
 *   - Пропуск интро (ручная отметка, запоминается на сериал)
 *   - Индикатор буфера/качества для диагностики
 *   - Автовосстановление при сетевых/медиа ошибках
 *
 * Подключение: Lampa -> Настройки -> Расширения -> добавить URL этого файла.
 * ---------------------------------------------------------------------------
 */

(function () {
    'use strict';

    // =====================================================================
    //  КОНФИГ
    // =====================================================================

    var PLUGIN_NAME = 'player_plus';
    var HLS_CDN = 'https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js';

    // Профили буфера. Подбираются под мощность устройства.
    // Для 4K-контента (~25 Мбит/с): нужно ≥200 МБ чтобы держать 60с буфер без прерываний.
    var BUFFER_PROFILES = {
        low: {       // слабые ATV-боксы / старый WebView
            maxBufferLength: 40,
            maxMaxBufferLength: 120,
            maxBufferSize: 100 * 1000 * 1000,   // 100 МБ
            backBufferLength: 15
        },
        medium: {    // средние боксы (по умолчанию)
            maxBufferLength: 90,
            maxMaxBufferLength: 300,
            maxBufferSize: 250 * 1000 * 1000,   // 250 МБ — держит 4K 80+ сек
            backBufferLength: 30
        },
        high: {      // мощные боксы / Shield / TV с хорошим железом
            maxBufferLength: 180,
            maxMaxBufferLength: 600,
            maxBufferSize: 600 * 1000 * 1000,   // 600 МБ
            backBufferLength: 60
        }
    };

    var SETTINGS_DEFAULT = {
        engine: 'hls',          // hls | native
        buffer: 'medium',       // low | medium | high
        prebuffer: 20,          // сек пред-буферизации до старта (0 = выкл)
        autonext: true,         // автопереход серий
        autonext_delay: 10,     // сек до перехода
        remember_tracks: true,  // запоминать дорожки на сериал
        skip_intro: true,       // показывать кнопку "пропустить интро"
        cast_bar: true,         // панель "в ролях" (долгое OK)
        audd_token: '',         // токен audd.io для распознавания музыки
        diag: false             // индикатор буфера/качества
    };

    // =====================================================================
    //  ХРАНИЛИЩЕ (на базе Lampa.Storage)
    // =====================================================================

    var Store = {
        _root: 'player_plus_data',

        _all: function () {
            return Lampa.Storage.get(this._root, '{}') || {};
        },
        _save: function (data) {
            Lampa.Storage.set(this._root, data);
        },

        // Ключ ресурса = хэш url (стабильный между сессиями)
        keyOf: function (url) {
            var h = 0, s = String(url || '');
            for (var i = 0; i < s.length; i++) {
                h = ((h << 5) - h) + s.charCodeAt(i);
                h |= 0;
            }
            return 'r' + Math.abs(h);
        },

        // Ключ сериала (для общих настроек дорожек/интро)
        showKey: function (card) {
            if (!card) return null;
            var id = card.id || card.tmdb_id || card.imdb_id || card.original_title || card.title;
            return id ? 's' + String(id).replace(/\W/g, '') : null;
        },

        getPosition: function (url) {
            var rec = this._all()[this.keyOf(url)];
            return rec ? rec : null;
        },
        setPosition: function (url, time, duration) {
            var d = this._all();
            var k = this.keyOf(url);
            d[k] = {
                time: time,
                duration: duration,
                percent: duration ? Math.round((time / duration) * 100) : 0,
                watched: duration ? (time / duration) > 0.9 : false,
                updated: Date.now()
            };
            this._save(d);
        },
        clearPosition: function (url) {
            var d = this._all();
            delete d[this.keyOf(url)];
            this._save(d);
        },

        // Настройки сериала: аудио, субтитры, точка интро
        getShow: function (card) {
            var k = this.showKey(card);
            if (!k) return {};
            return this._all()[k] || {};
        },
        patchShow: function (card, patch) {
            var k = this.showKey(card);
            if (!k) return;
            var d = this._all();
            d[k] = Object.assign({}, d[k] || {}, patch);
            this._save(d);
        }
    };

    function settings() {
        return Object.assign({}, SETTINGS_DEFAULT, Lampa.Storage.get(PLUGIN_NAME + '_settings', '{}'));
    }
    function setSetting(key, val) {
        var s = settings();
        s[key] = val;
        Lampa.Storage.set(PLUGIN_NAME + '_settings', s);
    }

    // =====================================================================
    //  ЗАГРУЗКА hls.js
    // =====================================================================

    var hlsReady = false;
    function loadHls(cb) {
        if (window.Hls) { hlsReady = true; return cb(true); }
        var s = document.createElement('script');
        s.src = HLS_CDN;
        s.onload = function () {
            hlsReady = !!(window.Hls && window.Hls.isSupported());
            cb(hlsReady);
        };
        s.onerror = function () { cb(false); };
        document.head.appendChild(s);
    }

    // =====================================================================
    //  ДВИЖОК ВОСПРОИЗВЕДЕНИЯ
    // =====================================================================

    var Engine = {
        hls: null,
        video: null,
        currentUrl: null,
        currentCard: null,
        saveTimer: null,
        diagTimer: null,
        endedHooked: false,

        isHls: function (url) {
            return /\.m3u8(\?|$)/i.test(String(url || ''));
        },

        // Подмена воспроизведения: вызывается при старте плеера Lampa
        // forceHls=true — пробуем hls.js даже если URL не содержит .m3u8 (для нестандартных балансеров)
        attach: function (video, url, card, forceHls) {
            this.detach();

            this.video = video;
            this.currentUrl = url;
            this.currentCard = card || null;

            var cfg = settings();

            // Восстановление позиции
            var saved = Store.getPosition(url);
            var resumeTo = (saved && saved.time && (!saved.watched)) ? saved.time : 0;

            var tryHls = forceHls || (cfg.engine === 'hls' && this.isHls(url));
            if (tryHls && hlsReady && window.Hls.isSupported()) {
                this._attachHls(video, url, cfg, resumeTo);
            } else {
                this._attachNative(video, url, resumeTo);
            }

            this._applyShowTracks();
            this._startAutosave();
            this._hookEnded();
            if (cfg.diag) this._startDiag();

            PlayerSkin.inject();
            HUD.start();
        },

        _attachHls: function (video, url, cfg, resumeTo) {
            var prof = BUFFER_PROFILES[cfg.buffer] || BUFFER_PROFILES.medium;

            // Сброс видеоэлемента — иначе hls.js не может взять управление у нативного плеера
            try { video.pause(); video.removeAttribute('src'); video.load(); } catch (e) {}

            var hls = new window.Hls({
                enableWorker: true,
                lowLatencyMode: false,
                // Буфер
                maxBufferLength: prof.maxBufferLength,
                maxMaxBufferLength: prof.maxMaxBufferLength,
                maxBufferSize: prof.maxBufferSize,
                backBufferLength: prof.backBufferLength,
                // Стабильность воспроизведения
                maxBufferHole: 0.5,
                highBufferWatchdogPeriod: 3,
                nudgeOffset: 0.2,
                nudgeMaxRetry: 10,
                // Загрузка фрагментов
                fragLoadingMaxRetry: 10,
                fragLoadingMaxRetryTimeout: 64000,
                manifestLoadingMaxRetry: 5,
                levelLoadingMaxRetry: 8,
                levelLoadingRetryDelay: 500,
                // ABR: предполагаем высокий битрейт — hls.js сразу выберет 4K если доступен
                abrEwmaDefaultEstimate: 30 * 1000 * 1000,  // 30 Мбит/с по умолчанию
                abrMaxWithRealBitrate: true,
                startLevel: -1,  // авто, но с учётом abrEwmaDefaultEstimate стартует высоко
                // Совместимость с ATV WebView
                preferManagedMediaSource: false,
                xhrSetup: function (xhr) {
                    try {
                        var hdr = Engine.currentCard && Engine.currentCard.headers;
                        if (hdr) {
                            Object.keys(hdr).forEach(function (k) {
                                xhr.setRequestHeader(k, hdr[k]);
                            });
                        }
                    } catch (e) {}
                }
            });

            this.hls = hls;

            // Правильный порядок по документации hls.js: сначала attachMedia, потом loadSource
            hls.attachMedia(video);
            hls.on(window.Hls.Events.MEDIA_ATTACHED, function () {
                hls.loadSource(url);
            });

            hls.on(window.Hls.Events.MANIFEST_PARSED, function (ev, data) {
                // Двухпроходный выбор уровня качества без HEVC-треков.
                // WebView на ATV не декодирует H.265 аппаратно — 4K-треки почти
                // всегда HEVC, поэтому их нужно явно пропустить.
                if (data && data.levels && data.levels.length > 1) {
                    var levels = data.levels;
                    var bestLevel = -1;

                    // Проход 1: ищем явный AVC-трек сверху вниз (пропускаем HEVC и без codec-инфо)
                    for (var i = levels.length - 1; i >= 0; i--) {
                        var codec = (levels[i].videoCodec || levels[i].codecs || '').toLowerCase();
                        if (!codec) continue; // без инфо — пропускаем в первом проходе
                        if (/hev|hvc|h\.?265|hevc/.test(codec)) continue; // явный HEVC — пропуск
                        if (/avc|h\.?264/.test(codec)) { bestLevel = i; break; } // явный AVC
                        // Неизвестный кодек — проверяем canPlayType
                        try {
                            var cp = document.createElement('video').canPlayType('video/mp4; codecs="' + codec + '"');
                            if (cp === 'probably' || cp === 'maybe') { bestLevel = i; break; }
                        } catch (ex) {}
                    }

                    // Проход 2: нет codec-инфо ни в одном уровне — ограничиваем 1080p.
                    // На ATV без явного кодека 4K-уровень почти всегда HEVC.
                    if (bestLevel < 0) {
                        for (var j = levels.length - 1; j >= 0; j--) {
                            if ((levels[j].height || 9999) <= 1080) { bestLevel = j; break; }
                        }
                    }

                    if (bestLevel >= 0) hls.currentLevel = bestLevel;
                    // bestLevel = -1 только если у всех уровней height > 1080 и нет codec-инфо
                    // → ABR выберет сам, MEDIA_ERROR-хендлер понизит при ошибке
                }
                if (resumeTo > 0) {
                    try { video.currentTime = resumeTo; } catch (e) {}
                }
                // Гейт пред-буферизации: держим паузу пока не наберётся буфер,
                // потом играем. Дальше BufferGate сам ловит просадки (waiting/stalled)
                // и делает один чистый ре-буфер вместо череды микро-фризов.
                var pre = settings().prebuffer | 0;
                if (pre > 0) {
                    BufferGate.start(video, hls, pre, function () {
                        video.play().catch(function () {});
                    });
                } else {
                    video.play().catch(function () {});
                }
            });

            // Автовосстановление ошибок (главное против зависаний)
            var _mediaErrCount = 0;
            hls.on(window.Hls.Events.ERROR, function (event, data) {
                if (!data.fatal) return;
                switch (data.type) {
                    case window.Hls.ErrorTypes.NETWORK_ERROR:
                        Engine._notify('Сеть: восстановление…');
                        hls.startLoad();
                        break;
                    case window.Hls.ErrorTypes.MEDIA_ERROR:
                        _mediaErrCount++;
                        if (_mediaErrCount === 1) {
                            // Первая попытка: стандартное восстановление
                            Engine._notify('Медиа: восстановление…');
                            hls.recoverMediaError();
                        } else if (_mediaErrCount <= 3 && hls.currentLevel > 0) {
                            // Повторные ошибки — понижаем качество (кодек несовместим)
                            hls.currentLevel = hls.currentLevel - 1;
                            Engine._notify('Кодек несовместим, понижаю качество…');
                            hls.recoverMediaError();
                        } else {
                            // Сдаёмся — переключаем на нативный
                            Engine._notify('Ошибка декодера, нативный режим');
                            Engine._attachNative(video, url, video.currentTime || 0);
                            hls.destroy(); Engine.hls = null;
                        }
                        break;
                    default:
                        Engine._notify('Ошибка потока, переключаюсь на нативный движок');
                        Engine._attachNative(video, url, video.currentTime || 0);
                        hls.destroy();
                        Engine.hls = null;
                }
            });
        },

        _attachNative: function (video, url, resumeTo) {
            try { video.src = url; } catch (e) {}
            var onMeta = function () {
                if (resumeTo > 0) {
                    try { video.currentTime = resumeTo; } catch (e) {}
                }
                video.play().catch(function () {});
                video.removeEventListener('loadedmetadata', onMeta);
            };
            video.addEventListener('loadedmetadata', onMeta);
        },

        // Применить запомненные дорожки сериала
        _applyShowTracks: function () {
            if (!settings().remember_tracks || !this.hls || !this.currentCard) return;
            var show = Store.getShow(this.currentCard);
            var hls = this.hls;
            setTimeout(function () {
                try {
                    if (show.audio != null && hls.audioTracks && hls.audioTracks.length > show.audio) {
                        hls.audioTrack = show.audio;
                    }
                    if (show.subtitle != null && hls.subtitleTracks && hls.subtitleTracks.length > show.subtitle) {
                        hls.subtitleTrack = show.subtitle;
                    }
                } catch (e) {}
            }, 1200);
        },

        rememberAudio: function (index) {
            if (settings().remember_tracks && this.currentCard) {
                Store.patchShow(this.currentCard, { audio: index });
            }
        },
        rememberSubtitle: function (index) {
            if (settings().remember_tracks && this.currentCard) {
                Store.patchShow(this.currentCard, { subtitle: index });
            }
        },

        // Периодическое сохранение позиции
        _startAutosave: function () {
            var self = this;
            this._stopAutosave();
            this.saveTimer = setInterval(function () {
                var v = self.video;
                if (v && !v.paused && v.currentTime > 0 && isFinite(v.duration)) {
                    Store.setPosition(self.currentUrl, v.currentTime, v.duration);
                }
            }, 5000);
        },
        _stopAutosave: function () {
            if (this.saveTimer) { clearInterval(this.saveTimer); this.saveTimer = null; }
        },

        // Финальное сохранение + автопереход
        _hookEnded: function () {
            var self = this;
            if (!this.video || this.endedHooked) return;
            this.endedHooked = true;

            this.video.addEventListener('timeupdate', function () {
                var v = self.video;
                if (!v || !isFinite(v.duration)) return;
                // показать кнопку автоперехода за autonext_delay сек до конца
                if (settings().autonext && (v.duration - v.currentTime) <= settings().autonext_delay) {
                    UI.showAutoNext();
                }
            });

            this.video.addEventListener('ended', function () {
                Store.setPosition(self.currentUrl, self.video.duration, self.video.duration);
                if (settings().autonext) self.playNext();
            });
        },

        playNext: function () {
            try {
                if (Lampa.Player && typeof Lampa.Player.next === 'function') {
                    Lampa.Player.next();
                }
            } catch (e) {}
        },

        // Перемотка
        seekBy: function (sec) {
            if (this.video && isFinite(this.video.duration)) {
                this.video.currentTime = Math.max(0, Math.min(
                    this.video.duration, this.video.currentTime + sec
                ));
            }
        },

        setRate: function (rate) {
            if (this.video) this.video.playbackRate = rate;
        },

        // Отметить точку интро (для пропуска на следующих сериях)
        markIntro: function () {
            if (this.video && this.currentCard) {
                Store.patchShow(this.currentCard, { intro_end: this.video.currentTime });
                this._notify('Точка интро сохранена: ' + Math.round(this.video.currentTime) + 'с');
            }
        },
        skipIntro: function () {
            var show = Store.getShow(this.currentCard);
            if (show.intro_end && this.video) {
                this.video.currentTime = show.intro_end;
            }
        },

        // Диагностика
        _startDiag: function () {
            var self = this;
            this._stopDiag();
            this.diagTimer = setInterval(function () {
                if (!self.video) return;
                var buf = 0;
                try {
                    var b = self.video.buffered;
                    if (b.length) buf = b.end(b.length - 1) - self.video.currentTime;
                } catch (e) {}
                var lvl = '';
                if (self.hls && self.hls.levels && self.hls.currentLevel >= 0) {
                    var L = self.hls.levels[self.hls.currentLevel];
                    if (L) lvl = (L.height ? L.height + 'p ' : '') + Math.round((L.bitrate || 0) / 1000) + 'kbps';
                }
                UI.updateDiag('Буфер: ' + buf.toFixed(1) + 'с  ' + lvl);
            }, 1000);
        },
        _stopDiag: function () {
            if (this.diagTimer) { clearInterval(this.diagTimer); this.diagTimer = null; }
            UI.updateDiag('');
        },

        _notify: function (msg) {
            try { Lampa.Noty.show(msg); } catch (e) {}
        },

        detach: function () {
            if (this.video && this.currentUrl && isFinite(this.video.duration) && this.video.currentTime > 0) {
                Store.setPosition(this.currentUrl, this.video.currentTime, this.video.duration);
            }
            this._stopAutosave();
            this._stopDiag();
            HUD.stop();
            BufferGate.stop();
            CastBar.hide();
            Shazam.stop();
            if (this.hls) {
                try { this.hls.destroy(); } catch (e) {}
                this.hls = null;
            }
            this.video = null;
            this.currentUrl = null;
            this.endedHooked = false;
        }
    };

    // =====================================================================
    //  UI ОВЕРЛЕИ (автопереход, диагностика, кнопка интро)
    // =====================================================================

    var UI = {
        autonext_shown: false,

        showAutoNext: function () {
            if (this.autonext_shown) return;
            this.autonext_shown = true;
            var delay = settings().autonext_delay;

            var el = document.createElement('div');
            el.id = 'pp-autonext';
            el.style.cssText = 'position:fixed;right:3em;bottom:6em;z-index:9999;' +
                'background:rgba(0,0,0,.8);color:#fff;padding:1em 1.5em;border-radius:.6em;' +
                'font-size:1.2em;cursor:pointer;';
            el.innerHTML = 'Следующая серия через <b>' + delay + '</b> сек';
            document.body.appendChild(el);

            var left = delay;
            var t = setInterval(function () {
                left--;
                var b = el.querySelector('b');
                if (b) b.textContent = left;
                if (left <= 0) { clearInterval(t); UI.hideAutoNext(); Engine.playNext(); }
            }, 1000);

            el.addEventListener('click', function () {
                clearInterval(t); UI.hideAutoNext(); Engine.playNext();
            });
            this._autonext_timer = t;
        },
        hideAutoNext: function () {
            var el = document.getElementById('pp-autonext');
            if (el) el.remove();
            if (this._autonext_timer) clearInterval(this._autonext_timer);
            this.autonext_shown = false;
        },

        updateDiag: function (text) {
            var el = document.getElementById('pp-diag');
            if (!text) { if (el) el.remove(); return; }
            if (!el) {
                el = document.createElement('div');
                el.id = 'pp-diag';
                el.style.cssText = 'position:fixed;left:1em;top:1em;z-index:9999;' +
                    'background:rgba(0,0,0,.6);color:#0f0;padding:.3em .6em;' +
                    'border-radius:.3em;font-size:.85em;font-family:monospace;';
                document.body.appendChild(el);
            }
            el.textContent = text;
        }
    };

    // =====================================================================
    //  СТИЛЬ ПЛЕЕРА (CSS-инъекция)
    // =====================================================================

    var PlayerSkin = {
        _el: null,
        inject: function () {
            if (this._el) return;
            var css = [
                // ── Нижняя панель ────────────────────────────────────────────
                // Убираем сплошной чёрный фон — заменяем на градиент-тень снизу
                '.player-panel{',
                    'background:linear-gradient(to top,rgba(0,0,0,.92) 0%,rgba(0,0,0,.55) 55%,transparent 100%)!important;',
                    'padding-bottom:.4em!important;',
                '}',
                // Верхняя "плашка" если есть (название + время сверху)
                '.player-panel--top,.player-panel__top{',
                    'background:linear-gradient(to bottom,rgba(0,0,0,.75) 0%,transparent 100%)!important;',
                '}',

                // ── Прогресс-бар ──────────────────────────────────────────────
                '.player-timeline,.player-panel__timeline{',
                    'height:4px!important;margin:.6em 0!important;',
                '}',
                // Фоновая дорожка
                '.player-timeline__peding,.player-timeline--peding,.player-timeline > i:first-child{',
                    'background:rgba(255,255,255,.14)!important;border-radius:4px!important;',
                '}',
                // Буфер
                '.player-timeline__buffer,.player-timeline--buffer{',
                    'background:rgba(255,255,255,.3)!important;border-radius:4px!important;',
                '}',
                // Просмотрено — синий/фиолетовый градиент
                '.player-timeline__position,.player-timeline--position,.player-timeline__progress{',
                    'background:linear-gradient(90deg,#3b82f6 0%,#8b5cf6 100%)!important;',
                    'border-radius:4px!important;',
                '}',
                // Маркер — светящийся белый кружок
                '.player-timeline__marker,.player-timeline--marker{',
                    'width:14px!important;height:14px!important;top:-5px!important;',
                    'background:#fff!important;border-radius:50%!important;',
                    'box-shadow:0 0 0 3px rgba(139,92,246,.5),0 0 12px rgba(59,130,246,.95)!important;',
                    'transition:transform .1s!important;',
                '}',
                '.player-timeline:hover .player-timeline__marker{transform:scale(1.35)!important;}',

                // ── Время ────────────────────────────────────────────────────
                '.player-panel__time,.player-panel--time,.player-panel .time{',
                    'font-weight:600!important;opacity:.75!important;',
                    'font-variant-numeric:tabular-nums!important;',
                    'font-size:.85em!important;letter-spacing:.01em!important;',
                '}',

                // ── Название эпизода ──────────────────────────────────────────
                '.player-panel__title,.player-panel--title,.player-info__title,.player-panel .title{',
                    'font-weight:700!important;letter-spacing:.02em!important;',
                    'text-shadow:0 1px 12px rgba(0,0,0,.95)!important;',
                '}',

                // ── Кнопки: нормальное состояние ─────────────────────────────
                '.player-panel svg,.player-panel .icon{',
                    'opacity:.75!important;transition:opacity .15s,filter .15s!important;',
                '}',
                // Фокус/активная кнопка — синий glow
                '.player-panel .focus svg,.player-panel .active svg,',
                '.player-panel .selector.focus svg,.player-panel .focus .icon{',
                    'opacity:1!important;',
                    'filter:drop-shadow(0 0 6px #60a5fa) drop-shadow(0 0 2px #3b82f6)!important;',
                '}',

                // ── Кнопка качества (FHD / HD и т.д.) ────────────────────────
                '.player-panel .player-panel__quality,.player-panel__quality{',
                    'background:linear-gradient(135deg,rgba(59,130,246,.55),rgba(139,92,246,.55))!important;',
                    'border:1px solid rgba(96,165,250,.5)!important;',
                    'border-radius:.3rem!important;padding:.1rem .35rem!important;',
                    'font-weight:700!important;font-size:.72rem!important;letter-spacing:.05em!important;',
                    'color:#fff!important;',
                '}',

                // ── Убираем outline у кнопок ──────────────────────────────────
                '.player-panel *:focus{outline:none!important;}',
            ].join('');
            var el = document.createElement('style');
            el.id = 'pp-skin';
            el.textContent = css;
            document.head.appendChild(el);
            this._el = el;
        },
        remove: function () {
            if (this._el) { try { this._el.remove(); } catch (e) {} this._el = null; }
        }
    };

    // =====================================================================
    //  HUD — качество / скорость / пропустить интро
    // =====================================================================

    var HUD = {
        _el: null,
        _badge: null,
        _speedBadge: null,
        _introBadge: null,
        _hideTimer: null,
        _qualLoop: null,
        _active: false,
        speeds: [0.5, 0.75, 1, 1.25, 1.5, 2],
        speedIdx: 2,    // индекс = 1×

        _badgeCSS: [
            'display:inline-flex;align-items:center;',
            'padding:.22rem .55rem;border-radius:.35rem;',
            'font-size:.7rem;font-weight:700;letter-spacing:.06em;',
            'font-family:system-ui,-apple-system,sans-serif;',
            'background:rgba(0,0,0,.72);color:rgba(255,255,255,.88);',
            'border:1px solid rgba(255,255,255,.12);',
            'backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);',
        ].join(''),

        _build: function () {
            if (this._el) return;
            var wrap = document.createElement('div');
            wrap.id = 'pp-hud';
            wrap.style.cssText = [
                'position:fixed;top:1.3rem;right:1.5rem;',
                'display:flex;align-items:center;gap:.45rem;',
                'z-index:9998;transition:opacity .35s;pointer-events:none;',
            ].join('');

            var qual = document.createElement('span');
            qual.id = 'pp-hud-qual';
            qual.style.cssText = this._badgeCSS;
            qual.textContent = '···';

            var spd = document.createElement('span');
            spd.id = 'pp-hud-speed';
            spd.style.cssText = this._badgeCSS + 'display:none;background:rgba(59,130,246,.7);border-color:transparent;';
            spd.textContent = '1×';

            var intro = document.createElement('span');
            intro.id = 'pp-hud-intro';
            intro.style.cssText = this._badgeCSS + [
                'display:none;cursor:pointer;pointer-events:auto;',
                'background:linear-gradient(90deg,rgba(59,130,246,.8),rgba(139,92,246,.8));',
                'border-color:transparent;color:#fff;',
            ].join('');
            intro.textContent = '▶▶  ПРОПУСТИТЬ ИНТРО';
            intro.title = 'Долгое OK → актёры';
            intro.addEventListener('click', function () { Engine.skipIntro(); });

            wrap.appendChild(qual);
            wrap.appendChild(spd);
            wrap.appendChild(intro);
            document.body.appendChild(wrap);

            this._el = wrap;
            this._badge = qual;
            this._speedBadge = spd;
            this._introBadge = intro;
        },

        start: function () {
            this._active = true;
            this.speedIdx = 2;
            this._build();
            this._el.style.opacity = '1';
            this._updateQuality();
            this._schedHide();
            this._startLoop();
        },

        stop: function () {
            this._active = false;
            this._stopLoop();
            clearTimeout(this._hideTimer);
            if (this._el) { try { this._el.remove(); } catch (e) {} }
            this._el = this._badge = this._speedBadge = this._introBadge = null;
        },

        peek: function () {
            if (!this._active || !this._el) return;
            this._el.style.opacity = '1';
            this._schedHide();
        },

        _schedHide: function () {
            clearTimeout(this._hideTimer);
            this._hideTimer = setTimeout(function () {
                if (HUD._el) HUD._el.style.opacity = '0';
            }, 4000);
        },

        cycleSpeed: function () {
            this.speedIdx = (this.speedIdx + 1) % this.speeds.length;
            var s = this.speeds[this.speedIdx];
            Engine.setRate(s);
            if (this._speedBadge) {
                this._speedBadge.textContent = s + '×';
                this._speedBadge.style.display = (s === 1) ? 'none' : '';
            }
            try { Lampa.Noty.show('Скорость: ' + s + '×'); } catch (e) {}
            this.peek();
        },

        _updateQuality: function () {
            if (!this._badge) return;
            var txt = '';
            if (Engine.hls && Engine.hls.levels && Engine.hls.currentLevel >= 0) {
                var L = Engine.hls.levels[Engine.hls.currentLevel];
                if (L) {
                    var h = L.height || 0;
                    var kbps = Math.round((L.bitrate || 0) / 1000);
                    var lbl = h >= 2160 ? '4K' : h >= 1440 ? '2K' : h >= 1080 ? 'FHD' : h >= 720 ? 'HD' : h ? h + 'p' : '';
                    txt = (lbl || 'AUTO') + (kbps ? '  ·  ' + kbps + ' kbps' : '');
                }
            } else if (Engine.video) {
                var vw = Engine.video.videoWidth, vh = Engine.video.videoHeight;
                if (vh) {
                    txt = vh >= 2160 ? '4K' : vh >= 1440 ? '2K' : vh >= 1080 ? 'FHD' : vh >= 720 ? 'HD' : vh + 'p';
                }
            }
            this._badge.textContent = txt || 'HLS';
        },

        _checkIntro: function () {
            if (!this._introBadge || !Engine.currentCard) return;
            var show = Store.getShow(Engine.currentCard);
            var v = Engine.video;
            var visible = !!(show.intro_end && v && v.currentTime < show.intro_end - 2 && v.currentTime > 2);
            this._introBadge.style.display = visible ? '' : 'none';
        },

        _startLoop: function () {
            var self = this;
            this._stopLoop();
            this._qualLoop = setInterval(function () {
                self._updateQuality();
                self._checkIntro();
            }, 2000);
        },
        _stopLoop: function () {
            if (this._qualLoop) { clearInterval(this._qualLoop); this._qualLoop = null; }
        }
    };

    // =====================================================================
    //  BUFFER GATE — пред-буферизация и чистый ре-буфер при просадках
    // =====================================================================
    //  Высокобитрейтные сцены (тёмный зал, плёночное зерно) выкачивают буфер
    //  быстрее, чем он скачивается → череда микро-фризов. Решение: держать
    //  паузу пока не накопится N сек буфера, затем играть. При просадке —
    //  один аккуратный ре-буфер вместо постоянных подёргиваний.

    function bufferedAhead(v) {
        try {
            var t = v.currentTime, b = v.buffered;
            for (var i = 0; i < b.length; i++) {
                if (t >= b.start(i) - 0.5 && t <= b.end(i) + 0.5) return b.end(i) - t;
            }
        } catch (e) {}
        return 0;
    }

    var BufferGate = {
        video: null, hls: null,
        _poll: null, _rebufPoll: null,
        _onWaiting: null, _gating: false,

        // Первичный гейт: держим паузу пока не наберётся target сек, потом onReady()
        start: function (video, hls, target, onReady) {
            this.stop();
            this.video = video; this.hls = hls;
            var self = this;
            try { video.pause(); } catch (e) {}
            this._show(0, 'Буферизация');
            this._poll = setInterval(function () {
                var ahead = bufferedAhead(video);
                self._show(Math.min(100, Math.round(ahead / target * 100)), 'Буферизация');
                var almostEnd = isFinite(video.duration) && ahead >= (video.duration - video.currentTime - 1);
                if (ahead >= target || almostEnd) {
                    clearInterval(self._poll); self._poll = null;
                    self._hide();
                    onReady();
                    self._hookRebuffer(target);
                }
            }, 250);
        },

        // После старта ловим просадки буфера и делаем один чистый ре-буфер
        _hookRebuffer: function (target) {
            var self = this, v = this.video;
            var resumeAt = Math.max(6, Math.round(target * 0.5)); // порог возобновления
            this._onWaiting = function () {
                if (self._gating) return;
                if (bufferedAhead(v) >= 2) return; // ложная тревога — буфер ещё есть
                self._gating = true;
                try { v.pause(); } catch (e) {}
                self._show(0, 'Догружаю буфер');
                self._rebufPoll = setInterval(function () {
                    var ahead = bufferedAhead(v);
                    self._show(Math.min(100, Math.round(ahead / resumeAt * 100)), 'Догружаю буфер');
                    var almostEnd = isFinite(v.duration) && ahead >= (v.duration - v.currentTime - 1);
                    if (ahead >= resumeAt || almostEnd) {
                        clearInterval(self._rebufPoll); self._rebufPoll = null;
                        self._gating = false;
                        self._hide();
                        v.play().catch(function () {});
                    }
                }, 250);
            };
            v.addEventListener('waiting', this._onWaiting);
            v.addEventListener('stalled', this._onWaiting);
        },

        _show: function (pct, label) {
            var el = document.getElementById('pp-buf');
            if (!el) {
                el = document.createElement('div');
                el.id = 'pp-buf';
                el.style.cssText = [
                    'position:fixed;top:0;left:0;width:100%;height:100%;z-index:9997;',
                    'display:flex;flex-direction:column;align-items:center;justify-content:center;',
                    'gap:1.1rem;background:rgba(0,0,0,.35);pointer-events:none;',
                    'font-family:system-ui,-apple-system,sans-serif;'
                ].join('');
                el.innerHTML = [
                    '<div id="pp-buf-ring" style="width:3.6rem;height:3.6rem;border-radius:50%;',
                        'border:3px solid rgba(255,255,255,.15);border-top-color:#60a5fa;',
                        'animation:pp-spin .8s linear infinite;"></div>',
                    '<div style="display:flex;flex-direction:column;align-items:center;gap:.3rem;">',
                        '<div id="pp-buf-lbl" style="color:#fff;font-weight:700;font-size:.95rem;',
                            'letter-spacing:.04em;text-shadow:0 1px 8px rgba(0,0,0,.9);"></div>',
                        '<div id="pp-buf-pct" style="color:#60a5fa;font-weight:700;font-size:.8rem;',
                            'font-variant-numeric:tabular-nums;"></div>',
                    '</div>'
                ].join('');
                if (!document.getElementById('pp-buf-kf')) {
                    var st = document.createElement('style');
                    st.id = 'pp-buf-kf';
                    st.textContent = '@keyframes pp-spin{to{transform:rotate(360deg)}}';
                    document.head.appendChild(st);
                }
                document.body.appendChild(el);
            }
            var l = document.getElementById('pp-buf-lbl');
            var p = document.getElementById('pp-buf-pct');
            if (l) l.textContent = label || 'Буферизация';
            if (p) p.textContent = (pct || 0) + '%';
        },
        _hide: function () {
            var el = document.getElementById('pp-buf');
            if (el) el.remove();
        },

        stop: function () {
            if (this._poll) { clearInterval(this._poll); this._poll = null; }
            if (this._rebufPoll) { clearInterval(this._rebufPoll); this._rebufPoll = null; }
            if (this.video && this._onWaiting) {
                this.video.removeEventListener('waiting', this._onWaiting);
                this.video.removeEventListener('stalled', this._onWaiting);
            }
            this._onWaiting = null; this._gating = false;
            this._hide();
            this.video = null; this.hls = null;
        }
    };

    // =====================================================================
    //  CAST BAR — "в ролях" по данным TMDB (долгое OK)
    // =====================================================================

    var CastBar = {
        _cache: {},
        _visible: false,
        _hideTimer: null,

        _firstShown: false,
        toggle: function () {
            if (!settings().cast_bar) return;
            if (this._visible) this.hide();
            else {
                this.show();
                if (!this._firstShown) {
                    this._firstShown = true;
                    setTimeout(function () {
                        try { Lampa.Noty.show('В ролях: долгое OK — открыть/закрыть'); } catch (e) {}
                    }, 600);
                }
            }
        },

        show: function () {
            var card = Engine.currentCard;
            // Пробуем найти карточку с TMDB ID агрессивнее:
            // при старте плеера передаётся "файл", а не полная карточка тайтла
            if (!card || !(card.id || card.tmdb_id)) {
                try { if (Lampa.Player && Lampa.Player.card) card = Lampa.Player.card; } catch (e) {}
            }
            if (!card || !(card.id || card.tmdb_id)) {
                try { var act = Lampa.Activity.active(); if (act) card = act.card || act.movie || act; } catch (e) {}
            }
            // Иногда id лежит внутри вложенного movie/show объекта
            if (card && !(card.id || card.tmdb_id)) card = card.movie || card.show || card.card || card;

            if (!card || !(card.id || card.tmdb_id)) {
                try { Lampa.Noty.show('Нет данных TMDB для этого тайтла'); } catch (e) {}
                return;
            }
            this._visible = true;
            this._render('Загрузка состава…', []);
            var self = this;
            this._fetch(card, function (list) {
                if (!self._visible) return;
                if (!list || !list.length) { self._render('Актёры не найдены', []); }
                else self._render(null, list);
                self._schedHide();
            });
        },

        hide: function () {
            this._visible = false;
            clearTimeout(this._hideTimer);
            var el = document.getElementById('pp-cast');
            if (el) el.remove();
        },

        _schedHide: function () {
            clearTimeout(this._hideTimer);
            this._hideTimer = setTimeout(function () { CastBar.hide(); }, 14000);
        },

        _fetch: function (card, cb) {
            var id = card.id || card.tmdb_id;
            var type = card.media_type || (card.number_of_seasons || card.first_air_date ? 'tv' : 'movie');
            var key = type + id;
            if (this._cache[key]) return cb(this._cache[key]);
            try {
                var lang = Lampa.Storage.get('language') || 'ru';
                var url = Lampa.TMDB.api(type + '/' + id + '/credits?api_key=' + Lampa.TMDB.key() + '&language=' + lang);
                var net = new Lampa.Reguest();
                net.silent(url, function (data) {
                    var cast = (data && data.cast) ? data.cast : [];
                    var list = cast.slice(0, 15).map(function (p) {
                        return {
                            name: p.name || '',
                            role: p.character || '',
                            img: p.profile_path ? Lampa.TMDB.image('t/p/w200' + p.profile_path) : ''
                        };
                    });
                    CastBar._cache[key] = list;
                    cb(list);
                }, function () { cb([]); });
            } catch (e) { cb([]); }
        },

        _render: function (loadingText, list) {
            var el = document.getElementById('pp-cast');
            if (!el) {
                el = document.createElement('div');
                el.id = 'pp-cast';
                el.style.cssText = [
                    'position:fixed;left:0;right:0;bottom:0;z-index:9996;',
                    'padding:1rem 1.4rem 1.2rem;',
                    'background:linear-gradient(to top,rgba(0,0,0,.95) 0%,rgba(0,0,0,.6) 70%,transparent 100%);',
                    'font-family:system-ui,-apple-system,sans-serif;',
                    'animation:pp-castin .25s ease both;pointer-events:none;'
                ].join('');
                if (!document.getElementById('pp-cast-kf')) {
                    var st = document.createElement('style');
                    st.id = 'pp-cast-kf';
                    st.textContent = '@keyframes pp-castin{from{opacity:0;transform:translateY(20px)}to{opacity:1;transform:none}}';
                    document.head.appendChild(st);
                }
                document.body.appendChild(el);
            }
            var head = '<div style="color:rgba(255,255,255,.55);font-size:.7rem;font-weight:700;' +
                'letter-spacing:.12em;margin-bottom:.7rem;">В РОЛЯХ</div>';
            if (loadingText) {
                el.innerHTML = head + '<div style="color:rgba(255,255,255,.6);font-size:.85rem;">' +
                    loadingText + '</div>';
                return;
            }
            var cards = list.map(function (p) {
                var ph = p.img
                    ? '<div style="width:3.2rem;height:3.2rem;border-radius:50%;flex-shrink:0;' +
                        'background:#222 center/cover url(' + p.img + ');' +
                        'box-shadow:0 0 0 2px rgba(96,165,250,.35);"></div>'
                    : '<div style="width:3.2rem;height:3.2rem;border-radius:50%;flex-shrink:0;' +
                        'background:linear-gradient(135deg,#3b82f6,#7c3aed);display:flex;' +
                        'align-items:center;justify-content:center;color:#fff;font-weight:700;">' +
                        (p.name ? p.name.charAt(0) : '?') + '</div>';
                return '<div style="display:flex;align-items:center;gap:.6rem;flex-shrink:0;' +
                    'background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.08);' +
                    'border-radius:.7rem;padding:.45rem .8rem .45rem .45rem;">' + ph +
                    '<div style="min-width:0;"><div style="color:#fff;font-weight:600;font-size:.82rem;' +
                    'white-space:nowrap;">' + p.name + '</div>' +
                    (p.role ? '<div style="color:rgba(255,255,255,.45);font-size:.7rem;white-space:nowrap;">' +
                        p.role + '</div>' : '') + '</div></div>';
            }).join('');
            el.innerHTML = head +
                '<div style="display:flex;gap:.6rem;overflow-x:auto;padding-bottom:.3rem;' +
                'scrollbar-width:none;">' + cards + '</div>';
        }
    };

    // =====================================================================
    //  SHAZAM — распознавание музыки через AudD API
    // =====================================================================

    function _escHtml(s) {
        return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    var Shazam = {
        _ctx:       null,
        _src:       null,   // MediaElementSourceNode (держим, т.к. нельзя пересоздать)
        _proc:      null,   // ScriptProcessorNode
        _samples:   [],
        _capturing: false,
        _timeout:   null,
        _overlay:   null,

        capture: function () {
            if (this._capturing) return;
            var video = Engine.video;
            if (!video) return;

            // Сбросить ноды если видеоэлемент сменился (новый фильм)
            if (this._srcVideo && this._srcVideo !== video) {
                if (this._proc) { try { this._proc.disconnect(); } catch (e) {} this._proc = null; }
                if (this._src)  { try { this._src.disconnect();  } catch (e) {} this._src = null; }
                this._srcVideo = null;
            }

            this._samples = [];
            this._capturing = true;
            this._showStatus('Слушаю…  (10 сек)');

            try {
                if (!this._ctx) {
                    this._ctx = new (window.AudioContext || window.webkitAudioContext)();
                }
                // Autoplay policy: разбудить контекст если suspend
                if (this._ctx.state === 'suspended') {
                    try { this._ctx.resume(); } catch (e) {}
                }

                if (!this._src) {
                    // captureStream() предпочтительнее с hls.js (MSE-источник).
                    // createMediaElementSource() бросает SecurityError на MSE-видео в некоторых WebView.
                    var cs = video.captureStream || video.mozCaptureStream;
                    if (typeof cs === 'function') {
                        var stream = cs.call(video);
                        this._src = this._ctx.createMediaStreamSource(stream);
                        this._captureMode = 'stream';
                    } else {
                        // Fallback: видео переходит полностью под AudioContext
                        // путь: video → MediaElementSource → ScriptProcessor → destination
                        this._src = this._ctx.createMediaElementSource(video);
                        this._captureMode = 'element';
                    }
                    this._srcVideo = video;
                }

                var sampleRate = this._ctx.sampleRate;
                var maxSamples = sampleRate * 10;
                var self = this;

                this._proc = this._ctx.createScriptProcessor(4096, 1, 1);
                this._src.connect(this._proc);

                if (this._captureMode === 'stream') {
                    // captureStream: видео играет через элемент как обычно.
                    // ScriptProcessor нужно подключить к destination (иначе onaudioprocess не срабатывает),
                    // но через нулевой gain чтобы не дублировать звук.
                    if (!this._silentGain) {
                        this._silentGain = this._ctx.createGain();
                        this._silentGain.gain.value = 0;
                        this._silentGain.connect(this._ctx.destination);
                    }
                    this._proc.connect(this._silentGain);
                } else {
                    // element mode: весь звук идёт через AudioContext
                    // один путь: src → proc → destination (без дублирования)
                    this._proc.connect(this._ctx.destination);
                }

                this._proc.onaudioprocess = function (e) {
                    if (!self._capturing) return;
                    var ch = e.inputBuffer.getChannelData(0);
                    for (var i = 0; i < ch.length && self._samples.length < maxSamples; i++) {
                        self._samples.push(ch[i]);
                    }
                    if (self._samples.length >= maxSamples) {
                        self._finish(sampleRate);
                    }
                };

                this._timeout = setTimeout(function () {
                    if (self._capturing) self._finish(sampleRate);
                }, 12000);

            } catch (ex) {
                this._capturing = false;
                var errMsg = ex && ex.name ? ex.name : 'Ошибка';
                this._showStatus('Аудио недоступно (' + errMsg + ')');
                var self = this;
                setTimeout(function () { self._hideOverlay(); }, 4000);
            }
        },

        _finish: function (sampleRate) {
            this._capturing = false;
            clearTimeout(this._timeout);
            if (this._proc) {
                try { this._proc.disconnect(); } catch (e) {}
                this._proc.onaudioprocess = null;
                this._proc = null;
            }
            if (!this._samples.length) { this._hideOverlay(); return; }
            this._showStatus('Распознаю…');
            var wav = this._toWav(this._samples, sampleRate);
            this._samples = [];
            this._recognize(wav);
        },

        _toWav: function (samples, sr) {
            var len = samples.length;
            var buf = new ArrayBuffer(44 + len * 2);
            var v   = new DataView(buf);
            var ws  = function (off, s) { for (var i = 0; i < s.length; i++) v.setUint8(off + i, s.charCodeAt(i)); };
            ws(0,  'RIFF'); v.setUint32(4,  36 + len * 2, true);
            ws(8,  'WAVE'); ws(12, 'fmt ');
            v.setUint32(16, 16, true);    // chunk size
            v.setInt16(20,  1,  true);    // PCM
            v.setInt16(22,  1,  true);    // mono
            v.setUint32(24, sr, true);
            v.setUint32(28, sr * 2, true);
            v.setInt16(32,  2,  true);    // block align
            v.setInt16(34, 16, true);     // bits
            ws(36, 'data'); v.setUint32(40, len * 2, true);
            for (var i = 0; i < len; i++) {
                var s = Math.max(-1, Math.min(1, samples[i]));
                v.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
            }
            return new Blob([buf], { type: 'audio/wav' });
        },

        _recognize: function (wav) {
            var token = settings().audd_token || '';
            if (!token) {
                var entered = '';
                try { entered = (window.prompt('AudD-токен (audd.io):', '') || '').trim(); } catch (e) {}
                if (!entered) {
                    this._showStatus('Токен не введён — Shazam недоступен');
                    var self = this;
                    setTimeout(function () { self._hideOverlay(); }, 4000);
                    return;
                }
                setSetting('audd_token', entered);
                token = entered;
            }
            var fd = new FormData();
            fd.append('file', wav, 'clip.wav');
            fd.append('api_token', token);
            fd.append('return', 'spotify,apple_music');
            var self = this;
            var xhr = new XMLHttpRequest();
            xhr.open('POST', 'https://api.audd.io/', true);
            xhr.timeout = 15000;
            xhr.onload = function () {
                try {
                    var res = JSON.parse(xhr.responseText);
                    if (res.status === 'success' && res.result) {
                        var r = res.result;
                        self._showResult(r.artist, r.title, r.album,
                            (r.spotify && r.spotify.album && r.spotify.album.images &&
                             r.spotify.album.images[0] && r.spotify.album.images[0].url) ||
                            (r.apple_music && r.apple_music.artwork && r.apple_music.artwork.url &&
                             r.apple_music.artwork.url.replace('{w}', '200').replace('{h}', '200')) || '');
                    } else {
                        self._showStatus('Музыка не распознана');
                        setTimeout(function () { self._hideOverlay(); }, 3000);
                    }
                } catch (ex) {
                    self._showStatus('Ошибка ответа сервера');
                    setTimeout(function () { self._hideOverlay(); }, 3000);
                }
            };
            xhr.onerror = xhr.ontimeout = function () {
                self._showStatus('Нет связи с AudD');
                setTimeout(function () { self._hideOverlay(); }, 3000);
            };
            xhr.send(fd);
        },

        _showStatus: function (text) {
            this._showOverlay(
                '<div style="font-size:2.5rem;margin-bottom:.9rem;">🎵</div>' +
                '<div style="color:#fff;font-size:.95rem;font-weight:600;">' + _escHtml(text) + '</div>'
            );
        },

        _showResult: function (artist, title, album, imgUrl) {
            var img = imgUrl
                ? '<img src="' + imgUrl + '" style="width:5.5rem;height:5.5rem;border-radius:.7rem;' +
                  'object-fit:cover;margin-bottom:.9rem;box-shadow:0 4px 20px rgba(0,0,0,.55);">'
                : '<div style="font-size:3rem;margin-bottom:.9rem;">🎵</div>';
            var html = img +
                '<div style="color:#60a5fa;font-size:.65rem;font-weight:700;letter-spacing:.14em;' +
                'margin-bottom:.5rem;">SHAZAM</div>' +
                '<div style="color:#fff;font-size:1.05rem;font-weight:700;margin-bottom:.25rem;' +
                'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:260px;">' +
                _escHtml(title || '—') + '</div>' +
                '<div style="color:rgba(255,255,255,.6);font-size:.82rem;white-space:nowrap;' +
                'overflow:hidden;text-overflow:ellipsis;max-width:260px;">' + _escHtml(artist || '') + '</div>' +
                (album ? '<div style="color:rgba(255,255,255,.38);font-size:.72rem;margin-top:.2rem;' +
                'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:260px;">' +
                _escHtml(album) + '</div>' : '');
            this._showOverlay(html);
            var self = this;
            setTimeout(function () { self._hideOverlay(); }, 8000);
        },

        _showOverlay: function (html) {
            this._hideOverlay();
            var el = document.createElement('div');
            el.id = 'pp-shazam';
            el.style.cssText = [
                'position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);z-index:9998;',
                'background:rgba(12,12,22,.93);border-radius:1.3rem;',
                'border:1px solid rgba(96,165,250,.18);',
                'padding:2rem 2.8rem;text-align:center;min-width:260px;',
                'font-family:system-ui,-apple-system,sans-serif;',
                'box-shadow:0 24px 64px rgba(0,0,0,.75);',
                'animation:pp-castin .2s ease both;pointer-events:none;'
            ].join('');
            el.innerHTML = html;
            document.body.appendChild(el);
            this._overlay = el;
        },

        _hideOverlay: function () {
            if (this._overlay) { try { this._overlay.remove(); } catch (e) {} this._overlay = null; }
        },

        stop: function () {
            this._capturing = false;
            clearTimeout(this._timeout);
            if (this._proc) {
                try { this._proc.disconnect(); } catch (e) {}
                this._proc.onaudioprocess = null;
                this._proc = null;
            }
            this._hideOverlay();
            this._samples = [];
            // _ctx и _src не разрушаем: MediaElementSource нельзя пересоздать для того же <video>
        }
    };

    // =====================================================================
    //  ПУЛЬТ (D-pad перемотка + горячие действия)
    // =====================================================================

    function bindRemote() {
        // OK поведение:
        //   Двойной тап  (< 400 мс между нажатиями) → Shazam
        //   Долгое (700 мс)                          → CastBar
        //   Обычное нажатие                          → пауза/воспроизведение (Lampa)
        var _lpTimer  = null;
        var _lpFired  = false;
        var _lastOkUp = 0;

        document.addEventListener('keydown', function (e) {
            var k = e.keyCode || e.which || 0;
            if ((k === 13 || e.key === 'Enter') && Engine.video && !_lpFired) {
                // Двойной тап: второе нажатие пришло < 400 мс после первого keyup
                if (!_lpTimer && Date.now() - _lastOkUp < 400) {
                    _lastOkUp = 0;
                    Shazam.capture();
                    e.stopPropagation();
                    return;
                }
                if (!_lpTimer) {
                    _lpTimer = setTimeout(function () {
                        _lpTimer = null;
                        _lpFired = true;
                        CastBar.toggle();
                    }, 700);
                }
            }
        }, false);
        document.addEventListener('keyup', function (e) {
            var k = e.keyCode || e.which || 0;
            if (k === 13 || e.key === 'Enter') {
                if (_lpTimer) { clearTimeout(_lpTimer); _lpTimer = null; }
                _lpFired  = false;
                _lastOkUp = Date.now();
            }
        }, false);

        Lampa.Controller.listener.follow('keydown', function (e) {
            if (!Engine.video) return;
            var k = e.keyCode || e.which || 0;
            switch (e.code) {
                case 'ArrowLeft':  Engine.seekBy(-10); HUD.peek(); break;
                case 'ArrowRight': Engine.seekBy(10);  HUD.peek(); break;
                case 'PageUp':     Engine.seekBy(30);  HUD.peek(); break;
                case 'PageDown':   Engine.seekBy(-30); HUD.peek(); break;
                default:           HUD.peek();         break;
            }
            // Жёлтая (405) / Info (457) — скорость (если есть на пульте)
            if (k === 457 || k === 405) HUD.cycleSpeed();
            // Menu/☰ (82) / зелёная (404) — тоже актёры (если есть)
            if (k === 404 || k === 82 || e.code === 'ContextMenu') CastBar.toggle();
        });
    }

    // =====================================================================
    //  ДИАЛОГ ВЫБОРА ПЛЕЕРА
    // =====================================================================

    function showPlayerSelect(cb) {
        // Задержка 700мс: Enter от запуска фильма не должен сразу выбрать пункт
        // (некоторые ATV-пульты дают повтор нажатия — увеличено с 450 до 700)
        var readyAt = Date.now() + 700;

        var style = document.createElement('style');
        style.textContent = [
            '@keyframes pp-in{from{opacity:0;transform:translateY(24px) scale(.97)}to{opacity:1;transform:translateY(0) scale(1)}}',
            '#pp-dialog{animation:pp-in .22s cubic-bezier(.22,.61,.36,1) both;}',
            '.pp-btn{',
                'display:flex;align-items:center;gap:1.1rem;',
                'border-radius:.8rem;padding:1.1rem 1.4rem;margin-bottom:.65rem;cursor:pointer;',
                'border:2px solid transparent;',
                'transition:border-color .12s,background .12s,box-shadow .12s,transform .08s;}',
            '.pp-btn.ppfocus{',
                'border-color:#60a5fa;',
                'background:rgba(59,130,246,.18)!important;',
                'box-shadow:0 0 0 3px rgba(96,165,250,.2),0 12px 40px rgba(0,0,0,.6);',
                'transform:scale(1.02);}',
            '.pp-ico{width:3rem;height:3rem;border-radius:.6rem;',
                'display:flex;align-items:center;justify-content:center;',
                'font-size:1.4rem;flex-shrink:0;font-style:normal;}',
            '.pp-txt{flex:1;min-width:0;}',
            '.pp-ttl{color:#fff;font-size:1rem;font-weight:700;letter-spacing:.01em;}',
            '.pp-sub{color:rgba(255,255,255,.4);font-size:.76rem;margin-top:.18rem;}'
        ].join('');
        document.head.appendChild(style);

        var overlay = document.createElement('div');
        overlay.id = 'pp-select-overlay';
        overlay.style.cssText = [
            'position:fixed;top:0;left:0;width:100%;height:100%;',
            'background:rgba(0,0,0,.72);',
            'backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);',
            'z-index:2147483647;',
            'display:flex;align-items:center;justify-content:center;',
            'font-family:system-ui,-apple-system,sans-serif;'
        ].join('');

        var box = document.createElement('div');
        box.id = 'pp-dialog';
        box.style.cssText = [
            'background:linear-gradient(160deg,rgba(15,15,28,.97) 0%,rgba(8,8,18,.97) 100%);',
            'border:1px solid rgba(255,255,255,.09);',
            'border-radius:1.2rem;padding:1.8rem 1.8rem 1.4rem;',
            'min-width:360px;max-width:92vw;',
            'box-shadow:0 40px 100px rgba(0,0,0,.85),0 0 0 .5px rgba(255,255,255,.04) inset;'
        ].join('');

        /* Шапка */
        var head = document.createElement('div');
        head.style.cssText = [
            'display:flex;align-items:center;gap:.8rem;',
            'margin-bottom:1.4rem;padding-bottom:1.2rem;',
            'border-bottom:1px solid rgba(255,255,255,.07);'
        ].join('');
        var headIco = document.createElement('div');
        headIco.style.cssText = [
            'width:2.4rem;height:2.4rem;border-radius:.55rem;',
            'background:linear-gradient(135deg,#3b82f6 0%,#7c3aed 100%);',
            'display:flex;align-items:center;justify-content:center;',
            'font-size:1rem;color:#fff;'
        ].join('');
        headIco.textContent = '▶';
        var headLbl = document.createElement('div');
        headLbl.style.cssText = 'color:#fff;font-size:1.05rem;font-weight:700;letter-spacing:.015em;';
        headLbl.textContent = 'Выберите плеер';
        head.appendChild(headIco);
        head.appendChild(headLbl);
        box.appendChild(head);

        function makeBtn(icon, label, sub, grad) {
            var btn = document.createElement('div');
            btn.className = 'pp-btn';
            btn.style.background = grad
                ? 'linear-gradient(135deg,rgba(59,130,246,.12),rgba(124,58,237,.08))'
                : 'rgba(255,255,255,.04)';
            var ico = document.createElement('i');
            ico.className = 'pp-ico';
            ico.style.background = grad
                ? 'linear-gradient(135deg,#3b82f6,#6366f1)'
                : 'rgba(255,255,255,.07)';
            ico.textContent = icon;
            var txt = document.createElement('div');
            txt.className = 'pp-txt';
            var ttl = document.createElement('div');
            ttl.className = 'pp-ttl';
            ttl.textContent = label;
            var dsc = document.createElement('div');
            dsc.className = 'pp-sub';
            dsc.textContent = sub;
            txt.appendChild(ttl);
            txt.appendChild(dsc);
            btn.appendChild(ico);
            btn.appendChild(txt);
            if (grad) {
                var badge = document.createElement('div');
                badge.style.cssText = [
                    'font-size:.62rem;font-weight:700;letter-spacing:.06em;',
                    'background:linear-gradient(90deg,#3b82f6,#7c3aed);',
                    'color:#fff;padding:.18rem .45rem;border-radius:.3rem;',
                    'white-space:nowrap;flex-shrink:0;'
                ].join('');
                badge.textContent = 'РЕКОМЕНДУЮ';
                btn.appendChild(badge);
            }
            return btn;
        }

        var btnPlus   = makeBtn('▶', 'Player Plus', 'hls.js · без зависаний на ATV · продолжение с места', true);
        var btnNative = makeBtn('◻', 'Встроенный Lampa', 'Стандартный нативный плеер', false);
        box.appendChild(btnPlus);
        box.appendChild(btnNative);

        /* Подсказка снизу */
        var hint = document.createElement('div');
        hint.style.cssText = [
            'text-align:center;color:rgba(255,255,255,.2);',
            'font-size:.68rem;letter-spacing:.07em;margin-top:.8rem;'
        ].join('');
        hint.textContent = '↑ ↓  НАВИГАЦИЯ   ·   OK  ВЫБРАТЬ   ·   ← НАЗАД';
        box.appendChild(hint);

        overlay.appendChild(box);
        document.body.appendChild(overlay);

        var focused = 0;
        var btns = [btnPlus, btnNative];

        function setFocus(i) {
            focused = Math.max(0, Math.min(btns.length - 1, i));
            btns.forEach(function (b, idx) {
                if (idx === focused) b.classList.add('ppfocus');
                else b.classList.remove('ppfocus');
            });
        }
        setFocus(0);

        function cleanup() {
            document.removeEventListener('keydown', onKey, true);
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
            if (style.parentNode) style.parentNode.removeChild(style);
        }

        function choose(usePlus) {
            cleanup();
            // ГЛАВНАЯ ПРИЧИНА автонажатия "продолжить с отметки": пульт на OK даёт
            // пару событий keydown+keyup. Мы гасили только keydown в onKey, а keyup
            // от ТОГО ЖЕ нажатия проскакивал в диалог Lampa под нашим окном и
            // подтверждал его. Поэтому глушим keydown+keyup+keypress ещё 1.5с.
            var sink = function (e) { e.stopPropagation(); e.preventDefault(); };
            document.addEventListener('keydown',  sink, true);
            document.addEventListener('keyup',    sink, true);
            document.addEventListener('keypress', sink, true);
            setTimeout(function () {
                document.removeEventListener('keydown',  sink, true);
                document.removeEventListener('keyup',    sink, true);
                document.removeEventListener('keypress', sink, true);
            }, 1500);
            cb(usePlus);
        }

        /* Capture-phase: перехватываем до Lampa, e.stopPropagation блокирует плеер */
        function onKey(e) {
            if (!document.getElementById('pp-select-overlay')) { cleanup(); return; }
            e.stopPropagation();
            e.preventDefault();
            /* Первые 450мс — глотаем нажатия, Enter не срабатывает */
            if (Date.now() < readyAt) return;
            var k = e.keyCode || e.which || 0;
            var key = e.key || '';
            if      (key === 'ArrowUp'   || k === 38)                              { setFocus(focused - 1); }
            else if (key === 'ArrowDown' || k === 40)                              { setFocus(focused + 1); }
            else if (key === 'Enter'     || k === 13)                              { choose(focused === 0); }
            else if (key === 'Escape' || key === 'GoBack' || k === 27 || k === 461) { choose(false); }
        }
        document.addEventListener('keydown', onKey, true);

        btnPlus.addEventListener('click',   function () { choose(true); });
        btnNative.addEventListener('click', function () { choose(false); });
    }

    // =====================================================================
    //  ИНТЕГРАЦИЯ С ПЛЕЕРОМ LAMPA
    // =====================================================================

    function hookPlayer() {
        if (!window.Lampa || !Lampa.Player) return;

        // Защита от двойного диалога: start может сработать повторно когда hls.js
        // перехватывает видеоэлемент (Lampa детектирует изменение src → новый start).
        var _lastSelectTime = 0;

        Lampa.Player.listener.follow('start', function (e) {
            // Не показываем диалог если он был < 8с назад (защита от двойного срабатывания)
            if (Date.now() - _lastSelectTime < 8000) return;

            // Lampa может передавать URL как e.url или как e.data.url
            var data = (e && e.data) ? e.data : (e || {});
            var url  = data.url
                    || (data.timeline && data.timeline.url)
                    || (data.file && data.file.url)
                    || '';
            var card = data.card || (data.file && data.file.card) || Lampa.Player.card || null;
            // Показываем диалог для любого URL (не только .m3u8): некоторые балансеры
            // отдают HLS без m3u8 в пути. isHls используется только внутри Engine.attach.
            if (!url || !hlsReady) return;

            _lastSelectTime = Date.now();
            showPlayerSelect(function (usePlus) {
                if (!usePlus) return;
                var tries = 0;
                var check = setInterval(function () {
                    var video = document.querySelector('video');
                    tries++;
                    if (video) {
                        clearInterval(check);
                        // forceHls=true: пробуем hls.js даже без .m3u8 в URL
                        Engine.attach(video, url, card, true);
                    } else if (tries > 50) clearInterval(check);
                }, 100);
            });
        });

        Lampa.Player.listener.follow('destroy', function () {
            UI.hideAutoNext();
            Engine.detach();
        });
    }

    // =====================================================================
    //  НАСТРОЙКИ В МЕНЮ LAMPA
    // =====================================================================

    function addSettings() {
        if (!window.Lampa || !Lampa.SettingsApi) return;

        Lampa.SettingsApi.addComponent({
            component: PLUGIN_NAME,
            name: 'Player Plus',
            icon: '<svg viewBox="0 0 24 24" width="24" height="24"><path fill="currentColor" d="M8 5v14l11-7z"/></svg>'
        });

        var s = settings();

        Lampa.SettingsApi.addParam({
            component: PLUGIN_NAME,
            param: { name: 'pp_engine', type: 'select', values: { hls: 'hls.js (рекомендую)', native: 'Нативный WebView' }, default: s.engine },
            field: { name: 'Движок воспроизведения', description: 'hls.js убирает зависания на ATV' },
            onChange: function (v) { setSetting('engine', v); }
        });

        Lampa.SettingsApi.addParam({
            component: PLUGIN_NAME,
            param: { name: 'pp_buffer', type: 'select', values: { low: 'Низкий (слабые боксы)', medium: 'Средний', high: 'Высокий (мощные)' }, default: s.buffer },
            field: { name: 'Размер буфера', description: 'Меньше буфер = меньше нагрузка на память' },
            onChange: function (v) { setSetting('buffer', v); }
        });

        Lampa.SettingsApi.addParam({
            component: PLUGIN_NAME,
            param: { name: 'pp_prebuffer', type: 'select', values: { '0': 'Выкл', '10': '10 сек', '20': '20 сек', '30': '30 сек', '45': '45 сек' }, default: String(s.prebuffer) },
            field: { name: 'Пред-буферизация', description: 'Ждать буфер перед стартом — убирает фризы в тяжёлых сценах' },
            onChange: function (v) { setSetting('prebuffer', parseInt(v, 10) || 0); }
        });

        Lampa.SettingsApi.addParam({
            component: PLUGIN_NAME,
            param: { name: 'pp_autonext', type: 'trigger', default: s.autonext },
            field: { name: 'Автопереход к след. серии' },
            onChange: function (v) { setSetting('autonext', v === true || v === 'true'); }
        });

        Lampa.SettingsApi.addParam({
            component: PLUGIN_NAME,
            param: { name: 'pp_tracks', type: 'trigger', default: s.remember_tracks },
            field: { name: 'Запоминать дорожки на сериал', description: 'Аудио и субтитры для всех серий' },
            onChange: function (v) { setSetting('remember_tracks', v === true || v === 'true'); }
        });

        Lampa.SettingsApi.addParam({
            component: PLUGIN_NAME,
            param: { name: 'pp_cast', type: 'trigger', default: s.cast_bar },
            field: { name: 'Панель "в ролях"', description: 'Долгое OK — показать/скрыть актёров тайтла' },
            onChange: function (v) { setSetting('cast_bar', v === true || v === 'true'); }
        });

        Lampa.SettingsApi.addParam({
            component: PLUGIN_NAME,
            param: { name: 'pp_diag', type: 'trigger', default: s.diag },
            field: { name: 'Индикатор буфера/качества', description: 'Для диагностики зависаний' },
            onChange: function (v) { setSetting('diag', v === true || v === 'true'); }
        });
    }

    // =====================================================================
    //  ИНИЦИАЛИЗАЦИЯ
    // =====================================================================

    function start() {
        loadHls(function (ok) {
            if (!ok) {
                try { Lampa.Noty.show('Player Plus: hls.js не загрузился, нативный режим'); } catch (e) {}
            }
            addSettings();
            hookPlayer();
            bindRemote();
            try { Lampa.Noty.show('Player Plus подключён'); } catch (e) {}
        });
    }

    if (window.appready) {
        start();
    } else {
        Lampa.Listener.follow('app', function (e) {
            if (e.type === 'ready') start();
        });
    }

})();
