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
}

const DEFAULT_BASE_URL = "https://api.callistosignal.com/v1";

export const DEFAULT_LOGS_ENDPOINT = "https://logs.callistosignal.com/v1/logs";

const LOGS_ACCESS_KEY_RE = /^pk_[0-9a-f]{32}$/;
const LOGS_SECRET_RE = /^sk_[0-9a-f]{48}$/;

/** Read an env var without assuming `process` exists (browsers, workers). */
function env(name: string): string | undefined {
  const proc = (globalThis as { process?: { env?: Record<string, string | undefined> } })
    .process;
  const value = proc?.env?.[name];
  return value ? value : undefined;
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
 * Both key and secret absent → disabled. Exactly one present, a malformed
 * value, or a non-http(s) endpoint → ConfigurationError (fail fast: a typo
 * must not silently lose every log). Error messages never echo the secret.
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
    throw new ConfigurationError("Callisto: logsEndpoint must be an absolute http(s) URL.");
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new ConfigurationError("Callisto: logsEndpoint must be an absolute http(s) URL.");
  }
  return { enabled: true, accessKey, secret, endpoint, serviceName, environment };
}

function resolveCaptureUnhandled(opts: CallistoOptions): boolean {
  if (opts.captureUnhandled !== undefined) return opts.captureUnhandled;
  const env = process.env.CALLISTO_CAPTURE_UNHANDLED;
  if (env === undefined) return false;
  return env === "1" || env.toLowerCase() === "true";
}

export function resolveConfig(opts: CallistoOptions = {}): ResolvedConfig {
  // Credentials are optional at construction: error reporting needs only a DSN.
  // The transport validates them lazily, the first time a messaging call runs.
  const clientId = opts.clientId ?? process.env.CALLISTO_CLIENT_ID;
  const apiKey = opts.apiKey ?? process.env.CALLISTO_API_KEY;
  const baseUrl = (
    opts.baseUrl ??
    process.env.CALLISTO_BASE_URL ??
    DEFAULT_BASE_URL
  ).replace(/\/+$/, "");
  const errorDsn = opts.errorDsn ?? process.env.CALLISTO_APP_ERROR_DSN;
  const environment = opts.environment ?? process.env.CALLISTO_ENVIRONMENT;
  return {
    clientId: clientId || undefined,
    apiKey: apiKey || undefined,
    baseUrl,
    timeoutMs: opts.timeoutMs ?? 30000,
    errorDsn: errorDsn || undefined,
    captureUnhandled: resolveCaptureUnhandled(opts),
    environment: environment || undefined,
  };
}
