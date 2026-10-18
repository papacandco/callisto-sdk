# Design: Log shipping to callisto-logs from every Callisto SDK

**Date:** 2026-10-18
**Status:** Approved (owner request "Allow callisto-sdk push log data")

## Goal

Let an application send its own log lines to **callisto-logs** through the Callisto SDK, in all
seven languages (JS/TS, Python, PHP, C#, Ruby, Java, Go), without an OpenTelemetry SDK. Logs are
batched, sent in the background where the language allows it, and never slow down or break the
caller. Customers who already run an OpenTelemetry SDK or Collector keep using it: this feature is
for the ones who do not.

## The ingest contract (callisto-logs, already built)

- **Endpoint:** `POST https://logs.callistosignal.com/v1/logs` (OTLP/HTTP logs).
- **Auth:** `Authorization: Basic base64("<access_key>:<secret>")`, an API key of **scope Logs**
  created in the dashboard (Organization → API keys, or Monitoring → Logs → Sources):
  - access key `pk_` + 32 lowercase hex;
  - secret `sk_` + 48 lowercase hex.

  A messaging API key is refused (401), and a Logs key is refused by the messaging API.
- **Body:** `Content-Type: application/json`, the OTLP JSON mapping (below). gzip is accepted but
  not required; v1 does not compress.
- **Limits:** 5 MB per request (compressed and decompressed). Over quota or plan → 429 / 403.
- **Responses:**

  | Status | Meaning | SDK behavior |
  | --- | --- | --- |
  | `200` | `ExportLogsServiceResponse`, possibly with `partialSuccess.rejectedLogRecords` | batch done |
  | `400`, `413`, `415` | bad payload | drop the batch |
  | `401` | bad key | drop the batch, pause sending 60 s |
  | `403` | plan has no logs, or the plan is suspended | drop the batch, pause sending 60 s |
  | `429`, `503` | quota, rate limit or back-pressure, with `Retry-After` | retry (below) |
  | network error, timeout | — | retry (below) |
  | any other | — | drop the batch |

## Configuration

Same resolution order as the rest of the SDK: explicit argument, then environment variable.

| Setting (camel / snake) | Env var | Default | Meaning |
| --- | --- | --- | --- |
| `logsAccessKey` / `logs_access_key` | `CALLISTO_LOGS_ACCESS_KEY` | none | `pk_…` of a Logs key |
| `logsSecret` / `logs_secret` | `CALLISTO_LOGS_SECRET` | none | `sk_…` of that key |
| `logsEndpoint` / `logs_endpoint` | `CALLISTO_LOGS_ENDPOINT` | `https://logs.callistosignal.com/v1/logs` | full POST URL |
| `serviceName` / `service_name` | `CALLISTO_SERVICE_NAME`, then `OTEL_SERVICE_NAME` | `unknown_service` | resource `service.name` |
| `environment` | `CALLISTO_ENVIRONMENT` (existing) | none | resource `deployment.environment.name` |

Validation rules:
- Both key and secret absent → logs **disabled**: every call is a cheap no-op and nothing is
  sent.
- Exactly one of the two present, or a value that does not match `^pk_[0-9a-f]{32}$` /
  `^sk_[0-9a-f]{48}$` → the constructor **fails fast** with the SDK's configuration error (the
  same error type used for missing messaging credentials). A typo must not silently lose every
  log.
- `logsEndpoint` must be an absolute `http(s)` URL (configuration error otherwise).

## Surface

### Standalone logger (works without messaging credentials)

A logger class that can be built on its own, because many apps that ship logs never send an SMS:

