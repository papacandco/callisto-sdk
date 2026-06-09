# Design: Go SDK for Callisto

**Date:** 2026-06-03
**Status:** Approved

## Goal

Add a seventh, feature-equivalent client SDK in **Go**, matching the existing
JS/Python/PHP/C#/Ruby/Java SDKs in surface area and behavior, including the built-in
error-reporting feature. Expressed idiomatically for Go (errors-as-values,
`context.Context`, per-call params structs, generics).

## Placement

`callisto-sdk/go/` — a subdirectory module, sibling to `js/`, `python/`, `csharp/`, `ruby/`,
`java/`.

- Module path: `github.com/papacandco/callisto-sdk/go`
- Package: `callisto`
- Go version: `go 1.21` (generics require 1.18+).
- Stdlib only: `net/http` + `encoding/json`; tests use `testing` + `net/http/httptest`. No
  third-party dependencies.

Chosen over a standalone `callisto-go` repo because it matches the five in-repo SDKs, needs no
new GitHub repository, and is trivially extractable later. (PHP is a separate repo only because
it pre-existed that way.)

## Layout

Single package `callisto` at `go/`, one file per concern:

- `client.go` — `Client`, `Options`, `NewClient`, resource wiring, public capture API, `Close`,
  `Recover`.
- `config.go` — `Options` resolution + env fallback + defaults + fail-fast validation.
- `transport.go` — HTTP transport, Basic auth, query/body handling, status→error mapping,
  reporter hook.
- `errors.go` — `Error` base + typed errors + `errorFromStatus`.
- `models.go` — read models + `Paginated[T]`.
- `enums.go` — string-typed enum constants.
- `balance.go`, `sms.go`, `otp.go`, `whatsapp.go`, `notify.go` — the five resource services.
- `reporter.go` — `ErrorReporter`, `ErrorSender`, background dispatch, payload builder.

Tests: `balance_test.go`, `sms_test.go`, `otp_test.go`, `whatsapp_test.go`, `notify_test.go`,
`transport_test.go`, `errors_test.go`, `reporter_test.go`.

## Public surface

```go
client, err := callisto.NewClient(callisto.Options{
    ClientID: "...", APIKey: "...", // or env CALLISTO_CLIENT_ID / CALLISTO_API_KEY
    // optional: BaseURL, Timeout, HTTPClient, ErrorDSN, Environment, ErrorSender
})
defer client.Close()

bal, err := client.Balance.Get(ctx, callisto.BalanceParams{})
res, err := client.SMS.Send(ctx, callisto.SendSMSParams{Sender: "Acme", To: []string{"+225..."}, Message: "Hi"})
```

- `NewClient(Options) (*Client, error)` — returns an error (never panics). Env fallback:
  `ClientID`→`CALLISTO_CLIENT_ID`, `APIKey`→`CALLISTO_API_KEY`, `BaseURL`→`CALLISTO_BASE_URL`,
  `ErrorDSN`→`CALLISTO_APP_ERROR_DSN`, `Environment`→`CALLISTO_ENVIRONMENT`. Default base URL
  `https://api.callistosignal.com/v1` (trailing slash trimmed); default timeout 30s; fail-fast
  error if `ClientID`/`APIKey` unresolved.
- Resources as exported fields: `client.Balance` (`*BalanceService`), `client.SMS`
  (`*SMSService`), `client.OTP` (`*OTPService`), `client.WhatsApp` (`*WhatsAppService`),
  `client.Notify` (`*NotifyService`).
- **Every resource method takes `ctx context.Context` as the first argument.**
- No named args → **per-call params structs** with `omitempty` JSON tags. `To []string` for SMS
  recipients (a single recipient is a one-element slice; always API-valid as an array).

### Methods (params via structs; paths/queries identical to the Python reference)

- `Balance.Get(ctx, BalanceParams{Format, Currency}) (*Balance, error)` → `GET /sms/balance`
- `SMS.Send(ctx, SendSMSParams{Sender, To, Message, NotifyURL, ScheduledAt}) (*SendSMSResult, error)`
  → `POST /sms/send`
- `SMS.List(ctx, ListSMSParams{StartedAt, EndedAt, Page, PerPage}) (*Paginated[SMSMessage], error)`
  → `GET /sms/messages`
