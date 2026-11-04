import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import WebSocket from 'ws';
import { GeminiLive, attachLive } from '../src/live.js';

const SECRET = 'test-secret';
const quiet = { info() {}, error() {}, warn() {} };
const servers = [];
after(() => servers.forEach((s) => s.close()));

/** Stands in for Gemini's Live socket. [behave] decides how setup goes. */
class FakeUpstream extends EventTarget {
  constructor(url, behave) {
    super();
    this.url = url;
    this.sent = [];
    this.readyState = 0;
    this.behave = behave;
    setImmediate(() => {
      this.readyState = 1;
      this.dispatchEvent(new Event('open'));
    });
  }

  send(data) {
    const msg = JSON.parse(data);
    this.sent.push(msg);
    if (msg.setup) this.behave(this, msg.setup);
  }

  emit(msg) {
    const event = new Event('message');
    event.data = JSON.stringify(msg);
    this.dispatchEvent(event);
  }

  close(code = 1000, reason = '') {
    if (this.readyState === 3) return;
    this.readyState = 3;
    const event = new Event('close');
    event.code = code;
    event.reason = reason;
    this.dispatchEvent(event);
  }
}

const accept = (up) => setImmediate(() => up.emit({ setupComplete: {} }));

async function start({ behave = accept, models = ['live-a', 'live-b'], now } = {}) {
  const upstreams = [];
  const live = new GeminiLive({
    models,
    voice: 'Despina',
    connect: (url) => {
      const up = new FakeUpstream(url, (u, setup) => behave(u, setup, upstreams.length));
      upstreams.push(up);
      return up;
    },
    now,
    setupTimeoutMs: 500,
    logger: quiet,
  });
  const server = createServer();
  attachLive(server, { sharedSecret: SECRET, live, logger: quiet, startTimeoutMs: 500 });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  servers.push(server);
  return { url: `ws://127.0.0.1:${server.address().port}/live`, upstreams, live };
}

/** Connects like the phone and collects every JSON and binary message. */
function connect(url, secret = SECRET) {
  const ws = new WebSocket(url, { headers: secret ? { 'x-jarvis-secret': secret } : {} });
  const json = [];
  const audio = [];
  const waiters = [];
  ws.on('message', (data, isBinary) => {
    if (isBinary) audio.push(Buffer.from(data));
    else json.push(JSON.parse(data.toString()));
    waiters.splice(0).forEach((w) => w());
  });
  const until = async (pred) => {
    for (let i = 0; i < 100 && !pred(); i++) await new Promise((r) => (waiters.push(r), setTimeout(r, 20)));
    assert.ok(pred(), `timed out; got ${JSON.stringify(json)}`);
  };
  const opened = new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('unexpected-response', (_req, res) => reject(Object.assign(new Error('rejected'), { status: res.statusCode })));
    ws.once('error', reject);
  });
  return { ws, json, audio, until, opened };
}

const waitFor = async (pred) => {
  for (let i = 0; i < 100 && !pred(); i++) await new Promise((r) => setTimeout(r, 10));
  assert.ok(pred());
};

test('live refuses a wrong secret', async () => {
  const { url } = await start();
  for (const secret of [null, 'nope']) {
    const phone = connect(url, secret);
    await assert.rejects(phone.opened, (err) => err.status === 401);
  }
});

