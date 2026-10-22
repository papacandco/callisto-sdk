/**
 * Pattern masking for the source lines the error reporter attaches to stack
 * frames (`pre_context` / `context_line` / `post_context`). A secret written
 * on, or next to, the failing line must not leave the process: the matched
 * secret part is replaced with FILTERED. Pure functions, no state.
 *
 * Every pattern is linear: no nested quantifiers, identifier matches anchored
 * by a lookbehind, quoted values bounded, so a hostile line cannot ReDoS.
 */

export const FILTERED = "[Filtered]";

const PRIVATE_KEY_RE = /-----BEGIN [A-Z ]*PRIVATE KEY-----/;
// scheme://user:password@host → keep the user, mask the password.
const URL_CREDENTIALS_RE = /(:\/\/[^\s:@/]+:)[^\s@/]+@/g;
const BEARER_RE = /\bBearer\s+(?!\[Filtered\])[^\s"'`]+/g;
const TOKEN_RES: RegExp[] = [
  /sk_[0-9a-fA-F]{16,}/g,
  /pk_[0-9a-fA-F]{16,}/g,
  /AKIA[0-9A-Z]{16}/g,
];
// name (optionally quoted) + `=`, `==`, `===`, `=>` or `:` + a quoted or bare value.
const ASSIGNMENT_RE =
  /(?<![\w$.-])(["'`]?)([A-Za-z_$][\w$.-]*)\1(\s*(?:={1,3}>?|:)\s*)("(?:[^"\\\n]|\\.){0,512}"|'(?:[^'\\\n]|\\.){0,512}'|`(?:[^`\\\n]|\\.){0,512}`|[^\s,;)}\]&"'`]+)/g;
// A quoted string of 40+ token characters (base64, hex, JWT segment...).
const LONG_TOKEN_STRING_RE = /(["'`])[A-Za-z0-9+/=_-]{40,}\1/g;

const SENSITIVE_WORD_RE =
  /secret|token|passw(?:or)?d|bearer|credential|signature|authori[sz]ation|cookie|session/i;

/**
 * Whether an identifier names a secret: it contains secret, token, password,
 * passwd, bearer, credential, signature, authorization, cookie or session, or
 * has a `key` segment (`key`, `api_key`, `access-key`, `private_key`,
 * `apiKey`, `API_KEY`) — but not `keyboard` or `monkey`.
 */
export function isSensitiveName(name: string): boolean {
  if (SENSITIVE_WORD_RE.test(name)) return true;
  // Split on -, _, ., spaces and camelCase humps; look for a `key` segment.
  const segments = name
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .split(/[\s._-]+/);
  return segments.some((segment) => segment.toLowerCase() === "key");
}

function maskValue(value: string): string {
  const quote = value[0];
  if ((quote === '"' || quote === "'" || quote === "`") && value.length >= 2) {
    return quote + FILTERED + quote;
  }
  return FILTERED;
}

/** Mask the secrets in one source line. Never throws. */
export function maskSecrets(line: string): string {
  if (typeof line !== "string" || line === "") return line;
  try {
    if (PRIVATE_KEY_RE.test(line)) return FILTERED;
    let out = line.replace(URL_CREDENTIALS_RE, `$1${FILTERED}@`);
    out = out.replace(BEARER_RE, `Bearer ${FILTERED}`);
    for (const re of TOKEN_RES) out = out.replace(re, FILTERED);
    out = out.replace(
      ASSIGNMENT_RE,
      (match: string, quote: string, name: string, sep: string, value: string) =>
        // `Authorization: Bearer xyz`: the token after Bearer is already masked.
        isSensitiveName(name) && value !== "Bearer"
          ? `${quote}${name}${quote}${sep}${maskValue(value)}`
          : match,
    );
    out = out.replace(LONG_TOKEN_STRING_RE, (_m, quote: string) => quote + FILTERED + quote);
    return out;
  } catch {
    // Masking failed: send nothing rather than the raw line.
    return FILTERED;
  }
}

/** Mask every line of a source window. */
export function maskLines(lines: string[]): string[] {
  return lines.map(maskSecrets);
}
