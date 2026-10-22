import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CallistoClient } from "../src/client.js";
import { isBrowserRuntime, resolveConfig } from "../src/config.js";
import { ConfigurationError } from "../src/errors.js";
import { CallistoLogger, type LogsRequest, type LogsResponse } from "../src/logger.js";
import {
  MAX_ATTRIBUTE_VALUE_CHARS, MAX_NESTED_ELEMENTS, MAX_RECORD_VALUES, buildLogRecord,
} from "../src/otlp.js";
import { ErrorReporter, type Sender, type StackFrame } from "../src/reporter.js";
import { FILTERED, isSensitiveName, maskSecrets } from "../src/scrub.js";
import { mockFetch } from "./helpers.js";

// The reporter reads source files through a global CommonJS `require`, which
// vitest's ESM runtime does not define. Provide one so the real path runs.
(globalThis as { require?: NodeRequire }).require ??= createRequire(import.meta.url);

const PK = "pk_00112233445566778899aabbccddeeff";
const SK = "sk_000102030405060708090a0b0c0d0e0f1011121314151617";
const ENDPOINT = "https://logs.test/v1/logs";
const DSN = "https://app.callistosignal.com/ingest/abc-123?key=deadbeef";

const ENV_KEYS = [
  "CALLISTO_LOGS_ACCESS_KEY",
  "CALLISTO_LOGS_SECRET",
  "CALLISTO_LOGS_ENDPOINT",
  "CALLISTO_SEND_SOURCE_CONTEXT",
  "CALLISTO_APP_ERROR_DSN",
];
let savedEnv: Record<string, string | undefined> = {};
const live: Array<{ close(): Promise<void> }> = [];

beforeEach(() => {
  savedEnv = {};
  for (const k of ENV_KEYS) {
    savedEnv[k] = process.env[k];
    delete process.env[k];
  }
});

afterEach(async () => {
  vi.restoreAllMocks();
  await Promise.all(live.splice(0).map((l) => l.close()));
  for (const k of ENV_KEYS) {
    if (savedEnv[k] === undefined) delete process.env[k];
    else process.env[k] = savedEnv[k];
  }
});

type Globals = { window?: unknown; document?: unknown; process?: unknown };

/**
 * Run `fn` synchronously with browser-like globals: `window` and `document`
 * defined and no `process` (so no Node runtime is detected).
 */
function inBrowser<T>(fn: () => T, { keepProcess = false } = {}): T {
  const g = globalThis as Globals;
  const had = { window: "window" in g, document: "document" in g };
  const saved = { window: g.window, document: g.document, process: g.process };
  g.window = {};
  g.document = {};
  if (!keepProcess) g.process = undefined;
  try {
    return fn();
  } finally {
    g.process = saved.process;
    if (had.window) g.window = saved.window;
    else delete g.window;
    if (had.document) g.document = saved.document;
    else delete g.document;
  }
}

/** Run `fn` synchronously with no `process` global (browsers, workers, Deno). */
function withoutProcess<T>(fn: () => T): T {
  const g = globalThis as Globals;
  const saved = g.process;
  g.process = undefined;
  try {
    return fn();
  } finally {
    g.process = saved;
  }
}

function okSender() {
  const requests: LogsRequest[] = [];
  const sender = vi.fn(async (req: LogsRequest): Promise<LogsResponse> => {
    requests.push(req);
    return { status: 200 };
  });
  return { sender, requests };
}

function warnings(spy: ReturnType<typeof vi.spyOn>): string[] {
  return spy.mock.calls.map((call: unknown[]) => call.map(String).join(" "));
}

// --- SDK-01: browser guard ------------------------------------------------

