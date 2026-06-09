import { describe, it, expect, vi } from "vitest";
import { throwError, of, lastValueFrom } from "rxjs";
import { CallistoInterceptor } from "../../src/integrations/nestjs.js";
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

// Minimal ExecutionContext stub exposing an HTTP request.
function httpContext(req: unknown): any {
  return {
    switchToHttp: () => ({ getRequest: () => req }),
  };
}

describe("CallistoInterceptor", () => {
  it("reports the error with method/path and re-emits it untouched", async () => {
    const client = fakeClient();
    const interceptor = new CallistoInterceptor(client);
    const err = new Error("boom");
    const ctx = httpContext({ method: "POST", route: { path: "/x/:id" } });
    const callHandler = { handle: () => throwError(() => err) };

    const result$ = interceptor.intercept(ctx, callHandler as any);

    await expect(lastValueFrom(result$)).rejects.toBe(err);
    expect(client.calls).toHaveLength(1);
    expect(client.calls[0].extra).toEqual({
      http_method: "POST",
      http_path: "/x/:id",
    });
  });

  it("reports nothing on the success path", async () => {
    const client = fakeClient();
    const interceptor = new CallistoInterceptor(client);
    const ctx = httpContext({ method: "GET", originalUrl: "/ok" });
    const callHandler = { handle: () => of("ok") };

    const result = await lastValueFrom(interceptor.intercept(ctx, callHandler as any));

    expect(result).toBe("ok");
    expect(client.calls).toHaveLength(0);
  });

  it("respects shouldReport returning false but still propagates the error", async () => {
    const client = fakeClient();
    const interceptor = new CallistoInterceptor(client, { shouldReport: () => false });
    const err = new Error("x");
    const ctx = httpContext({ method: "GET", originalUrl: "/" });
    const callHandler = { handle: () => throwError(() => err) };

    await expect(lastValueFrom(interceptor.intercept(ctx, callHandler as any))).rejects.toBe(err);
    expect(client.calls).toHaveLength(0);
  });

  it("does not throw when the context has no HTTP request", async () => {
    const client = fakeClient();
    const interceptor = new CallistoInterceptor(client);
    const err = new Error("rpc");
    const ctx = { switchToHttp: () => ({ getRequest: () => undefined }) } as any;
    const callHandler = { handle: () => throwError(() => err) };

    await expect(lastValueFrom(interceptor.intercept(ctx, callHandler as any))).rejects.toBe(err);
    expect(client.calls).toHaveLength(1);
    expect((client.calls[0].extra as any).http_path).toBe("");
  });
});
