import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import { MockProvider } from '../src/providers/mock.js';
import { ProviderError } from '../src/providers/errors.js';

const SECRET = 'test-secret';
const quiet = { info() {}, error() {} };
const servers = [];

async function start(provider, opts = {}) {
  const app = createApp({ provider, sharedSecret: SECRET, logger: quiet, ...opts });
  const server = await new Promise((resolve) => {
    const s = app.listen(0, '127.0.0.1', () => resolve(s));
  });
  servers.push(server);
  return `http://127.0.0.1:${server.address().port}`;
}

const post = (base, body, secret = SECRET) =>
  fetch(`${base}/interpret`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(secret ? { 'x-jarvis-secret': secret } : {}) },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });

let base;
before(async () => {
  base = await start(new MockProvider());
});
after(() => servers.forEach((s) => s.close()));

test('health is public', async () => {
  const res = await fetch(`${base}/health`);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true, provider: 'mock', model: 'rules-v1', configured: true });
});

test('rejects missing or wrong secret', async () => {
  assert.equal((await post(base, { text: 'call king' }, null)).status, 401);
  assert.equal((await post(base, { text: 'call king' }, 'nope')).status, 401);
});

test('validates input', async () => {
  for (const body of [{}, { text: '' }, { text: 'x'.repeat(501) }, { text: 'hi', contacts: 'King' }, '{bad json']) {
    const res = await post(base, body);
    assert.equal(res.status, 400, JSON.stringify(body));
    assert.equal((await res.json()).error, 'invalid_request');
  }
});

const PHRASES = [
  ['Jarvis call king', 'call_contact', { name: 'King' }],
  ['Jarvis message junior on WhatsApp hi', 'send_whatsapp', { contact_name: 'Junior', message: 'hi' }],
  ['text mom on my way', 'send_sms', { contact_name: 'Mom', message: 'on my way' }],
  ['set an alarm for 6:30 pm', 'set_alarm', { hour: 18, minute: 30, label: '' }],
  ['set a timer for 5 minutes', 'set_timer', { seconds: 300, label: '' }],
  ['open youtube', 'open_app', { app_name: 'Youtube' }],
  ['search for best biryani near me', 'web_search', { query: 'best biryani near me' }],
  ['play arijit singh', 'play_music', { query: 'arijit singh' }],
  ['hello', 'answer', { text: 'Hello! How can I help?' }],
  ['read my whatsapp messages', 'read_notifications', { app_name: 'Whatsapp', contact_name: '' }],
  ['reply to king saying on my way', 'reply_message', { contact_name: 'King', message: 'on my way' }],
];

for (const [text, tool, args] of PHRASES) {
  test(`interprets "${text}"`, async () => {
    const res = await post(base, { text });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.tool, tool);
    assert.deepEqual(body.args, args);
    assert.equal(body.provider, 'mock');
  });
}

test('provider 429 returns friendly retry message', async () => {
  const url = await start({
    name: 'flaky',
    model: 'x',
    configured: true,
    interpret: async () => {
      throw new ProviderError('rate_limited', 'quota', { retryAfterSeconds: 30 });
    },
  });
  const res = await post(url, { text: 'call king' });
  assert.equal(res.status, 429);
  assert.equal(res.headers.get('retry-after'), '30');
  assert.match((await res.json()).message, /try again in a minute/);
});

test('missing provider key returns 503', async () => {
  const url = await start({
    name: 'g',
    model: 'x',
    configured: false,
    interpret: async () => {
      throw new ProviderError('not_configured', 'no key');
    },
  });
  assert.equal((await post(url, { text: 'hi' })).status, 503);
});

test('local rate limiter kicks in', async () => {
  const url = await start(new MockProvider(), { rateLimitPerMinute: 2 });
  await post(url, { text: 'hi' });
  await post(url, { text: 'hi' });
  const res = await post(url, { text: 'hi' });
  assert.equal(res.status, 429);
  assert.equal((await res.json()).error, 'rate_limited');
});
