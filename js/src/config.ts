import { ConfigurationError } from "./errors.js";

export interface CallistoOptions {
  clientId?: string;
  apiKey?: string;
  baseUrl?: string;
  timeoutMs?: number;
  /** Error-reporting ingest DSN. Absent → error reporting is disabled. */
  errorDsn?: string;
  /** Install the global unhandled-exception handler (default false). */
  captureUnhandled?: boolean;
  /** Optional environment tag included in `context.environment`. */
  environment?: string;
  /** Access key (`pk_…`) of a Logs-scope API key. See CallistoLogger. */
  logsAccessKey?: string;
  /** Secret (`sk_…`) of that Logs key. */
  logsSecret?: string;
  /** Full OTLP/HTTP logs POST URL. */
  logsEndpoint?: string;
  /** Resource `service.name` attached to shipped logs. */
  serviceName?: string;
  /**
   * Attach masked source lines (`pre_context` / `context_line` /
   * `post_context`) to reported stack frames. Default true; falls back to
   * `CALLISTO_SEND_SOURCE_CONTEXT` (`false` / `0` turns it off).
   */
  sendSourceContext?: boolean;
}

export interface ResolvedConfig {
  /** Only required for messaging API calls — validated lazily by the transport. */
  clientId?: string;
  /** Only required for messaging API calls — validated lazily by the transport. */
  apiKey?: string;
  baseUrl: string;
  timeoutMs: number;
  errorDsn?: string;
  captureUnhandled: boolean;
  environment?: string;
  sendSourceContext: boolean;
}

const DEFAULT_BASE_URL = "https://api.callistosignal.com/v1";

export const DEFAULT_LOGS_ENDPOINT = "https://logs.callistosignal.com/v1/logs";

const LOGS_ACCESS_KEY_RE = /^pk_[0-9a-f]{32}$/;
const LOGS_SECRET_RE = /^sk_[0-9a-f]{48}$/;

/**
 * Read an env var without assuming `process` exists (browsers, workers).
 * Every environment read in the SDK goes through here.
 */
export function env(name: string): string | undefined {
  try {
    const proc = (globalThis as { process?: { env?: Record<string, string | undefined> } })
      .process;
    const value = proc?.env?.[name];
    return value ? value : undefined;
  } catch {
    return undefined;
  }
}

/**
 * True when running in a web page: `window` and `document` exist and this is
 * not Node (jsdom / SSR test runners define both under Node). Web workers have
 * no `document`, so they never count as a browser here.
 */
export function isBrowserRuntime(): boolean {
  const g = globalThis as {
    window?: unknown;
    document?: unknown;
    process?: { versions?: { node?: string } };
  };
  if (typeof g.window === "undefined" || typeof g.document === "undefined") return false;
  try {
    if (g.process?.versions?.node) return false;
  } catch {
    // an exotic `process` global: treat as a browser
  }
  return true;
}

/**
 * Secret-bearing endpoints must use https. Plain http is allowed only for a
 * local collector: localhost, 127.0.0.1, ::1 or a `*.localhost` name.
 */
export function isSecureEndpoint(url: URL): boolean {
  if (url.protocol === "https:") return true;
  if (url.protocol !== "http:") return false;
  const host = url.hostname.toLowerCase();
  return (
    host === "localhost" ||
    host === "127.0.0.1" ||
    host === "[::1]" ||
    host === "::1" ||
    host.endsWith(".localhost")
  );
}

/** `sendSourceContext` option, then `CALLISTO_SEND_SOURCE_CONTEXT`, default true. */
export function resolveSendSourceContext(option?: boolean): boolean {
  if (option !== undefined) return option;
  const value = env("CALLISTO_SEND_SOURCE_CONTEXT")?.trim().toLowerCase();
  return !(value === "false" || value === "0" || value === "no" || value === "off");
}

export interface LogsConfigInput {
  logsAccessKey?: string;
  logsSecret?: string;
  logsEndpoint?: string;
  serviceName?: string;
  environment?: string;
}

export interface ResolvedLogsConfig {
  /** False when neither key nor secret resolves: the logger is a no-op. */
  enabled: boolean;
  accessKey?: string;
  secret?: string;
  endpoint: string;
  serviceName: string;
  environment?: string;
}