test('live relays audio, transcripts, interruptions and validated tool calls', async () => {
  const { url, upstreams } = await start();
  const phone = connect(url);
  await phone.opened;
  phone.ws.send(JSON.stringify({ type: 'start', voice: 'Kore', now: '2026-11-04 18:30 Wed', contacts: ['King'] }));
  await phone.until(() => phone.json.some((m) => m.type === 'ready'));
  const ready = phone.json.find((m) => m.type === 'ready');
  assert.deepEqual(ready, { type: 'ready', model: 'live-a', inputRate: 16000, outputRate: 24000 });

  const up = upstreams[0];
  const setup = up.sent[0].setup;
  assert.equal(setup.model, 'models/live-a');
  assert.equal(setup.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName, 'Kore');
  assert.match(setup.systemInstruction.parts[0].text, /Known contact names: King/);
  assert.ok(!setup.tools[0].functionDeclarations.some((f) => f.name === 'answer'));
  assert.ok(setup.tools[0].functionDeclarations.some((f) => f.name === 'call_contact'));
  assert.equal(setup.realtimeInputConfig.automaticActivityDetection.startOfSpeechSensitivity, 'START_SENSITIVITY_LOW');

  // Microphone audio goes up as base64 realtime input.
  const pcm = Buffer.from([1, 2, 3, 4]);
  phone.ws.send(pcm);
  await waitFor(() => up.sent.some((m) => m.realtimeInput?.audio));
  const audio = up.sent.find((m) => m.realtimeInput?.audio).realtimeInput.audio;
  assert.equal(audio.mimeType, 'audio/pcm;rate=16000');
  assert.deepEqual(Buffer.from(audio.data, 'base64'), pcm);

  // Her voice comes down as binary; the rest as small JSON events.
  up.emit({ serverContent: { modelTurn: { parts: [{ inlineData: { mimeType: 'audio/pcm;rate=24000', data: Buffer.from([9, 8]).toString('base64') } }] } } });
  up.emit({ serverContent: { inputTranscription: { text: 'call king' } } });
  up.emit({ serverContent: { outputTranscription: { text: 'Calling' } } });
  up.emit({ serverContent: { interrupted: true } });
  up.emit({ serverContent: { turnComplete: true } });
  up.emit({ toolCall: { functionCalls: [{ id: 'c1', name: 'call_contact', args: { name: 'King' } }] } });
  up.emit({ toolCall: { functionCalls: [{ id: 'c2', name: 'set_alarm', args: { hour: 99 } }] } });
  await phone.until(() => phone.json.some((m) => m.type === 'toolCall'));
  await phone.until(() => phone.audio.length === 1);
  assert.deepEqual(phone.audio[0], Buffer.from([9, 8]));
  const types = phone.json.map((m) => m.type);
  for (const t of ['heard', 'said', 'interrupted', 'turnComplete']) assert.ok(types.includes(t), t);
  assert.deepEqual(phone.json.find((m) => m.type === 'toolCall'), { type: 'toolCall', id: 'c1', tool: 'call_contact', args: { name: 'King' } });
  assert.equal(phone.json.filter((m) => m.type === 'toolCall').length, 1, 'invalid calls never reach the phone');
  await waitFor(() => up.sent.some((m) => m.toolResponse?.functionResponses[0].id === 'c2'));
  assert.match(up.sent.find((m) => m.toolResponse).toolResponse.functionResponses[0].response.error, /Invalid/);

  // Tool results and typed text go back up.
  phone.ws.send(JSON.stringify({ type: 'toolResult', id: 'c1', name: 'call_contact', result: 'success: Calling King' }));
  phone.ws.send(JSON.stringify({ type: 'text', text: 'and tomorrow?' }));
  await waitFor(() => up.sent.some((m) => m.realtimeInput?.text === 'and tomorrow?'));
  const result = up.sent.find((m) => m.toolResponse?.functionResponses[0].id === 'c1').toolResponse.functionResponses[0];
  assert.deepEqual(result, { id: 'c1', name: 'call_contact', response: { result: 'success: Calling King' } });

  phone.ws.send(JSON.stringify({ type: 'end' }));
  await waitFor(() => up.readyState === 3);
});

test('live falls back to the next model and cools down one that is out of quota', async () => {
  let clock = 1_000;
  const { url, live } = await start({
    now: () => clock,
    behave: (up, setup) => (setup.model === 'models/live-a' ? setImmediate(() => up.close(1011, 'You exceeded your current quota')) : accept(up)),
  });
  const phone = connect(url);
  await phone.opened;
  phone.ws.send(JSON.stringify({ type: 'start' }));
  await phone.until(() => phone.json.some((m) => m.type === 'ready'));
  assert.equal(phone.json.find((m) => m.type === 'ready').model, 'live-b');
  assert.ok(live.cooldownUntil.get('live-a') > clock);
  phone.ws.close();
});

test('live reports rate limiting when every model is out of quota', async () => {
  const { url } = await start({ behave: (up) => setImmediate(() => up.close(1011, 'RESOURCE_EXHAUSTED quota')) });
  const phone = connect(url);
  await phone.opened;
  phone.ws.send(JSON.stringify({ type: 'start' }));
  await phone.until(() => phone.json.some((m) => m.type === 'error'));
  const error = phone.json.find((m) => m.type === 'error');
  assert.equal(error.error, 'rate_limited');
  assert.match(error.message, /normal mode/);
});

test('live reports quota running out mid-conversation', async () => {
  const { url, upstreams } = await start();
  const phone = connect(url);
  await phone.opened;
  phone.ws.send(JSON.stringify({ type: 'start' }));
  await phone.until(() => phone.json.some((m) => m.type === 'ready'));
  upstreams[0].close(1011, 'quota exceeded');
  await phone.until(() => phone.json.some((m) => m.type === 'error'));
  assert.equal(phone.json.find((m) => m.type === 'error').error, 'rate_limited');
});

test('live needs a valid start message', async () => {
  const { url } = await start();
  const phone = connect(url);
  await phone.opened;
  phone.ws.send(JSON.stringify({ type: 'start', voice: 'not a voice!' }));
  await phone.until(() => phone.json.some((m) => m.type === 'error'));
  assert.equal(phone.json[0].error, 'invalid_request');
});

test('live without a key says so', async () => {
  const live = new GeminiLive({ models: ['m'], voice: 'Despina', logger: quiet });
  assert.equal(live.configured, false);
  await assert.rejects(live.open({}), (err) => err.kind === 'not_configured');
});
