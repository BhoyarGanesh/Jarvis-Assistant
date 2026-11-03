import { GoogleGenAI, FunctionCallingConfigMode, ApiError } from '@google/genai';
import { SYSTEM_PROMPT, buildUserMessage } from '../prompt.js';
import { TOOLS, jsonSchemaFor, normalizeToolCall, answer, FALLBACK_TEXT } from '../tools.js';
import { ProviderError, isMissingSecret } from './errors.js';

const FUNCTION_DECLARATIONS = TOOLS.map((t) => ({
  name: t.name,
  description: t.description,
  parametersJsonSchema: jsonSchemaFor(t),
}));

// Overload, quota and transient server errors are worth trying on the next model.
const FALLBACK_STATUSES = new Set([429, 500, 502, 503, 504]);

/** @implements {import('./index.js').LLMProvider} */
export class GeminiProvider {
  name = 'gemini';

  /**
   * @param {{ apiKey?: string, model: string, fallbackModels?: string[], timeoutMs?: number,
   *           client?: { models: { generateContent: Function } } }} opts
   */
  constructor({ apiKey, model, fallbackModels = [], timeoutMs = 12_000, generateTimeoutMs = 25_000, client }) {
    this.model = model;
    this.models = [model, ...fallbackModels.filter((m) => m && m !== model)];
    this.timeoutMs = timeoutMs;
    this.generateTimeoutMs = generateTimeoutMs;
    this.configured = Boolean(client) || !isMissingSecret(apiKey);
    this.client = client ?? (this.configured ? new GoogleGenAI({ apiKey }) : null);
  }

  async interpret(request) {
    return toToolCall(await this.withFallback((model) => this.call(model, request)));
  }

  /** Free-form spoken reply, optionally about an image (screen questions, summaries). */
  async generate({ system, text, image }) {
    const parts = [];
    if (image) parts.push({ inlineData: { mimeType: image.mimeType, data: image.data } });
    parts.push({ text });
    const response = await this.withFallback((model) =>
      this.client.models.generateContent({
        model,
        contents: [{ role: 'user', parts }],
        config: {
          systemInstruction: system,
          maxOutputTokens: 1024,
          httpOptions: { timeout: this.generateTimeoutMs, retryOptions: { attempts: 1 } },
        },
      }),
    );
    return response.text?.trim() || FALLBACK_TEXT;
  }

  async withFallback(request) {
    if (!this.configured) {
      throw new ProviderError('not_configured', 'GEMINI_API_KEY is not set in backend/.env');
    }
    let lastError;
    for (const model of this.models) {
      try {
        return await request(model);
      } catch (err) {
        lastError = err;
        if (!FALLBACK_STATUSES.has(statusOf(err)) && !isTimeout(err)) break;
        console.warn(`[gemini] ${model} failed (${statusOf(err) ?? err.message}), trying next model`);
      }
    }
    throw mapGeminiError(lastError);
  }

  call(model, request) {
    return this.client.models.generateContent({
      model,
      contents: [{ role: 'user', parts: [{ text: buildUserMessage(request) }] }],
      config: {
        systemInstruction: SYSTEM_PROMPT,
        tools: [{ functionDeclarations: FUNCTION_DECLARATIONS }],
        toolConfig: { functionCallingConfig: { mode: FunctionCallingConfigMode.ANY } },
        maxOutputTokens: 1024,
        // A voice reply can't wait on the SDK's default exponential backoff; we fall back to another model instead.
        httpOptions: { timeout: this.timeoutMs, retryOptions: { attempts: 1 } },
      },
    });
  }
}

function toToolCall(response) {
  const call = response.functionCalls?.[0];
  if (call) return normalizeToolCall(call.name, call.args);
  const text = response.text?.trim();
  return answer(text || FALLBACK_TEXT);
}

export function statusOf(err) {
  return err instanceof ApiError ? err.status : err?.status;
}

export function isTimeout(err) {
  return err?.name === 'AbortError' || err?.name === 'TimeoutError' || /timed? ?out|aborted/i.test(err?.message ?? '');
}

export function mapGeminiError(err) {
  const status = statusOf(err);
  if (status === 429 || status === 503) {
    return new ProviderError('rate_limited', 'Gemini is rate limited or overloaded', { cause: err, retryAfterSeconds: 30 });
  }
  if (status === 401 || status === 403 || (status === 400 && /API key/i.test(err?.message ?? ''))) {
    return new ProviderError('not_configured', 'Gemini rejected the API key', { cause: err });
  }
  return new ProviderError('upstream', `Gemini request failed${status ? ` (${status})` : ''}`, { cause: err });
}
