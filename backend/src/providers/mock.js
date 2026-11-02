import { normalizeToolCall, answer } from '../tools.js';

const WORD_NUMBERS = { a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, ten: 10, fifteen: 15, twenty: 20, thirty: 30 };
const UNIT_SECONDS = { second: 1, sec: 1, minute: 60, min: 60, hour: 3600 };

const toNumber = (s) => (/^\d+$/.test(s) ? Number(s) : WORD_NUMBERS[s] ?? NaN);
const titleCase = (s) => s.replace(/\b\w/g, (c) => c.toUpperCase());

/**
 * Keyword-based stand-in for an LLM. Lets the app and the curl scripts run end to end
 * without any API key (LLM_PROVIDER=mock) and backs the automated tests.
 * @implements {import('./index.js').LLMProvider}
 */
export class MockProvider {
  name = 'mock';
  model = 'rules-v1';
  configured = true;

  async interpret({ text }) {
    const t = text.trim().replace(/^jarvis[,\s]*/i, '').replace(/[.!?]+$/, '');
    let m;

    if ((m = t.match(/^(?:message|whatsapp)\s+(.+?)\s+on\s+whatsapp\s+(?:saying\s+|that\s+)?(.+)$/i))) {
      return normalizeToolCall('send_whatsapp', { contact_name: titleCase(m[1]), message: m[2] });
    }
    if ((m = t.match(/^whatsapp\s+(\w+)\s+(?:saying\s+|that\s+)?(.+)$/i))) {
      return normalizeToolCall('send_whatsapp', { contact_name: titleCase(m[1]), message: m[2] });
    }
    if ((m = t.match(/^(?:text|sms)\s+(\w+)\s+(?:saying\s+|that\s+)?(.+)$/i))) {
      return normalizeToolCall('send_sms', { contact_name: titleCase(m[1]), message: m[2] });
    }
    if ((m = t.match(/^message\s+(\w+)\s+(?:saying\s+|that\s+)?(.+)$/i))) {
      return normalizeToolCall('send_whatsapp', { contact_name: titleCase(m[1]), message: m[2] });
    }
    if ((m = t.match(/^(?:call|phone|dial|ring)\s+(.+)$/i))) {
      return normalizeToolCall('call_contact', { name: titleCase(m[1]) });
    }
    if ((m = t.match(/^read (?:my )?(\w+ )?(?:messages|notifications)$/i))) {
      return normalizeToolCall('read_notifications', { app_name: m[1] ? titleCase(m[1].trim()) : '', contact_name: '' });
    }
    if ((m = t.match(/^reply(?: to (\w+))?(?: saying)?\s+(.+)$/i))) {
      return normalizeToolCall('reply_message', { contact_name: m[1] ? titleCase(m[1]) : '', message: m[2] });
    }
    if ((m = t.match(/(?:alarm|wake me up).*?\b(?:at|for)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m|p\.m)?/i))) {
      let hour = Number(m[1]) % 24;
      const pm = /^p/i.test(m[3] ?? '');
      if (pm && hour < 12) hour += 12;
      if (/^a/i.test(m[3] ?? '') && hour === 12) hour = 0;
      return normalizeToolCall('set_alarm', { hour, minute: Number(m[2] ?? 0), label: '' });
    }
    if ((m = t.match(/timer.*?\b(\d+|\w+)\s+(second|sec|minute|min|hour)s?/i))) {
      const seconds = toNumber(m[1].toLowerCase()) * UNIT_SECONDS[m[2].toLowerCase()];
      return normalizeToolCall('set_timer', { seconds, label: '' });
    }
    if ((m = t.match(/^(?:open|launch|start)\s+(.+)$/i))) {
      return normalizeToolCall('open_app', { app_name: titleCase(m[1]) });
    }
    if ((m = t.match(/^(?:play)\s+(.+)$/i))) {
      return normalizeToolCall('play_music', { query: m[1] });
    }
    if ((m = t.match(/^(?:search(?: for)?|google|look up)\s+(.+)$/i))) {
      return normalizeToolCall('web_search', { query: m[1] });
    }
    if (/^(hi|hello|hey)\b/i.test(t)) return answer('Hello! How can I help?');
    return answer("I'm running in offline test mode, so I can only handle simple commands.");
  }
}
