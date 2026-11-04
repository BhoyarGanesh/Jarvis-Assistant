import { timingSafeEqual } from 'node:crypto';
import { WebSocketServer } from 'ws';
import { z } from 'zod';
import { ProviderError, isMissingSecret } from './providers/errors.js';
import { LIVE_PROMPT, buildLiveContext } from './prompt.js';
import { TOOLS, jsonSchemaFor, normalizeToolCall } from './tools.js';
import { VOICE_PATTERN } from './speech.js';

/** The phone streams 16-bit mono PCM at this rate... */
export const LIVE_INPUT_RATE = 16_000;
/** ...and plays what comes back at this one. */
export const LIVE_OUTPUT_RATE = 24_000;

const GEMINI_LIVE_URL =
  'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent';

// In Live mode Gemini speaks its answers itself, so there is no answer() tool.
const LIVE_TOOLS = TOOLS.filter((t) => t.name !== 'answer').map((t) => ({
  name: t.name,
  description: t.description,
  parametersJsonSchema: jsonSchemaFor(t),
}));

const MESSAGES = {
  rate_limited: "Live mode is busy right now, so I'll use the normal mode.",
  not_configured: "Live mode needs a Gemini API key on the backend, so I'll use the normal mode.",
  upstream: "I couldn't start Live mode, so I'll use the normal mode.",
  dropped: 'The live connection dropped. Say Jarvis to start again.',
  quota_mid_session: "I've hit the free Live quota for now. Say Jarvis to continue in normal mode.",
};

const StartMessage = z.object({
  type: z.literal('start'),
  voice: z.string().regex(VOICE_PATTERN).optional(),
  now: z.string().max(64).optional(),
  locale: z.string().max(20).optional(),
  contacts: z.array(z.string().trim().min(1).max(100)).max(2000).optional(),
});

const PhoneMessage = z.discriminatedUnion('type', [
  z.object({ type: z.literal('text'), text: z.string().trim().min(1).max(500) }),
  z.object({
    type: z.literal('toolResult'),
    id: z.string().min(1).max(200),
    name: z.string().min(1).max(64),
    result: z.string().max(4000),
  }),
  z.object({ type: z.literal('end') }),
]);

const isQuota = (code, reason) => /quota|exhaust|rate.?limit|429/i.test(reason) || code === 1013;

/**
 * Opens Gemini Live sessions, trying each model in turn. A model that refuses for quota reasons is
 * skipped until its cooldown ends, like the text-to-speech models.
 */
export class GeminiLive {
  /**
   * @param {{ apiKey?: string, models: string[], voice: string, setupTimeoutMs?: number,
   *           connect?: (url: string) => WebSocket, now?: () => number, logger?: Pick<Console, 'warn'> }} opts
   */
  constructor({ apiKey, models, voice, setupTimeoutMs = 8_000, connect, now = Date.now, logger = console }) {
    this.models = models;
    this.voice = voice;
    this.setupTimeoutMs = setupTimeoutMs;
    this.now = now;
    this.logger = logger;
    this.configured = Boolean(connect) || !isMissingSecret(apiKey);
    this.connect = connect ?? ((url) => new WebSocket(url));
    this.url = `${GEMINI_LIVE_URL}?key=${encodeURIComponent(apiKey ?? '')}`;
    /** model -> epoch ms until which it is out of quota */
    this.cooldownUntil = new Map();
  }

