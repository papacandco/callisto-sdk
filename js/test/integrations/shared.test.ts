import { describe, it, expect } from "vitest";
import {
  reportRequestError,
  type ErrorCapturer,
} from "../../src/integrations/shared.js";

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

describe("reportRequestError", () => {
  it("reports with http_method/http_path and default level 'error'", () => {
    const client = fakeClient();
    const err = new Error("boom");
    reportRequestError(client, err, { method: "GET", path: "/users/:id" });

    expect(client.calls).toHaveLength(1);
    expect(client.calls[0].err).toBe(err);
    expect(client.calls[0].level).toBe("error");
    expect(client.calls[0].extra).toEqual({
      http_method: "GET",
      http_path: "/users/:id",
    });
  });

  it("skips reporting when shouldReport returns false", () => {
    const client = fakeClient();
    reportRequestError(client, new Error("x"), { method: "GET", path: "/" }, {
      shouldReport: () => false,
    });
    expect(client.calls).toHaveLength(0);
  });

  it("resolves a string level and a function level", () => {
    const client = fakeClient();
    reportRequestError(client, new Error("a"), { method: "GET", path: "/" }, {
      level: "warning",
    });
    reportRequestError(client, new Error("b"), { method: "POST", path: "/" }, {
      level: () => "fatal",
    });
    expect(client.calls[0].level).toBe("warning");
    expect(client.calls[1].level).toBe("fatal");
  });

  it("never throws when captureException throws", () => {
    const client: ErrorCapturer = {
      captureException() {
        throw new Error("reporter exploded");
      },
    };
    expect(() =>
      reportRequestError(client, new Error("x"), { method: "GET", path: "/" }),
    ).not.toThrow();
  });
});
