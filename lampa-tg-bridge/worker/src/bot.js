import { searchMovie, findByImdbId, getMovieByTmdbId, normalizeResult, parseLinkFromText } from './tmdb.js';

const RATE_LIMIT_COUNT = 5;
const RATE_LIMIT_WINDOW = 60 * 1000;

async function checkRateLimit(userId, env) {
    const key = `ratelimit:${userId}`;
    const data = JSON.parse(await env.KV.get(key) || 'null');
    const now = Date.now();

    if (!data || now - data.window_start > RATE_LIMIT_WINDOW) {
        await env.KV.put(key, JSON.stringify({ count: 1, window_start: now }), { expirationTtl: 120 });
        return true;
    }

    if (data.count >= RATE_LIMIT_COUNT) return false;

    data.count++;
    await env.KV.put(key, JSON.stringify(data), { expirationTtl: 120 });
    return true;
}

async function pushToFeed(chatId, rec, env) {
    const key = `feed:${chatId}`;
    const existing = JSON.parse(await env.KV.get(key) || '[]');

    // Deduplicate: same tmdb_id → aggregate comments instead of duplicating
    const idx = existing.findIndex(r => r.tmdb_id === rec.tmdb_id);
    if (idx !== -1) {
        const prev = existing[idx];
        if (!prev.comments) {
            prev.comments = prev.comment ? [{ from_name: prev.from_name, comment: prev.comment }] : [];
        }
        if (rec.comment) {
            prev.comments.push({ from_name: rec.from_name, comment: rec.comment });
        }
        prev.ts = rec.ts;
        existing.splice(idx, 1);
        existing.unshift(prev);
    } else {
        existing.unshift(rec);
    }

    await env.KV.put(key, JSON.stringify(existing.slice(0, 100)), { expirationTtl: 30 * 24 * 60 * 60 });
}

async function getChatTokenNames(chatId, env) {
    const chatData = JSON.parse(await env.KV.get(`chat:${chatId}`) || 'null');
    if (!chatData || !chatData.lampa_tokens || chatData.lampa_tokens.length === 0) return [];

    const names = [];
    for (const token of chatData.lampa_tokens) {
        const td = JSON.parse(await env.KV.get(`token:${token}`) || 'null');
        if (td && td.name) names.push(td.name);
    }
    return names;
}

async function processRec(ctx, rawText, comment, env) {
    const allowed = await checkRateLimit(ctx.from.id, env);
    if (!allowed) {
        return ctx.reply('⏱ Слишком много рекомендаций. Подожди минуту (лимит: 5 в минуту).');
    }

    let movie = null;
    const linkInfo = parseLinkFromText(rawText);

    if (linkInfo) {
        if (linkInfo.type === 'tmdb') {
            const raw = await getMovieByTmdbId(linkInfo.id, env.TMDB_KEY);
            movie = normalizeResult(raw);
        } else if (linkInfo.type === 'imdb') {
            const raw = await findByImdbId(linkInfo.id, env.TMDB_KEY);
            movie = normalizeResult(raw);
        }
    } else {
        const raw = await searchMovie(rawText, env.TMDB_KEY);
        movie = normalizeResult(raw);
    }

    if (!movie) {
        return ctx.reply(`❌ Не нашёл «${rawText}». Попробуй уточнить название или вставь ссылку.`);
    }

    const rec = {
        id: 'rec_' + crypto.randomUUID(),
        tmdb_id: movie.id,
        title: movie.title,
        year: movie.year,
        poster: movie.poster,
        vote: movie.vote,
        media_type: movie.media_type,
        from_user: ctx.from.id,
        from_name: ctx.from.first_name,
        comment: comment || '',
        ts: Math.floor(Date.now() / 1000)
    };

    const chatId = ctx.chat.id;
    await pushToFeed(chatId, rec, env);

    const names = await getChatTokenNames(chatId, env);
    const chatData = JSON.parse(await env.KV.get(`chat:${chatId}`) || '{}');
    const chatName = chatData.name || 'фид';
    const yearText = rec.year ? ` (${rec.year})` : '';
    const whoText = names.length > 0 ? `\nПолучат: ${names.join(', ')}` : '';

    await ctx.reply(`✅ «${movie.title}»${yearText} добавлено в общий фид «${chatName}».${whoText}`);
}

