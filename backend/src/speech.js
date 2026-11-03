import { GoogleGenAI } from '@google/genai';
import { ProviderError, isMissingSecret } from './providers/errors.js';
import { statusOf, isTimeout, mapGeminiError } from './providers/gemini.js';

/** Gemini TTS streams 16-bit little-endian mono PCM at this rate. */
export const SAMPLE_RATE = 24_000;

// Prebuilt Gemini voices: one capitalised word.
export const VOICE_PATTERN = /^[A-Z][a-z]{2,20}$/;

const FALLBACK_STATUSES = new Set([429, 500, 502, 503, 504]);

/**
 * Text-to-speech through Gemini's TTS models, streamed so the phone can start playing
 * after the first chunk. The free tier allows only a few requests per minute per model,
 * so a model that answers 429 is skipped until its cooldown ends and the next one is used.
 */
export class GeminiSpeech {
  /**
   * @param {{ apiKey?: string, models: string[], voice: string, timeoutMs?: number,
   *           client?: { models: { generateContentStream: Function } }, now?: () => number,
   *           logger?: Pick<Console, 'warn'> }} opts
   */
  constructor({ apiKey, models, voice, timeoutMs = 15_000, client, now = Date.now, logger = console }) {
    this.models = models;
    this.voice = voice;
    this.timeoutMs = timeoutMs;
    this.now = now;
    this.logger = logger;
    this.configured = Boolean(client) || !isMissingSecret(apiKey);
    this.client = client ?? (this.configured ? new GoogleGenAI({ apiKey }) : null);
    /** model -> epoch ms until which it is rate limited */
    this.cooldownUntil = new Map();
  }

  /**
   * Yields raw PCM chunks (Buffers). Model fallback only happens before the first chunk;
   * once audio has started a failure ends the stream.
   * @param {{ text: string, voice?: string }} req
   */
  async *stream({ text, voice }) {
    if (!this.configured) throw new ProviderError('not_configured', 'GEMINI_API_KEY is not set in backend/.env');
    let lastError;
    for (const model of this.models) {
      if ((this.cooldownUntil.get(model) ?? 0) > this.now()) continue;
      let started = false;
      try {
        const chunks = await this.client.models.generateContentStream({
          model,
          contents: [{ role: 'user', parts: [{ text }] }],
          config: {
            responseModalities: ['AUDIO'],
            speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: voice || this.voice } } },
            httpOptions: { timeout: this.timeoutMs, retryOptions: { attempts: 1 } },
          },
        });
        for await (const chunk of chunks) {
          for (const part of chunk.candidates?.[0]?.content?.parts ?? []) {
            if (part.inlineData?.data) {
              started = true;
              yield Buffer.from(part.inlineData.data, 'base64');
            }
          }
        }
        if (started) return;
        lastError = new ProviderError('upstream', `${model} returned no audio`);
      } catch (err) {
        if (started) throw mapGeminiError(err);
        lastError = err;
        const status = statusOf(err);
        if (status === 429) this.cooldownUntil.set(model, this.now() + retryDelayMs(err));
        if (!FALLBACK_STATUSES.has(status) && !isTimeout(err)) break;
        this.logger.warn(`[speak] ${model} failed (${status ?? err.message}), trying next model`);
      }
    }
    if (!lastError) {
      throw new ProviderError('rate_limited', 'Every voice model is cooling down', { retryAfterSeconds: this.secondsUntilFree() });
    }
    throw lastError instanceof ProviderError ? lastError : mapGeminiError(lastError);
  }

  secondsUntilFree() {
    const soonest = Math.min(...this.models.map((m) => this.cooldownUntil.get(m) ?? 0));
    return Math.max(1, Math.ceil((soonest - this.now()) / 1000));
  }
}

/** Reads Google's RetryInfo ("Please retry in 4.8s"); defaults to a minute. */
function retryDelayMs(err) {
  const match = /retry in ([\d.]+)s/i.exec(err?.message ?? '');
  return match ? Math.ceil(Number(match[1]) * 1000) + 500 : 60_000;
}
