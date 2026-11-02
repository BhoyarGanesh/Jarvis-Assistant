import { GeminiProvider } from './gemini.js';
import { AnthropicProvider } from './anthropic.js';
import { MockProvider } from './mock.js';

/**
 * @typedef {{ tool: string, args: Record<string, unknown> }} ToolCall
 * @typedef {{ text: string, contacts?: string[], now?: string, locale?: string }} InterpretRequest
 * @typedef {object} LLMProvider
 * @property {string} name
 * @property {string} model
 * @property {boolean} configured
 * @property {(req: InterpretRequest) => Promise<ToolCall>} interpret
 */

/** @returns {LLMProvider} */
export function createProvider(config) {
  switch (config.provider) {
    case 'gemini':
      return new GeminiProvider({
        apiKey: config.geminiApiKey,
        model: config.geminiModel,
        fallbackModels: config.geminiFallbackModels,
      });
    case 'anthropic':
      return new AnthropicProvider({
        apiKey: config.anthropicApiKey,
        model: config.claudeModel,
        effort: config.claudeEffort,
      });
    case 'mock':
      return new MockProvider();
    default:
      throw new Error(`Unknown LLM_PROVIDER "${config.provider}" (expected gemini, anthropic or mock)`);
  }
}
