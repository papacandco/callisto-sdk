import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CallistoClient } from "../src/client.js";
import { ConfigurationError } from "../src/errors.js";
import {
  CallistoLogger,
  type CallistoLoggerOptions,
  type LogsRequest,
  type LogsResponse,
} from "../src/logger.js";
import { SDK_VERSION } from "../src/reporter.js";
import { mockFetch } from "./helpers.js";

// Shared cross-SDK test vector.
const PK = "pk_00112233445566778899aabbccddeeff";
const SK = "sk_000102030405060708090a0b0c0d0e0f1011121314151617";
const AUTH =
  "Basic cGtfMDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmY6c2tfMDAwMTAyMDMwNDA1MDYwNzA4MDkwYTBiMGMwZDBlMGYxMDExMTIxMzE0MTUxNjE3";
const ENDPOINT = "https://logs.test/v1/logs";
const FIXED_MS = 1_760_745_600_000;

type Reply = LogsResponse | Error | Promise<LogsResponse>;

/** A fake sender replaying `replies` in order (then 200), recording every request. */
function fakeSender(replies: Reply[] = []) {
  const requests: LogsRequest[] = [];
  const sender = vi.fn(async (req: LogsRequest): Promise<LogsResponse> => {
    requests.push(req);
    const next = replies.shift() ?? { status: 200 };
    if (next instanceof Error) throw next;
    return next;
  });
  const bodies = () => requests.map((r) => JSON.parse(r.body));
  const records = (i: number) =>
    bodies()[i].resourceLogs[0].scopeLogs[0].logRecords as Array<Record<string, unknown>>;
  return { sender, requests, bodies, records };
}

const live: CallistoLogger[] = [];
function makeLogger(opts: CallistoLoggerOptions = {}): CallistoLogger {
  const logger = new CallistoLogger({
    logsAccessKey: PK,
    logsSecret: SK,
    logsEndpoint: ENDPOINT,
    clock: () => FIXED_MS,
    ...opts,
  });
  live.push(logger);
  return logger;
}

const ENV_KEYS = [
  "CALLISTO_LOGS_ACCESS_KEY",
  "CALLISTO_LOGS_SECRET",
  "CALLISTO_LOGS_ENDPOINT",
  "CALLISTO_SERVICE_NAME",
  "OTEL_SERVICE_NAME",
  "CALLISTO_ENVIRONMENT",
];
let savedEnv: Record<string, string | undefined> = {};

beforeEach(() => {
  savedEnv = {};
  for (const k of ENV_KEYS) {
    savedEnv[k] = process.env[k];
    delete process.env[k];
  }
});

afterEach(async () => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  await Promise.all(live.splice(0).map((l) => l.close()));
  for (const k of ENV_KEYS) {
    if (savedEnv[k] === undefined) delete process.env[k];
    else process.env[k] = savedEnv[k];
  }
});