describe("SDK-01 browser guard", () => {
  it("detects a browser only with window + document and no Node process", () => {
    expect(isBrowserRuntime()).toBe(false);
    expect(inBrowser(() => isBrowserRuntime())).toBe(true);
    // jsdom / SSR test runners: window + document under Node are not a browser.
    expect(inBrowser(() => isBrowserRuntime(), { keepProcess: true })).toBe(false);
  });

  it("the standalone logger refuses a Logs secret in a browser", () => {
    const err = inBrowser(() => {
      try {
        new CallistoLogger({ logsAccessKey: PK, logsSecret: SK, logsEndpoint: ENDPOINT });
        return undefined;
      } catch (e) {
        return e;
      }
    });
    expect(err).toBeInstanceOf(ConfigurationError);
    expect((err as Error).message).toBe("Callisto Logs secrets must not be used in a browser");
    expect((err as Error).message).not.toContain(SK);
  });

  it("refuses the secret even when it is malformed (any configured secret)", () => {
    expect(() =>
      inBrowser(() => new CallistoLogger({ logsAccessKey: PK, logsSecret: "sk_short" })),
    ).toThrow("Callisto Logs secrets must not be used in a browser");
  });

  it("CallistoClient disables logs in a browser with one warning, without the secret", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const { sender } = okSender();
    const client = inBrowser(
      () =>
        new CallistoClient(
          { logsAccessKey: PK, logsSecret: SK, logsEndpoint: ENDPOINT },
          undefined,
          { logsSender: sender },
        ),
    );
    live.push(client);
    expect(client.logs.isEnabled).toBe(false);
    client.logs.info("dropped");
    expect(warn).toHaveBeenCalledTimes(1);
    const text = warnings(warn)[0];
    expect(text).toContain("must not be used in a browser");
    expect(text).not.toContain(SK);
    expect(text).not.toContain(PK);
    expect(sender).not.toHaveBeenCalled();
  });

  it("a browser client without a Logs secret builds silently", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const client = inBrowser(() => new CallistoClient({ clientId: "cid", apiKey: "k" }));
    live.push(client);
    expect(client.logs.isEnabled).toBe(false);
    expect(warn).not.toHaveBeenCalled();
  });

  it("package.json declares sideEffects: false", async () => {
    const pkg = await import("../package.json", { with: { type: "json" } });
    expect((pkg.default as { sideEffects?: unknown }).sideEffects).toBe(false);
  });
});

// --- SDK-02: source context masking --------------------------------------

