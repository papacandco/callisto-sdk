# Design: C#, Ruby & Java SDKs for callisto-sdk

**Date:** 2026-06-03
**Status:** Approved

## Goal

Add three new feature-equivalent client SDKs — **C#**, **Ruby**, and **Java** — to the
`callisto-sdk` monorepo, matching the existing JavaScript/TypeScript, Python, and PHP
implementations in surface area, behavior, and completeness (source + tests + packaging +
README).

## Approach

Faithfully port the existing SDK into each new language, expressed idiomatically. The
architecture is already established by the three existing SDKs; the design work is mapping the
shared surface onto each language's conventions, dependencies, and packaging.

Each SDK is self-contained in its own top-level folder, sibling to `js/`, `python/`, `php/`:

- `csharp/`
- `ruby/`
- `java/`

## Chosen stacks

| | **C#** | **Ruby** | **Java** |
| --- | --- | --- | --- |
| Package | NuGet `Callisto.Sdk` | gem `callisto-sdk` | Maven `com.callisto:callisto-sdk` |
| Namespace / module | `Callisto.Sdk` | `Callisto` (flat: `Callisto::Client`) | `com.callisto.sdk` |
| Min target | .NET 8 | Ruby 3.0+ | Java 11+ |
| HTTP client | `System.Net.Http.HttpClient` | stdlib `Net::HTTP` | `java.net.http.HttpClient` |
| JSON | `System.Text.Json` | stdlib `json` | Jackson (`jackson-databind`) |
| Build tool | `dotnet` / `.csproj` | `gem` / `.gemspec` + Bundler | Maven / `pom.xml` |
| Test framework | xUnit | RSpec + WebMock | JUnit 5 |
| Entry point | `new CallistoClient(...)` | `Callisto::Client.new(...)` | `new CallistoClient(...)` |
| Resource access | `client.Sms.Send(...)` | `client.sms.send(...)` | `client.sms().send(...)` |

## Shared behavior (identical across all SDKs)

### Configuration

- Constructor accepts `clientId`, `apiKey`, `baseUrl`, `timeout`.
- Each credential falls back to an environment variable when the argument is absent:
  - `CALLISTO_CLIENT_ID` → `client_id`
  - `CALLISTO_API_KEY` → `api_key`
  - `CALLISTO_BASE_URL` → `base_url`
- Fail fast (raise/throw a validation-style error) if `client_id` or `api_key` cannot be
  resolved from either source.
- `base_url` defaults to `https://api.callistosignal.com/v1`; any trailing slash is trimmed.
- Default request timeout is 30 seconds.

### Transport

- HTTP Basic auth: `Authorization: Basic base64(client_id:api_key)` — built internally; callers
  never construct it.
- `Accept: application/json`; request bodies serialized as JSON.
- Query parameters with `null` values are dropped before sending.
- On a non-2xx response, parse the body and extract `message` (fall back to `HTTP <status>`),
  then map status → typed error:
  - `401` → `AuthenticationError`
  - `400` / `422` → `ValidationError`
  - `404` → `NotFoundError`
  - `429` → `RateLimitError` (carries `retry_after` parsed from the `Retry-After` header)
  - other non-2xx → `ApiError`
  - transport/connection failure → `NetworkError` (status code `0`)
- Every error derives from a base `CallistoError` carrying `message`, `status_code`, and the
  decoded response `body`.

### Resources & methods

- **balance**: `get(format="full", currency=null)` → `GET /sms/balance`
- **sms**:
  - `send(sender, to, message, notify_url?, scheduled_at?)` → `POST /sms/send` (`to` accepts a
    single recipient or a list)
  - `list(started_at?, ended_at?, page?, per_page?)` → `GET /sms/messages`
  - `get_status(message_id)` → `GET /sms/{id}`
- **otp**:
  - `send(to, message, sender?, expired_in?, type?, digit_size?, provider?, instance_code?)` →
    `POST /otp/send`. Client-side validation: when `provider == whatsapp`, `instance_code` is
    required (sent as `instanceCode`).
  - `verify(otp_id, code)` → `POST /otp/verify`
  - `get_status(otp_id)` → `GET /otps/{id}`
  - `list(started_at?, ended_at?, page?, limit?)` → `GET /otps`
