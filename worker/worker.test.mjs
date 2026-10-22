// Запуск: node --test worker/worker.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.js';

const env = () => ({ APP_TOKEN: 'app-secret', GROQ_API_KEY: 'gsk_test_key' });
const URL_OK = 'https://proxy.example/v1/chat/completions';
const post = (headers = {}, body = '{"model":"m"}', url = URL_OK) =>
  new Request(url, { method: 'POST', headers: { 'X-App-Token': 'app-secret', 'CF-Connecting-IP': '1.1.1.1', ...headers }, body });

test('без токена и с неверным токеном — 401', async () => {
  assert.equal((await worker.fetch(post({ 'X-App-Token': '' }), env())).status, 401);
  assert.equal((await worker.fetch(post({ 'X-App-Token': 'wrong' }), env())).status, 401);
  assert.equal((await worker.fetch(post({}), { GROQ_API_KEY: 'x' })).status, 401); // токен в Worker не задан
});

test('другой путь — 404, другой метод — 405', async () => {
  assert.equal((await worker.fetch(post({}, '{}', 'https://proxy.example/other'), env())).status, 404);
  const get = new Request(URL_OK, { method: 'GET', headers: { 'X-App-Token': 'app-secret' } });
  assert.equal((await worker.fetch(get, env())).status, 405);
});

test('слишком большое тело — 413', async () => {
  const big = 'a'.repeat(2 * 1024 * 1024 + 10);
  assert.equal((await worker.fetch(post({}, big), env())).status, 413);
});

test('запрос пересылается в Groq с ключом из секрета, ответ возвращается как есть', async () => {
  let seen;
  globalThis.fetch = async (url, init) => {
    seen = { url, init };
    return new Response('{"ok":true}', { status: 200, headers: { 'content-type': 'application/json', 'x-secret-extra': 'no' } });
  };
  const res = await worker.fetch(post({ 'CF-Connecting-IP': '2.2.2.2' }, '{"model":"m"}'), env());
  assert.equal(res.status, 200);
  assert.equal(await res.text(), '{"ok":true}');
  assert.equal(seen.url, 'https://api.groq.com/openai/v1/chat/completions');
  assert.equal(seen.init.headers.Authorization, 'Bearer gsk_test_key');
  assert.equal(new TextDecoder().decode(seen.init.body), '{"model":"m"}');
  assert.equal(res.headers.get('x-secret-extra'), null);
});

test('ошибка Groq 429 передаётся с Retry-After', async () => {
  globalThis.fetch = async () => new Response('{"error":"limit"}', { status: 429, headers: { 'retry-after': '12' } });
  const res = await worker.fetch(post({ 'CF-Connecting-IP': '3.3.3.3' }), env());
  assert.equal(res.status, 429);
  assert.equal(res.headers.get('retry-after'), '12');
});

test('лимит: 31-й запрос в минуту с одного IP — 429 rate_limited', async () => {
  globalThis.fetch = async () => new Response('{}', { status: 200 });
  const e = env();
  let last;
  for (let i = 0; i < 31; i++) last = await worker.fetch(post({ 'CF-Connecting-IP': '9.9.9.9' }), e);
  assert.equal(last.status, 429);
  assert.deepEqual(await last.json(), { error: 'rate_limited' });
  // другой IP не задет
  assert.equal((await worker.fetch(post({ 'CF-Connecting-IP': '8.8.8.8' }), e)).status, 200);
});

test('ключи и тело не попадают в логи', async () => {
  const logged = [];
  const orig = { log: console.log, error: console.error, warn: console.warn };
  console.log = console.error = console.warn = (...a) => logged.push(a.join(' '));
  globalThis.fetch = async () => new Response('{}', { status: 200 });
  await worker.fetch(post({ 'CF-Connecting-IP': '7.7.7.7' }, '{"secret":"body"}'), env());
  Object.assign(console, orig);
  assert.equal(logged.length, 0);
});

test('привязка RATE_LIMITER используется, если есть', async () => {
  globalThis.fetch = async () => new Response('{}', { status: 200 });
  const e = { ...env(), RATE_LIMITER: { limit: async () => ({ success: false }) } };
  assert.equal((await worker.fetch(post({ 'CF-Connecting-IP': '6.6.6.6' }), e)).status, 429);
});