describe("SDK-02 masker", () => {
  it.each([
    ["sk_ token", `const s = "${SK}";`, SK],
    ["pk_ token", `call(${"pk_" + "ab".repeat(16)})`, "pk_" + "ab".repeat(16)],
    ["AWS access key id", "aws(AKIAIOSFODNN7EXAMPLE)", "AKIAIOSFODNN7EXAMPLE"],
    ["Bearer token", `fetch(u, { headers: { x: "Bearer abc.def.ghi" } })`, "abc.def.ghi"],
    ["URL password", `connect("postgres://app:s3cr3tPass@db:5432/x")`, "s3cr3tPass"],
    ["password assignment", `const password = "hunter2";`, "hunter2"],
    ["passwd bare", "passwd=hunter2 next", "hunter2"],
    ["api_key in an object", `{ api_key: 'abc123' }`, "abc123"],
    ["quoted name (JSON)", `{"access_key": "abc123"}`, "abc123"],
    ["PHP arrow", `'private_key' => 'abc123',`, "abc123"],
    ["camelCase apiKey", `apiKey: "abc123",`, "abc123"],
    ["UPPER_CASE KEY", "const STRIPE_KEY = 'abc123';", "abc123"],
    ["bare key", "key=abc123", "abc123"],
    ["dashed x-api-key", `"x-api-key": "abc123"`, "abc123"],
    ["token", "this.accessToken = tok_live_1", "tok_live_1"],
    ["secret", "client_secret: 'abc123'", "abc123"],
    ["credential", "credentials = `abc123`", "abc123"],
    ["signature", "sig.signature = 'abc123'", "abc123"],
    ["authorization", `authorization: "Basic dXNlcjpwYXNz"`, "dXNlcjpwYXNz"],
    ["cookie", "cookie = 'sid=abc123'", "sid=abc123"],
    ["session", "sessionId = 'abc123'", "abc123"],
    ["bearer name", "bearer = 'abc123'", "abc123"],
    ["comparison", `if (token === "abc123") {`, "abc123"],
    ["40+ char quoted token", `x("${"A".repeat(30)}+/=_-${"z".repeat(10)}")`, "A".repeat(30)],
  ])("masks %s", (_name, line, secret) => {
    const masked = maskSecrets(line);
    expect(masked).not.toContain(secret);
    expect(masked).toContain(FILTERED);
  });

  it("keeps the word Bearer and the rest of the line", () => {
    expect(maskSecrets(`h.set("Authorization", "Bearer abc123");`)).toBe(
      `h.set("Authorization", "Bearer ${FILTERED}");`,
    );
    expect(maskSecrets("Authorization: Bearer abc123")).toBe(`Authorization: Bearer ${FILTERED}`);
  });

  it("keeps the URL user and host, masks only the password", () => {
    expect(maskSecrets("const u = 'redis://default:p4ss@cache:6379';")).toBe(
      `const u = 'redis://default:${FILTERED}@cache:6379';`,
    );
  });

  it("keeps quotes and the name around a masked value", () => {
    expect(maskSecrets(`const password = "hunter2";`)).toBe(`const password = "${FILTERED}";`);
    expect(maskSecrets("api_key=abc123;")).toBe(`api_key=${FILTERED};`);
  });

  it("masks a whole private-key line", () => {
    expect(maskSecrets("const pem = `-----BEGIN RSA PRIVATE KEY-----`;")).toBe(FILTERED);
    expect(maskSecrets("-----BEGIN PRIVATE KEY-----")).toBe(FILTERED);
  });

  it.each([
    "const keyboard = 'qwerty';",
    "monkey = 'banana';",
    "const keys = Object.keys(obj);",
    "const message = 'hello world';",
    "const url = 'https://example.com/path';",
    "throw new Error('order not found: ' + id);",
    "const sk = short_value;",
    `const id = "${"a".repeat(39)}";`,
    "-----BEGIN PUBLIC KEY-----",
    "return a + b;",
  ])("leaves a harmless line untouched: %s", (line) => {
    expect(maskSecrets(line)).toBe(line);
  });

  it("recognises sensitive names", () => {
    for (const name of ["key", "api_key", "API_KEY", "apiKey", "access-key", "private_key",
      "x-api-key", "secretKey", "authToken", "password", "passwd", "Authorization",
      "set-cookie", "session_id", "signature", "credentials", "bearer"]) {
      expect(isSensitiveName(name), name).toBe(true);
    }
    for (const name of ["keyboard", "monkey", "keys", "keystone", "turkey", "name", "url"]) {
      expect(isSensitiveName(name), name).toBe(false);
    }
  });

  it("is linear on a long hostile line", () => {
    const hostile = 'x="'.repeat(50_000) + "a".repeat(100_000);
    const start = Date.now();
    maskSecrets(hostile);
    expect(Date.now() - start).toBeLessThan(2_000);
  });
});

