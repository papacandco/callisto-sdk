import { vi } from "vitest";

export interface CapturedRequest {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: unknown;
}

export function mockFetch(
  status: number,
  json: unknown,
  responseHeaders?: Record<string, string>,
): { fetch: typeof fetch; captured: CapturedRequest[] } {
  const captured: CapturedRequest[] = [];
  const fn = vi.fn(async (url: string | URL, init?: RequestInit) => {
    const headers: Record<string, string> = {};
    new Headers(init?.headers).forEach((v, k) => (headers[k] = v));
    captured.push({
      url: String(url),
      method: init?.method ?? "GET",
      headers,
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    });
    return new Response(JSON.stringify(json), {
      status,
      headers: { "content-type": "application/json", ...responseHeaders },
    });
  });
  return { fetch: fn as unknown as typeof fetch, captured };
}
