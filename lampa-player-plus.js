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
        autonext: true,         // автопереход серий
        autonext_delay: 10,     // сек до перехода
        remember_tracks: true,  // запоминать дорожки на сериал
        skip_intro: true,       // показывать кнопку "пропустить интро"
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

        keyOf: function (url) {
            var h = 0, s = String(url || '');
            for (var i = 0; i < s.length; i++) {
                h = ((h << 5) - h) + s.charCodeAt(i);
                h |= 0;
            }
            return 'r' + Math.abs(h);
        },

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

        attach: function (video, url, card, forceHls) {
            this.detach();

            this.video = video;
            this.currentUrl = url;
            this.currentCard = card || null;

            var cfg = settings();

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
        },

        _attachHls: function (video, url, cfg, resumeTo) {
            var prof = BUFFER_PROFILES[cfg.buffer] || BUFFER_PROFILES.medium;

            try { video.pause(); video.removeAttribute('src'); video.load(); } catch (e) {}

            var hls = new window.Hls({
                enableWorker: true,
                lowLatencyMode: false,
                maxBufferLength: prof.maxBufferLength,
                maxMaxBufferLength: prof.maxMaxBufferLength,
                maxBufferSize: prof.maxBufferSize,
                backBufferLength: prof.backBufferLength,
                maxBufferHole: 0.5,
                highBufferWatchdogPeriod: 3,
                nudgeOffset: 0.2,
                nudgeMaxRetry: 10,
                fragLoadingMaxRetry: 10,
                fragLoadingMaxRetryTimeout: 64000,
                manifestLoadingMaxRetry: 5,
                levelLoadingMaxRetry: 8,
                levelLoadingRetryDelay: 500,
                // ABR: предполагаем высокий битрейт чтобы не начинать с низкого
                abrEwmaDefaultEstimate: 30 * 1000 * 1000,
                abrMaxWithRealBitrate: true,
                startLevel: -1,
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

            hls.attachMedia(video);
            hls.on(window.Hls.Events.MEDIA_ATTACHED, function () {
                hls.loadSource(url);
            });

            hls.on(window.Hls.Events.MANIFEST_PARSED, function (ev, data) {
                // Выбираем лучший уровень с поддерживаемым кодеком.
                // 4K обычно HEVC, WebView на ATV не декодирует H.265 аппаратно.
                // Внешний плеер (ExoPlayer) поддерживает HEVC через аппаратный декодер.
                if (data && data.levels && data.levels.length > 1) {
                    var bestLevel = -1;
                    for (var i = data.levels.length - 1; i >= 0; i--) {
                        var lvl = data.levels[i];
                        var codec = (lvl.videoCodec || lvl.codecs || '').toLowerCase();
                        var isAvc = !codec || codec.indexOf('avc') >= 0 || codec.indexOf('h264') >= 0;
                        var canPlay = isAvc;
                        if (!canPlay && codec) {
                            try {
                                var mime = 'video/mp4; codecs="' + codec + '"';
                                var cp = document.createElement('video').canPlayType(mime);
                                canPlay = (cp === 'probably' || cp === 'maybe');
                            } catch (ex) { canPlay = false; }
                        }
                        if (canPlay) { bestLevel = i; break; }
                    }
                    if (bestLevel >= 0) hls.currentLevel = bestLevel;
                }
                if (resumeTo > 0) {
                    try { video.currentTime = resumeTo; } catch (e) {}
                }
                video.play().catch(function () {});
            });

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
                            Engine._notify('Медиа: восстановление…');
                            hls.recoverMediaError();
                        } else if (_mediaErrCount <= 3 && hls.currentLevel > 0) {
                            hls.currentLevel = hls.currentLevel - 1;
                            Engine._notify('Кодек несовместим, понижаю качество…');
                            hls.recoverMediaError();
                        } else {
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

        _hookEnded: function () {
            var self = this;
            if (!this.video || this.endedHooked) return;
            this.endedHooked = true;

            this.video.addEventListener('timeupdate', function () {
                var v = self.video;
                if (!v || !isFinite(v.duration)) return;
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
    //  ПУЛЬТ (D-pad перемотка + горячие действия)
    // =====================================================================

    function bindRemote() {
        Lampa.Controller.listener.follow('keydown', function (e) {
            if (!Engine.video) return;
            switch (e.code) {
                case 'ArrowLeft':  Engine.seekBy(-10); break;
                case 'ArrowRight': Engine.seekBy(10);  break;
                case 'PageUp':     Engine.seekBy(30);  break;
                case 'PageDown':   Engine.seekBy(-30); break;
            }
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

        function choose(usePlus) { cleanup(); cb(usePlus); }

        function onKey(e) {
            if (!document.getElementById('pp-select-overlay')) { cleanup(); return; }
            e.stopPropagation();
            e.preventDefault();
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
            if (Date.now() - _lastSelectTime < 8000) return;

            var data = (e && e.data) ? e.data : (e || {});
            var url  = data.url
                    || (data.timeline && data.timeline.url)
                    || (data.file && data.file.url)
                    || '';
            var card = data.card || (data.file && data.file.card) || Lampa.Player.card || null;
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
