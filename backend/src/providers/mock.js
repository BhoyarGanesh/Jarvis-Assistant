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
    if ((m = t.match(/^remind me in (\d+) minutes? to (.+)$/i))) {
      const at = new Date();
      at.setMinutes(at.getMinutes() + Number(m[1]));
      const pad = (n) => String(n).padStart(2, '0');
      const when = `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}`;
      return normalizeToolCall('set_reminder', { text: m[2], when });
    }
    if (/^what are my reminders$/i.test(t)) return normalizeToolCall('list_reminders', {});
    if (/^what'?s on my calendar today$/i.test(t)) {
      const d = new Date();
      const pad = (n) => String(n).padStart(2, '0');
      return normalizeToolCall('read_calendar', { date: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`, days: 1 });
    }
    if (/^(?:where are you|find my phone|ring my phone)$/i.test(t)) {
      return normalizeToolCall('find_phone', {});
    }
    if (/^(?:what'?s on my screen|explain this|translate this|is this (?:a |offer a )?scam)$/i.test(t)) {
      return normalizeToolCall('screen_question', { question: t });
    }
    if (/^(?:what did i miss|anything important(?: today)?)$/i.test(t)) {
      return normalizeToolCall('summarize_messages', { app_name: '', contact_name: '', question: t });
    }
    if ((m = t.match(/^summari[sz]e (?:my )?(\w+?)(?: group| chat| messages)?$/i))) {
      return normalizeToolCall('summarize_messages', { app_name: '', contact_name: titleCase(m[1]), question: t });
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

  async generate({ text, image }) {
    if (image) return "I can see your screen, but I'm in offline test mode so I can't describe it.";
    const count = text.split('\n').filter((line) => line.startsWith('[')).length;
    return `You have ${count} ${count === 1 ? 'notification' : 'notifications'}. I'm in offline test mode, so I can't summarise them.`;
  }
}