describe("CallistoLogger wire format", () => {
  it("produces the golden payload", async () => {
    const { sender, bodies } = fakeSender();
    const logger = makeLogger({ sender, serviceName: "checkout", environment: "production" });
    logger.info("order paid", {
      order_id: 42,
      paid: true,
      trace_id: "5B8EFFF798038103D269B633813FC60C",
      span_id: "EEE19B7EC3C1B174",
    });
    await logger.flush();

    expect(bodies()).toHaveLength(1);
    expect(bodies()[0]).toEqual({
      resourceLogs: [{
        resource: { attributes: [
          { key: "service.name", value: { stringValue: "checkout" } },
          { key: "deployment.environment.name", value: { stringValue: "production" } },
          { key: "telemetry.sdk.name", value: { stringValue: "callisto-sdk" } },
          { key: "telemetry.sdk.language", value: { stringValue: "nodejs" } },
          { key: "telemetry.sdk.version", value: { stringValue: SDK_VERSION } },
        ] },
        scopeLogs: [{
          scope: { name: "callisto-sdk", version: SDK_VERSION },
          logRecords: [{
            timeUnixNano: "1760745600000000000",
            observedTimeUnixNano: "1760745600000000000",
            severityNumber: 9,
            severityText: "INFO",
            body: { stringValue: "order paid" },
            attributes: [
              { key: "order_id", value: { intValue: "42" } },
              { key: "paid", value: { boolValue: true } },
            ],
            traceId: "5b8efff798038103d269b633813fc60c",
            spanId: "eee19b7ec3c1b174",
          }],
        }],
      }],
    });
  });

  it("uses version 1.2.0 matching package.json", async () => {
    const pkg = await import("../package.json", { with: { type: "json" } });
    expect(SDK_VERSION).toBe(pkg.default.version);
    expect(SDK_VERSION).toBe("1.2.0");
  });

  it("omits the environment, empty attributes and absent trace ids; defaults service.name", async () => {
    const { sender, bodies, records } = fakeSender();
    const logger = makeLogger({ sender });
    logger.info("plain");
    await logger.flush();
    const resource = bodies()[0].resourceLogs[0].resource.attributes;
    expect(resource.map((a: { key: string }) => a.key)).toEqual([
      "service.name", "telemetry.sdk.name", "telemetry.sdk.language", "telemetry.sdk.version",
    ]);
    expect(resource[0].value).toEqual({ stringValue: "unknown_service" });
    const rec = records(0)[0];
    expect(rec).not.toHaveProperty("attributes");
    expect(rec).not.toHaveProperty("traceId");
    expect(rec).not.toHaveProperty("spanId");
  });

  it("sends Basic auth with the shared vector, JSON content type and User-Agent to the endpoint", async () => {
    const { fetch, captured } = mockFetch(200, {});
    const logger = makeLogger({ fetch });
    logger.info("hello");
    await logger.flush();

    expect(captured).toHaveLength(1);
    expect(captured[0].url).toBe(ENDPOINT);
    expect(captured[0].method).toBe("POST");
    expect(captured[0].headers.authorization).toBe(AUTH);
    expect(captured[0].headers["content-type"]).toBe("application/json");
    expect(captured[0].headers["user-agent"]).toBe(`callisto-sdk-nodejs/${SDK_VERSION}`);
  });

  it("maps all five levels and log(level)", async () => {
    const { sender, records } = fakeSender();
    const logger = makeLogger({ sender });
    logger.debug("d");
    logger.info("i");
    logger.warn("w");
    logger.error("e");
    logger.fatal("f");
    logger.log("warn", "lw");
    logger.log("bogus", "fallback");
    await logger.flush();
    expect(records(0).map((r) => [r.severityNumber, r.severityText])).toEqual([
      [5, "DEBUG"], [9, "INFO"], [13, "WARN"], [17, "ERROR"], [21, "FATAL"],
      [13, "WARN"], [9, "INFO"],
    ]);
  });

  it("maps attribute values to OTLP AnyValue", async () => {
    const { sender, records } = fakeSender();
    const logger = makeLogger({ sender });
    let deep: unknown = "leaf";
    for (let i = 0; i < 7; i++) deep = [deep];
    logger.info(12345, {
      s: "x",
      t: true,
      i: 42,
      neg: -7,
      f: 1.5,
      nan: Number.NaN,
      inf: Infinity,
      ninf: -Infinity,
      big: 2n ** 62n,
      huge: 2n ** 64n,
      nil: null,
      undef: undefined,
      list: [1, "a", null, { k: false }],
      map: { a: 1, b: { c: "d" }, n: null },
      date: new Date(FIXED_MS),
      deep,
    });
    await logger.flush();
    const rec = records(0)[0];
    expect(rec.body).toEqual({ stringValue: "12345" });
    const attrs = Object.fromEntries(
      (rec.attributes as Array<{ key: string; value: unknown }>).map((a) => [a.key, a.value]),
    );
    expect(attrs).not.toHaveProperty("nil");
    expect(attrs).not.toHaveProperty("undef");
    expect(attrs.s).toEqual({ stringValue: "x" });
    expect(attrs.t).toEqual({ boolValue: true });
    expect(attrs.i).toEqual({ intValue: "42" });
    expect(attrs.neg).toEqual({ intValue: "-7" });
    expect(attrs.f).toEqual({ doubleValue: 1.5 });
    expect(attrs.nan).toEqual({ stringValue: "NaN" });
    expect(attrs.inf).toEqual({ stringValue: "Infinity" });
    expect(attrs.ninf).toEqual({ stringValue: "-Infinity" });
    expect(attrs.big).toEqual({ intValue: "4611686018427387904" });
    expect(attrs.huge).toEqual({ stringValue: "18446744073709551616" });
    expect(attrs.list).toEqual({ arrayValue: { values: [
      { intValue: "1" },
      { stringValue: "a" },
      {},
      { kvlistValue: { values: [{ key: "k", value: { boolValue: false } }] } },
    ] } });
    expect(attrs.map).toEqual({ kvlistValue: { values: [
      { key: "a", value: { intValue: "1" } },
      { key: "b", value: { kvlistValue: { values: [{ key: "c", value: { stringValue: "d" } }] } } },
    ] } });
    expect(attrs.date).toEqual({ stringValue: "2025-10-18T00:00:00.000Z" });

    // Five container levels survive; the sixth is stringified.
    let v = attrs.deep as Record<string, any>;
    for (let i = 0; i < 5; i++) v = v.arrayValue.values[0];
    expect(v).toEqual({ stringValue: '[["leaf"]]' });
  });

  it("keeps an invalid trace_id / span_id as ordinary attributes", async () => {
    const { sender, records } = fakeSender();
    const logger = makeLogger({ sender });
    logger.info("a", { trace_id: "not-a-trace", span_id: "123" });
    logger.info("b", { trace_id: "0".repeat(32), span_id: "0".repeat(16) });
    logger.info("c", { trace_id: "a".repeat(31) });
    await logger.flush();
    const [a, b, c] = records(0);
    expect(a).not.toHaveProperty("traceId");
    expect(a.attributes).toEqual([
      { key: "trace_id", value: { stringValue: "not-a-trace" } },
      { key: "span_id", value: { stringValue: "123" } },
    ]);
    expect(b).not.toHaveProperty("traceId");
    expect(b).not.toHaveProperty("spanId");
    expect((b.attributes as unknown[]).length).toBe(2);
    expect(c.attributes).toEqual([{ key: "trace_id", value: { stringValue: "a".repeat(31) } }]);
  });

  it("truncates the body and string attributes, and caps attributes at 128", async () => {
    const { sender, records } = fakeSender();
    const logger = makeLogger({ sender });
    const many: Record<string, unknown> = { long: "y".repeat(9000) };
    for (let i = 0; i < 200; i++) many[`k${i}`] = i;
    logger.info("x".repeat(40_000), many);
    await logger.flush();
    const rec = records(0)[0];
    const body = (rec.body as { stringValue: string }).stringValue;
    expect(body.length).toBe(32_768);
    expect(body.endsWith("…")).toBe(true);
    expect(body.startsWith("xxx")).toBe(true);
    const attrs = rec.attributes as Array<{ key: string; value: { stringValue?: string } }>;
    expect(attrs).toHaveLength(128);
    expect(attrs[0].value.stringValue!.length).toBe(8_192);
    expect(attrs[0].value.stringValue!.endsWith("…")).toBe(true);
  });

  it("never throws from a log call, even with hostile input", () => {
    const { sender } = fakeSender();
    const logger = makeLogger({ sender });
    const hostile = { toString() { throw new Error("nope"); } };
    const exploding = new Proxy({}, { ownKeys() { throw new Error("boom"); } });
    expect(() => logger.info(hostile)).not.toThrow();
    expect(() => logger.info("x", exploding as Record<string, unknown>)).not.toThrow();
    const circular: Record<string, unknown> = {};
    circular.self = circular;
    expect(() => logger.info("x", { circular })).not.toThrow();
  });
});