describe("SDK-02 reporter source context", () => {
  const dir = mkdtempSync(join(tmpdir(), "callisto-sdk-src-"));
  const file = join(dir, "app.js");
  writeFileSync(
    file,
    [
      "const db = 'postgres://app:dbPassw0rd@db:5432/shop';", // 1
      `const STRIPE_KEY = "${SK}";`, // 2
      "-----BEGIN PRIVATE KEY-----", // 3
      "function charge(order) {", // 4
      `  const headers = { authorization: "Bearer tok_abcdef" };`, // 5
      "  throw new Error('declined');", // 6 (the error line)
      `  const password = "hunter2";`, // 7
      "}", // 8
    ].join("\n"),
  );
  afterAll(() => rmSync(dir, { recursive: true, force: true }));

  function fixtureError(): Error {
    const err = new Error("declined");
    err.stack = `Error: declined\n    at charge (${file}:6:9)`;
    return err;
  }

  async function capture(opts: { sendSourceContext?: boolean } = {}): Promise<StackFrame> {
    const sent: Array<Record<string, unknown>> = [];
    const sender: Sender = async (_url, payload) => {
      sent.push(payload as Record<string, unknown>);
    };
    const r = new ErrorReporter({ dsn: DSN, sender, ...opts });
    r.captureException(fixtureError());
    await r.flush();
    return (sent[0].stacktrace as StackFrame[])[0];
  }

  it("masks every source line it sends", async () => {
    const frame = await capture();
    expect(frame.context_line).toBe("  throw new Error('declined');");
    expect(frame.pre_context).toHaveLength(5);
    expect(frame.post_context).toEqual([`  const password = "${FILTERED}";`, "}"]);
    const sent = JSON.stringify(frame);
    for (const secret of ["dbPassw0rd", SK, "tok_abcdef", "hunter2", "BEGIN PRIVATE KEY"]) {
      expect(sent).not.toContain(secret);
    }
    expect(frame.pre_context).toContain("function charge(order) {");
  });

  it("sendSourceContext: false sends frames without source", async () => {
    const frame = await capture({ sendSourceContext: false });
    expect(frame).toEqual({ function: "charge", file, line: 6 });
  });

  it("CALLISTO_SEND_SOURCE_CONTEXT=false turns it off; the option wins over env", async () => {
    process.env.CALLISTO_SEND_SOURCE_CONTEXT = "false";
    expect(await capture()).not.toHaveProperty("context_line");
    expect(await capture({ sendSourceContext: true })).toHaveProperty("context_line");
    process.env.CALLISTO_SEND_SOURCE_CONTEXT = "0";
    expect(await capture()).not.toHaveProperty("context_line");
  });

  it("CallistoClient passes sendSourceContext to its reporter", async () => {
    const sent: Array<Record<string, unknown>> = [];
    const client = new CallistoClient(
      { errorDsn: DSN, sendSourceContext: false },
      undefined,
      { errorSender: async (_u, p) => void sent.push(p as Record<string, unknown>) },
    );
    client.captureException(fixtureError());
    await client.close();
    expect((sent[0].stacktrace as StackFrame[])[0]).not.toHaveProperty("context_line");
    expect(resolveConfig({}).sendSourceContext).toBe(true);
  });
});

// --- SDK-04: https only ---------------------------------------------------

describe("SDK-04 https endpoints", () => {
  it.each([
    "http://logs.callistosignal.com/v1/logs",
    "http://10.0.0.5:4318/v1/logs",
    "http://localhost.evil.com/v1/logs",
    "http://mylocalhost/v1/logs",
    "ftp://logs.test/v1/logs",
  ])("the standalone logger rejects %s", (endpoint) => {
    expect(
      () => new CallistoLogger({ logsAccessKey: PK, logsSecret: SK, logsEndpoint: endpoint }),
    ).toThrow(ConfigurationError);
  });

  it.each([
    "https://logs.example.com/v1/logs",
    "http://localhost:4318/v1/logs",
    "http://127.0.0.1:4318/v1/logs",
    "http://[::1]:4318/v1/logs",
    "http://collector.localhost/v1/logs",
  ])("the standalone logger accepts %s", async (endpoint) => {
    const { sender, requests } = okSender();
    const logger = new CallistoLogger({
      logsAccessKey: PK, logsSecret: SK, logsEndpoint: endpoint, sender,
    });
    live.push(logger);
    logger.info("x");
    await logger.flush();
    expect(requests[0].url).toBe(endpoint);
  });

  it("an http logs endpoint on the client disables logs with one warning", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const client = new CallistoClient({
      logsAccessKey: PK, logsSecret: SK, logsEndpoint: "http://logs.example.com/v1/logs",
    });
    live.push(client);
    expect(client.logs.isEnabled).toBe(false);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warnings(warn)[0]).toContain("https");
    expect(warnings(warn)[0]).not.toContain("logs.example.com");
  });

  it("an http DSN disables error reporting with one warning that hides the DSN", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const sender = vi.fn(async () => undefined);
    const httpDsn = "http://app.callistosignal.com/ingest/abc?key=deadbeef";
    const r = new ErrorReporter({ dsn: httpDsn, sender });
    expect(r.isEnabled).toBe(false);
    r.captureException(new Error("boom"));
    await r.flush();
    expect(sender).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warnings(warn)[0]).toContain("https");
    expect(warnings(warn)[0]).not.toContain("deadbeef");
    expect(warnings(warn)[0]).not.toContain("app.callistosignal.com");
  });

  it("accepts an http DSN on localhost", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    expect(new ErrorReporter({ dsn: "http://localhost:8080/ingest/x?key=y" }).isEnabled).toBe(true);
    expect(new ErrorReporter({ dsn: "http://127.0.0.1/ingest/x" }).isEnabled).toBe(true);
    expect(new ErrorReporter({ dsn: DSN }).isEnabled).toBe(true);
    expect(warn).not.toHaveBeenCalled();
  });

  it("the client with an http DSN constructs, warns once and still sends messages", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const { fetch, captured } = mockFetch(200, { available_credit: 1 });
    const client = new CallistoClient(
      {
        clientId: "cid", apiKey: "k", baseUrl: "https://api.test/v1",
        errorDsn: "http://ingest.example.com/apps/x?key=y",
      },
      fetch,
    );
    expect(client.errorReporter.isEnabled).toBe(false);
    expect(warn).toHaveBeenCalledTimes(1);
    await client.balance.get();
    expect(captured).toHaveLength(1);
  });
});

