import { timingSafeEqual } from 'node:crypto';
import express from 'express';
import { rateLimit } from 'express-rate-limit';
import { z } from 'zod';
import { ProviderError, isMissingSecret } from './providers/errors.js';
import { SCREEN_PROMPT, SUMMARY_PROMPT, buildScreenMessage, buildSummaryMessage } from './prompt.js';
import { SAMPLE_RATE, VOICE_PATTERN } from './speech.js';

const InterpretBody = z.object({
  text: z.string().trim().min(1, 'text is required').max(500),
  contacts: z.array(z.string().trim().min(1).max(100)).max(2000).optional(),
  now: z.string().max(64).optional(),
  locale: z.string().max(20).optional(),
  // Earlier turns of the same conversation, oldest first, for follow-ups ("call him", "and tomorrow?").
  history: z
    .array(z.object({ user: z.string().trim().max(400), assistant: z.string().trim().max(400) }))
    .max(6)
    .optional(),
});

// A 1280px JPEG screenshot is ~150-400 KB as base64; leave headroom for dense screens.
const MAX_IMAGE_BASE64 = 3_000_000;

const ScreenBody = z.object({
  question: z.string().trim().min(1, 'question is required').max(300),
  image: z.string().min(1, 'image is required').max(MAX_IMAGE_BASE64).regex(/^[A-Za-z0-9+/]+=*$/, 'image must be base64'),
  mimeType: z.enum(['image/jpeg', 'image/png', 'image/webp']).default('image/jpeg'),
  locale: z.string().max(20).optional(),
});

const SummaryBody = z.object({
  question: z.string().trim().max(300).default(''),
  messages: z
    .array(
      z.object({
        app: z.string().trim().max(50).default(''),
        chat: z.string().trim().max(100).default(''),
        sender: z.string().trim().max(100).default(''),
        text: z.string().trim().min(1).max(4000),
        time: z.string().max(32).optional(),
      }),
    )
    .min(1, 'messages is required')
    .max(60),
  now: z.string().max(64).optional(),
  locale: z.string().max(20).optional(),
});

const SpeakBody = z.object({
  text: z.string().trim().min(1, 'text is required').max(1000),
  voice: z.string().regex(VOICE_PATTERN, 'unknown voice').optional(),
});

const MESSAGES = {
  unauthorized: 'The backend rejected my shared secret. Please check it in settings.',
  invalid_request: "I didn't receive anything to work with. Please try again.",
  rate_limited: "I'm getting too many requests right now. Please try again in a minute.",
  not_configured: "My brain isn't configured yet. Please add an API key to the backend.",
  upstream: "I couldn't reach my brain just now. Please try again.",
  local_rate_limited: "You're going a bit fast. Give me a moment and try again.",
};

function safeEqual(a, b) {
  const ab = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return ab.length === bb.length && timingSafeEqual(ab, bb);
}

/**
 * @param {{ provider: import('./providers/index.js').LLMProvider, sharedSecret?: string,
 *           speech?: import('./speech.js').GeminiSpeech | null,
 *           rateLimitPerMinute?: number, logger?: Pick<Console, 'info'|'error'> }} deps
 */