describe("CallistoLogger delivery", () => {
  it("sends as soon as 100 records are queued, and flush() sends the rest", async () => {
    const { sender, records } = fakeSender();
    const logger = makeLogger({ sender });
    for (let i = 0; i < 99; i++) logger.info(`m${i}`);
    expect(sender).not.toHaveBeenCalled();
    logger.info("m99");
    expect(sender).toHaveBeenCalledTimes(1);
    expect(records(0)).toHaveLength(100);

    for (let i = 0; i < 7; i++) logger.info(`r${i}`);
    await logger.flush();
    expect(sender).toHaveBeenCalledTimes(2);
    expect(records(1)).toHaveLength(7);
  });

  it("sends on the 5 s interval", async () => {
    vi.useFakeTimers();
    const { sender } = fakeSender();
    const logger = makeLogger({ sender });
    logger.info("tick");
    await vi.advanceTimersByTimeAsync(4_999);
    expect(sender).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(sender).toHaveBeenCalledTimes(1);
  });

  it("cuts a batch at 500 records", async () => {
    let release!: (r: LogsResponse) => void;
    const first = new Promise<LogsResponse>((r) => (release = r));
    const { sender, records } = fakeSender([first]);
    const logger = makeLogger({ sender });
    for (let i = 0; i < 1_100; i++) logger.info(`m${i}`);
    expect(sender).toHaveBeenCalledTimes(1); // first 100 in flight, 1000 queued
    release({ status: 200 });
    await logger.flush();
    expect([0, 1, 2].map((i) => records(i).length)).toEqual([100, 500, 500]);
    expect(logger.droppedCount()).toBe(0);
  });

  it("cuts a batch at about 1 MB of JSON", async () => {
    const { sender, requests } = fakeSender();
    const logger = makeLogger({ sender });
    for (let i = 0; i < 40; i++) logger.info("z".repeat(32_000));
    await logger.flush();
    expect(requests.length).toBe(2);
    for (const r of requests) expect(r.body.length).toBeLessThan(1_100_000);
    expect(requests.reduce((n, r) => n + JSON.parse(r.body).resourceLogs[0].scopeLogs[0].logRecords.length, 0)).toBe(40);
  });

  it("drops a 400 batch without retrying", async () => {
    const { sender } = fakeSender([{ status: 400 }]);
    const logger = makeLogger({ sender });
    logger.info("a");
    logger.info("b");
    await logger.flush();
    expect(sender).toHaveBeenCalledTimes(1);
    expect(logger.droppedCount()).toBe(2);
  });

  it("retries a 429 after Retry-After, then succeeds", async () => {
    vi.useFakeTimers();
    const { sender, records } = fakeSender([{ status: 429, retryAfter: "1" }, { status: 200 }]);
    const logger = makeLogger({ sender });
    logger.info("again");
    const flushed = logger.flush();
    await vi.advanceTimersByTimeAsync(999);
    expect(sender).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    await flushed;
    expect(sender).toHaveBeenCalledTimes(2);
    expect(records(1)).toHaveLength(1);
    expect(logger.droppedCount()).toBe(0);
  });

  it("drops after 3 failed attempts (503)", async () => {
    const fail = { status: 503, retryAfter: "0" };
    const { sender } = fakeSender([fail, fail, fail]);
    const logger = makeLogger({ sender });
    logger.info("x");
    await logger.flush();
    expect(sender).toHaveBeenCalledTimes(3);
    expect(logger.droppedCount()).toBe(1);
  });

  it("retries network errors after 1 s then 5 s, then drops", async () => {
    vi.useFakeTimers();
    const { sender } = fakeSender([new Error("ECONNRESET"), new Error("ECONNRESET"), new Error("ECONNRESET")]);
    const logger = makeLogger({ sender });
    logger.info("x");
    void logger.flush();
    await vi.advanceTimersByTimeAsync(0);
    expect(sender).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(sender).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(4_999);
    expect(sender).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(sender).toHaveBeenCalledTimes(3);
    expect(logger.droppedCount()).toBe(1);
  });

  it("pauses 60 s after a 401: queued and new records are dropped, then sending resumes", async () => {
    let now = FIXED_MS;
    const { sender } = fakeSender([{ status: 401 }]);
    const logger = makeLogger({ sender, clock: () => now });
    logger.info("a");
    await logger.flush();
    expect(sender).toHaveBeenCalledTimes(1);
    expect(logger.droppedCount()).toBe(1);

    now += 30_000;
    logger.info("during pause");
    await logger.flush();
    expect(sender).toHaveBeenCalledTimes(1);
    expect(logger.droppedCount()).toBe(2);

    now += 30_001;
    logger.info("after pause");
    await logger.flush();
    expect(sender).toHaveBeenCalledTimes(2);
    expect(logger.droppedCount()).toBe(2);
  });

  it("drops and counts records once 2000 are queued", async () => {
    const hang = new Promise<LogsResponse>(() => {});
    const { sender } = fakeSender([hang]);
    const logger = makeLogger({ sender, flushTimeout: 20 });
    for (let i = 0; i < 100; i++) logger.info("in flight");
    for (let i = 0; i < 2_000; i++) logger.info("queued");
    expect(logger.droppedCount()).toBe(0);
    for (let i = 0; i < 5; i++) logger.info("overflow");
    expect(logger.droppedCount()).toBe(5);
  });

  it("bounds flush() and close() by flushTimeout and never rejects", async () => {
    const hang = new Promise<LogsResponse>(() => {});
    const { sender } = fakeSender([hang]);
    const logger = makeLogger({ sender, flushTimeout: 30 });
    logger.info("x");
    const start = Date.now();
    await expect(logger.flush()).resolves.toBeUndefined();
    await expect(logger.close()).resolves.toBeUndefined();
    expect(Date.now() - start).toBeLessThan(1_000);
  });

  it("close() flushes, stops the timer, and later calls are no-ops", async () => {
    const clear = vi.spyOn(globalThis, "clearInterval");
    const { sender } = fakeSender();
    const logger = makeLogger({ sender });
    logger.info("last words");
    await logger.close();
    expect(sender).toHaveBeenCalledTimes(1);
    expect(clear).toHaveBeenCalled();
    logger.info("ignored");
    await logger.flush();
    await logger.close();
    expect(sender).toHaveBeenCalledTimes(1);
  });

  it("swallows sender failures (a synchronous throw counts as a network error)", async () => {
    const logger = makeLogger({
      flushTimeout: 50,
      sender: () => {
        throw new Error("sync boom");
      },
    });
    logger.info("x");
    await expect(logger.flush()).resolves.toBeUndefined();
  });
});