// --- SDK-05 equivalent: the client never throws on the Logs config --------

describe("SDK-05 client never throws on a bad Logs configuration", () => {
  const BAD_SECRET = "sk_" + "Z".repeat(48);
  it.each([
    ["half pair (key only)", { CALLISTO_LOGS_ACCESS_KEY: PK }],
    ["half pair (secret only)", { CALLISTO_LOGS_SECRET: SK }],
    ["malformed secret", { CALLISTO_LOGS_ACCESS_KEY: PK, CALLISTO_LOGS_SECRET: BAD_SECRET }],
    ["malformed key", { CALLISTO_LOGS_ACCESS_KEY: "pk_nope", CALLISTO_LOGS_SECRET: SK }],
    ["bad endpoint", {
      CALLISTO_LOGS_ACCESS_KEY: PK, CALLISTO_LOGS_SECRET: SK, CALLISTO_LOGS_ENDPOINT: "not a url",
    }],
    ["http endpoint", {
      CALLISTO_LOGS_ACCESS_KEY: PK, CALLISTO_LOGS_SECRET: SK,
      CALLISTO_LOGS_ENDPOINT: "http://logs.example.com/v1/logs",
    }],
  ])("%s in CALLISTO_LOGS_*: no throw, disabled, one warning without the key or secret", async (_n, vars) => {
    Object.assign(process.env, vars);
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const { fetch, captured } = mockFetch(200, { available_credit: 1 });
    let client: CallistoClient | undefined;
    expect(() => {
      client = new CallistoClient(
        { clientId: "cid", apiKey: "k", baseUrl: "https://api.test/v1" },
        fetch,
      );
    }).not.toThrow();
    live.push(client!);
    expect(client!.logs.isEnabled).toBe(false);
    expect(warn).toHaveBeenCalledTimes(1);
    const text = warnings(warn)[0];
    expect(text).toMatch(/^Callisto: log shipping is disabled: /);
    for (const value of Object.values(vars)) expect(text).not.toContain(value);
    expect(text).not.toContain(BAD_SECRET);

    // Messaging is unaffected, and the disabled logger never sends.
    client!.logs.error("dropped");
    await client!.balance.get();
    await client!.close();
    expect(captured).toHaveLength(1);
    expect(captured[0].url.startsWith("https://api.test/v1/")).toBe(true);
  });

  it("the standalone logger still throws on the same configuration", () => {
    process.env.CALLISTO_LOGS_ACCESS_KEY = PK;
    process.env.CALLISTO_LOGS_SECRET = BAD_SECRET;
    expect(() => new CallistoLogger()).toThrow(ConfigurationError);
  });

  it("a valid Logs configuration on the client emits no warning", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const client = new CallistoClient({ logsAccessKey: PK, logsSecret: SK, logsEndpoint: ENDPOINT });
    live.push(client);
    expect(client.logs.isEnabled).toBe(true);
    expect(warn).not.toHaveBeenCalled();
  });
});

