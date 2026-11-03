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

const post = (base, body, secret = SECRET, path = '/interpret') =>
  fetch(`${base}${path}`, {
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
  assert.deepEqual(await res.json(), { ok: true, provider: 'mock', model: 'rules-v1', configured: true, speech: false });
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
  ['Jarvis message junior on WhatsApp hi', 'send_whatsapp', { contact_name: 'Junior', message: 'hi', composed: false }],
  ['text mom on my way', 'send_sms', { contact_name: 'Mom', message: 'on my way', composed: false }],
  ['set an alarm for 6:30 pm', 'set_alarm', { hour: 18, minute: 30, label: '' }],
  ['set a timer for 5 minutes', 'set_timer', { seconds: 300, label: '' }],
  ['open youtube', 'open_app', { app_name: 'Youtube' }],
  ['search for best biryani near me', 'web_search', { query: 'best biryani near me' }],
  ['play arijit singh', 'play_music', { query: 'arijit singh' }],
  ['hello', 'answer', { text: 'Hello! How can I help?' }],
  ['read my whatsapp messages', 'read_notifications', { app_name: 'Whatsapp', contact_name: '' }],
  ['reply to king saying on my way', 'reply_message', { contact_name: 'King', message: 'on my way', composed: false }],
  ['Jarvis, where are you?', 'find_phone', {}],
  ['is this a scam', 'screen_question', { question: 'is this a scam' }],
  ['what did I miss', 'summarize_messages', { app_name: '', contact_name: '', question: 'what did I miss' }],
  ['summarise my family group', 'summarize_messages', { app_name: '', contact_name: 'Family', question: 'summarise my family group' }],
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

// A tiny but valid base64 payload; the mock provider never decodes it.
const IMAGE = Buffer.from('fake-jpeg-bytes').toString('base64');

test('screen answers a question about a screenshot', async () => {
  const res = await post(base, { question: 'is this a scam?', image: IMAGE }, SECRET, '/screen');
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.match(body.text, /see your screen/);
  assert.equal(body.provider, 'mock');
});

test('screen validates question, image and mime type', async () => {
  for (const body of [
    { image: IMAGE },
    { question: 'what is this' },
    { question: 'what is this', image: 'not base64!' },
    { question: 'what is this', image: IMAGE, mimeType: 'image/gif' },
  ]) {
    const res = await post(base, body, SECRET, '/screen');
    assert.equal(res.status, 400, JSON.stringify(body));
  }
});

test('screen requires the shared secret before reading the body', async () => {
  assert.equal((await post(base, { question: 'x', image: IMAGE }, null, '/screen')).status, 401);
});

test('screen accepts a realistic screenshot size but rejects huge uploads', async () => {
  const realistic = 'A'.repeat(600_000);
  assert.equal((await post(base, { question: 'explain this', image: realistic }, SECRET, '/screen')).status, 200);
  const huge = 'A'.repeat(5_000_000);
  const res = await post(base, { question: 'explain this', image: huge }, SECRET, '/screen');
  assert.equal(res.status, 413);
  assert.equal((await res.json()).error, 'invalid_request');
});

test('summarize condenses notifications', async () => {
  const messages = [
    { app: 'WhatsApp', chat: 'Family', sender: 'Mom', text: 'Dinner at 8', time: '19:02' },
    { app: 'WhatsApp', chat: 'Family', sender: 'Dad', text: 'Bring milk', time: '19:05' },
  ];
  const res = await post(base, { question: 'summarise my family group', messages }, SECRET, '/summarize');
  assert.equal(res.status, 200);
  assert.match((await res.json()).text, /2 notifications/);
});

test('summarize rejects empty or oversized message lists', async () => {
  assert.equal((await post(base, { messages: [] }, SECRET, '/summarize')).status, 400);
  const many = Array.from({ length: 61 }, () => ({ text: 'hi' }));
  assert.equal((await post(base, { messages: many }, SECRET, '/summarize')).status, 400);
});

test('screen and summary prompts tie the reply language to the question', async () => {
  const seen = [];
  const url = await start({
    name: 'spy',
    model: 'x',
    configured: true,
    interpret: async () => ({ tool: 'answer', args: { text: 'hi' } }),
    generate: async (req) => {
      seen.push(req);
      return 'ok';
    },
  });
  await post(url, { question: 'explain the screen', image: IMAGE, locale: 'hi-IN' }, SECRET, '/screen');
  await post(url, { question: 'what did I miss', messages: [{ text: 'नमस्ते' }] }, SECRET, '/summarize');
  for (const req of seen) {
    assert.match(req.system, /same language the user's question is written in/);
    assert.doesNotMatch(req.system, /prefers Hindi/);
    assert.match(req.text, /same language as this question/);
  }
  assert.equal(seen.length, 2);
});
