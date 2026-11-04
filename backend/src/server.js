import { networkInterfaces } from 'node:os';
import { config } from './config.js';
import { createProvider } from './providers/index.js';
import { createApp } from './app.js';
import { GeminiSpeech } from './speech.js';
import { GeminiLive, attachLive } from './live.js';
import { isMissingSecret } from './providers/errors.js';

const provider = createProvider(config);
// Voice replies always use Gemini TTS (whichever LLM provider interprets commands).
const speech = new GeminiSpeech({ apiKey: config.geminiApiKey, models: config.ttsModels, voice: config.ttsVoice });
// Live mode (talk over her) also always uses Gemini.
const live = new GeminiLive({ apiKey: config.geminiApiKey, models: config.liveModels, voice: config.ttsVoice });
const app = createApp({ provider, speech, live, sharedSecret: config.sharedSecret, rateLimitPerMinute: config.rateLimitPerMinute });

const server = app.listen(config.port, config.host, () => {
  console.log(`Jarvis backend listening on port ${config.port} (provider: ${provider.name}, model: ${provider.model})`);
  if (!provider.configured) console.warn(`WARNING: no API key configured for "${provider.name}". Edit backend/.env.`);
  if (isMissingSecret(config.sharedSecret)) console.warn('WARNING: APP_SHARED_SECRET is not set. /interpret will refuse requests.');
  const lan = Object.values(networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => `http://${i.address}:${config.port}`);
  if (lan.length) console.log(`Backend URL candidates for the app: ${lan.join('  ')}`);
});
attachLive(server, { sharedSecret: config.sharedSecret, live });
