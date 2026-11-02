/** Provider-agnostic failure; `kind` drives the HTTP status and the spoken message. */
export class ProviderError extends Error {
  /** @param {'rate_limited'|'not_configured'|'upstream'} kind */
  constructor(kind, message, { cause, retryAfterSeconds } = {}) {
    super(message, { cause });
    this.kind = kind;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

const PLACEHOLDER = /^(|PASTE_.*_HERE|changeme)$/i;

export function isMissingSecret(value) {
  return value == null || PLACEHOLDER.test(String(value).trim());
}
