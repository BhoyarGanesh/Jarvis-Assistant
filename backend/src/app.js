import { timingSafeEqual } from 'node:crypto';
import express from 'express';
import { rateLimit } from 'express-rate-limit';
import { z } from 'zod';
import { ProviderError, isMissingSecret } from './providers/errors.js';

const InterpretBody = z.object({
  text: z.string().trim().min(1, 'text is required').max(500),
  contacts: z.array(z.string().trim().min(1).max(100)).max(2000).optional(),
  now: z.string().max(64).optional(),
  locale: z.string().max(20).optional(),
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
 *           rateLimitPerMinute?: number, logger?: Pick<Console, 'info'|'error'> }} deps
 */
export function createApp({ provider, sharedSecret, rateLimitPerMinute = 30, logger = console }) {
  const app = express();
  app.disable('x-powered-by');
  app.use(express.json({ limit: '64kb' }));

  app.get('/health', (_req, res) => {
    res.json({ ok: true, provider: provider.name, model: provider.model, configured: provider.configured });
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

  app.post('/interpret', requireSecret, limiter, async (req, res) => {
    const parsed = InterpretBody.safeParse(req.body ?? {});
    if (!parsed.success) {
      return res.status(400).json({
        error: 'invalid_request',
        message: MESSAGES.invalid_request,
        details: parsed.error.issues.map((i) => `${i.path.join('.') || 'body'}: ${i.message}`),
      });
    }

    const started = Date.now();
    try {
      const call = await provider.interpret(parsed.data);
      const latencyMs = Date.now() - started;
      logger.info(`[interpret] ${provider.name} ${latencyMs}ms "${parsed.data.text}" -> ${call.tool}`);
      res.json({ ...call, provider: provider.name, model: provider.model, latencyMs });
    } catch (err) {
      const kind = err instanceof ProviderError ? err.kind : 'upstream';
      logger.error(`[interpret] ${kind}: ${err.message}`);
      if (kind === 'rate_limited' && err.retryAfterSeconds) res.set('Retry-After', String(err.retryAfterSeconds));
      const status = { rate_limited: 429, not_configured: 503, upstream: 502 }[kind];
      res.status(status).json({ error: kind, message: MESSAGES[kind] });
    }
  });

  app.use((_req, res) => res.status(404).json({ error: 'not_found', message: 'Not found' }));

  // Express 5 forwards JSON parse errors here.
  app.use((err, _req, res, _next) => {
    const status = err.status ?? 500;
    if (status >= 500) logger.error(err);
    res.status(status).json({ error: status === 400 ? 'invalid_request' : 'server_error', message: MESSAGES.invalid_request });
  });

  return app;
}
