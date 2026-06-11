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
