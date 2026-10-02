const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

function loadPlugin(staleList, browser = false) {
    const played = [];
    const lampa = {
        Storage: { get: (_key, fallback) => fallback, set: () => {}, field: () => 'android' },
        Player: { play(video) { played.push(video); } },
        PlayerPlaylist: { get: () => staleList, position: () => 1, listener: { follow() {} } },
        Activity: { active: () => null, all: () => [] },
        Listener: { follow() {} },
        Noty: { show() {} },
    };
    const sandbox = {
        window: { appready: true, Lampa: lampa, location: { href: '' } }, Lampa: lampa,
        navigator: { userAgent: browser ? 'Mozilla/5.0 Chrome/120' : 'Lampa' },
        btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
        unescape, encodeURIComponent, setTimeout, clearTimeout,
    };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'lmnp.js'), 'utf8'), sandbox);
    return { lampa, played, window: sandbox.window };
}

function unpack(video) {
    assert.match(video.title, /^lmpmeta:\/\//);
    return JSON.parse(Buffer.from(video.title.slice(10), 'base64').toString('utf8'));
}

test('torrent episodes use current file list and playable stream URLs', () => {
    const stale = [
        { url: 'https://old.test/one.mp4', episode: 1 },
        { url: 'https://old.test/two.mp4', episode: 2 },
    ];
    const { lampa, played } = loadPlugin(stale);
    const files = [1, 2, 3].map((n) => ({
        url: `http://192.0.2.10:8090/stream/e${n}.mkv?link=hash&index=${n}&preload`,
        title: `Серия ${n}`, episode: n, season: 1,
    }));
    const video = { ...files[1], playlist: files, card: { id: 42, title: 'Сериал' } };
    lampa.Player.play(video);
    assert.equal(played.length, 1);
    const meta = unpack(played[0]);
    assert.equal(meta.pl.pi, 1);
    assert.deepEqual(meta.pl.items.map((x) => x.u), files.map((x) => x.url.replace('&preload', '&play')));
    assert.deepEqual(JSON.parse(Buffer.from(played[0].headers['X-Lmnp-Pl'], 'base64').toString()).items.map((x) => x.u), meta.pl.items.map((x) => x.u));
    assert.equal(files[2].url.endsWith('&preload'), true); // no mutation of Lampa's data
});

test('online series URLs and global playlist selection remain unchanged', () => {
    const online = [
        { url: 'https://online.test/one.mp4?preload', episode: 1 },
        { url: 'https://online.test/two.mp4?preload', episode: 2 },
    ];
    const { lampa, played } = loadPlugin(online);
    lampa.Player.play({ ...online[0], card: { id: 43, title: 'Сериал' } });
    assert.equal(played.length, 1);
    assert.deepEqual(unpack(played[0]).pl.items.map((x) => x.u), online.map((x) => x.url));
});

test('browser launch also uses the torrent file list and play URL', () => {
    const stale = [{ url: 'https://old.test/one.mp4' }, { url: 'https://old.test/two.mp4' }];
    const { lampa, played, window } = loadPlugin(stale, true);
    const files = [1, 2].map((n) => ({
        url: `http://192.0.2.10:8090/stream/e${n}.mkv?link=hash&index=${n}&preload`,
        episode: n,
    }));
    lampa.Player.play({ ...files[0], playlist: files, card: { id: 44, title: 'Сериал' } });
    assert.equal(played.length, 0);
    const intent = window.location.href;
    assert.match(intent, /^intent:\/\/play\?/);
    const query = new URLSearchParams(intent.slice(intent.indexOf('?') + 1, intent.indexOf('#Intent')));
    assert.equal(query.get('url'), files[0].url.replace('&preload', '&play'));
    const data = JSON.parse(Buffer.from(query.get('d'), 'base64').toString('utf8'));
    assert.deepEqual(JSON.parse(data.episodes).map((x) => x.url), files.map((x) => x.url.replace('&preload', '&play')));
    assert.equal(data.episode_index, undefined);
});
