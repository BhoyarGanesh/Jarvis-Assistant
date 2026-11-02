import Anthropic from '@anthropic-ai/sdk';
import { SYSTEM_PROMPT, buildUserMessage } from '../prompt.js';
import { TOOLS, jsonSchemaFor, normalizeToolCall, answer, FALLBACK_TEXT } from '../tools.js';
import { ProviderError, isMissingSecret } from './errors.js';

// Strict tool schemas don't accept numeric/length constraints; zod enforces them after the call.
const UNSUPPORTED_STRICT_KEYS = new Set(['minimum', 'maximum', 'maxLength', 'minLength']);
function stripConstraints(schema) {
  const properties = Object.fromEntries(
    Object.entries(schema.properties).map(([key, prop]) => [
      key,
      Object.fromEntries(Object.entries(prop).filter(([k]) => !UNSUPPORTED_STRICT_KEYS.has(k))),
    ]),
  );
  return { ...schema, properties };
}

const ANTHROPIC_TOOLS = TOOLS.map((t) => ({
  name: t.name,
  description: t.description,
  input_schema: stripConstraints(jsonSchemaFor(t)),
  strict: true,
}));

// Server-side refusal fallbacks are only offered on these model families.
const FALLBACK_CAPABLE = /^claude-(opus-5|fable-5|sonnet-5-5)/;

/** @implements {import('./index.js').LLMProvider} */
export class AnthropicProvider {
  name = 'anthropic';

  /** @param {{ apiKey?: string, model: string, effort?: string, client?: object }} opts */
  constructor({ apiKey, model, effort, client }) {
    this.model = model;
    this.effort = effort;
    this.configured = Boolean(client) || !isMissingSecret(apiKey);
    this.client = client ?? (this.configured ? new Anthropic({ apiKey, maxRetries: 1 }) : null);
  }

  buildParams(request) {
    const params = {
      model: this.model,
      max_tokens: 2048,
      system: SYSTEM_PROMPT,
      tools: ANTHROPIC_TOOLS,
      // Forced tool_choice (any/tool) is rejected by current models, so steer via the prompt
      // and treat any plain-text reply as an answer().
      tool_choice: { type: 'auto', disable_parallel_tool_use: true },
      messages: [{ role: 'user', content: buildUserMessage(request) }],
    };
    if (this.effort) params.output_config = { effort: this.effort };
    if (FALLBACK_CAPABLE.test(this.model)) {
      params.betas = ['server-side-fallback-2026-07-01'];
      params.fallbacks = 'default';
    }
    return params;
  }

  async interpret(request) {
    if (!this.configured) {
      throw new ProviderError('not_configured', 'ANTHROPIC_API_KEY is not set in backend/.env');
    }
    let message;
    try {
      message = await this.client.beta.messages.create(this.buildParams(request));
    } catch (err) {
      throw mapAnthropicError(err);
    }

    if (message.stop_reason === 'refusal') return answer("Sorry, I can't help with that.");
    const toolUse = message.content.find((b) => b.type === 'tool_use');
    if (toolUse) return normalizeToolCall(toolUse.name, toolUse.input);
    const text = message.content
      .filter((b) => b.type === 'text')
      .map((b) => b.text)
      .join(' ')
      .trim();
    return answer(text || FALLBACK_TEXT);
  }
}

function mapAnthropicError(err) {
  if (err instanceof Anthropic.RateLimitError) {
    const retryAfter = Number(err.headers?.get?.('retry-after')) || 60;
    return new ProviderError('rate_limited', 'Anthropic rate limit reached', { cause: err, retryAfterSeconds: retryAfter });
  }
  if (err instanceof Anthropic.AuthenticationError || err instanceof Anthropic.PermissionDeniedError) {
    return new ProviderError('not_configured', 'Anthropic rejected the API key', { cause: err });
  }
  if (err instanceof Anthropic.APIError) {
    return new ProviderError('upstream', `Anthropic request failed (${err.status ?? 'network'})`, { cause: err });
  }
  return new ProviderError('upstream', 'Anthropic request failed', { cause: err });
}
