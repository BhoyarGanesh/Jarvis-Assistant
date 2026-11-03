import { z } from 'zod';

// Single source of truth for the tools the LLM may call. Each entry carries the JSON schema
// sent to providers and a zod schema used to validate whatever the provider returns.
const str = (description, maxLength = 500) => ({ type: 'string', description, maxLength });
const int = (description, minimum, maximum) => ({ type: 'integer', description, minimum, maximum });
const composedParam = {
  type: 'boolean',
  description:
    'true if you wrote or reworded the message (the user asked for a tone, a language, a translation or for you to write it); false if it is the user\'s own dictated words',
};
// Models sometimes send booleans as strings.
const composedZod = z.preprocess((v) => (typeof v === 'string' ? v.toLowerCase() === 'true' : v), z.boolean()).default(false);

export const TOOLS = [
  {
    name: 'call_contact',
    description:
      'Place a phone call to a contact. Use the contact name exactly as the user said it (e.g. "King", "Mom"). Never invent names or phone numbers.',
    parameters: { name: str('Contact name as spoken by the user', 100) },
    zod: z.object({ name: z.string().trim().min(1).max(100) }),
  },
  {
    name: 'send_whatsapp',
    description: 'Send a WhatsApp message to a contact.',
    parameters: {
      contact_name: str('Contact name as spoken by the user', 100),
      message: str('The message text to send, without the contact name or the word WhatsApp', 1000),
      composed: composedParam,
    },
    zod: z.object({
      contact_name: z.string().trim().min(1).max(100),
      message: z.string().trim().min(1).max(1000),
      composed: composedZod,
    }),
  },
  {
    name: 'send_sms',
    description: 'Send an SMS text message to a contact. Use this when the user says "text" or "SMS" without WhatsApp.',
    parameters: {
      contact_name: str('Contact name as spoken by the user', 100),
      message: str('The message text to send', 1000),
      composed: composedParam,
    },
    zod: z.object({
      contact_name: z.string().trim().min(1).max(100),
      message: z.string().trim().min(1).max(1000),
      composed: composedZod,
    }),
  },
  {
    name: 'set_alarm',
    description:
      'Set an alarm at a clock time, 24-hour format. Convert "7 pm" to hour 19. Use the provided current local time for relative phrases like "tomorrow morning".',
    parameters: {
      hour: int('Hour 0-23', 0, 23),
      minute: int('Minute 0-59', 0, 59),
      label: str('Short alarm label, or empty string', 100),
    },
    zod: z.object({
      hour: z.coerce.number().int().min(0).max(23),
      minute: z.coerce.number().int().min(0).max(59),
      label: z.string().max(100).default(''),
    }),
  },
  {
    name: 'set_timer',
    description: 'Start a countdown timer. Convert durations to seconds ("5 minutes" = 300).',
    parameters: {
      seconds: int('Duration in seconds, 1-86400', 1, 86400),
      label: str('Short timer label, or empty string', 100),
    },
    zod: z.object({
      seconds: z.coerce.number().int().min(1).max(86400),
      label: z.string().max(100).default(''),
    }),
  },
  {
    name: 'open_app',
    description: 'Open an installed app by its name, e.g. "YouTube", "Camera", "Settings".',
    parameters: { app_name: str('App name', 100) },
    zod: z.object({ app_name: z.string().trim().min(1).max(100) }),
  },
  {
    name: 'web_search',
    description: 'Search the web when the user explicitly asks to search/google/look something up, or needs live information.',
    parameters: { query: str('Search query', 300) },
    zod: z.object({ query: z.string().trim().min(1).max(300) }),
  },
  {
    name: 'play_music',
    description: 'Play a song, artist, album, playlist or genre.',
    parameters: { query: str('What to play', 200) },
    zod: z.object({ query: z.string().trim().min(1).max(200) }),
  },
  {
    name: 'read_notifications',
    description:
      'Read the user\'s latest messages or notifications aloud ("read my messages", "any new WhatsApp messages?", "what did King say?"). Filter by app and/or sender when the user names one; otherwise use empty strings.',
    parameters: {
      app_name: str('App to filter by, e.g. "WhatsApp", or empty string', 50),
      contact_name: str('Sender to filter by, or empty string', 100),
    },
    zod: z.object({
      app_name: z.string().trim().max(50).default(''),
      contact_name: z.string().trim().max(100).default(''),
    }),
  },
  {
    name: 'reply_message',
    description:
      'Reply to a message the user received ("reply I\'m on my way", "reply to King saying see you soon"). Use contact_name only if the user names who to reply to; otherwise empty string (replies to the most recent or last-read message). Use this instead of send_whatsapp/send_sms when the user says "reply".',
    parameters: {
      contact_name: str('Who to reply to, or empty string for the latest message', 100),
      message: str('The reply text', 1000),
      composed: composedParam,
    },
    zod: z.object({
      contact_name: z.string().trim().max(100).default(''),
      message: z.string().trim().min(1).max(1000),
      composed: composedZod,
    }),
  },
  {
    name: 'screen_question',
    description:
      'Look at what is on the phone screen right now and answer about it: "what\'s on my screen?", "explain this", "translate this", "summarise this page", "is this offer a scam?", "what should I reply to this?". Use this whenever the user says "this" or "my screen" about something they are looking at.',
    parameters: { question: str('The user\'s question about the screen, in their words', 300) },
    zod: z.object({ question: z.string().trim().min(1).max(300) }),
  },
  {
    name: 'summarize_messages',
    description:
      'Summarise the user\'s unread messages and flag anything urgent: "what did I miss?", "anything important today?", "summarise my Family group", "catch me up on WhatsApp". Filter by app and/or chat/sender name when the user names one; otherwise empty strings. Use read_notifications instead when the user wants messages read out word for word.',
    parameters: {
      app_name: str('App to filter by, e.g. "WhatsApp", or empty string', 50),
      contact_name: str('Chat, group or sender to filter by, e.g. "Family", or empty string', 100),
      question: str('The user\'s request in their words', 300),
    },
    zod: z.object({
      app_name: z.string().trim().max(50).default(''),
      contact_name: z.string().trim().max(100).default(''),
      question: z.string().trim().max(300).default(''),
    }),
  },
  {
    name: 'find_phone',
    description:
      'Ring the phone loudly, even on silent, so the user can find it: "where are you?", "find my phone", "ring my phone", "I lost my phone".',
    parameters: {},
    zod: z.object({}),
  },
  {
    name: 'answer',
    description:
      'Reply with speech only. Use for general questions, small talk, and for ambiguous or unsupported requests (ask a short clarifying question). Keep it to one or two short spoken sentences.',
    parameters: { text: str('What Jarvis should say', 600) },
    zod: z.object({ text: z.string().trim().min(1).max(600) }),
  },
];

const BY_NAME = new Map(TOOLS.map((t) => [t.name, t]));
export const TOOL_NAMES = TOOLS.map((t) => t.name);

/** Strict JSON schema for a tool's parameters (all fields required, no extras). */
export function jsonSchemaFor(tool) {
  return {
    type: 'object',
    properties: tool.parameters,
    required: Object.keys(tool.parameters),
    additionalProperties: false,
  };
}

export const FALLBACK_TEXT = "Sorry, I didn't quite get that. Could you say it again?";

export function answer(text) {
  return { tool: 'answer', args: { text } };
}

/**
 * Validates a provider's raw tool call and returns the normalized shape
 * `{ tool, args }`. Anything unknown or malformed degrades to an answer().
 */
export function normalizeToolCall(name, rawArgs) {
  const tool = BY_NAME.get(name);
  if (!tool) return answer(FALLBACK_TEXT);
  const parsed = tool.zod.safeParse(rawArgs ?? {});
  if (!parsed.success) return answer(FALLBACK_TEXT);
  return { tool: name, args: parsed.data };
}