  setupFor(model, { voice, now, contacts }) {
    const context = buildLiveContext({ now, contacts });
    return {
      setup: {
        model: `models/${model}`,
        generationConfig: {
          responseModalities: ['AUDIO'],
          speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: voice || this.voice } } },
        },
        systemInstruction: { parts: [{ text: context ? `${LIVE_PROMPT}\n\n${context}` : LIVE_PROMPT }] },
        tools: [{ functionDeclarations: LIVE_TOOLS }],
        realtimeInputConfig: {
          automaticActivityDetection: {
            // Low start sensitivity: echoes of her own voice and room noise shouldn't count as barge-in.
            startOfSpeechSensitivity: 'START_SENSITIVITY_LOW',
            prefixPaddingMs: 40,
            silenceDurationMs: 700,
          },
        },
        inputAudioTranscription: {},
        outputAudioTranscription: {},
        contextWindowCompression: { slidingWindow: {} },
      },
    };
  }

  /** Resolves with an upstream socket that has completed setup. */
  async open(options) {
    if (!this.configured) throw new ProviderError('not_configured', 'GEMINI_API_KEY is not set in backend/.env');
    let quota = false;
    let lastError;
    for (const model of this.models) {
      if ((this.cooldownUntil.get(model) ?? 0) > this.now()) {
        quota = true;
        continue;
      }
      try {
        const socket = await this.handshake(model, options);
        return { socket, model };
      } catch (err) {
        lastError = err;
        if (err.quota) {
          quota = true;
          this.cooldownUntil.set(model, this.now() + 60_000);
        }
        this.logger.warn(`[live] ${model} failed (${err.message}), trying next model`);
      }
    }
    if (quota) throw new ProviderError('rate_limited', 'Every Live model is out of quota', { retryAfterSeconds: 60 });
    throw new ProviderError('upstream', `Gemini Live failed: ${lastError?.message ?? 'no models'}`);
  }

  handshake(model, options) {
    return new Promise((resolve, reject) => {
      const socket = this.connect(this.url);
      socket.binaryType = 'arraybuffer';
      const fail = (message, quota = false) => {
        clearTimeout(timer);
        socket.removeEventListener('message', onMessage);
        socket.removeEventListener('close', onClose);
        try {
          socket.close();
        } catch {}
        reject(Object.assign(new Error(message), { quota }));
      };
      const timer = setTimeout(() => fail('setup timed out'), this.setupTimeoutMs);
      const onMessage = (event) => {
        const msg = parse(event.data);
        if (!msg?.setupComplete) return;
        clearTimeout(timer);
        socket.removeEventListener('message', onMessage);
        socket.removeEventListener('close', onClose);
        resolve(socket);
      };
      // Gemini reports a bad model or an exhausted quota by closing the socket with a reason.
      const onClose = (event) => fail(`closed ${event.code} ${String(event.reason).slice(0, 120)}`, isQuota(event.code, String(event.reason)));
      socket.addEventListener('open', () => socket.send(JSON.stringify(this.setupFor(model, options))));
      socket.addEventListener('message', onMessage);
      socket.addEventListener('close', onClose);
      socket.addEventListener('error', () => {});
    });
  }
}

function parse(data) {
  try {
    return JSON.parse(typeof data === 'string' ? data : Buffer.from(data).toString('utf8'));
  } catch {
    return null;
  }
}

function safeEqual(a, b) {
  const ab = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return ab.length === bb.length && timingSafeEqual(ab, bb);
}

/**
 * Serves `GET /live` (WebSocket) on [server]: the phone streams microphone PCM in and gets her voice
 * back, while the Gemini API key stays here. Phone protocol:
 *  - phone → backend: first `{"type":"start", voice?, now?, locale?, contacts?}`, then binary 16 kHz PCM,
 *    `{"type":"text","text"}`, `{"type":"toolResult","id","name","result"}`, `{"type":"end"}`.
 *  - backend → phone: `{"type":"ready","model","inputRate","outputRate"}`, binary 24 kHz PCM,
 *    `{"type":"heard","text"}`, `{"type":"said","text"}`, `{"type":"interrupted"}`, `{"type":"turnComplete"}`,
 *    `{"type":"toolCall","id","tool","args"}` (validated like /interpret), `{"type":"toolCancelled","ids"}`,
 *    `{"type":"goAway"}`, `{"type":"error","error","message"}`.
 * Audio and transcripts are never logged.
 *
 * @param {import('node:http').Server} server
 * @param {{ sharedSecret?: string, live: GeminiLive | null, maxSessions?: number, maxSessionMs?: number,
 *           startTimeoutMs?: number, logger?: Pick<Console, 'info'|'error'> }} opts
 */
export function attachLive(server, { sharedSecret, live, maxSessions = 2, maxSessionMs = 10 * 60_000, startTimeoutMs = 5_000, logger = console }) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: 256 * 1024 });
  let active = 0;

  server.on('upgrade', (req, socket, head) => {
    const reject = (status, text) => {
      socket.end(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
    };
    if (new URL(req.url ?? '/', 'http://local').pathname !== '/live') return reject(404, 'Not Found');
    if (isMissingSecret(sharedSecret)) return reject(500, 'Not Configured');
    if (!safeEqual(req.headers['x-jarvis-secret'] ?? '', sharedSecret)) return reject(401, 'Unauthorized');
    if (active >= maxSessions) return reject(429, 'Too Many Requests');
    active++;
    wss.handleUpgrade(req, socket, head, (client) => {
      runSession(client, { live, maxSessionMs, startTimeoutMs, logger })
        .catch((err) => logger.error(`[live] ${err.message}`))
        .finally(() => active--);
    });
  });

  return wss;
}