- `SMS.GetStatus(ctx, messageID) (*SMSMessage, error)` → `GET /sms/{id}`
- `OTP.Send(ctx, SendOTPParams{To, Message, Sender, ExpiredIn, Type, DigitSize, Provider, InstanceCode})
  (*SendOTPResult, error)` → `POST /otp/send`. Client-side validation: `Provider == whatsapp`
  requires `InstanceCode` (JSON key `instanceCode`).
- `OTP.Verify(ctx, otpID, code) (*VerifyOTPResult, error)` → `POST /otp/verify`
- `OTP.GetStatus(ctx, otpID) (*OTP, error)` → `GET /otps/{id}`
- `OTP.List(ctx, ListOTPParams{StartedAt, EndedAt, Page, Limit}) (*Paginated[OTP], error)` →
  `GET /otps` (query key `limit`)
- `WhatsApp.CreateInstance(ctx, CreateInstanceParams{Name, PhoneNumber, WebhookURL, IdempotencyKey})
  (*WhatsAppInstance, error)` → `POST /whatsapp/instances`
- `WhatsApp.ListInstances(ctx, page) (*Paginated[WhatsAppInstance], error)` →
  `GET /whatsapp/instances` (default page 1)
- `WhatsApp.GetInstance(ctx, code) (*WhatsAppInstance, error)` → `GET /whatsapp/{code}`
- `WhatsApp.GetQR(ctx, code) (json.RawMessage, error)` → `GET /whatsapp/{code}/qr` (raw)
- `WhatsApp.GetStatus(ctx, code) (json.RawMessage, error)` → `GET /whatsapp/{code}/status` (raw)
- `WhatsApp.ListMessages(ctx, code, ListMessagesParams{...}) (*Paginated[WhatsAppMessage], error)`
  → `GET /whatsapp/{code}/messages`
- `WhatsApp.GetMessage(ctx, messageID) (*WhatsAppMessage, error)` → `GET /whatsapp/messages/{id}`
- `WhatsApp.SendText/SendMedia/SendButtons/SendLocation/SendList(ctx, code, <Params>) (*SendWAResult, error)`
  → `POST /whatsapp/{code}/send/{text|media|buttons|location|list}`
- `Notify.Send(ctx, NotifyParams{Topic, Email, SMS, MobilePush, WebPush, Webhook, Messaging, RealTime})
  (*NotifyResult, error)` → `POST /notify/send`. Client-side validation: at least one event block
  must be present (else `*ValidationError`). JSON keys snake_case: `email`, `sms`, `mobile_push`,
  `web_push`, `webhook`, `messaging`, `real_time`.

Null/zero query params and empty body fields are dropped before sending (`omitempty` + explicit
query filtering).

## Models & enums

- Read models as plain structs with `json` tags: `Balance`, `SendSMSResult`, `SendOTPResult`,
  `VerifyOTPResult`, `SendWAResult`, `NotifyResult`, `SMSMessage`, `OTP`, `WhatsAppInstance`,
  `WhatsAppMessage`. Generic `Paginated[T any]` with `Items`, `Total`, `PerPage`, `CurrentPage`,
  `Next`, `Previous`, `TotalPages`. Tolerant decoding is automatic (`encoding/json` ignores
  unknown fields).
- Enums as string-typed constants: `OtpType` (`digit|alpha|alphanumeric`), `OtpProvider`
  (`sms|whatsapp`), `WhatsAppMediaType` (`image|video|document|audio`), `MessageStatus`,
  `OtpStatus`. Send params accept the typed constant or a raw string (params field typed as the
  enum which is a `string`).

## Errors (Go-native)

```go
type Error struct { Message string; StatusCode int; Body any }
func (e *Error) Error() string { return e.Message }

type AuthenticationError struct{ *Error }
type ValidationError    struct{ *Error }
type NotFoundError      struct{ *Error }
type RateLimitError     struct{ *Error; RetryAfter int }
type APIError           struct{ *Error }
type NetworkError       struct{ *Error }
```

Concrete types embed `*Error` (field promotion gives `.Message/.StatusCode/.Body` and the
`Error()` method). `errorFromStatus` maps 401→Authentication, 400|422→Validation, 404→NotFound,
429→RateLimit (`RetryAfter` from the `Retry-After` header), other non-2xx→API, transport
failure→Network (status 0). Callers branch with `errors.As(err, &target)`.

