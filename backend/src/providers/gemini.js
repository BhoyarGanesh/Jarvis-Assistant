import { GoogleGenAI, FunctionCallingConfigMode, ApiError } from '@google/genai';
import { SYSTEM_PROMPT, buildUserMessage } from '../prompt.js';
import { TOOLS, jsonSchemaFor, normalizeToolCall, answer, FALLBACK_TEXT } from '../tools.js';
import { ProviderError, isMissingSecret } from './errors.js';

const FUNCTION_DECLARATIONS = TOOLS.map((t) => ({
  name: t.name,
  description: t.description,
  parametersJsonSchema: jsonSchemaFor(t),
}));

/** @implements {import('./index.js').LLMProvider} */
export class GeminiProvider {
  name = 'gemini';

  /** @param {{ apiKey?: string, model: string, client?: { models: { generateContent: Function } } }} opts */
  constructor({ apiKey, model, client }) {
    this.model = model;
    this.configured = Boolean(client) || !isMissingSecret(apiKey);
    this.client = client ?? (this.configured ? new GoogleGenAI({ apiKey }) : null);
  }

  async interpret(request) {
    if (!this.configured) {
      throw new ProviderError('not_configured', 'GEMINI_API_KEY is not set in backend/.env');
    }
    let response;
    try {
      response = await this.client.models.generateContent({
        model: this.model,
        contents: [{ role: 'user', parts: [{ text: buildUserMessage(request) }] }],
        config: {
          systemInstruction: SYSTEM_PROMPT,
          tools: [{ functionDeclarations: FUNCTION_DECLARATIONS }],
          toolConfig: { functionCallingConfig: { mode: FunctionCallingConfigMode.ANY } },
          maxOutputTokens: 1024,
        },
      });
    } catch (err) {
      throw mapGeminiError(err);
    }

    const call = response.functionCalls?.[0];
    if (call) return normalizeToolCall(call.name, call.args);
    const text = response.text?.trim();
    return answer(text || FALLBACK_TEXT);
  }
}

function mapGeminiError(err) {
  const status = err instanceof ApiError ? err.status : err?.status;
  if (status === 429) {
    return new ProviderError('rate_limited', 'Gemini rate limit reached', { cause: err, retryAfterSeconds: 60 });
  }
  if (status === 401 || status === 403 || (status === 400 && /API key/i.test(err?.message ?? ''))) {
    return new ProviderError('not_configured', 'Gemini rejected the API key', { cause: err });
  }
  return new ProviderError('upstream', `Gemini request failed${status ? ` (${status})` : ''}`, { cause: err });
}
