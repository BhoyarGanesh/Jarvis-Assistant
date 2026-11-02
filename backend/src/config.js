import 'dotenv/config';

const env = process.env;

export const config = {
  port: Number(env.PORT ?? 8787),
  host: env.HOST ?? '0.0.0.0',
  provider: (env.LLM_PROVIDER ?? 'gemini').toLowerCase(),
  geminiApiKey: env.GEMINI_API_KEY,
  geminiModel: env.GEMINI_MODEL || 'gemini-3.8-flash',
  anthropicApiKey: env.ANTHROPIC_API_KEY,
  claudeModel: env.CLAUDE_MODEL || 'claude-opus-5-5',
  claudeEffort: env.CLAUDE_EFFORT ?? 'low',
  sharedSecret: env.APP_SHARED_SECRET,
  rateLimitPerMinute: Number(env.RATE_LIMIT_PER_MINUTE ?? 30),
};
