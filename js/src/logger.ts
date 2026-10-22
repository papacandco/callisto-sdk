import { resolveLogsConfig } from "./config.js";
import {
  buildLogRecord,
  envelope,
  normalizeLogLevel,
  type LogAttributes,
  type LogLevel,
} from "./otlp.js";
import { SDK_VERSION } from "./reporter.js";

/** One OTLP/HTTP export request, as handed to a LogsSender. */
export interface LogsRequest {
  url: string;
  headers: Record<string, string>;
  /** The serialized OTLP JSON body. */
  body: string;
}

/** What the logger needs back from a send. */
export interface LogsResponse {
  status: number;
  /** Raw `Retry-After` header value, if any. */
  retryAfter?: string | null;
}

/**
 * The injectable HTTP sender. Resolves with the response status (any status);
 * rejects on a network error or timeout. The logger swallows everything.
 */
export type LogsSender = (request: LogsRequest) => Promise<LogsResponse>;

export interface CallistoLoggerOptions {
  /** `pk_…` of a Logs-scope API key. Falls back to `CALLISTO_LOGS_ACCESS_KEY`. */
  logsAccessKey?: string;
  /** `sk_…` of that key. Falls back to `CALLISTO_LOGS_SECRET`. */
  logsSecret?: string;
  /** Full POST URL. Falls back to `CALLISTO_LOGS_ENDPOINT`, then the Callisto endpoint. */
  logsEndpoint?: string;
  /** Resource `service.name`. Falls back to `CALLISTO_SERVICE_NAME`, `OTEL_SERVICE_NAME`, `unknown_service`. */
  serviceName?: string;
  /** Resource `deployment.environment.name`. Falls back to `CALLISTO_ENVIRONMENT`. */
  environment?: string;
  /** Upper bound for `flush()` / `close()`, in milliseconds. Default 5000. */
  flushTimeout?: number;
  /** Inject a sender (tests/advanced use). Default: POST with `fetch`. */
  sender?: LogsSender;
  /** Inject a fetch implementation for the default sender. Default: global `fetch`. */
  fetch?: typeof fetch;
  /** Inject a clock returning epoch milliseconds (tests). Default: `Date.now`. */
  clock?: () => number;
}

/** Records queued in memory at most; beyond this, new records are dropped. */
export const MAX_QUEUE = 2_000;
/** A send starts as soon as this many records are queued. */
export const BATCH_TRIGGER = 100;
/** At most this many records per request... */
export const MAX_BATCH_RECORDS = 500;
/** ...and about this many characters of serialized JSON. */
export const MAX_BATCH_CHARS = 1_000_000;
/** Background send interval. */
export const FLUSH_INTERVAL_MS = 5_000;
const DEFAULT_FLUSH_TIMEOUT_MS = 5_000;
const REQUEST_TIMEOUT_MS = 10_000;
const MAX_ATTEMPTS = 3;
const NETWORK_RETRY_DELAYS_MS = [1_000, 5_000];
const DEFAULT_RETRY_AFTER_MS = 5_000;
const MAX_RETRY_AFTER_MS = 60_000;
const AUTH_PAUSE_MS = 60_000;

type TimerHandle = ReturnType<typeof setTimeout>;

function unref(handle: TimerHandle): TimerHandle {
  (handle as { unref?: () => void }).unref?.();
  return handle;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => unref(setTimeout(resolve, ms)));
}

function basicAuth(accessKey: string, secret: string): string {
  const raw = `${accessKey}:${secret}`;
  const b64 =
    typeof Buffer !== "undefined" ? Buffer.from(raw).toString("base64") : btoa(raw);
  return `Basic ${b64}`;
}

/** Retry-After in seconds → ms, capped at 60 s; 5 s when absent or unparsable. */
function retryAfterMs(header: string | null | undefined): number {
  const value = header?.trim();
  if (!value || !/^\d+(\.\d+)?$/.test(value)) return DEFAULT_RETRY_AFTER_MS;
  return Math.min(Number(value) * 1000, MAX_RETRY_AFTER_MS);
}

