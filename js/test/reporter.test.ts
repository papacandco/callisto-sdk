import { describe, it, expect, vi } from "vitest";
import { CallistoClient } from "../src/client.js";
import { ErrorReporter, type Sender } from "../src/reporter.js";
import { AuthenticationError, NotFoundError } from "../src/errors.js";
import { mockFetch } from "./helpers.js";

const DSN = "https://app.callistosignal.com/ingest/abc-123?key=deadbeef";

interface Captured {
  url: string;
  payload: Record<string, unknown>;
}

/** A fake sender that records every (url, payload). */
function fakeSender(): { sender: Sender; sent: Captured[] } {
  const sent: Captured[] = [];
  const sender: Sender = async (url, payload) => {
    sent.push({ url, payload: payload as Record<string, unknown> });
  };
  return { sender, sent };
}

const opts = {
  clientId: "cid",
  apiKey: "k",
  baseUrl: "https://api.test/v1",
  errorDsn: DSN,
};

describe("ErrorReporter", () => {
  it("posts a captured exception to the DSN URL with message/type/level", async () => {
    const { sender, sent } = fakeSender();
    const r = new ErrorReporter({ dsn: DSN, sender });
    r.captureException(new AuthenticationError("bad key", 401, { error: "x" }));
    await r.flush();

    expect(sent).toHaveLength(1);
    expect(sent[0].url).toBe(DSN);
    expect(sent[0].payload.message).toBe("bad key");
    expect(sent[0].payload.type).toBe("AuthenticationError");
    expect(sent[0].payload.level).toBe("error");
  });

  it("includes context.sdk metadata", async () => {
    const { sender, sent } = fakeSender();
    const r = new ErrorReporter({ dsn: DSN, sender });
    r.captureException(new Error("boom"));
    await r.flush();

    const ctx = sent[0].payload.context as Record<string, unknown>;
    expect(ctx.sdk).toMatchObject({
      name: "callisto-sdk",
      language: "javascript",
    });
    expect((ctx.sdk as Record<string, unknown>).version).toBeTypeOf("string");
  });

  it("includes status_code and request{method,path} for transport errors", async () => {
    const { sender, sent } = fakeSender();
    const { fetch } = mockFetch(404, { message: "Message not found" });
    const client = new CallistoClient(opts, fetch, { errorSender: sender });

    await expect(client.otp.getStatus("missing")).rejects.toBeInstanceOf(
      NotFoundError,
    );
    await client.errorReporter.flush();

    expect(sent).toHaveLength(1);
    const p = sent[0].payload;
    expect(p.type).toBe("NotFoundError");
    expect((p.context as Record<string, unknown>).status_code).toBe(404);
    expect(p.request).toEqual({ method: "GET", path: "/otps/missing" });
    expect(p.culprit).toBe("GET /otps/missing");
  });

  it("includes environment when configured", async () => {
    const { sender, sent } = fakeSender();
    const r = new ErrorReporter({ dsn: DSN, environment: "staging", sender });
    r.captureException(new Error("x"));
    await r.flush();
    expect((sent[0].payload.context as Record<string, unknown>).environment).toBe(
      "staging",
    );
  });

  it("never leaks credentials or the outgoing request body", async () => {
    const { sender, sent } = fakeSender();
    const { fetch } = mockFetch(401, { message: "invalid" });
    const client = new CallistoClient(
      {
        clientId: "MY_CLIENT_ID_SECRET",
        apiKey: "MY_API_KEY_SECRET",
        baseUrl: "https://api.test/v1",
        errorDsn: DSN,
      },
      fetch,
      { errorSender: sender },
    );

    await expect(
      client.sms.send({ to: "+2250700000000", message: "SECRET_BODY_CONTENT" }),
    ).rejects.toBeTruthy();
    await client.errorReporter.flush();

    const serialized = JSON.stringify(sent[0].payload);
    expect(serialized).not.toContain("MY_CLIENT_ID_SECRET");
    expect(serialized).not.toContain("MY_API_KEY_SECRET");
    expect(serialized).not.toContain("Basic");
    expect(serialized.toLowerCase()).not.toContain("authorization");
    expect(serialized).not.toContain("SECRET_BODY_CONTENT");
    expect(serialized).not.toContain("+2250700000000");
  });

  it("swallows sender failures — captureException never throws", async () => {
    const throwing: Sender = vi.fn(async () => {
      throw new Error("network down");
    });
    const r = new ErrorReporter({ dsn: DSN, sender: throwing });
    expect(() => r.captureException(new Error("boom"))).not.toThrow();
    await expect(r.flush()).resolves.toBeUndefined();
    expect(throwing).toHaveBeenCalledTimes(1);
  });

  it("is a no-op when no DSN is set", async () => {
    const { sender, sent } = fakeSender();
    const r = new ErrorReporter({ sender });
    expect(r.isEnabled).toBe(false);
    r.captureException(new Error("x"));
    r.captureMessage("y");
    await r.flush();
    expect(sent).toHaveLength(0);
  });

  it("is a no-op when the DSN is not a well-formed URL", async () => {
    const { sender, sent } = fakeSender();
    const r = new ErrorReporter({ dsn: "not a url", sender });
    expect(r.isEnabled).toBe(false);
    r.captureException(new Error("x"));
    await r.flush();
    expect(sent).toHaveLength(0);
  });

  it("captureMessage builds an info-level message event", async () => {
    const { sender, sent } = fakeSender();
    const r = new ErrorReporter({ dsn: DSN, sender });
    r.captureMessage("hello world", "warning", { feature: "otp" });
    await r.flush();
    expect(sent[0].payload.message).toBe("hello world");
    expect(sent[0].payload.level).toBe("warning");
    expect((sent[0].payload.context as Record<string, unknown>).feature).toBe(
      "otp",
    );
  });

  it("constrains unknown levels to error", async () => {
    const { sender, sent } = fakeSender();
    const r = new ErrorReporter({ dsn: DSN, sender });
    r.captureException(new Error("x"), "totally-bogus");
    await r.flush();
    expect(sent[0].payload.level).toBe("error");
  });

  it("attaches the setUser context to events", async () => {
    const { sender, sent } = fakeSender();
    const r = new ErrorReporter({ dsn: DSN, sender });
    r.setUser({ id: "u1", email: "a@b.com" });
    r.captureException(new Error("x"));
    await r.flush();
    expect(sent[0].payload.user).toEqual({ id: "u1", email: "a@b.com" });
  });

  it("parses a stacktrace into frames", async () => {
    const { sender, sent } = fakeSender();
    const r = new ErrorReporter({ dsn: DSN, sender });
    const err = new Error("boom");
    err.stack =
      "Error: boom\n    at doThing (/app/src/x.ts:10:5)\n    at /app/index.ts:3:1";
    r.captureException(err);
    await r.flush();
    const frames = sent[0].payload.stacktrace as Array<Record<string, unknown>>;
    expect(frames[0]).toEqual({
      function: "doThing",
      file: "/app/src/x.ts",
      line: 10,
    });
    expect(frames[1]).toEqual({ file: "/app/index.ts", line: 3 });
    expect(sent[0].payload.culprit).toBe("doThing (/app/src/x.ts:10)");
  });

  it("default sender treats non-202 as a swallowed failure", async () => {
    const { fetch } = mockFetch(401, { error: "invalid_key" });
    const r = new ErrorReporter({ dsn: DSN }, fetch);
    expect(() => r.captureException(new Error("x"))).not.toThrow();
    await expect(r.flush()).resolves.toBeUndefined();
  });

  it("default sender POSTs JSON to the DSN", async () => {
    const { fetch, captured } = mockFetch(202, { ok: true });
    const r = new ErrorReporter({ dsn: DSN }, fetch);
    r.captureException(new Error("boom"));
    await r.flush();
    expect(captured).toHaveLength(1);
    expect(captured[0].url).toBe(DSN);
    expect(captured[0].method).toBe("POST");
    expect(captured[0].headers["content-type"]).toBe("application/json");
    expect((captured[0].body as Record<string, unknown>).message).toBe("boom");
  });
});

