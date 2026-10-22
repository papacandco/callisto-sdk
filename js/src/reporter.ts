import { isSecureEndpoint, resolveSendSourceContext } from "./config.js";
import { CallistoError, RateLimitError } from "./errors.js";
import { maskLines, maskSecrets } from "./scrub.js";

/** SDK version reported in `context.sdk.version`. Mirrors package.json. */
export const SDK_VERSION = "1.3.0";

export type ErrorLevel = "fatal" | "error" | "warning" | "info";

const LEVELS: ReadonlySet<string> = new Set([
  "fatal",
  "error",
  "warning",
  "info",
]);

function normalizeLevel(level: string): ErrorLevel {
  return LEVELS.has(level) ? (level as ErrorLevel) : "error";
}

export interface SdkMeta {
  name: string;
  version: string;
  language: string;
}

export interface StackFrame {
  function?: string;
  file?: string;
  line?: number;
  /** Up to CONTEXT_LINES source lines before the error line (Node only). */
  pre_context?: string[];
  /** The error line's source (Node only). */
  context_line?: string;
  /** Up to CONTEXT_LINES source lines after the error line (Node only). */
  post_context?: string[];
}

/** Source lines captured on each side of a frame's error line. */
const CONTEXT_LINES = 10;

/** Skip source capture for files larger than this (bytes). */
const MAX_SOURCE_BYTES = 2_000_000;

interface FsLike {
  statSync(path: string): { size: number };
  readFileSync(path: string, encoding: "utf8"): string;
}

let fsCache: FsLike | null | undefined;

/**
 * Resolve Node's `fs` synchronously, or null when unavailable (browsers,
 * bundled ESM without a CJS `require`). The indirect `eval` keeps bundlers
 * from statically resolving `require` and breaking browser builds.
 */
function nodeFs(): FsLike | null {
  if (fsCache !== undefined) return fsCache;
  fsCache = null;
  try {
    const proc = (globalThis as { process?: { versions?: { node?: string } } })
      .process;
    if (proc?.versions?.node) {
      const req = (0, eval)(
        "typeof require === 'function' ? require : null",
      ) as ((m: string) => unknown) | null;
      if (req) fsCache = req("node:fs") as FsLike;
    }
  } catch {
    fsCache = null;
  }
  return fsCache;
}

/**
 * Best-effort source window around a frame's error line — `pre_context` /
 * `context_line` / `post_context`, up to CONTEXT_LINES each side. Every line
 * goes through the secret masker (scrub.ts) before it is attached. Returns the
 * frame unchanged when the file can't be read (no Node fs, missing/oversized
 * file, line out of range), so the frame simply renders without source.
 */
function withSourceContext(frame: StackFrame): StackFrame {
  const { file, line } = frame;
  if (!file || line === undefined || line < 1) return frame;

  const fs = nodeFs();
  if (!fs) return frame;

  try {
    if (fs.statSync(file).size > MAX_SOURCE_BYTES) return frame;
    const lines = fs.readFileSync(file, "utf8").split("\n");
    if (line > lines.length) return frame;

    const index = line - 1; // 0-based offset of the error line
    const start = Math.max(0, index - CONTEXT_LINES);
    return {
      ...frame,
      pre_context: maskLines(lines.slice(start, index)),
      context_line: maskSecrets(lines[index]),
      post_context: maskLines(lines.slice(index + 1, index + 1 + CONTEXT_LINES)),
    };
  } catch {
    return frame;
  }
}

/** Extra metadata an error carries for the reporter (set by the transport). */
export interface ReportableRequest {
  method: string;
  path: string;
}

/**
 * The injectable HTTP sender. Receives the DSN URL and the JSON payload.
 * The default implementation POSTs via `fetch`. Must resolve/reject; the
 * reporter swallows everything.
 */
export type Sender = (url: string, payload: unknown) => Promise<void>;