function fetchSender(fetchImpl: typeof fetch | undefined): LogsSender {
  return async (request) => {
    const doFetch = fetchImpl ?? globalThis.fetch;
    const controller = new AbortController();
    const timer = unref(setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS));
    try {
      const resp = await doFetch(request.url, {
        method: "POST",
        headers: request.headers,
        body: request.body,
        signal: controller.signal,
      });
      try {
        await resp.text(); // release the connection
      } catch {
        // ignore
      }
      return { status: resp.status, retryAfter: resp.headers.get("retry-after") };
    } finally {
      clearTimeout(timer);
    }
  };
}

// Loggers still open when the event loop drains get one last flush, so a short
// script that never calls close() does not silently lose its last lines. One
// process listener for all loggers.
const openLoggers = new Set<CallistoLogger>();
let beforeExitInstalled = false;

function trackOpen(logger: CallistoLogger): void {
  openLoggers.add(logger);
  if (beforeExitInstalled) return;
  const proc = (globalThis as { process?: NodeJS.Process }).process;
  if (!proc || typeof proc.on !== "function") return;
  proc.on("beforeExit", () => {
    for (const open of openLoggers) void open.flush();
  });
  beforeExitInstalled = true;
}

let buildingDisabled = false;

/**
 * A no-op logger that reads no option and no environment variable. Used by
 * CallistoClient when the Logs configuration is invalid. Not exported from
 * the package entry point.
 */
export function disabledLogger(): CallistoLogger {
  buildingDisabled = true;
  try {
    return new CallistoLogger();
  } finally {
    buildingDisabled = false;
  }
}

/**
 * Ships log lines to callisto-logs as OTLP/HTTP JSON. Server only: a Logs
 * secret is refused in a browser (it would be public in the bundle). Records
 * are queued in memory and sent in the background (every 5 s, or as soon as
 * 100 are queued). A log call never throws and never waits on the network.
 *
 * Built with no Logs key and no secret, the logger is a no-op.
 */
export class CallistoLogger {
  private readonly enabled: boolean;
  private readonly url: string = "";
  private readonly headers: Record<string, string> = {};
  private readonly prefix: string = "";
  private readonly suffix: string = "";
  private readonly sender: LogsSender;
  private readonly clock: () => number;
  private readonly flushTimeoutMs: number;

  private readonly queue: string[] = [];
  private dropped = 0;
  private pausedUntil = 0;
  private worker: Promise<void> | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private closed = false;
  private stopped = false;
  private closing: Promise<void> | null = null;

  /**
   * @throws ConfigurationError on half or malformed credentials, an endpoint
   * that is not https (http only for localhost), or a Logs secret configured
   * in a browser.
   */
  constructor(options: CallistoLoggerOptions = {}) {
    const cfg = buildingDisabled
      ? { enabled: false as const, accessKey: undefined, secret: undefined }
      : resolveLogsConfig(options);
    this.enabled = cfg.enabled;
    this.sender = options.sender ?? fetchSender(options.fetch);
    this.clock = options.clock ?? Date.now;
    this.flushTimeoutMs =
      options.flushTimeout !== undefined && options.flushTimeout >= 0
        ? options.flushTimeout
        : DEFAULT_FLUSH_TIMEOUT_MS;
    if (!cfg.enabled || !cfg.accessKey || !cfg.secret) return;

    this.url = cfg.endpoint;
    this.headers = {
      authorization: basicAuth(cfg.accessKey, cfg.secret),
      "content-type": "application/json",
      "user-agent": `callisto-sdk-nodejs/${SDK_VERSION}`,
    };
    ({ prefix: this.prefix, suffix: this.suffix } = envelope({
      serviceName: cfg.serviceName,
      environment: cfg.environment,
      sdkName: "callisto-sdk",
      sdkLanguage: "nodejs",
      sdkVersion: SDK_VERSION,
    }));

    this.timer = setInterval(() => {
      if (this.queue.length > 0) void this.kick();
    }, FLUSH_INTERVAL_MS);
    (this.timer as { unref?: () => void }).unref?.();
    trackOpen(this);
  }

  /** Whether a Logs key resolved (false → every call is a no-op). */
  get isEnabled(): boolean {
    return this.enabled;
  }

  /** Records dropped so far: queue full, sending paused, or a failed batch. */
  droppedCount(): number {
    return this.dropped;
  }

  debug(message: unknown, attributes?: LogAttributes | null): void {
    this.log("debug", message, attributes);
  }

