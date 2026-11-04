import 'dotenv/config';

const env = process.env;

export const config = {
  port: Number(env.PORT ?? 8787),
  host: env.HOST ?? '0.0.0.0',
  provider: (env.LLM_PROVIDER ?? 'gemini').toLowerCase(),
  geminiApiKey: env.GEMINI_API_KEY,
  geminiModel: env.GEMINI_MODEL || 'gemini-3.5-flash-lite',
  geminiFallbackModels: (env.GEMINI_FALLBACK_MODELS ?? 'gemini-3.1-flash-lite,gemini-3.8-flash')
    .split(',')
    .map((m) => m.trim())
    .filter(Boolean),
  anthropicApiKey: env.ANTHROPIC_API_KEY,
  claudeModel: env.CLAUDE_MODEL || 'claude-opus-5-5',
  claudeEffort: env.CLAUDE_EFFORT ?? 'low',
  ttsModels: (env.GEMINI_TTS_MODELS ?? 'gemini-3.8-flash-lite-tts,gemini-3.8-flash-tts,gemini-3.1-flash-tts-preview')
    .split(',')
    .map((m) => m.trim())
    .filter(Boolean),
  ttsVoice: env.GEMINI_TTS_VOICE || 'Despina',
  liveModels: (env.GEMINI_LIVE_MODELS ?? 'gemini-3.8-live,gemini-3.1-flash-live-preview')
    .split(',')
    .map((m) => m.trim())
    .filter(Boolean),
  sharedSecret: env.APP_SHARED_SECRET,
  rateLimitPerMinute: Number(env.RATE_LIMIT_PER_MINUTE ?? 30),
  // Hops of reverse proxy in front of the app (Render, Cloud Run...), so rate limits see the phone's IP.
  // Render sets RENDER=true; set TRUST_PROXY yourself on other hosts.
  trustProxy: Number(env.TRUST_PROXY ?? (env.RENDER ? 1 : 0)),
};
