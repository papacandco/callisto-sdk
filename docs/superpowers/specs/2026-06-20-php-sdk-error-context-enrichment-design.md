# PHP SDK error-context enrichment: request info, headers, more context

**Date:** 2026-06-20
**Status:** Approved (design) — ready for implementation plan
**Scope:** callisto-sdk/php only (ErrorReporter + CallistoIntegration + Laravel/Symfony/Bowphp bridges). No other language SDKs; no server changes required for storage.

## Problem

The PHP SDK's `ErrorReporter` currently attaches only a minimal `request` block
(`{method, path}`) and a thin `context` (`sdk`, `environment`, plus caller
`extra`). Captured errors lack the request and runtime detail engineers need to
triage: headers, query string, client IP, full URL, PHP/OS/runtime info,
memory/timing, and a correlation id.

This adds that detail **additively**, behind a hard PII guarantee.

## Locked decisions (from brainstorming)

- **Scope:** PHP SDK only, full end-to-end capture (reporter + 3 framework bridges).
- **Header policy:** denylist + redact. Send headers, but replace sensitive
  values with the placeholder `[Filtered]` (matches the dashboard's existing
  convention).
- **Fields captured:** headers (redacted), query string (key-redacted), client
  IP, full URL + host — in addition to the existing method/path.
- **More context:** runtime block (PHP version, SAPI, OS), server/host
  (hostname), request id / trace correlation, memory + timing.
- **Redaction lives centrally** in `ErrorReporter` (one tested place), so the
  guarantee holds for every call path (the 3 bridges, the Transport call sites,
  and any direct caller). Bridges collect raw data; the reporter redacts.

## The ingest constraint (why additive, nested)

The Callisto error ingest (`callisto-app` `ErrorIngestService`) reads the SDK's
**custom** payload shape — `type`, `message`, `culprit`, `level`, `stacktrace`,
`context`, `user`, `request` — and persists `payload['request']` and
`payload['context']` **verbatim** into JSON columns (`error_events.request` /
`error_events.context`). It **ignores unknown top-level keys**.

