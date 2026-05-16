function json(data, status = 200) {
    return new Response(JSON.stringify(data), {
        status,
        headers: {
            'Content-Type': 'application/json',
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type'
        }
    });
}

function preflight() {
    return new Response(null, {
        status: 204,
        headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type'
        }
    });
}

// Rate limit: 1 API request per second per token
const apiRateMap = new Map();
function checkApiRate(token) {
    const now = Date.now();
    const last = apiRateMap.get(token) || 0;
    if (now - last < 1000) return false;
    apiRateMap.set(token, now);
    return true;
}

async function handleLink(request, env, code) {
    if (request.method === 'OPTIONS') return preflight();
    if (request.method !== 'POST') return json({ error: 'Method not allowed' }, 405);

    const linkData = JSON.parse(await env.KV.get(`linkcode:${code}`) || 'null');
    if (!linkData) return json({ error: 'Code not found or expired' }, 404);

    if (Date.now() > linkData.expires_at) {
        await env.KV.delete(`linkcode:${code}`);
        return json({ error: 'Code expired' }, 410);
    }

    const token = crypto.randomUUID();
    const chatId = linkData.chat_id;

    await env.KV.put(`token:${token}`, JSON.stringify({
        chat_id: chatId,
        name: linkData.chat_name,
        created_at: Date.now()
    }), { expirationTtl: 30 * 24 * 60 * 60 });

    const chatData = JSON.parse(await env.KV.get(`chat:${chatId}`) || '{}');
    const tokens = chatData.lampa_tokens || [];
    if (!tokens.includes(token)) tokens.push(token);
    await env.KV.put(`chat:${chatId}`, JSON.stringify({
        ...chatData,
        lampa_tokens: tokens,
        name: linkData.chat_name
    }));

    await env.KV.delete(`linkcode:${code}`);

    return json({ token, chat_name: linkData.chat_name });
}

async function handleFeed(request, env) {
    if (request.method === 'OPTIONS') return preflight();

    const url = new URL(request.url);
    const token = url.searchParams.get('token');
    const since = parseInt(url.searchParams.get('since') || '0');

    if (!token) return json({ error: 'Missing token' }, 400);

    if (!checkApiRate(token)) {
        return json({ error: 'Rate limited' }, 429);
    }

    const tokenData = JSON.parse(await env.KV.get(`token:${token}`) || 'null');
    if (!tokenData) return json({ error: 'Invalid token', code: 'INVALID_TOKEN' }, 401);

    const chatId = tokenData.chat_id;
    const chatData = JSON.parse(await env.KV.get(`chat:${chatId}`) || '{}');
    const feed = JSON.parse(await env.KV.get(`feed:${chatId}`) || '[]');

    const items = since > 0 ? feed.filter(r => r.ts > since) : feed;

    return json({ items, chat_name: chatData.name || tokenData.name || 'Чат' });
}

async function handleShare(request, env) {
    if (request.method === 'OPTIONS') return preflight();
    if (request.method !== 'POST') return json({ error: 'Method not allowed' }, 405);

    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }

    const { token, type, title, user_name } = body;
    if (!token) return json({ error: 'Missing token' }, 400);

    const tokenData = JSON.parse(await env.KV.get(`token:${token}`) || 'null');
    if (!tokenData) return json({ error: 'Invalid token' }, 401);

    const chatId = tokenData.chat_id;
    let message = '';

    if (type === 'added_to_book') {
        message = `📚 ${user_name || 'Кто-то'} добавил «${title}» в книгу.`;
    }

    if (message && env.BOT_TOKEN) {
        await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/sendMessage`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ chat_id: chatId, text: message })
        });
    }

    return json({ ok: true });
}

async function handleUnlink(request, env) {
    if (request.method === 'OPTIONS') return preflight();
    if (request.method !== 'POST') return json({ error: 'Method not allowed' }, 405);

    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }

    const { token } = body;
    if (!token) return json({ error: 'Missing token' }, 400);

    const tokenData = JSON.parse(await env.KV.get(`token:${token}`) || 'null');
    if (!tokenData) return json({ ok: true });

    const chatId = tokenData.chat_id;
    const chatData = JSON.parse(await env.KV.get(`chat:${chatId}`) || '{}');
    if (chatData.lampa_tokens) {
        chatData.lampa_tokens = chatData.lampa_tokens.filter(t => t !== token);
        await env.KV.put(`chat:${chatId}`, JSON.stringify(chatData));
    }

    await env.KV.delete(`token:${token}`);

    return json({ ok: true });
}

export async function handleApi(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === '/api/health') {
        return json({ ok: true, ts: Date.now() });
    }

    const linkMatch = path.match(/^\/api\/link\/(\d{6})$/);
    if (linkMatch) return handleLink(request, env, linkMatch[1]);

    if (path === '/api/feed') return handleFeed(request, env);
    if (path === '/api/share') return handleShare(request, env);
    if (path === '/api/unlink') return handleUnlink(request, env);

    return json({ error: 'Not found' }, 404);
}