| Language | Class | Construction |
| --- | --- | --- |
| JS/TS | `CallistoLogger` | `new CallistoLogger({ logsAccessKey, logsSecret, ... })` |
| Python | `CallistoLogger` | `CallistoLogger(logs_access_key=..., logs_secret=...)` |
| PHP | `Callisto\Sdk\Logs\Logger` | `new Logger(accessKey: ..., secret: ...)` |
| C# | `Callisto.Sdk.Logs.CallistoLogger` | `new CallistoLogger(new CallistoLoggerOptions { ... })` |
| Ruby | `Callisto::Logger` | `Callisto::Logger.new(logs_access_key: ..., logs_secret: ...)` |
| Java | `com.callisto.sdk.logs.CallistoLogger` | `CallistoLogger.builder()...build()` |
| Go | `callisto.Logger` | `callisto.NewLogger(callisto.LoggerConfig{...})` |

All config arguments are optional and fall back to the env vars above. Constructing a disabled
logger (no key, no secret) is valid and returns a no-op logger.

### On the messaging client

The existing client exposes the same logger as `client.logs` (`client.Logs` in C#/Go, `logs()` in
Java), built from the same settings passed to the client constructor (and env vars). When no Logs
key resolves, `client.logs` is a no-op logger. The client's existing close/dispose also closes the
logger. The messaging credentials are never used for logs, and vice versa.

### Methods (idiomatic case per language)

```
logger.debug(message[, attributes])
logger.info(message[, attributes])
logger.warn(message[, attributes])
logger.error(message[, attributes])
logger.fatal(message[, attributes])
logger.log(level, message[, attributes])   # level: debug|info|warn|error|fatal
logger.flush()                              # send what is queued now; bounded, best-effort
logger.close()                              # flush, then stop the worker; later calls are no-ops
```

- `message` is converted to a string.
- `attributes` is a flat or nested map. Values can be string, bool, int, float, null, list or
  map (mapped to OTLP `AnyValue`, below). Other values are stringified.
- Two reserved attribute keys tie a log to a trace (and so to an error issue, which carries
  `context.trace.trace_id`):
  - `trace_id`: 32 hex characters, any case, not all zeros;
  - `span_id`: 16 hex characters, not all zeros.

  When valid, they are **moved** to the record's `traceId` / `spanId` (lowercased) and removed
  from the attributes. When invalid, they stay as ordinary attributes.
- A log call **never throws** and never blocks on the network (PHP excepted, below).

### Native logging adapters (zero new runtime dependencies)

Where the standard library has a logging interface, ship an adapter that forwards to a
`CallistoLogger`:

| Language | Adapter | Mapping |
| --- | --- | --- |
| Python | `CallistoLogHandler(logging.Handler)` | `record.levelno` → severity; `record.getMessage()` → body; `logger.name` → attribute `logger.name`; `exc_info` → `exception.type`, `exception.message`, `exception.stacktrace`; any `extra` keys not in the standard `LogRecord` → attributes |
| Go | `(*Logger).Handler() slog.Handler` | slog levels → severity; slog attrs and groups → attributes (groups as nested maps) |
| Java | `CallistoLogHandler extends java.util.logging.Handler` | `Level` → severity; `getLoggerName()` → `logger.name`; thrown → `exception.*` |
| PHP | `Callisto\Sdk\Logs\PsrLogger implements Psr\Log\LoggerInterface` | only when `psr/log` is installed: listed in `suggest`, **not** `require`; PSR-3 `{placeholder}` interpolation; `context['exception']` → `exception.*` |

