import { describe, it, expect, vi } from "vitest";
import { callistoErrorHandler } from "../../src/integrations/express.js";
import type { ErrorCapturer } from "../../src/integrations/shared.js";

function fakeClient(): ErrorCapturer & {
  calls: Array<{ err: unknown; level?: string; extra?: unknown }>;
} {
  const calls: Array<{ err: unknown; level?: string; extra?: unknown }> = [];
  return {
    calls,
    captureException(err, level, extra) {
      calls.push({ err, level, extra });
    },
  };
}

// Express recognizes error middleware by its 4-arg arity.
function isErrorMiddleware(fn: Function): boolean {
  return fn.length === 4;
}

describe("callistoErrorHandler", () => {
  it("returns a 4-arg express error middleware", () => {
    const handler = callistoErrorHandler(fakeClient());
    expect(isErrorMiddleware(handler)).toBe(true);
  });

  it("reports with route path and method, then calls next(err)", () => {
    const client = fakeClient();
    const handler = callistoErrorHandler(client);
    const err = new Error("boom");
    const next = vi.fn();

    handler(err, { method: "POST", route: { path: "/users/:id" } } as any, {} as any, next);

    expect(client.calls).toHaveLength(1);
    expect(client.calls[0].extra).toEqual({
      http_method: "POST",
      http_path: "/users/:id",
    });
    expect(next).toHaveBeenCalledWith(err);
  });

  it("falls back to originalUrl when no route is matched", () => {
    const client = fakeClient();
    const handler = callistoErrorHandler(client);
    const next = vi.fn();

    handler(new Error("x"), { method: "GET", originalUrl: "/missing" } as any, {} as any, next);

    expect((client.calls[0].extra as any).http_path).toBe("/missing");
  });

  it("always calls next(err) even when shouldReport is false", () => {
    const client = fakeClient();
    const handler = callistoErrorHandler(client, { shouldReport: () => false });
    const err = new Error("x");
    const next = vi.fn();

    handler(err, { method: "GET", originalUrl: "/" } as any, {} as any, next);

    expect(client.calls).toHaveLength(0);
    expect(next).toHaveBeenCalledWith(err);
  });
});
