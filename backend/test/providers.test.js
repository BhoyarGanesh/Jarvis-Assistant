import { test } from 'node:test';
import assert from 'node:assert/strict';
import Anthropic from '@anthropic-ai/sdk';
import { ApiError } from '@google/genai';
import { GeminiProvider } from '../src/providers/gemini.js';
import { AnthropicProvider } from '../src/providers/anthropic.js';
import { ProviderError } from '../src/providers/errors.js';
import { normalizeToolCall, TOOL_NAMES } from '../src/tools.js';

// Each tool: a raw provider call and the normalized output we expect from it.
const CASES = [
  ['call_contact', { name: 'King' }],
  ['send_whatsapp', { contact_name: 'Junior', message: 'hi' }],
  ['send_sms', { contact_name: 'Mom', message: 'on my way' }],
  ['set_alarm', { hour: 6, minute: 30, label: 'gym' }],
  ['set_timer', { seconds: 300, label: '' }],
  ['open_app', { app_name: 'YouTube' }],
  ['web_search', { query: 'weather in Hyderabad' }],
  ['play_music', { query: 'lofi beats' }],
  ['read_notifications', { app_name: 'WhatsApp', contact_name: '' }],
  ['reply_message', { contact_name: '', message: 'on my way' }],
  ['screen_question', { question: 'is this a scam?' }],
  ['summarize_messages', { app_name: 'WhatsApp', contact_name: 'Family', question: 'summarise my family group' }],
  ['find_phone', {}],
  ['answer', { text: 'Paris is the capital of France.' }],
];

test('every declared tool has a case', () => {
  assert.deepEqual(CASES.map(([n]) => n).sort(), [...TOOL_NAMES].sort());
});

const fakeGemini = (impl) => new GeminiProvider({ model: 'test-model', client: { models: { generateContent: impl } } });

const fakeAnthropic = (impl, model = 'claude-opus-5-5') =>
  new AnthropicProvider({ model, effort: 'low', client: { beta: { messages: { create: impl } } } });

for (const [tool, args] of CASES) {
  test(`gemini normalizes ${tool}`, async () => {
    const provider = fakeGemini(async () => ({ functionCalls: [{ name: tool, args }] }));
    assert.deepEqual(await provider.interpret({ text: 'x' }), { tool, args });
  });

  test(`anthropic normalizes ${tool}`, async () => {
    const provider = fakeAnthropic(async () => ({
      stop_reason: 'tool_use',
      content: [{ type: 'text', text: 'ok' }, { type: 'tool_use', id: 't1', name: tool, input: args }],
    }));
    assert.deepEqual(await provider.interpret({ text: 'x' }), { tool, args });
  });
}

test('gemini sends tools in ANY mode with the transcript and contacts', async () => {
  let seen;
  const provider = fakeGemini(async (req) => {
    seen = req;
    return { functionCalls: [{ name: 'call_contact', args: { name: 'King' } }] };
  });
  await provider.interpret({ text: 'call king', contacts: ['King Kumar', 'Junior'] });
  assert.equal(seen.model, 'test-model');
  assert.equal(seen.config.toolConfig.functionCallingConfig.mode, 'ANY');
  assert.equal(seen.config.tools[0].functionDeclarations.length, TOOL_NAMES.length);
  assert.match(seen.contents[0].parts[0].text, /call king/);
  assert.match(seen.contents[0].parts[0].text, /King Kumar, Junior/);
});

test('anthropic request uses auto tool choice, strict tools, effort and fallbacks', async () => {
  let seen;
  const provider = fakeAnthropic(async (req) => {
    seen = req;
    return { stop_reason: 'tool_use', content: [{ type: 'tool_use', name: 'answer', input: { text: 'hi' } }] };
  });
  await provider.interpret({ text: 'hello' });
  assert.deepEqual(seen.tool_choice, { type: 'auto', disable_parallel_tool_use: true });
  assert.ok(seen.tools.every((t) => t.strict === true && t.input_schema.additionalProperties === false));
  assert.ok(seen.tools.every((t) => Object.values(t.input_schema.properties).every((p) => !('maximum' in p))));
  assert.deepEqual(seen.output_config, { effort: 'low' });
  assert.equal(seen.fallbacks, 'default');
  assert.equal('temperature' in seen, false);
});

test('anthropic omits fallbacks for models without server-side fallback', () => {
  const provider = fakeAnthropic(async () => ({}), 'claude-haiku-5-5');
  assert.equal('fallbacks' in provider.buildParams({ text: 'x' }), false);
});

test('anthropic plain-text reply becomes answer()', async () => {
  const provider = fakeAnthropic(async () => ({ stop_reason: 'end_turn', content: [{ type: 'text', text: 'Who should I call?' }] }));
  assert.deepEqual(await provider.interpret({ text: 'call' }), { tool: 'answer', args: { text: 'Who should I call?' } });
});

test('anthropic refusal becomes a polite answer()', async () => {
  const provider = fakeAnthropic(async () => ({ stop_reason: 'refusal', content: [] }));
  assert.equal((await provider.interpret({ text: 'x' })).tool, 'answer');
});

test('gemini text-only reply becomes answer()', async () => {
  const provider = fakeGemini(async () => ({ functionCalls: undefined, text: 'Hello there' }));
  assert.deepEqual(await provider.interpret({ text: 'hi' }), { tool: 'answer', args: { text: 'Hello there' } });
});