JS, C# and Ruby get no adapter in v1. Their standard logging either has no common interface (JS),
or needs a package dependency (C# `Microsoft.Extensions.Logging`).

Level mapping for adapters:
- below INFO → debug;
- INFO → info;
- WARNING → warn;
- ERROR → error;
- CRITICAL / FATAL / SEVERE → error, except Python `CRITICAL` → fatal and Java `SEVERE` → error.

Each adapter must guard against recursion: a log emitted while the logger itself is sending
(for example by the HTTP library) is dropped, never re-shipped.

## Wire format (OTLP JSON)

One request = one `resourceLogs` entry, one `scopeLogs` entry, N `logRecords`:

```json
{
  "resourceLogs": [{
    "resource": { "attributes": [
      { "key": "service.name", "value": { "stringValue": "checkout" } },
      { "key": "deployment.environment.name", "value": { "stringValue": "production" } },
      { "key": "telemetry.sdk.name", "value": { "stringValue": "callisto-sdk" } },
      { "key": "telemetry.sdk.language", "value": { "stringValue": "python" } },
      { "key": "telemetry.sdk.version", "value": { "stringValue": "1.4.0" } }
    ]},
    "scopeLogs": [{
      "scope": { "name": "callisto-sdk", "version": "1.4.0" },
      "logRecords": [{
        "timeUnixNano": "1760745600000000000",
        "observedTimeUnixNano": "1760745600000000000",
        "severityNumber": 9,
        "severityText": "INFO",
        "body": { "stringValue": "order paid" },
        "attributes": [
          { "key": "order_id", "value": { "intValue": "42" } },
          { "key": "paid", "value": { "boolValue": true } }
        ],
        "traceId": "5b8efff798038103d269b633813fc60c",
        "spanId": "eee19b7ec3c1b174"
      }]
    }]
  }]
}
```

Rules:
- `deployment.environment.name` is present only when an environment is set.
- `telemetry.sdk.language` uses the OTel names: `nodejs`, `python`, `php`, `dotnet`, `ruby`,
  `java`, `go`.
- `timeUnixNano` is the time of the log call; `observedTimeUnixNano` is equal to it. Both are
  **decimal strings** (int64 in OTLP JSON). Use the best clock resolution available; millisecond
  × 1 000 000 is acceptable.
- Severity:

  | level | severityNumber | severityText |
  | --- | --- | --- |
  | debug | 5 | `DEBUG` |
  | info | 9 | `INFO` |
  | warn | 13 | `WARN` |
  | error | 17 | `ERROR` |
  | fatal | 21 | `FATAL` |

- AnyValue mapping:
  - string → `stringValue`;
  - bool → `boolValue`;
  - integer → `intValue`, as a **decimal string**;
  - float → `doubleValue` (a JSON number; NaN and ±Inf → `stringValue` "NaN" / "Infinity" /
    "-Infinity");
  - null → omit the attribute (or the list element becomes `{}`);
  - list → `arrayValue: { values: [...] }`;
  - map → `kvlistValue: { values: [{key, value}, ...] }`.

  Nesting deeper than 5 levels is stringified.
- `traceId` / `spanId` are omitted when absent.
- An empty `attributes` list is omitted.

### Size guards

- `body` is truncated to **32 768** characters; string attribute values to **8 192**. The cut is
  marked with a trailing `…`.
- At most **128** attributes per record (the extra ones are dropped).
- A batch is at most **500** records, or about **1 MB** of serialized JSON (it is cut at whichever
  comes first). This stays well under the 5 MB server cap.

## Delivery

- A bounded in-memory queue holds at most `maxQueue` = **2 000** records. When it is full, new
  records are dropped.
- A dropped counter (exposed as `droppedCount()` / `dropped_count`; read-only, for tests and
  diagnostics) counts **every record lost without delivery**:
  - queue full;
  - dropped during a 401/403 pause;
  - a batch rejected by a non-retryable status;
  - a batch dropped after its last retry.
- Truncation: a cut value is exactly the limit long, the trailing `…` included.
- **Fork safety:** Python and Ruby detect a fork (`os.register_at_fork` / a `Process.pid` check).
  The child resets its queue and state and starts its own worker on the next record. Without this,
  a pre-forking server (gunicorn, Puma, Unicorn) would queue logs that are never sent.
- Background languages (JS, Python, C#, Ruby, Java, Go): one worker sends a batch when 100 records
  are queued, every **5 s** otherwise, and on `flush()` / `close()`.
  - JS: an `unref`'d interval timer and promise-based sends.
  - Python: a daemon thread, plus `atexit` → `close()`.
  - C#: a `Task` loop over a `Channel`; `Dispose` / `DisposeAsync` → close.
  - Ruby: a `Thread` over a `Queue`, plus `at_exit` → close.
  - Java: a daemon `ScheduledExecutorService`; `close()` (AutoCloseable).
  - Go: a goroutine; `Close(ctx)` / `Flush(ctx)`.
- **PHP, documented exception:** PHP has no portable background thread in a request. Records are
  queued in memory. A batch is sent synchronously when 100 records are queued, at `flush()`, and
  from a `register_shutdown_function` registered once per logger. The timeout is 2 s connect and
  3 s total. There is no retry (a failed batch is dropped), so a slow ingest never holds a request
  for long.
- Retry (background languages):
  - on `429`, `503` or a network error, the batch is retried up to **3** attempts in total;
  - the delay is `Retry-After` (seconds; capped at 60 s, default 5 s when absent or unparsable)
    for 429/503, and 1 s then 5 s for network errors;
  - while a batch waits for its retry, new records keep queueing (bounded as above).
- After a `401` or `403`, sending pauses **60 s**: queued and new records are dropped during the
  pause, then one new attempt is allowed.
- `flush()` / `close()` are bounded to **5 s** in total (configurable as `flushTimeout`) and never
  throw.
- Request headers:
  - `Authorization: Basic …`;
  - `Content-Type: application/json`;
  - `User-Agent: callisto-sdk-<language>/<version>`.

  HTTP timeout: 10 s per request in background languages.

## Isolation and privacy (hard requirements)

1. The logger uses its **own** HTTP path, never the messaging `Transport`. It never sends
   `client_id` / `api_key` to callisto-logs, and never sends the Logs secret anywhere else.
2. The logger's own failures are swallowed. They are never sent to the error reporter and never
   logged through the logger itself (no recursion).
3. The SDK does not scrub content: callisto-logs masks sensitive keys and, by default, e-mails,
   IPs and phone numbers. The README states plainly: **do not log passwords, tokens, card numbers
   or other secrets**. This mirrors the platform terms (CGU 7.6).
4. The error reporter is unchanged. Logs and errors are separate products; they only meet through
   `trace_id`.

## Testing (per SDK)

Inject a fake sender (and a fake clock or a short interval where needed). Assert:

1. **Golden payload:** logging `info("order paid", {order_id: 42, paid: true, trace_id:
   "5B8EFFF798038103D269B633813FC60C", span_id: "EEE19B7EC3C1B174"})` with service `checkout`,
   environment `production` and a fixed clock of `1760745600` s produces a body structurally
   equal to the JSON above, except `telemetry.sdk.language` and the version.
2. The `Authorization` header is `Basic base64(pk:sk)` with the shared vector
   `pk_00112233445566778899aabbccddeeff` / `sk_000102030405060708090a0b0c0d0e0f1011121314151617`,
   and the endpoint is the configured URL.
3. Severity mapping for all five levels; AnyValue mapping (int as string, float, nested list/map,
   null dropped).
4. Invalid `trace_id` stays an attribute.
5. Batching: 100 records trigger a send; `flush()` sends the rest; batch cut at 500.
6. Truncation of body and attribute values.
7. 400 drops (no retry); 429 with `Retry-After: 1` retries, then succeeds; 3 failed attempts
   drop; 401 pauses.
8. Full queue drops and counts.
9. Disabled logger (no credentials): no send, no thread started where checkable.
10. Half or malformed credentials: configuration error at construction.
11. The messaging credentials never appear in a logs request; the Logs secret never appears in a
    messaging request.
12. Each native adapter: level mapping, attributes, exception fields, and no recursion.

Each SDK's README gains a "Logs" section covering: creating a Logs key, configuration and env
vars, quick start, trace correlation, the adapter, delivery semantics (PHP sync note), and the
"do not log secrets" rule. The root `readme.md` gains a short section.

## Out of scope (v1)

- gzip compression, protobuf encoding, OTLP traces and metrics.
- Disk persistence of the queue, sampling.
- JS, C# and Ruby framework adapters (winston/pino, `ILogger`, Rails logger).
- A callisto-app dashboard snippet for the SDK (today the source page shows OTel env vars only).
  It is a follow-up in callisto-app.