export function createApp({ provider, sharedSecret, speech = null, rateLimitPerMinute = 30, logger = console }) {
  const app = express();
  app.disable('x-powered-by');

  app.get('/health', (_req, res) => {
    res.json({
      ok: true,
      provider: provider.name,
      model: provider.model,
      configured: provider.configured,
      speech: Boolean(speech?.configured),
    });
  });

  const requireSecret = (req, res, next) => {
    if (isMissingSecret(sharedSecret)) {
      return res.status(500).json({ error: 'not_configured', message: 'APP_SHARED_SECRET is not set on the backend.' });
    }
    if (!safeEqual(req.get('x-jarvis-secret') ?? '', sharedSecret)) {
      return res.status(401).json({ error: 'unauthorized', message: MESSAGES.unauthorized });
    }
    next();
  };

  const limiter = rateLimit({
    windowMs: 60_000,
    limit: rateLimitPerMinute,
    standardHeaders: 'draft-8',
    legacyHeaders: false,
    handler: (_req, res) => res.status(429).json({ error: 'rate_limited', message: MESSAGES.local_rate_limited }),
  });

  /** Maps a provider failure to an HTTP status and a message the phone can read aloud. */
  const sendError = (res, path, err) => {
    const kind = err instanceof ProviderError ? err.kind : 'upstream';
    logger.error(`[${path.slice(1)}] ${kind}: ${err.message}`);
    if (kind === 'rate_limited' && err.retryAfterSeconds) res.set('Retry-After', String(err.retryAfterSeconds));
    const status = { rate_limited: 429, not_configured: 503, upstream: 502 }[kind];
    res.status(status).json({ error: kind, message: MESSAGES[kind] });
  };

  const badRequest = (res, error) =>
    res.status(400).json({
      error: 'invalid_request',
      message: MESSAGES.invalid_request,
      details: error.issues.map((i) => `${i.path.join('.') || 'body'}: ${i.message}`),
    });

  /** Validates the body with [schema], runs [work] and maps provider failures to friendly spoken errors. */
  const route = (path, bodyLimit, schema, work) =>
    app.post(path, requireSecret, limiter, express.json({ limit: bodyLimit }), async (req, res) => {
      const parsed = schema.safeParse(req.body ?? {});
      if (!parsed.success) return badRequest(res, parsed.error);
      const started = Date.now();
      try {
        const { result, log } = await work(parsed.data);
        const latencyMs = Date.now() - started;
        logger.info(`[${path.slice(1)}] ${provider.name} ${latencyMs}ms ${log}`);
        res.json({ ...result, provider: provider.name, model: provider.model, latencyMs });
      } catch (err) {
        sendError(res, path, err);
      }
    });

  route('/interpret', '64kb', InterpretBody, async (body) => {
    const call = await provider.interpret(body);
    // Earlier turns can hold message drafts, so only their count is logged.
    const followUp = body.history?.length ? ` (follow-up, ${body.history.length} earlier turns)` : '';
    return { result: call, log: `"${body.text}" -> ${call.tool}${followUp}` };
  });

  // Screenshots are never logged or stored; only the question is.
  route('/screen', '4mb', ScreenBody, async ({ question, image, mimeType }) => {
    const text = await provider.generate({
      system: SCREEN_PROMPT,
      text: buildScreenMessage({ question }),
      image: { mimeType, data: image },
    });
    return { result: { text }, log: `"${question}" (${Math.round((image.length * 3) / 4 / 1024)} KB image)` };
  });

  // Message contents are never logged; only how many were summarised.
  route('/summarize', '256kb', SummaryBody, async ({ question, messages, now }) => {
    const text = await provider.generate({
      system: SUMMARY_PROMPT,
      text: buildSummaryMessage({ question, messages, now }),
    });
    return { result: { text }, log: `${messages.length} notifications` };
  });

  /**
   * Streams the voice reply as raw 16-bit mono PCM (see X-Sample-Rate) so the phone can start
   * playing within a second. Errors before the first chunk are JSON, and the phone then falls back
   * to its own text-to-speech. Spoken text is never logged.
   */
  app.post('/speak', requireSecret, limiter, express.json({ limit: '16kb' }), async (req, res) => {
    const parsed = SpeakBody.safeParse(req.body ?? {});
    if (!parsed.success) return badRequest(res, parsed.error);
    if (!speech?.configured) {
      return sendError(res, '/speak', new ProviderError('not_configured', 'Speech needs GEMINI_API_KEY'));
    }
    const started = Date.now();
    const audio = speech.stream(parsed.data);
    // Stop generating if the phone hangs up mid-reply (the request's own 'close' fires once its body is read).
    res.on('close', () => {
      if (!res.writableFinished) audio.return(undefined).catch(() => {});
    });

    let first;
    try {
      first = await audio.next();
    } catch (err) {
      return sendError(res, '/speak', err);
    }
    res.status(200).set({
      'Content-Type': `audio/L16; rate=${SAMPLE_RATE}; channels=1`,
      'X-Sample-Rate': String(SAMPLE_RATE),
      'Cache-Control': 'no-store',
    });
    logger.info(`[speak] first audio after ${Date.now() - started}ms (${parsed.data.text.length} chars)`);
    try {
      if (!first.done) res.write(first.value);
      for await (const chunk of audio) res.write(chunk);
    } catch (err) {
      logger.error(`[speak] stream broke: ${err.message}`);
    } finally {
      res.end();
    }
  });

  app.use((_req, res) => res.status(404).json({ error: 'not_found', message: 'Not found' }));

  // Express 5 forwards JSON parse errors (and oversized bodies) here.
  app.use((err, _req, res, _next) => {
    const status = err.status ?? 500;
    if (status >= 500) logger.error(err);
    res.status(status).json({ error: status < 500 ? 'invalid_request' : 'server_error', message: MESSAGES.invalid_request });
  });

  return app;
}