async function runSession(client, { live, maxSessionMs, startTimeoutMs, logger }) {
  const sendJson = (msg) => {
    if (client.readyState === client.OPEN) client.send(JSON.stringify(msg));
  };
  const endWith = (error, message) => {
    sendJson({ type: 'error', error, message });
    client.close(1000);
  };

  const start = await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), startTimeoutMs);
    client.once('message', (data, isBinary) => {
      clearTimeout(timer);
      resolve(isBinary ? null : StartMessage.safeParse(parse(data)).data ?? null);
    });
    client.once('close', () => resolve(null));
  });
  if (!start) return endWith('invalid_request', 'The live session did not start properly.');
  if (!live?.configured) return endWith('not_configured', MESSAGES.not_configured);

  const started = Date.now();
  let upstream;
  let model;
  try {
    ({ socket: upstream, model } = await live.open(start));
  } catch (err) {
    const kind = err instanceof ProviderError ? err.kind : 'upstream';
    logger.error(`[live] could not start: ${err.message}`);
    return endWith(kind, MESSAGES[kind] ?? MESSAGES.upstream);
  }
  if (client.readyState !== client.OPEN) {
    upstream.close();
    return;
  }
  logger.info(`[live] session started on ${model} after ${Date.now() - started}ms`);
  sendJson({ type: 'ready', model, inputRate: LIVE_INPUT_RATE, outputRate: LIVE_OUTPUT_RATE });

  const tools = [];
  const send = (msg) => {
    if (upstream.readyState === 1) upstream.send(JSON.stringify(msg));
  };
  const limit = setTimeout(() => endWith('session_limit', 'That was a long chat. Say Jarvis to start a new one.'), maxSessionMs);

  upstream.addEventListener('message', (event) => {
    const msg = parse(event.data);
    if (!msg) return;
    const content = msg.serverContent;
    if (content) {
      for (const part of content.modelTurn?.parts ?? []) {
        if (part.inlineData?.data && client.readyState === client.OPEN) client.send(Buffer.from(part.inlineData.data, 'base64'));
      }
      if (content.inputTranscription?.text) sendJson({ type: 'heard', text: content.inputTranscription.text });
      if (content.outputTranscription?.text) sendJson({ type: 'said', text: content.outputTranscription.text });
      if (content.interrupted) sendJson({ type: 'interrupted' });
      if (content.turnComplete) sendJson({ type: 'turnComplete' });
    }
    for (const call of msg.toolCall?.functionCalls ?? []) {
      const normalized = normalizeToolCall(call.name, call.args);
      if (normalized.tool !== call.name) {
        // Unknown tool or bad arguments: let Gemini correct itself without bothering the phone.
        send({ toolResponse: { functionResponses: [{ id: call.id, name: call.name, response: { error: 'Invalid tool or arguments. Check the schema and try again.' } }] } });
        continue;
      }
      tools.push(call.name);
      sendJson({ type: 'toolCall', id: call.id, tool: normalized.tool, args: normalized.args });
    }
    if (msg.toolCallCancellation?.ids) sendJson({ type: 'toolCancelled', ids: msg.toolCallCancellation.ids });
    if (msg.goAway) sendJson({ type: 'goAway' });
  });

  upstream.addEventListener('close', (event) => {
    const reason = String(event.reason);
    if (client.readyState === client.OPEN) {
      if (isQuota(event.code, reason)) endWith('rate_limited', MESSAGES.quota_mid_session);
      else endWith('upstream', MESSAGES.dropped);
    }
  });
  upstream.addEventListener('error', () => {});

  client.on('message', (data, isBinary) => {
    if (isBinary) {
      send({ realtimeInput: { audio: { data: Buffer.from(data).toString('base64'), mimeType: `audio/pcm;rate=${LIVE_INPUT_RATE}` } } });
      return;
    }
    const parsed = PhoneMessage.safeParse(parse(data));
    if (!parsed.success) return;
    const m = parsed.data;
    if (m.type === 'text') send({ realtimeInput: { text: m.text } });
    else if (m.type === 'toolResult') send({ toolResponse: { functionResponses: [{ id: m.id, name: m.name, response: { result: m.result } }] } });
    else client.close(1000);
  });

  await new Promise((resolve) => client.once('close', resolve));
  clearTimeout(limit);
  try {
    upstream.close();
  } catch {}
  // Only the length and the tool names, never what was said.
  logger.info(`[live] session ended after ${Math.round((Date.now() - started) / 1000)}s; tools: ${tools.join(', ') || 'none'}`);
}