test('gemini 429 maps to rate_limited', async () => {
  const provider = fakeGemini(async () => {
    throw new ApiError({ message: 'quota', status: 429 });
  });
  await assert.rejects(provider.interpret({ text: 'x' }), (e) => e instanceof ProviderError && e.kind === 'rate_limited');
});

test('gemini falls back to the next model when one is overloaded', async () => {
  const tried = [];
  const provider = new GeminiProvider({
    model: 'primary',
    fallbackModels: ['backup'],
    client: {
      models: {
        generateContent: async ({ model }) => {
          tried.push(model);
          if (model === 'primary') throw new ApiError({ message: 'high demand', status: 503 });
          return { functionCalls: [{ name: 'call_contact', args: { name: 'King' } }] };
        },
      },
    },
  });
  assert.deepEqual(await provider.interpret({ text: 'call king' }), { tool: 'call_contact', args: { name: 'King' } });
  assert.deepEqual(tried, ['primary', 'backup']);
});

test('gemini does not fall back on non-transient errors', async () => {
  const tried = [];
  const provider = new GeminiProvider({
    model: 'primary',
    fallbackModels: ['backup'],
    client: {
      models: {
        generateContent: async ({ model }) => {
          tried.push(model);
          throw new ApiError({ message: 'bad key', status: 403 });
        },
      },
    },
  });
  await assert.rejects(provider.interpret({ text: 'x' }), (e) => e.kind === 'not_configured');
  assert.deepEqual(tried, ['primary']);
});

test('anthropic 429 maps to rate_limited with retry-after', async () => {
  const provider = fakeAnthropic(async () => {
    throw new Anthropic.RateLimitError(429, { type: 'error' }, 'rate', new Headers({ 'retry-after': '7' }));
  });
  await assert.rejects(provider.interpret({ text: 'x' }), (e) => e.kind === 'rate_limited' && e.retryAfterSeconds === 7);
});

test('providers without keys report not_configured', async () => {
  const g = new GeminiProvider({ apiKey: 'PASTE_KEY_HERE', model: 'm' });
  const a = new AnthropicProvider({ apiKey: '', model: 'm' });
  assert.equal(g.configured, false);
  await assert.rejects(g.interpret({ text: 'x' }), (e) => e.kind === 'not_configured');
  await assert.rejects(a.interpret({ text: 'x' }), (e) => e.kind === 'not_configured');
});

test('normalizeToolCall rejects unknown tools and bad args', () => {
  assert.equal(normalizeToolCall('format_disk', {}).tool, 'answer');
  assert.equal(normalizeToolCall('set_alarm', { hour: 25, minute: 0, label: '' }).tool, 'answer');
  assert.equal(normalizeToolCall('call_contact', { name: '   ' }).tool, 'answer');
  assert.deepEqual(normalizeToolCall('set_timer', { seconds: '60' }), { tool: 'set_timer', args: { seconds: 60, label: '' } });
});

test('gemini generate sends the image before the text and returns the reply', async () => {
  let seen;
  const provider = fakeGemini(async (req) => {
    seen = req;
    return { text: '  This looks like a scam.  ' };
  });
  const text = await provider.generate({ system: 'sys', text: 'is this a scam?', image: { mimeType: 'image/jpeg', data: 'AAAA' } });
  assert.equal(text, 'This looks like a scam.');
  assert.deepEqual(seen.contents[0].parts[0], { inlineData: { mimeType: 'image/jpeg', data: 'AAAA' } });
  assert.equal(seen.contents[0].parts[1].text, 'is this a scam?');
  assert.equal(seen.config.systemInstruction, 'sys');
  assert.equal(seen.config.tools, undefined);
});

test('gemini generate falls back to the next model', async () => {
  const tried = [];
  const provider = new GeminiProvider({
    model: 'primary',
    fallbackModels: ['backup'],
    client: {
      models: {
        generateContent: async ({ model }) => {
          tried.push(model);
          if (model === 'primary') throw new ApiError({ message: 'busy', status: 503 });
          return { text: 'Two messages from Mom.' };
        },
      },
    },
  });
  assert.equal(await provider.generate({ system: 's', text: 't' }), 'Two messages from Mom.');
  assert.deepEqual(tried, ['primary', 'backup']);
});

test('anthropic generate sends an image block without tools', async () => {
  let seen;
  const provider = fakeAnthropic(async (req) => {
    seen = req;
    return { stop_reason: 'end_turn', content: [{ type: 'text', text: 'A bill for 450 rupees.' }] };
  });
  const text = await provider.generate({ system: 'sys', text: 'explain this', image: { mimeType: 'image/png', data: 'BBBB' } });
  assert.equal(text, 'A bill for 450 rupees.');
  assert.deepEqual(seen.messages[0].content[0], { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'BBBB' } });
  assert.equal(seen.system, 'sys');
  assert.equal(seen.tools, undefined);
  assert.equal(seen.fallbacks, 'default');
});

test('anthropic generate turns a refusal into a polite reply', async () => {
  const provider = fakeAnthropic(async () => ({ stop_reason: 'refusal', content: [] }));
  assert.match(await provider.generate({ system: 's', text: 't' }), /can't help/);
});

test('generate without a key reports not_configured', async () => {
  await assert.rejects(
    new GeminiProvider({ apiKey: '', model: 'm' }).generate({ system: 's', text: 't' }),
    (e) => e.kind === 'not_configured',
  );
});