## Transport

`transport` holds the config, an `*http.Client` (injectable via `Options.HTTPClient`), and an
optional `*ErrorReporter`. `request(ctx, method, path, body, query) (json.RawMessage, error)`:
sets Basic auth via `req.SetBasicAuth(clientID, apiKey)`, `Accept: application/json`, JSON body,
drops nil query params; on non-2xx parses the body `message` and returns the mapped typed error;
wraps transport failures as `*NetworkError`. Before returning any error it calls
`reporter.CaptureException(err, method, path)` (fire-and-forget). The reporter's background send
uses a fresh short-timeout context, not the caller's `ctx`.

## Error reporting (full parity, Go-translated)

`ErrorReporter` posts captured errors to the DSN (the full ingest URL
`{APP_URL}/ingest/{id}?key={public_key}`), exactly as the other SDKs.

- **Delivery:** a background goroutine draining a buffered channel; `CaptureException` /
  `CaptureMessage` enqueue and return immediately. `Flush()` drains with a short bound; `Close()`
  stops the worker. Best-effort: all own failures and any non-202 are swallowed and never
  re-captured. No-op when DSN is empty or not a well-formed URL.
- **Sender:** an exported `ErrorSender` interface (default posts via the reporter's own
  `*http.Client`); injectable through `Options.ErrorSender` for tests. The reporter never uses the
  main transport, so it never inherits Basic auth.
- **Payload** (per the ingest contract): `message`, `type` (Go type name of the error),
  `level` (`fatal|error|warning|info`), `culprit` (`"{METHOD} {path}"` for transport errors),
  `stacktrace` (from `runtime.Callers`, best-effort), `context` (`{sdk:{name,version,language:"go"},
  environment?}` + extra; for `*Error`-derived errors also `status_code`, `retry_after`, `body`),
  `request` (`{method, path}` for transport errors), `user` (from `SetUser`).
- **PII rule (hard):** never transmit `client_id`/`api_key`/the `Authorization` header/the
  outgoing request body. Only the server's error `body`, `status_code`, `method`, `path` leave the
  process. A dedicated test asserts this.
- **Public API:** `client.CaptureException(err error, opts ...CaptureOption)`,
  `client.CaptureMessage(msg string, level string)`, `client.SetUser(map[string]any)`.

### Idiomatic divergence: no global handler

Go has no process-wide uncaught-exception hook, so the `captureUnhandled` concept is replaced by
an explicit helper: `func (c *Client) Recover()`, used as `defer client.Recover()`. It recovers a
panic, reports it at `level=fatal`, then re-panics (preserving crash semantics). There is no
`CaptureUnhandled` option for Go. This is documented in the README.

## Testing

`net/http/httptest` servers mock the API. Per resource: assert request method, path, query, the
Basic-auth header, and JSON body; decode canned responses; cover the status→error mapping
(including 429 `RetryAfter`) and the two client-side validations. Reporter tests inject a fake
`ErrorSender`, use `Flush()` for determinism, and assert: posts to the DSN URL; payload
`message`/`type`/`level`; `context.sdk` + `status_code` + `request` for transport errors; **no
credential or request-body leak**; sender failures swallowed (capture never panics); no-op without
a DSN; the original error still returned. `go vet ./...` clean.

## CI

`.github/workflows/go.yml`, path-filtered to `go/**` (push to `main` + PR):
`go vet ./...` → `go build ./...` → `go test ./...`, on Go 1.21 and 1.22, with the
`actions/setup-go` build cache. Working directory `go`.

## Documentation

- `go/README.md`: full reference — every method, params struct, model field, enum, error, the
  error-reporting API, the `Recover()` helper, and the PII guarantee.
- Root `readme.md`: update "six" → "seven" implementations — add the Go table row, the
  `go get github.com/papacandco/callisto-sdk/go` install line, a Go quick-start snippet, and Go to
  the per-language guides list.

## Out of scope

- Extraction to a standalone `callisto-go` repo.
- Breadcrumbs, tags, event sampling (matches the other SDKs).
- A string-or-slice overloaded recipient type — `To []string` only.