describe("CallistoClient error-reporting integration", () => {
  it("captures client-side validation errors and still propagates them", async () => {
    const { sender, sent } = fakeSender();
    const { fetch, captured } = mockFetch(200, {});
    const client = new CallistoClient(opts, fetch, { errorSender: sender });

    await expect(client.notify.send({ topic: "t" })).rejects.toThrow(
      /event block/,
    );
    await client.errorReporter.flush();

    expect(captured).toHaveLength(0); // never hit the network
    expect(sent).toHaveLength(1);
    expect(sent[0].payload.type).toBe("ValidationError");
    expect(sent[0].payload.request).toEqual({
      method: "POST",
      path: "/notify/send",
    });
  });

  it("exposes captureException / captureMessage / setUser", async () => {
    const { sender, sent } = fakeSender();
    const { fetch } = mockFetch(200, {});
    const client = new CallistoClient(opts, fetch, { errorSender: sender });
    client.setUser({ id: "u9" });
    client.captureMessage("manual ping", "info");
    client.captureException(new Error("manual boom"));
    await client.close();

    expect(sent).toHaveLength(2);
    expect(sent[0].payload.message).toBe("manual ping");
    expect(sent[1].payload.message).toBe("manual boom");
    expect(sent[1].payload.user).toEqual({ id: "u9" });
  });

  it("does nothing (no DSN) but errors still propagate", async () => {
    const { sender, sent } = fakeSender();
    const { fetch } = mockFetch(404, { message: "nope" });
    const client = new CallistoClient(
      { clientId: "cid", apiKey: "k", baseUrl: "https://api.test/v1" },
      fetch,
      { errorSender: sender },
    );
    expect(client.errorReporter.isEnabled).toBe(false);
    await expect(client.otp.getStatus("x")).rejects.toBeInstanceOf(NotFoundError);
    await client.close();
    expect(sent).toHaveLength(0);
  });

  it("installs and chains the unhandled handler when opted in", async () => {
    const before = process.listenerCount("uncaughtException");
    const { sender } = fakeSender();
    const client = new CallistoClient(
      { ...opts, captureUnhandled: true },
      undefined,
      { errorSender: sender },
    );
    expect(process.listenerCount("uncaughtException")).toBe(before + 1);
    expect(process.listenerCount("unhandledRejection")).toBeGreaterThan(0);
    // close() removes our listeners (does not clobber pre-existing ones).
    await client.close();
    expect(process.listenerCount("uncaughtException")).toBe(before);
  });
});