/**
 * Resolve the Logs settings: explicit option first, then environment variable.
 * Both key and secret absent → disabled. A secret in a browser, exactly one of
 * the pair, a malformed value, or an endpoint that is not https (http only for
 * localhost) → ConfigurationError (fail fast: a typo must not silently lose
 * every log). Error messages never echo the key, the secret or the endpoint.
 */
export function resolveLogsConfig(opts: LogsConfigInput = {}): ResolvedLogsConfig {
  const accessKey = opts.logsAccessKey || env("CALLISTO_LOGS_ACCESS_KEY");
  const secret = opts.logsSecret || env("CALLISTO_LOGS_SECRET");
  const endpoint =
    opts.logsEndpoint || env("CALLISTO_LOGS_ENDPOINT") || DEFAULT_LOGS_ENDPOINT;
  const serviceName =
    opts.serviceName ||
    env("CALLISTO_SERVICE_NAME") ||
    env("OTEL_SERVICE_NAME") ||
    "unknown_service";
  const environment = opts.environment || env("CALLISTO_ENVIRONMENT");

  if (!accessKey && !secret) {
    return { enabled: false, endpoint, serviceName, environment };
  }
  if (secret && isBrowserRuntime()) {
    // Anything shipped to a browser is public: the sk_ would be readable by
    // anyone who opens the bundle.
    throw new ConfigurationError("Callisto Logs secrets must not be used in a browser");
  }
  if (!accessKey || !secret) {
    throw new ConfigurationError(
      "Callisto: logsAccessKey and logsSecret must be set together " +
        "(pass options or set CALLISTO_LOGS_ACCESS_KEY / CALLISTO_LOGS_SECRET).",
    );
  }
  if (!LOGS_ACCESS_KEY_RE.test(accessKey)) {
    throw new ConfigurationError(
      "Callisto: logsAccessKey must be pk_ followed by 32 lowercase hex characters.",
    );
  }
  if (!LOGS_SECRET_RE.test(secret)) {
    throw new ConfigurationError(
      "Callisto: logsSecret must be sk_ followed by 48 lowercase hex characters.",
    );
  }
  let url: URL;
  try {
    url = new URL(endpoint);
  } catch {
    throw new ConfigurationError("Callisto: logsEndpoint must be an absolute https URL.");
  }
  if (!isSecureEndpoint(url)) {
    throw new ConfigurationError(
      "Callisto: logsEndpoint must use https (plain http is allowed only for localhost).",
    );
  }
  return { enabled: true, accessKey, secret, endpoint, serviceName, environment };
}

function resolveCaptureUnhandled(opts: CallistoOptions): boolean {
  if (opts.captureUnhandled !== undefined) return opts.captureUnhandled;
  const value = env("CALLISTO_CAPTURE_UNHANDLED");
  if (value === undefined) return false;
  return value === "1" || value.toLowerCase() === "true";
}

export function resolveConfig(opts: CallistoOptions = {}): ResolvedConfig {
  // Credentials are optional at construction: error reporting needs only a DSN.
  // The transport validates them lazily, the first time a messaging call runs.
  // Every read goes through env(): `process` may not exist (browsers, workers).
  const clientId = opts.clientId ?? env("CALLISTO_CLIENT_ID");
  const apiKey = opts.apiKey ?? env("CALLISTO_API_KEY");
  const baseUrl = (
    opts.baseUrl ??
    env("CALLISTO_BASE_URL") ??
    DEFAULT_BASE_URL
  ).replace(/\/+$/, "");
  const errorDsn = opts.errorDsn ?? env("CALLISTO_APP_ERROR_DSN");
  const environment = opts.environment ?? env("CALLISTO_ENVIRONMENT");
  return {
    clientId: clientId || undefined,
    apiKey: apiKey || undefined,
    baseUrl,
    timeoutMs: opts.timeoutMs ?? 30000,
    errorDsn: errorDsn || undefined,
    captureUnhandled: resolveCaptureUnhandled(opts),
    environment: environment || undefined,
    sendSourceContext: resolveSendSourceContext(opts.sendSourceContext),
  };
}
