import { CallistoError, RateLimitError } from "./errors.js";

/** SDK version reported in `context.sdk.version`. Mirrors package.json. */
export const SDK_VERSION = "0.1.0";

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
  dsn?: string;
  sdk?: SdkMeta;
  environment?: string;
  sender?: Sender;
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

function isValidUrl(dsn: string | undefined): dsn is string {
  if (!dsn) return false;
  try {
    const u = new URL(dsn);
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
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
  private user?: Record<string, unknown>;
  private readonly inflight: Set<Promise<void>> = new Set();

  constructor(options: ErrorReporterOptions = {}, fetchImpl: typeof fetch = fetch) {
    this.dsn = isValidUrl(options.dsn) ? options.dsn : undefined;
    this.enabled = this.dsn !== undefined;
    this.sdk = options.sdk ?? {
      name: "callisto-sdk",
      version: SDK_VERSION,
      language: "javascript",
    };
    this.environment = options.environment;
    this.sender = options.sender ?? defaultSender(fetchImpl);
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
    if (frames.length > 0) payload.stacktrace = frames;
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
