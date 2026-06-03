import type { ResolvedConfig } from "./config.js";
import { errorFromStatus, NetworkError } from "./errors.js";

export type Query = Record<string, string | number | boolean | undefined>;

export interface RequestOptions {
  body?: unknown;
  query?: Query;
}

export class Transport {
  constructor(
    private readonly cfg: ResolvedConfig,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  private authHeader(): string {
    const raw = `${this.cfg.clientId}:${this.cfg.apiKey}`;
    const b64 =
      typeof Buffer !== "undefined"
        ? Buffer.from(raw).toString("base64")
        : btoa(raw);
    return `Basic ${b64}`;
  }

  private buildUrl(path: string, query?: Query): string {
    let url = this.cfg.baseUrl + path;
    if (query) {
      const params = new URLSearchParams();
      for (const [k, v] of Object.entries(query)) {
        if (v !== undefined) params.append(k, String(v));
      }
      const qs = params.toString();
      if (qs) url += `?${qs}`;
    }
    return url;
  }

  async request<T = unknown>(
    method: string,
    path: string,
    opts: RequestOptions = {},
  ): Promise<T> {
    const url = this.buildUrl(path, opts.query);
    const headers: Record<string, string> = {
      authorization: this.authHeader(),
      accept: "application/json",
    };
    const init: RequestInit = { method, headers };
    if (opts.body !== undefined) {
      headers["content-type"] = "application/json";
      init.body = JSON.stringify(opts.body);
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.cfg.timeoutMs);
    init.signal = controller.signal;

    let resp: Response;
    try {
      resp = await this.fetchImpl(url, init);
    } catch (err) {
      throw new NetworkError(`Request to ${url} failed`, err);
    } finally {
      clearTimeout(timer);
    }

    const text = await resp.text();
    const data = text ? JSON.parse(text) : undefined;

    if (!resp.ok) {
      const message =
        (data && typeof data === "object" && "message" in data
          ? String((data as Record<string, unknown>).message)
          : undefined) ?? `HTTP ${resp.status}`;
      let retryAfter: number | undefined;
      if (resp.status === 429) {
        const header = resp.headers.get("retry-after");
        if (header !== null) {
          const parsed = Number.parseInt(header, 10);
          if (Number.isFinite(parsed)) retryAfter = parsed;
        }
      }
      throw errorFromStatus(resp.status, message, data, retryAfter);
    }
    return data as T;
  }
}