Therefore:
- A wholesale Sentry-envelope rewrite is rejected — it would break ingestion.
- New request detail nests under **`request.*`** (already persisted).
- All "more context" nests under **`context.*`** (already persisted).
- No new top-level keys (they'd be silently dropped server-side).

Result: enrichment stores end-to-end with **zero server changes**. Rendering the
new fields in the dashboard UI is an optional, separate follow-on.

## Payload shape (after enrichment)

`request` (when a framework bridge supplies a request):
```jsonc
"request": {
  "method": "POST",
  "path": "/index.php",
  "url": "http://host/index.php",        // scheme+host+path, no query string
  "query_string": { "token": "[Filtered]", "page": "2" },  // key-redacted map
  "headers": { "Accept": "*/*", "Authorization": "[Filtered]", "User-Agent": "..." },
  "ip": "203.0.113.7"
}
```

`context` (always present; new sub-blocks added):
```jsonc
"context": {
  "sdk": { "name": "callisto-php/sdk", "version": "1.1.0", "language": "php" },
  "environment": "production",
  "runtime": { "name": "php", "version": "8.4.8", "sapi": "fpm-fcgi",
               "os": "Linux", "os_version": "<uname r>" },
  "server": { "name": "<gethostname()>" },
  "trace": { "trace_id": "<32 hex>", "request_id": "<X-Request-Id if present>" },
  "runtime_stats": { "memory_peak_bytes": 12582912, "duration_ms": 87.4 },
  // ...existing caller $extra still merged here...
}
```

Backward compatible: `culprit` still derives from `method`/`path` only; existing
`{method, path}` callers (Transport) keep working — the new keys are all optional.

## Components & changes

### 1. `ErrorReporter` — central redaction + context builders

- Extend the `$request` contract to `array{method, path, url?, query?:array,
  headers?:array, ip?:string}`. `buildExceptionPayload` emits the redacted
  request block; redaction happens here regardless of caller.
- **Redaction** (`redactMap()`), case-insensitive on key, applied to both
  `headers` and `query`:
  - exact denylist: `authorization, cookie, set-cookie, proxy-authorization,
    x-api-key, x-csrf-token, x-xsrf-token`
  - regex on key: `/authoriz|cookie|secret|token|password|passwd|api[-_]?key|csrf|session/i`
  - match → value becomes `[Filtered]`.
  - header keys Title-Cased; multi-value header arrays joined with `, `.
  - defensive caps: ≤ 50 headers, ≤ 50 query keys, each value ≤ 1 KB.
- **`url`** stored without the query string (query lives in `query_string`).
- **More context** added in `buildContext()` (no framework input needed):
  - `runtime`: `php`, `PHP_VERSION`, `php_sapi_name()`, `php_uname('s')`, `php_uname('r')`.
  - `server.name`: `gethostname()` (false → omit).
  - `runtime_stats`: `memory_get_peak_usage(true)`, and
    `(microtime(true) - $_SERVER['REQUEST_TIME_FLOAT']) * 1000` when available.
  - `trace.trace_id`: `bin2hex(random_bytes(16))` per event; `trace.request_id`
    from the inbound `X-Request-Id` header when present (derived in the request
    path, surfaced into context).
- **Body still never transmitted** — keep the hard rule and the `withSource`
  opt-out exactly as today; add a comment that `request.data`/body is
  deliberately absent.

### 2. `CallistoIntegration::request()` — widen the builder

Signature grows to
`request(?string $method, ?string $path, ?string $url = null, ?array $query = null, ?array $headers = null, ?string $ip = null): ?array`,
packaging present keys **raw** (no redaction here — that's the reporter's job).
Returns null only when method/path are both missing (unchanged guard).

### 3. Framework bridges — extract raw request detail (duck-typed, guarded)

Each bridge gathers what its request object exposes; every accessor guarded so a
missing method degrades gracefully:
- **Laravel** (`CallistoServiceProvider::requestContext`): `fullUrl()`,
  `query()`, `headers->all()`, `ip()`.
- **Symfony** (`CallistoExceptionListener::requestContext`): `getUri()`,
  `query->all()`, `headers->all()`, `getClientIp()`.
- **Bowphp** (`CallistoErrorHandler::requestFrom`): whatever
  `Bow\Http\Request` exposes for url/query/headers/ip, each `method_exists`-guarded.

IP precedence applied in the reporter when an explicit ip is absent:
`Cf-Connecting-Ip → X-Forwarded-For[0] → X-Real-Ip → bridge-supplied`.

## Safety / non-functional

- **PII:** Authorization/credentials/cookies/tokens never leave — central
  denylist redaction covers all paths; unit-tested against the existing
  leak-guard plus new header/query cases.
- **No body:** unchanged hard guarantee; transport `withSource:false` path intact.
- **Bounded:** header/query counts and value lengths capped.
- **Never throws:** capture remains best-effort; all new extraction is inside the
  existing try/catch swallow.

## Testing

- Redaction: denylist + regex on headers and query; value/length caps; Title-Case
  header keys; multi-value join. Extend the existing `testNoCredentialOrRequestBodyLeak`
  to assert `Authorization`/`Cookie`/`token` are `[Filtered]` and never raw.
- Request block: url excludes query string; ip precedence (CF → XFF → real → supplied).
- Context: runtime/server/runtime_stats/trace blocks present and well-formed;
  `trace_id` is 32 hex.
- Backward compatibility: `{method, path}`-only callers (Transport) still produce
  a valid payload; `culprit` unchanged.
- Bridges: `request()` packages the new optional keys; static/duck-typed
  extractors work with stand-in request objects.

## Out of scope

- Other language SDKs (JS/Go/etc.) — parity deferred.
- Sentry-envelope schema — rejected (ingest reads the custom shape).
- Server-side UI rendering of the new fields — the data persists today via the
  existing `request`/`context` JSON columns; a dashboard panel is a follow-on.
- Top-level `handled`/`mechanism` fields — would be dropped by the ingest;
  revisit only alongside a server-side ingest change.