export interface ErrorReporterOptions {
  /** Ingest DSN: https only (http only for localhost). Anything else disables reporting. */
  dsn?: string;
  sdk?: SdkMeta;
  environment?: string;
  sender?: Sender;
  /**
   * Attach masked source lines to stack frames. Default true; falls back to
   * `CALLISTO_SEND_SOURCE_CONTEXT` (`false` / `0` turns it off).
   */
  sendSourceContext?: boolean;
}

function defaultSender(fetchImpl: typeof fetch): Sender {
  return async (url, payload) => {
    const resp = await fetchImpl(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload),
    });
    if (resp.status !== 202) {
      throw new Error(`ingest returned ${resp.status}`);
    }
  };
}

/**
 * Validate a DSN: an absolute https URL (http only for localhost). A DSN that
 * is set but rejected disables reporting with one console warning that never
 * echoes the DSN (it carries the ingest key).
 */
function acceptDsn(dsn: string | undefined): string | undefined {
  if (!dsn) return undefined;
  let url: URL | undefined;
  try {
    url = new URL(dsn);
  } catch {
    url = undefined;
  }
  if (url && isSecureEndpoint(url)) return dsn;
  warnOnce(
    url && url.protocol === "http:"
      ? "Callisto: error reporting is disabled: the DSN must use https " +
          "(plain http is allowed only for localhost)."
      : "Callisto: error reporting is disabled: the DSN is not an absolute https URL.",
  );
  return undefined;
}

function warnOnce(message: string): void {
  try {
    globalThis.console?.warn?.(message);
  } catch {
    // a console that throws must not break construction
  }
}

/**
 * Parse a V8/Node `error.stack` string into best-effort frames
 * (innermost-first). Returns an empty array when nothing parseable.
 */
export function parseStack(stack: string | undefined): StackFrame[] {
  if (!stack) return [];
  const frames: StackFrame[] = [];
  for (const raw of stack.split("\n")) {
    const line = raw.trim();
    if (!line.startsWith("at ")) continue;
    const body = line.slice(3).trim();
    // Forms:  "fn (file:line:col)"  |  "file:line:col"
    const withFn = body.match(/^(.*?)\s+\((.*):(\d+):(\d+)\)$/);
    if (withFn) {
      frames.push({
        function: withFn[1],
        file: withFn[2],
        line: Number.parseInt(withFn[3], 10),
      });
      continue;
    }
    const bare = body.match(/^(.*):(\d+):(\d+)$/);
    if (bare) {
      frames.push({ file: bare[1], line: Number.parseInt(bare[2], 10) });
      continue;
    }
    frames.push({ function: body });
  }
  return frames;
}

/**
 * Background, best-effort, Sentry-style error reporter. POSTs captured
 * errors directly to the Callisto ingest DSN. Never throws, never delays
 * the caller's error path, and never transmits credentials or request
 * bodies (PII).
 */
export class ErrorReporter {
  private readonly dsn?: string;
  private readonly enabled: boolean;
  private readonly sdk: SdkMeta;
  private readonly environment?: string;
  private readonly sender: Sender;
  private readonly sendSourceContext: boolean;
  private user?: Record<string, unknown>;
  private readonly inflight: Set<Promise<void>> = new Set();

  constructor(options: ErrorReporterOptions = {}, fetchImpl: typeof fetch = fetch) {
    this.dsn = acceptDsn(options.dsn);
    this.enabled = this.dsn !== undefined;
    this.sdk = options.sdk ?? {
      name: "callisto-sdk",
      version: SDK_VERSION,
      language: "javascript",
    };
    this.environment = options.environment;
    this.sender = options.sender ?? defaultSender(fetchImpl);
    this.sendSourceContext = resolveSendSourceContext(options.sendSourceContext);
  }

  /** Whether a valid DSN is configured. */
  get isEnabled(): boolean {
    return this.enabled;
  }

  setUser(user: Record<string, unknown> | null | undefined): void {
    this.user = user ?? undefined;
  }

  captureException(
    error: unknown,
    level: string = "error",
    extra?: Record<string, unknown> | null,
  ): void {
    if (!this.enabled) return;
    try {
      const payload = this.buildExceptionPayload(error, level, extra);
      this.dispatch(payload);
    } catch {
      // Never let our own bookkeeping disturb the caller.
    }
  }

