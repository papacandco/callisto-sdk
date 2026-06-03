# Design: Built-in Error Reporting for the Callisto SDKs

**Date:** 2026-06-03
**Status:** Approved

## Goal

Give every Callisto SDK (JS/TypeScript, Python, PHP, C#, Ruby, Java) an opt-in,
Sentry-style error reporter that POSTs captured errors to the Callisto error-tracking
**ingest endpoint** (the DSN target in callisto-app). The reporter auto-captures the SDK's own
`CallistoError`s and also exposes a public API so the host application can report its own
exceptions. Delivery is background, best-effort, and never alters or delays the original error.

## The ingest contract (callisto-app, already built)

- **Endpoint:** `POST {APP_URL}/ingest/{id}?key={public_key}` — see
  `callisto-app` `routes/v1/webhook.php` and `App\Controllers\Errors\ErrorIngestController`.
- **DSN:** `{APP_URL}/ingest/{id}?key={public_key}` (e.g.
  `https://app.callistosignal.com/ingest/<uuid>?key=<hex>`). The DSN **is** the full POST URL —
  the SDK posts directly to it; no parsing beyond a well-formed-URL check.
- **Auth:** the `?key=` query param (the public key) is validated server-side against the app's
  `public_key`. No other auth.
- **Request body (JSON):**
  - `message` — string, **required** (422 `message_required` if blank).
  - `type` — string, exception type/class name.
  - `level` — one of `fatal | error | warning | info` (defaults to `error` server-side).
  - `culprit` — string, where the error originated.
  - `stacktrace` — JSON array/object (stored only if a non-empty array/object).
  - `context` — JSON object.
  - `user` — JSON object.
  - `request` — JSON object.
  - Note: the ingest service stores `stacktrace`/`context`/`user`/`request` only when they are
    non-empty arrays/objects (`ErrorIngestService::jsonOrNull`). Send objects/arrays, not scalars.
- **Responses:** `202 {ok:true, event_id}` success · `401 invalid_key` · `404 unknown_app` ·
  `422 message_required`. The reporter treats any non-202 as a swallowed failure.

## Configuration (mirrors each SDK's existing config conventions)

| Setting (camel / snake) | Env var | Default | Meaning |
| --- | --- | --- | --- |
| `errorDsn` / `error_dsn` | `CALLISTO_ERROR_DSN` | none | Ingest DSN. Absent → reporting fully disabled (no-op). |
| `captureUnhandled` / `capture_unhandled` | `CALLISTO_CAPTURE_UNHANDLED` | `false` | Install the global unhandled-exception handler. |
| `environment` | `CALLISTO_ENVIRONMENT` | none | Optional tag included in `context.environment`. |

Resolution order matches the existing credentials: explicit constructor argument first, then env
var. When no DSN resolves, the client builds a **no-op reporter** and behavior is identical to
today.

## Architecture

One new unit per SDK (`ErrorReporter`) plus small hooks. The reporter is created by the client
from config and shared with the transport (so resources, which already hold the transport, can
reach it).

### `ErrorReporter`

- **State:** DSN, SDK metadata (`name`, `version`, `language`), optional `environment`, an
  injectable HTTP **sender**, a background **dispatcher**, and a settable `user` context.
- **Methods:**
  - `captureException(error, level = error, extra = null)` — build payload from an
    exception/throwable, enqueue a background send. Returns void/nil. Never throws.
  - `captureMessage(message, level = info, extra = null)` — capture a plain message.
  - `setUser(map)` — set/clear the `user` context attached to subsequent events.
  - `flush()` / `close()` — drain/await pending sends with a short bound (called on client
    dispose/close). Best-effort.
- **Isolation rules:**
  - Uses its **own** minimal HTTP path, never the main `Transport`, so it never recurses and
    never inherits the Basic-auth credentials.
  - Its own send failures (any exception, any non-202) are swallowed and **never** re-captured.
  - When DSN is absent, every method is a cheap no-op.

### Hooks

- **Transport:** immediately before raising a mapped status error or `NetworkError`, call
  `reporter.captureException(err)`. This single choke point covers all API + network errors.
- **Resources:** the client-side validation sites — `otp.send` (WhatsApp without
  `instance_code`) and `notify.send` (no event block) — call `reporter.captureException(err)`
  via the transport's reporter before raising.
- **Client:** exposes `captureException` / `captureMessage` / `setUser` (delegating to the
  reporter) for the host app's own errors; on dispose/close calls `reporter.flush()`; if
  `captureUnhandled` is enabled and a DSN is set, installs the global handler (below).

### Global unhandled-exception handler (opt-in, default off)

When `captureUnhandled` is true, install the platform's global hook to capture uncaught errors at
`level = fatal`, report, then preserve the platform's default behavior (re-raise / propagate):

- C#: `AppDomain.CurrentDomain.UnhandledException` (+ `TaskScheduler.UnobservedTaskException`).
- Java: `Thread.setDefaultUncaughtExceptionHandler` (chains to any existing handler).
- Ruby: `at_exit` inspecting `$!`.
- Python: `sys.excepthook` (+ `threading.excepthook`), chaining the previous hook.
- JS/Node: `process.on('uncaughtException')` and `process.on('unhandledRejection')`.
- PHP: `set_exception_handler` + `register_shutdown_function` (for fatals), chaining any prior
  handler.

The handler must chain (not clobber) any pre-existing handler.

## Payload mapping (exception → ingest body)

- `message` — the exception message (for `captureMessage`, the supplied text).
- `type` — the exception's class/type name (e.g. `AuthenticationError`, `NotFoundError`,
  `NetworkError`, or the host app's exception class for `captureException`).
- `level` — `error` for auto-captured `CallistoError`s, `fatal` for the unhandled handler,
  caller-supplied for the public API; constrained to `fatal|error|warning|info`.
- `culprit` — for transport-originated errors: `"{METHOD} {path}"` (e.g. `POST /sms/send`);
  otherwise the top application stack frame (`function (file:line)`), best-effort.
- `stacktrace` — array of frames `{ function, file, line }` extracted from the exception
  (innermost-first), best-effort; omitted if unavailable.
- `context` — object: `{ sdk: { name, version, language }, environment? }` merged with any
  per-call `extra`; for `CallistoError` also `status_code` and (rate-limit) `retry_after`, plus
  the decoded API response `body` under `context.body`.
- `request` — for transport errors: `{ method, path }`. Omitted otherwise.
- `user` — the current `setUser` map, if any.

### PII / secrets rule (hard requirement)

The reporter MUST NEVER transmit: `client_id`, `api_key`, the `Authorization` header, or the
**outgoing request body** (it carries phone numbers and message content). Only the server's error
`body`, `status_code`, HTTP `method`, and request `path` may leave the process. Tests assert this
explicitly.

## Delivery — background, per-language idiom

Fire-and-forget so the caller's error path adds zero latency. `close()`/dispose flushes briefly.

- **C#:** worker over a `Channel`/`BlockingCollection` (or `Task.Run` per event); flush on
  `Dispose`.
- **Java:** single daemon-thread `ExecutorService`; `submit` per event; `close()` shuts down with
  a short `awaitTermination`.
- **Ruby:** a `Queue` + single worker `Thread`; `close` pushes a sentinel and joins briefly.
- **Python:** daemon `threading.Thread` worker over a `queue.Queue`; `close()` joins briefly.
- **JS/Node:** the capture POST is simply not awaited (`void send().catch(() => {})`); `close()`
  awaits in-flight sends.
- **PHP — documented exception:** PHP has no portable background threads in a request context, so
  it delivers **synchronously with a short timeout** (best-effort, all failures swallowed). This
  is the one place "background" degrades to inline; it is documented in the PHP README.

## Public API surface (per language, idiomatic)

```
client.captureException(error[, level][, extra])
client.captureMessage(text[, level][, extra])
client.setUser({ id, email, ... })
```

(C#/Java/JS camelCase; Python/Ruby snake_case; PHP camelCase methods.) The reporter is also
reachable directly (e.g. `client.errorReporter`) for advanced use, but the three client methods
are the supported surface.

## Testing (per SDK)

Inject a fake HTTP sender and use the `flush()` seam for determinism. Assert:

1. A captured `CallistoError` produces a POST to the **DSN URL** with the correct
   `message`/`type`/`level`.
2. `context.sdk` and (for transport errors) `status_code` / `request.{method,path}` are present.
3. **No credential or request-body leak** — payload contains neither `api_key`/`client_id`/auth
   header nor the outgoing request body.
4. Sender failures (throw / non-202) are swallowed; `captureException` never raises.
5. When no DSN is set, nothing is sent and the SDK behaves exactly as before; the original error
   still propagates in both cases.
6. `captureException` / `captureMessage` public methods work; `captureUnhandled` wiring installs
   and chains the global handler (where testable without crashing the test process).

README for each SDK documents: enabling the DSN, the env vars, the public API, the opt-in handler,
and the PII guarantee. The root `readme.md` gains a short "Error reporting" section.

## Out of scope (v1)

- Breadcrumbs, tags, event sampling, and on-disk retry/queue persistence (YAGNI).
- Adding `csharp`/`ruby`/`java` to callisto-app's `ErrorApp::PLATFORMS` dropdown — the SDK does
  not send a platform field (it is a dashboard-side label), so reporting works regardless. This
  is a separate, optional callisto-app change.
- Capturing arbitrary non-error log lines.