describe("CallistoLogger configuration", () => {
  it("is a no-op without credentials and starts no timer", async () => {
    const setInt = vi.spyOn(globalThis, "setInterval");
    const { sender } = fakeSender();
    const logger = new CallistoLogger({ sender });
    expect(logger.isEnabled).toBe(false);
    for (let i = 0; i < 150; i++) logger.info("nothing");
    await logger.flush();
    await logger.close();
    expect(sender).not.toHaveBeenCalled();
    expect(setInt).not.toHaveBeenCalled();
  });

  it.each([
    ["only the key", { logsAccessKey: PK }],
    ["only the secret", { logsSecret: SK }],
    ["an uppercase key", { logsAccessKey: PK.toUpperCase().replace("PK_", "pk_"), logsSecret: SK }],
    ["a short key", { logsAccessKey: PK.slice(0, -1), logsSecret: SK }],
    ["a wrong key prefix", { logsAccessKey: PK.replace("pk_", "sk_"), logsSecret: SK }],
    ["a short secret", { logsAccessKey: PK, logsSecret: SK.slice(0, -2) }],
    ["a non-hex secret", { logsAccessKey: PK, logsSecret: SK.slice(0, -1) + "g" }],
    ["a relative endpoint", { logsAccessKey: PK, logsSecret: SK, logsEndpoint: "/v1/logs" }],
    ["a non-http endpoint", { logsAccessKey: PK, logsSecret: SK, logsEndpoint: "ftp://logs.test/v1/logs" }],
  ])("throws ConfigurationError with %s", (_name, opts) => {
    expect(() => new CallistoLogger(opts)).toThrow(ConfigurationError);
  });

  it("never echoes the secret in a configuration error", () => {
    const bad = "sk_" + "z".repeat(48);
    try {
      new CallistoLogger({ logsAccessKey: PK, logsSecret: bad });
      expect.unreachable();
    } catch (err) {
      expect(String((err as Error).message)).not.toContain(bad);
    }
  });

  it("throws ConfigurationError for half credentials from the environment", () => {
    process.env.CALLISTO_LOGS_ACCESS_KEY = PK;
    expect(() => new CallistoLogger()).toThrow(ConfigurationError);
    expect(() => new CallistoClient()).toThrow(ConfigurationError);
  });

  it("resolves key, secret, endpoint, service and environment from env vars", async () => {
    process.env.CALLISTO_LOGS_ACCESS_KEY = PK;
    process.env.CALLISTO_LOGS_SECRET = SK;
    process.env.CALLISTO_LOGS_ENDPOINT = "https://env.logs.test/v1/logs";
    process.env.OTEL_SERVICE_NAME = "otel-name";
    process.env.CALLISTO_ENVIRONMENT = "staging";
    const { sender, requests, bodies } = fakeSender();
    const logger = new CallistoLogger({ sender });
    live.push(logger);
    logger.info("from env");
    await logger.flush();
    expect(requests[0].url).toBe("https://env.logs.test/v1/logs");
    expect(requests[0].headers.authorization).toBe(AUTH);
    const attrs = bodies()[0].resourceLogs[0].resource.attributes;
    expect(attrs[0]).toEqual({ key: "service.name", value: { stringValue: "otel-name" } });
    expect(attrs[1]).toEqual({ key: "deployment.environment.name", value: { stringValue: "staging" } });

    process.env.CALLISTO_SERVICE_NAME = "callisto-name";
    const second = new CallistoLogger({ sender });
    live.push(second);
    second.info("x");
    await second.flush();
    expect(bodies()[1].resourceLogs[0].resource.attributes[0].value).toEqual({ stringValue: "callisto-name" });
  });

  it("defaults to the Callisto logs endpoint", async () => {
    const { sender, requests } = fakeSender();
    const logger = makeLogger({ sender, logsEndpoint: undefined });
    logger.info("x");
    await logger.flush();
    expect(requests[0].url).toBe("https://logs.callistosignal.com/v1/logs");
  });
});

