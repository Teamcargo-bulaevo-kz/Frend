// Прокси между приложением и Groq. Ключ Groq хранится только здесь (секрет GROQ_API_KEY), в приложении его нет.
// Тело запросов и ключи не логируются.

const UPSTREAM = 'https://api.groq.com/openai/v1/chat/completions';
const PATH = '/v1/chat/completions';
const MAX_BODY_BYTES = 2 * 1024 * 1024; // 2 МБ: хватает для картинок
const LIMIT = 30;                        // запросов
const WINDOW_MS = 60 * 1000;             // в минуту на один IP

// Запасной счётчик на случай, если привязка RATE_LIMITER не настроена (он живёт в памяти одного экземпляра Worker)
const hits = new Map();

function json(body, status, extraHeaders) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...(extraHeaders || {}) },
  });
}

// Сравнение без утечки по времени
function safeEqual(a, b) {
  const enc = new TextEncoder();
  const x = enc.encode(a);
  const y = enc.encode(b);
  let diff = x.length ^ y.length;
  const n = Math.max(x.length, y.length);
  for (let i = 0; i < n; i++) diff |= (x[i] || 0) ^ (y[i] || 0);
  return diff === 0;
}

async function isRateLimited(env, key) {
  if (env.RATE_LIMITER && typeof env.RATE_LIMITER.limit === 'function') {
    const { success } = await env.RATE_LIMITER.limit({ key });
    return !success;
  }
  const now = Date.now();
  let entry = hits.get(key);
  if (!entry || now - entry.start >= WINDOW_MS) {
    entry = { start: now, count: 0 };
    hits.set(key, entry);
  }
  entry.count++;
  if (hits.size > 5000) {
    for (const [k, v] of hits) if (now - v.start >= WINDOW_MS) hits.delete(k);
  }
  return entry.count > LIMIT;
}

// Читает тело, но не больше max байт. null — слишком большое.
async function readLimited(request, max) {
  if (!request.body) return new Uint8Array(0);
  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    out.set(c, offset);
    offset += c.byteLength;
  }
  return out;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== PATH) return json({ error: 'not_found' }, 404);
    if (request.method !== 'POST') return json({ error: 'method_not_allowed' }, 405, { Allow: 'POST' });

    const token = request.headers.get('X-App-Token') || '';
    if (!env.APP_TOKEN || !safeEqual(token, env.APP_TOKEN)) return json({ error: 'unauthorized' }, 401);

    const declared = parseInt(request.headers.get('Content-Length') || '0', 10);
    if (declared > MAX_BODY_BYTES) return json({ error: 'payload_too_large' }, 413);

    const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
    if (await isRateLimited(env, ip)) return json({ error: 'rate_limited' }, 429, { 'Retry-After': '60' });

    const body = await readLimited(request, MAX_BODY_BYTES);
    if (body === null) return json({ error: 'payload_too_large' }, 413);

    let upstream;
    try {
      upstream = await fetch(UPSTREAM, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer ' + env.GROQ_API_KEY,
        },
        body,
      });
    } catch (e) {
      return json({ error: 'upstream_unreachable' }, 502);
    }

    // Ответ Groq отдаём как есть (включая его ошибки 400/429), но только нужные заголовки
    const headers = new Headers();
    for (const name of ['content-type', 'retry-after']) {
      const v = upstream.headers.get(name);
      if (v) headers.set(name, v);
    }
    return new Response(upstream.body, { status: upstream.status, headers });
  },
};