// --- SDK-07: no ReferenceError without process -----------------------------

describe("SDK-07 no process global", () => {
  it("new CallistoClient() does not throw when process is undefined", () => {
    expect(() => withoutProcess(() => new CallistoClient())).not.toThrow();
    const client = withoutProcess(
      () => new CallistoClient({ clientId: "cid", apiKey: "k", errorDsn: DSN }),
    );
    live.push(client);
    expect(client.errorReporter.isEnabled).toBe(true);
  });

  it("resolveConfig falls back to defaults without process", () => {
    const cfg = withoutProcess(() => resolveConfig());
    expect(cfg).toEqual({
      clientId: undefined,
      apiKey: undefined,
      baseUrl: "https://api.callistosignal.com/v1",
      timeoutMs: 30000,
      errorDsn: undefined,
      captureUnhandled: false,
      environment: undefined,
      sendSourceContext: true,
    });
  });

  it("the standalone logger builds disabled without process", () => {
    const logger = withoutProcess(() => new CallistoLogger());
    expect(logger.isEnabled).toBe(false);
  });
});

// --- SDK-08: nested attribute caps ----------------------------------------

describe("SDK-08 nested attribute caps", () => {
  const T = 1_760_745_600_000;
  type Kv = { key: string; value: Record<string, any> };
  function attr(attributes: Record<string, unknown>, key: string): Record<string, any> {
    const rec = buildLogRecord("info", "m", attributes, T);
    return (rec.attributes as Kv[]).find((a) => a.key === key)!.value;
  }

  it("keeps at most 128 elements per nested list, set and map", () => {
    const big = Array.from({ length: 1_000 }, (_, i) => i);
    const obj = Object.fromEntries(big.map((i) => [`k${i}`, i]));
    const map = new Map(big.map((i) => [`m${i}`, i]));
    expect(attr({ list: big }, "list").arrayValue.values).toHaveLength(MAX_NESTED_ELEMENTS);
    expect(attr({ set: new Set(big) }, "set").arrayValue.values).toHaveLength(128);
    expect(attr({ obj }, "obj").kvlistValue.values).toHaveLength(128);
    expect(attr({ map }, "map").kvlistValue.values).toHaveLength(128);
    // The first ones are kept, in order.
    expect(attr({ list: big }, "list").arrayValue.values[127]).toEqual({ intValue: "127" });
    expect(attr({ obj }, "obj").kvlistValue.values[0]).toEqual({ key: "k0", value: { intValue: "0" } });
  });

  it("caps every nested level and truncates nested strings", () => {
    const inner = Array.from({ length: 500 }, () => "s".repeat(10_000));
    const value = attr({ outer: { a: [inner] } }, "outer");
    const list = value.kvlistValue.values[0].value.arrayValue.values[0].arrayValue.values;
    expect(list).toHaveLength(128);
    expect(list[0].stringValue).toHaveLength(MAX_ATTRIBUTE_VALUE_CHARS);
    expect(list[0].stringValue.endsWith("…")).toBe(true);
  });

  it("bounds a huge container nested past the depth limit", () => {
    const huge = Array.from({ length: 200_000 }, (_, i) => ({ i, s: "x".repeat(50) }));
    let deep: unknown = huge;
    for (let i = 0; i < 6; i++) deep = [deep];
    const start = Date.now();
    let v = attr({ deep }, "deep");
    expect(Date.now() - start).toBeLessThan(1_000);
    for (let i = 0; i < 5; i++) v = v.arrayValue.values[0];
    expect(v.stringValue.length).toBeLessThanOrEqual(MAX_ATTRIBUTE_VALUE_CHARS);
    expect(v.stringValue.startsWith('[[{"i":0,"s":"xxx')).toBe(true);
  });

  it("stringifies past the depth limit like JSON, with caps and circular markers", () => {
    let deep: unknown = { a: 1, u: undefined, f: () => 1, n: null, b: 2n, d: new Date(T),
      m: new Map([["k", "v"]]), list: [undefined, 1] };
    for (let i = 0; i < 5; i++) deep = [deep];
    let v = attr({ deep }, "deep");
    for (let i = 0; i < 5; i++) v = v.arrayValue.values[0];
    expect(v.stringValue).toBe(
      '{"a":1,"n":null,"b":"2","d":"2025-10-18T00:00:00.000Z","m":{"k":"v"},"list":[null,1]}',
    );

    const circular: Record<string, unknown> = { name: "c" };
    circular.self = circular;
    let wrapped: unknown = circular;
    for (let i = 0; i < 5; i++) wrapped = [wrapped];
    let c = attr({ wrapped }, "wrapped");
    for (let i = 0; i < 5; i++) c = c.arrayValue.values[0];
    expect(c.stringValue).toBe('{"name":"c","self":"[Circular]"}');
  });

  /** Every encoded AnyValue in a record: scalars, containers and `{}` list elements. */
  function countValues(values: Array<Record<string, any>>): number {
    let n = 0;
    for (const v of values) {
      n++;
      if (v.arrayValue) n += countValues(v.arrayValue.values);
      if (v.kvlistValue) n += countValues(v.kvlistValue.values.map((kv: Kv) => kv.value));
    }
    return n;
  }
  function recordValues(attributes: Record<string, unknown>): { count: number; keys: string[] } {
    const rec = buildLogRecord("info", "m", attributes, T);
    const attrs = (rec.attributes ?? []) as Kv[];
    return { count: countValues(attrs.map((a) => a.value)), keys: attrs.map((a) => a.key) };
  }

  it("spends at most 2048 values on a tiny object reusing one 128-element list at 5 levels", () => {
    // 5 levels of the same references: ~640 references in memory, 128^5 (3.4e10)
    // leaves if every visit were encoded.
    let shared: unknown = Array.from({ length: 128 }, (_, i) => i);
    for (let level = 1; level < 5; level++) shared = new Array(128).fill(shared);
    const start = performance.now();
    const { count, keys } = recordValues({ shared, after: "dropped" });
    const elapsed = performance.now() - start;
    expect(elapsed).toBeLessThan(50);
    expect(count).toBeLessThanOrEqual(MAX_RECORD_VALUES);
    expect(count).toBe(2_048);
    expect(keys).toEqual(["shared"]); // the budget is spent: later attributes are dropped
  });

  it("shares the budget across attributes: containers are cut, later attributes dropped", () => {
    const list = Array.from({ length: 128 }, (_, i) => i);
    const attributes: Record<string, unknown> = {};
    for (let i = 0; i < 20; i++) attributes[`a${i}`] = list; // 20 × 129 values > 2048
    const { count, keys } = recordValues(attributes);
    expect(count).toBe(MAX_RECORD_VALUES);
    // 15 full lists (1935 values), then a 16th list cut after 112 elements.
    expect(keys).toEqual(Array.from({ length: 16 }, (_, i) => `a${i}`));
    const rec = buildLogRecord("info", "m", attributes, T);
    const last = (rec.attributes as Kv[])[15].value.arrayValue.values;
    expect(last).toHaveLength(2_048 - 15 * 129 - 1);
  });

  it("counts null list elements, and a small record is unaffected", () => {
    const { count } = recordValues({ nulls: new Array(5_000).fill(null) });
    expect(count).toBe(1 + 128);
    const small = recordValues({ a: 1, b: [1, 2, { c: "d" }] });
    expect(small.count).toBe(6);
  });

  it("a whole record stays bounded through the logger", async () => {
    const { sender, requests } = okSender();
    const logger = new CallistoLogger({
      logsAccessKey: PK, logsSecret: SK, logsEndpoint: ENDPOINT, sender,
    });
    live.push(logger);
    const big = Array.from({ length: 100_000 }, (_, i) => `v${i}`);
    logger.info("m", { a: big, b: { c: big } });
    await logger.flush();
    expect(requests[0].body.length).toBeLessThan(20_000);
  });
});
