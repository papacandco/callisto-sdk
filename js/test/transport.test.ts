import { describe, it, expect } from "vitest";
import { Transport } from "../src/http.js";
import { NotFoundError, RateLimitError } from "../src/errors.js";
import { mockFetch } from "./helpers.js";

const cfg = {
  clientId: "cid",
  apiKey: "secret",
  baseUrl: "https://api.test/v1",
  timeoutMs: 1000,
};

describe("Transport", () => {
  it("sends Basic auth + JSON headers and decodes the body", async () => {
    const { fetch, captured } = mockFetch(200, { ok: true });
    const t = new Transport(cfg, fetch);
    const out = await t.request("GET", "/sms/balance");
    expect(out).toEqual({ ok: true });
    expect(captured[0].url).toBe("https://api.test/v1/sms/balance");
    expect(captured[0].headers["authorization"]).toBe(
      "Basic " + Buffer.from("cid:secret").toString("base64"),
    );
    expect(captured[0].headers["accept"]).toBe("application/json");
  });

  it("serializes a JSON body and query params", async () => {
    const { fetch, captured } = mockFetch(200, {});
    const t = new Transport(cfg, fetch);
    await t.request("POST", "/sms/send", {
      body: { message: "hi" },
      query: { page: 2, skip: undefined },
    });
    expect(captured[0].method).toBe("POST");
    expect(captured[0].url).toBe("https://api.test/v1/sms/send?page=2");
    expect(captured[0].body).toEqual({ message: "hi" });
    expect(captured[0].headers["content-type"]).toBe("application/json");
  });

  it("maps error status to a typed error carrying the server message", async () => {
    const { fetch } = mockFetch(404, { message: "Message not found" });
    const t = new Transport(cfg, fetch);
    await expect(t.request("GET", "/sms/x")).rejects.toMatchObject({
      constructor: NotFoundError,
      statusCode: 404,
      message: "Message not found",
    });
  });

  it("surfaces Retry-After on a 429 RateLimitError", async () => {
    const { fetch } = mockFetch(429, { message: "Too many requests" }, {
      "retry-after": "30",
    });
    const t = new Transport(cfg, fetch);
    await expect(t.request("GET", "/sms/x")).rejects.toMatchObject({
      constructor: RateLimitError,
      statusCode: 429,
      retryAfter: 30,
    });
  });
});