- **whatsapp**:
  - `create_instance(name, phone_number?, webhook_url?, idempotency_key?)` →
    `POST /whatsapp/instances`
  - `list_instances(page=1)` → `GET /whatsapp/instances`
  - `get_instance(code)` → `GET /whatsapp/{code}`
  - `get_qr(code)` → `GET /whatsapp/{code}/qr` (returns raw payload)
  - `get_status(code)` → `GET /whatsapp/{code}/status` (returns raw payload)
  - `list_messages(code, started_at?, ended_at?, page?, per_page?)` →
    `GET /whatsapp/{code}/messages`
  - `get_message(message_id)` → `GET /whatsapp/messages/{id}`
  - `send_text(code, to, message, scheduled_at?)` → `POST /whatsapp/{code}/send/text`
  - `send_media(code, to, type, media_url, caption?, filename?, scheduled_at?)` →
    `POST /whatsapp/{code}/send/media`
  - `send_buttons(code, to, body, buttons, header?, footer?, scheduled_at?)` →
    `POST /whatsapp/{code}/send/buttons`
  - `send_location(code, to, latitude, longitude, name?, address?, scheduled_at?)` →
    `POST /whatsapp/{code}/send/location`
  - `send_list(code, to, body, button_text, sections, header?, footer?, scheduled_at?)` →
    `POST /whatsapp/{code}/send/list`
- **notify**:
  - `send(topic, email?, sms?, mobile_push?, web_push?, webhook?, messaging?, real_time?)` →
    `POST /notify/send`. Client-side validation: at least one event block must be present.

### Models & enums

- Read models: `Balance`, `SendSmsResult`, `SendOtpResult`, `VerifyOtpResult`, `SendWaResult`,
  `NotifyResult`, `SmsMessage`, `Otp`, `WhatsAppInstance`, `WhatsAppMessage`, and a generic
  `Paginated<T>` container exposing `items`, `total`, `per_page`, `current_page`, `next`,
  `previous`, `total_pages`.
- Enums: `MessageStatus`, `OtpStatus`, `OtpType` (`digit`/`alpha`/`alphanumeric`),
  `OtpProvider` (`sms`/`whatsapp`), `WhatsAppMediaType` (`image`/`video`/`document`/`audio`).
- Deserialization is tolerant: unknown JSON fields are ignored so new API fields do not break
  decoding. Send methods accept either the enum or its raw string value.

## Per-language idiomatic notes

- **C#**: PascalCase methods and properties; `[JsonPropertyName]` attributes map snake_case JSON;
  optional/named parameters mirror the Python signatures; client implements `IDisposable`.
- **Ruby**: snake_case methods with keyword arguments throughout; client exposes `close` and a
  block form (`Callisto::Client.new(...) { |c| ... }`) that auto-closes.
- **Java**: camelCase methods; resources accessed via accessor methods (`client.sms()`); because
  Java lacks named arguments, send methods with many optional parameters (`sms.send`, `otp.send`,
  the `whatsapp.send*` family) take a small request/options object (builder) rather than long
  overload chains; `@JsonProperty` maps snake_case JSON; client implements `AutoCloseable`.

## Testing

Each SDK exposes a seam for injecting a fake HTTP handler/transport so tests run without network
access. Coverage mirrors the existing SDKs — one test file per resource (balance, sms, otp,
whatsapp, notify) plus transport and error-mapping tests — asserting request method, path, query
params, JSON body, and the Basic-auth header, and verifying response decoding and the
status→error mapping (including `429` `retry_after`).

## Documentation

- Each SDK gets a full README documenting every method, parameter, model field, enum, and error
  in its own language, matching the depth of the existing `js/README.md`, `python/README.md`, and
  `php/README.md`.
- The root `callisto-sdk/readme.md` is updated: add C#, Ruby, and Java rows to the language table,
  install commands, and quick-start snippets.

## Out of scope

- Async/await variants beyond what is idiomatic and trivial (the existing SDKs are synchronous;
  these will be too).
- Publishing to package registries (NuGet, RubyGems, Maven Central) — the SDKs will be
  build-ready but publishing is a separate operational step.
- Retries/backoff beyond surfacing `retry_after` on `RateLimitError` (matches existing SDKs).
