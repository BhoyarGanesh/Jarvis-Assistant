import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '@google/genai';
import { createApp } from '../src/app.js';
import { MockProvider } from '../src/providers/mock.js';
import { GeminiSpeech } from '../src/speech.js';

const SECRET = 'test-secret';
const quiet = { info() {}, error() {}, warn() {} };
const servers = [];
after(() => servers.forEach((s) => s.close()));

async function* chunks(...buffers) {
  for (const b of buffers) {
    yield { candidates: [{ content: { parts: [{ inlineData: { mimeType: 'audio/l16; rate=24000', data: b.toString('base64') } }] } }] };
  }
}

const quota = () => new ApiError({ status: 429, message: 'Quota exceeded. Please retry in 4.8s.' });

function fakeSpeech(impl, opts = {}) {
  const calls = [];
  const speech = new GeminiSpeech({
    models: ['tts-a', 'tts-b'],
    voice: 'Despina',
    logger: quiet,
    client: {
      models: {
        generateContentStream: async (req) => {
          calls.push(req);
          return impl(req);
        },
      },
    },
    ...opts,
  });
  return { speech, calls };
}

async function collect(stream) {
  const out = [];
  for await (const c of stream) out.push(c);
  return Buffer.concat(out);
}

test('streams PCM chunks with the requested voice', async () => {
  const { speech, calls } = fakeSpeech(() => chunks(Buffer.from([1, 2]), Buffer.from([3, 4])));
  assert.deepEqual([...(await collect(speech.stream({ text: 'hi', voice: 'Leda' })))], [1, 2, 3, 4]);
  assert.equal(calls[0].model, 'tts-a');
  assert.deepEqual(calls[0].config.responseModalities, ['AUDIO']);
  assert.equal(calls[0].config.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName, 'Leda');
});

test('falls back to the next model on quota and skips it while it cools down', async () => {
  let now = 0;
  const { speech, calls } = fakeSpeech(
    (req) => {
      if (req.model === 'tts-a') throw quota();
      return chunks(Buffer.from([9]));
    },
    { now: () => now },
  );
  await collect(speech.stream({ text: 'one' }));
  await collect(speech.stream({ text: 'two' }));
  assert.deepEqual(calls.map((c) => c.model), ['tts-a', 'tts-b', 'tts-b']);
  now = 6_000; // cooldown (4.8s + margin) is over
  await collect(speech.stream({ text: 'three' }));
  assert.equal(calls.at(-1).model, 'tts-b');
  assert.equal(calls.at(-2).model, 'tts-a');
});

test('reports rate_limited when every model is cooling down', async () => {
  const { speech } = fakeSpeech(() => {
    throw quota();
  });
  await assert.rejects(collect(speech.stream({ text: 'hi' })), { kind: 'rate_limited' });
  await assert.rejects(collect(speech.stream({ text: 'hi' })), (err) => err.kind === 'rate_limited' && err.retryAfterSeconds >= 1);
});

test('non-transient errors do not try other models', async () => {
  const { speech, calls } = fakeSpeech(() => {
    throw new ApiError({ status: 400, message: 'bad voice' });
  });
  await assert.rejects(collect(speech.stream({ text: 'hi' })), { kind: 'upstream' });
  assert.equal(calls.length, 1);
});

async function start(speech) {
  const app = createApp({ provider: new MockProvider(), sharedSecret: SECRET, speech, logger: quiet });
  const server = await new Promise((resolve) => {
    const s = app.listen(0, '127.0.0.1', () => resolve(s));
  });
  servers.push(server);
  return `http://127.0.0.1:${server.address().port}`;
}

const speak = (base, body, secret = SECRET) =>
  fetch(`${base}/speak`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-jarvis-secret': secret },
    body: JSON.stringify(body),
  });

test('/speak streams raw audio with the sample rate header', async () => {
  const base = await start(fakeSpeech(() => chunks(Buffer.from([1, 0]), Buffer.from([2, 0]))).speech);
  const res = await speak(base, { text: 'Message sent', voice: 'Despina' });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('x-sample-rate'), '24000');
  assert.match(res.headers.get('content-type'), /audio\/L16/);
  assert.deepEqual([...new Uint8Array(await res.arrayBuffer())], [1, 0, 2, 0]);
});

test('/speak returns JSON errors so the phone can use its own voice', async () => {
  const busy = await start(fakeSpeech(() => {
    throw quota();
  }).speech);
  const res = await speak(busy, { text: 'hi' });
  assert.equal(res.status, 429);
  assert.equal((await res.json()).error, 'rate_limited');

  const none = await start(null);
  assert.equal((await speak(none, { text: 'hi' })).status, 503);
  assert.equal((await speak(none, { text: 'hi' }, 'wrong')).status, 401);
  assert.equal((await speak(none, { text: '' })).status, 400);
  assert.equal((await speak(none, { text: 'hi', voice: '../etc' })).status, 400);
});
