export interface CallistoOptions {
  clientId?: string;
  apiKey?: string;
  baseUrl?: string;
  timeoutMs?: number;
}

export interface ResolvedConfig {
  clientId: string;
  apiKey: string;
  baseUrl: string;
  timeoutMs: number;
}

const DEFAULT_BASE_URL = "https://api.callistosignal.com/v1";

export function resolveConfig(opts: CallistoOptions = {}): ResolvedConfig {
  const clientId = opts.clientId ?? process.env.CALLISTO_CLIENT_ID;
  const apiKey = opts.apiKey ?? process.env.CALLISTO_API_KEY;
  if (!clientId || !apiKey) {
    throw new Error(
      "Callisto: clientId and apiKey are required (pass options or set CALLISTO_CLIENT_ID / CALLISTO_API_KEY).",
    );
  }
  const baseUrl = (
    opts.baseUrl ??
    process.env.CALLISTO_BASE_URL ??
    DEFAULT_BASE_URL
  ).replace(/\/+$/, "");
  return { clientId, apiKey, baseUrl, timeoutMs: opts.timeoutMs ?? 30000 };
}
