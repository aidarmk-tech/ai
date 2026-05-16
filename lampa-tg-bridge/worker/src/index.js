import { Bot, webhookCallback } from 'grammy';
import { setupBot } from './bot.js';
import { handleApi } from './api.js';

export default {
    async fetch(request, env) {
        try {
            const url = new URL(request.url);

            if (url.pathname.startsWith('/api/')) {
                return handleApi(request, env);
            }

            const bot = new Bot(env.BOT_TOKEN);
            setupBot(bot, env);
            return webhookCallback(bot, 'cloudflare-mod')(request);
        } catch (err) {
            return new Response(JSON.stringify({ error: err.message }), {
                status: 500,
                headers: { 'Content-Type': 'application/json' }
            });
        }
    }
};