  captureMessage(
    message: string,
    level: string = "info",
    extra?: Record<string, unknown> | null,
  ): void {
    if (!this.enabled) return;
    try {
      const payload = {
        message: String(message ?? ""),
        type: "Message",
        level: normalizeLevel(level),
        context: this.buildContext(undefined, extra),
        ...(this.user ? { user: this.user } : {}),
      };
      this.dispatch(payload);
    } catch {
      // swallow
    }
  }

  /** Await all in-flight sends. Best-effort; never throws. */
  async flush(): Promise<void> {
    await Promise.allSettled(Array.from(this.inflight));
  }

  /** Flush pending sends and stop accepting new ones implicitly. */
  async close(): Promise<void> {
    await this.flush();
  }

  // --- internals ---

  private dispatch(payload: unknown): void {
    if (!this.dsn) return;
    // Fire-and-forget: do NOT await. Track so flush() can drain it.
    const p = Promise.resolve()
      .then(() => this.sender(this.dsn as string, payload))
      .catch(() => {
        // Swallow every failure; never re-capture our own errors.
      })
      .finally(() => {
        this.inflight.delete(p);
      });
    this.inflight.add(p);
  }

  private buildContext(
    error: unknown,
    extra?: Record<string, unknown> | null,
  ): Record<string, unknown> {
    const context: Record<string, unknown> = { sdk: { ...this.sdk } };
    if (this.environment) context.environment = this.environment;

    if (error instanceof CallistoError) {
      context.status_code = error.statusCode;
      if (error instanceof RateLimitError && error.retryAfter !== undefined) {
        context.retry_after = error.retryAfter;
      }
      if (error.body !== undefined) context.body = error.body;
    }

    if (extra && typeof extra === "object") {
      for (const [k, v] of Object.entries(extra)) context[k] = v;
    }
    return context;
  }

  private buildExceptionPayload(
    error: unknown,
    level: string,
    extra?: Record<string, unknown> | null,
  ): Record<string, unknown> {
    const err = error instanceof Error ? error : undefined;
    const message =
      err?.message ??
      (typeof error === "string" ? error : String(error ?? "Unknown error"));
    const type =
      err?.name ??
      (err ? err.constructor.name : undefined) ??
      "Error";

    const frames = parseStack(err?.stack);

    const reqInfo: ReportableRequest | undefined =
      (error as { __callistoRequest?: ReportableRequest })?.__callistoRequest;

    let culprit: string | undefined;
    if (reqInfo) {
      culprit = `${reqInfo.method} ${reqInfo.path}`;
    } else if (frames.length > 0) {
      const top = frames[0];
      const fn = top.function ?? "<anonymous>";
      culprit =
        top.file && top.line !== undefined
          ? `${fn} (${top.file}:${top.line})`
          : fn;
    }

    const payload: Record<string, unknown> = {
      message: String(message || "Unknown error"),
      type,
      level: normalizeLevel(level),
      context: this.buildContext(error, extra),
    };
    if (culprit) payload.culprit = culprit;
    if (frames.length > 0) {
      // Source context only for genuine application exceptions. For transport
      // errors a call site can embed the outgoing request body as literal
      // arguments — capturing it would violate the no-request-body guarantee.
      payload.stacktrace =
        reqInfo || !this.sendSourceContext ? frames : frames.map(withSourceContext);
    }
    if (reqInfo) {
      payload.request = { method: reqInfo.method, path: reqInfo.path };
    }
    if (this.user) payload.user = this.user;
    return payload;
  }
}

/**
 * Tag an error with transport request info so the reporter can derive
 * `culprit` / `request`. Carries only method + path — never the body.
 */
export function tagRequest(
  error: unknown,
  method: string,
  path: string,
): void {
  if (error && typeof error === "object") {
    (error as { __callistoRequest?: ReportableRequest }).__callistoRequest = {
      method,
      path,
    };
  }
}