  info(message: unknown, attributes?: LogAttributes | null): void {
    this.log("info", message, attributes);
  }

  warn(message: unknown, attributes?: LogAttributes | null): void {
    this.log("warn", message, attributes);
  }

  error(message: unknown, attributes?: LogAttributes | null): void {
    this.log("error", message, attributes);
  }

  fatal(message: unknown, attributes?: LogAttributes | null): void {
    this.log("fatal", message, attributes);
  }

  /** Queue one record. `level` is debug|info|warn|error|fatal (unknown → info). */
  log(level: LogLevel | string, message: unknown, attributes?: LogAttributes | null): void {
    if (!this.enabled || this.closed) return;
    try {
      const now = this.clock();
      if (this.queue.length >= MAX_QUEUE || now < this.pausedUntil) {
        this.dropped++;
        return;
      }
      const record = buildLogRecord(normalizeLogLevel(level), message, attributes, now);
      this.queue.push(JSON.stringify(record));
      if (this.queue.length >= BATCH_TRIGGER) void this.kick();
    } catch {
      // A log call never throws.
    }
  }

  /** Send everything queued now. Bounded by `flushTimeout`; never rejects. */
  async flush(): Promise<void> {
    if (!this.enabled) return;
    await this.bounded(this.drainAll());
  }

  /** Flush (bounded), then stop the worker. Later calls are no-ops. */
  close(): Promise<void> {
    if (!this.enabled) return Promise.resolve();
    if (!this.closing) {
      this.closed = true;
      if (this.timer) clearInterval(this.timer);
      this.timer = null;
      openLoggers.delete(this);
      this.closing = this.bounded(this.drainAll()).then(() => {
        this.stopped = true;
        this.queue.length = 0;
      });
    }
    return this.closing;
  }

  // --- internals ---

  private async bounded(work: Promise<void>): Promise<void> {
    let timer: TimerHandle | undefined;
    const timeout = new Promise<void>((resolve) => {
      timer = unref(setTimeout(resolve, this.flushTimeoutMs));
    });
    try {
      await Promise.race([work.catch(() => undefined), timeout]);
    } catch {
      // never reject
    } finally {
      clearTimeout(timer);
    }
  }

  private async drainAll(): Promise<void> {
    while (this.queue.length > 0 && !this.stopped) await this.kick();
  }

  /** Start the single worker if idle; return its promise. */
  private kick(): Promise<void> {
    if (!this.worker) {
      this.worker = this.drain()
        .catch(() => undefined)
        .finally(() => {
          this.worker = null;
        });
    }
    return this.worker;
  }

  private async drain(): Promise<void> {
    while (this.queue.length > 0 && !this.stopped) {
      if (this.clock() < this.pausedUntil) {
        this.dropped += this.queue.length;
        this.queue.length = 0;
        return;
      }
      await this.sendBatch(this.takeBatch());
    }
  }

  private takeBatch(): string[] {
    let count = 0;
    let chars = 0;
    while (count < this.queue.length && count < MAX_BATCH_RECORDS) {
      const size = this.queue[count].length + 1;
      if (count > 0 && chars + size > MAX_BATCH_CHARS) break;
      chars += size;
      count++;
    }
    return this.queue.splice(0, count);
  }

  private async sendBatch(records: string[]): Promise<void> {
    const request: LogsRequest = {
      url: this.url,
      headers: { ...this.headers },
      body: this.prefix + records.join(",") + this.suffix,
    };
    for (let attempt = 1; attempt <= MAX_ATTEMPTS && !this.stopped; attempt++) {
      let status: number;
      let retryAfter: string | null | undefined;
      try {
        ({ status, retryAfter } = await this.sender(request));
      } catch {
        if (attempt === MAX_ATTEMPTS) break;
        await sleep(NETWORK_RETRY_DELAYS_MS[attempt - 1]);
        continue;
      }
      if (status >= 200 && status < 300) return;
      if (status === 401 || status === 403) {
        this.pausedUntil = this.clock() + AUTH_PAUSE_MS;
        break;
      }
      if ((status === 429 || status === 503) && attempt < MAX_ATTEMPTS) {
        await sleep(retryAfterMs(retryAfter));
        continue;
      }
      break; // 400/413/415, other statuses, or the last 429/503: drop
    }
    this.dropped += records.length;
  }
}