describe("client.logs", () => {
  it("is a no-op logger when no Logs key resolves", async () => {
    const { fetch, captured } = mockFetch(200, {});
    const client = new CallistoClient({ clientId: "cid", apiKey: "key" }, fetch);
    expect(client.logs).toBeInstanceOf(CallistoLogger);
    expect(client.logs.isEnabled).toBe(false);
    client.logs.info("nothing");
    await client.close();
    expect(captured).toHaveLength(0);
  });

  it("client.close() closes the logger", async () => {
    const { sender } = fakeSender();
    const client = new CallistoClient(
      { logsAccessKey: PK, logsSecret: SK, logsEndpoint: ENDPOINT },
      undefined,
      { logsSender: sender },
    );
    client.logs.warn("bye");
    await client.close();
    expect(sender).toHaveBeenCalledTimes(1);
    client.logs.warn("after close");
    await client.logs.flush();
    expect(sender).toHaveBeenCalledTimes(1);
  });

  it("never mixes messaging and Logs credentials", async () => {
    const { fetch, captured } = mockFetch(200, {
      total_amount: 1, available_credit: 9, status: "sent",
      recipient_count: 1, scheduled: false, messages: [],
    });
    const client = new CallistoClient(
      {
        clientId: "messaging-client-id",
        apiKey: "messaging-api-key",
        baseUrl: "https://api.test/v1",
        logsAccessKey: PK,
        logsSecret: SK,
        logsEndpoint: ENDPOINT,
      },
      fetch,
    );
    await client.sms.send({ sender: "Acme", to: "+2250700000000", message: "Hi" });
    client.logs.info("order paid", { order_id: 42 });
    await client.close();

    const messagingAuth =
      "Basic " + Buffer.from("messaging-client-id:messaging-api-key").toString("base64");
    const logsReq = captured.filter((c) => c.url === ENDPOINT);
    const msgReq = captured.filter((c) => c.url.startsWith("https://api.test/"));
    expect(logsReq).toHaveLength(1);
    expect(msgReq).toHaveLength(1);

    const logsText = JSON.stringify(logsReq[0]);
    expect(logsReq[0].headers.authorization).toBe(AUTH);
    expect(logsText).not.toContain("messaging-client-id");
    expect(logsText).not.toContain("messaging-api-key");
    expect(logsText).not.toContain(messagingAuth.slice(6));

    const msgText = JSON.stringify(msgReq[0]);
    expect(msgReq[0].headers.authorization).toBe(messagingAuth);
    expect(msgText).not.toContain(SK);
    expect(msgText).not.toContain(AUTH.slice(6));
  });
});