export function setupBot(bot, env) {
    bot.command('start', ctx => {
        const isPrivate = ctx.chat.type === 'private';
        ctx.reply(
            `👋 Привет! Я бот-мост между Telegram и Lampa.\n\n` +
            `📱 Как использовать:\n` +
            `1. Добавь меня в чат с друзьями\n` +
            `2. Напиши /link чтобы привязать Lampa\n` +
            `3. Рекомендуй фильмы!\n\n` +
            (isPrivate
                ? `💬 В личке достаточно написать название:\n   Дюна 2\n   Интерстеллар — шедевр!\n\n`
                : `💬 В чате используй команду:\n   /rec Дюна 2\n   /rec Интерстеллар — шедевр!\n\n`) +
            `Команды:\n` +
            `/link — привязать Lampa\n` +
            `/rec <название> — рекомендовать фильм\n` +
            `/feed — последние рекомендации\n` +
            `/who — кто привязан в чате\n` +
            `/unlink — отвязать Lampa`
        );
    });

    bot.command('link', async ctx => {
        const code = String(Math.floor(100000 + Math.random() * 900000));
        const chatId = ctx.chat.id;
        const chatName = ctx.chat.title || ctx.from.first_name;

        await env.KV.put(`linkcode:${code}`, JSON.stringify({
            chat_id: chatId,
            chat_name: chatName,
            expires_at: Date.now() + 10 * 60 * 1000
        }), { expirationTtl: 600 });

        const existing = JSON.parse(await env.KV.get(`chat:${chatId}`) || 'null');
        if (!existing) {
            await env.KV.put(`chat:${chatId}`, JSON.stringify({ lampa_tokens: [], name: chatName }));
        }

        await ctx.reply(
            `🔗 Введи этот код в Lampa в течение 10 минут:\n\n<code>${code}</code>\n\n` +
            `Путь в Lampa: Настройки → Плагины → Telegram Bridge → вставь код`,
            { parse_mode: 'HTML' }
        );
    });

    bot.command('rec', async ctx => {
        const text = ctx.match;
        if (!text) {
            return ctx.reply(
                'Использование:\n' +
                '/rec Название\n' +
                '/rec Название — комментарий\n\n' +
                'Пример: /rec Дюна 2 — обязательно посмотри!'
            );
        }

        // Split on — – or standalone -
        const parts = text.split(/\s*[—–]\s*|\s+-\s+/);
        const titlePart = parts[0].trim();
        const comment = parts.slice(1).join(' — ').trim();

        await processRec(ctx, titlePart, comment, env);
    });

    bot.command('feed', async ctx => {
        const chatId = ctx.chat.id;
        const feedData = JSON.parse(await env.KV.get(`feed:${chatId}`) || '[]');

        if (feedData.length === 0) {
            return ctx.reply('📭 Фид пустой. Рекомендуй фильмы командой:\n/rec Название фильма');
        }

        const lines = feedData.slice(0, 5).map((r, i) => {
            const year = r.year ? ` (${r.year})` : '';
            const comment = r.comment ? `\n   💬 «${r.comment}»` : '';
            return `${i + 1}. *${r.title}*${year}\n   👤 ${r.from_name}${comment}`;
        });

        await ctx.reply(`🎬 Последние рекомендации:\n\n${lines.join('\n\n')}`, { parse_mode: 'Markdown' });
    });

    bot.command('unlink', async ctx => {
        if (ctx.chat.type !== 'private') {
            return ctx.reply('Команда /unlink доступна только в личке с ботом.');
        }
        await ctx.reply('Чтобы отвязать Lampa, нажми «Отвязать» в настройках плагина Telegram Bridge.');
    });

    bot.command('who', async ctx => {
        const chatId = ctx.chat.id;
        const names = await getChatTokenNames(chatId, env);

        if (names.length === 0) {
            return ctx.reply('📱 В этом чате нет привязанных Lampa.\nИспользуй /link для привязки.');
        }

        await ctx.reply(`📱 Привязанные Lampa в чате:\n${names.map(n => `• ${n}`).join('\n')}`);
    });

    // In private chat, plain text = recommendation (no /rec needed)
    bot.on('message:text', async ctx => {
        if (ctx.message.text.startsWith('/')) return;
        if (ctx.chat.type !== 'private') return;
        const parts = ctx.message.text.split(/\s*[—–]\s*|\s+-\s+/);
        const titlePart = parts[0].trim();
        const comment = parts.slice(1).join(' — ').trim();
        if (!titlePart) return;
        await processRec(ctx, titlePart, comment, env);
    });
}
