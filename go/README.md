# Callisto Go SDK

Official Go client for the **Callisto messaging API** — send SMS, one-time passwords (OTP), and
WhatsApp messages, publish multi-channel notifications, check your account balance, and ship your
application logs to callisto-logs.

- Module: `github.com/papacandco/callisto-sdk/go`
- Package: `callisto`
- Requires: Go 1.21+
- Dependencies: none (standard library only)

## Install

```bash
go get github.com/papacandco/callisto-sdk/go
```

```go
import callisto "github.com/papacandco/callisto-sdk/go"
```

## Quick start

```go
package main

import (
	"context"
	"fmt"
	"log"

	callisto "github.com/papacandco/callisto-sdk/go"
)

func main() {
	client, err := callisto.NewClient(callisto.Options{
		ClientID: "your-client-id",
		APIKey:   "your-api-key",
	})
	if err != nil {
		log.Fatal(err)
	}
	defer client.Close()

	ctx := context.Background()

	bal, err := client.Balance.Get(ctx, callisto.BalanceParams{})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Printf("%v %s\n", bal.Credit, bal.Currency)

	_, err = client.SMS.Send(ctx, callisto.SendSMSParams{
		Sender:  "Acme",
		To:      []string{"+2250700000000"},
		Message: "Welcome to Acme!",
	})
	if err != nil {
		log.Fatal(err)
	}
}
```

Every resource method takes a `context.Context` as its first argument, so calls honour
cancellation, deadlines, and tracing.

## Configuration

`NewClient(Options) (*Client, error)` returns an error (it never panics). Each credential falls
back to an environment variable when its `Options` field is empty.

| `Options` field    | Env var               | Default                                  |
| ------------------ | --------------------- | ---------------------------------------- |
| `ClientID`         | `CALLISTO_CLIENT_ID`  | — (required)                             |
| `APIKey`           | `CALLISTO_API_KEY`    | — (required)                             |
| `BaseURL`          | `CALLISTO_BASE_URL`   | `https://api.callistosignal.com/v1`      |
| `Timeout`          | —                     | `30s`                                    |
| `HTTPClient`       | —                     | a `*http.Client` with the timeout above  |
| `ErrorDSN`         | `CALLISTO_APP_ERROR_DSN`  | — (error reporting disabled when absent) |
| `Environment`      | `CALLISTO_ENVIRONMENT`| —                                        |
| `ErrorSender`      | —                     | default HTTP sender (mainly for testing) |
| `LogsAccessKey`, `LogsSecret`, `LogsEndpoint`, `ServiceName`, `LogsFlushTimeout`, `LogSender` | see [Logs](#logs) | `client.Logs` is a no-op without a Logs key |

If `ClientID` or `APIKey` cannot be resolved, `NewClient` returns an error. Any trailing slash on
the base URL is trimmed. Credentials are sent as HTTP Basic auth (`base64(client_id:api_key)`) on
every request — you never build the header yourself.

## Resources

### Balance

```go
bal, err := client.Balance.Get(ctx, callisto.BalanceParams{Format: "full", Currency: "XOF"})
```

- `Balance.Get(ctx, BalanceParams{Format, Currency}) (*Balance, error)` — `GET /sms/balance`.
  `Format` defaults to `"full"`.

### SMS

```go
res, err := client.SMS.Send(ctx, callisto.SendSMSParams{
	Sender: "Acme", To: []string{"+2250700000000"}, Message: "Hi", NotifyURL: "https://hook",
})
page, err := client.SMS.List(ctx, callisto.ListSMSParams{Page: callisto.Int(1)})
msg, err := client.SMS.GetStatus(ctx, "message-id")
```

- `SMS.Send(ctx, SendSMSParams{Sender, To, Message, NotifyURL, ScheduledAt}) (*SendSMSResult, error)`
  — `POST /sms/send`. `To` is a slice; a single recipient is a one-element slice.
- `SMS.List(ctx, ListSMSParams{StartedAt, EndedAt, Page, PerPage}) (*Paginated[SMSMessage], error)`
  — `GET /sms/messages`.
- `SMS.GetStatus(ctx, messageID) (*SMSMessage, error)` — `GET /sms/{id}`. An id that does not
  exist, belongs to another organization or is not a UUID returns a `*NotFoundError` (HTTP 404),
  the same in all three cases.

### OTP

```go
res, err := client.OTP.Send(ctx, callisto.SendOTPParams{
	To: "+2250700000000", Message: "Your code is {code}", Type: callisto.OtpTypeDigit,
})
v, err := client.OTP.Verify(ctx, res.ID, "123456")
```

- `OTP.Send(ctx, SendOTPParams{To, Message, Sender, ExpiredIn, Type, DigitSize, Provider, InstanceCode}) (*SendOTPResult, error)`
  — `POST /otp/send`. When `Provider` is `OtpProviderWhatsApp`, `InstanceCode` is required (sent as
  the JSON key `instanceCode`); otherwise a `*ValidationError` is returned before any request.
- `OTP.Verify(ctx, otpID, code) (*VerifyOTPResult, error)` — `POST /otp/verify`.
- `OTP.GetStatus(ctx, otpID) (*OTP, error)` — `GET /otps/{id}`.
- `OTP.List(ctx, ListOTPParams{StartedAt, EndedAt, Page, Limit}) (*Paginated[OTP], error)` —
  `GET /otps` (note the `limit` query key).

### WhatsApp

```go
inst, err := client.WhatsApp.CreateInstance(ctx, callisto.CreateInstanceParams{Name: "Sales"})
qr, err := client.WhatsApp.GetQR(ctx, inst.Code)              // json.RawMessage
_, err = client.WhatsApp.SendText(ctx, inst.Code, callisto.SendTextParams{To: "+225...", Message: "hi"})
```

- `CreateInstance(ctx, CreateInstanceParams{Name, PhoneNumber, WebhookURL, IdempotencyKey}) (*WhatsAppInstance, error)`
  — `POST /whatsapp/instances`.
- `ListInstances(ctx, page) (*Paginated[WhatsAppInstance], error)` — `GET /whatsapp/instances`
  (`page` defaults to 1 when ≤ 0).
- `GetInstance(ctx, code) (*WhatsAppInstance, error)` — `GET /whatsapp/{code}`.
- `GetQR(ctx, code) (json.RawMessage, error)` — `GET /whatsapp/{code}/qr` (raw payload).
- `GetStatus(ctx, code) (json.RawMessage, error)` — `GET /whatsapp/{code}/status` (raw payload).
- `ListMessages(ctx, code, ListMessagesParams{StartedAt, EndedAt, Page, PerPage}) (*Paginated[WhatsAppMessage], error)`
  — `GET /whatsapp/{code}/messages`.
- `GetMessage(ctx, messageID) (*WhatsAppMessage, error)` — `GET /whatsapp/messages/{id}`.
- `SendText(ctx, code, SendTextParams{To, Message, ScheduledAt}) (*SendWAResult, error)` —
  `POST /whatsapp/{code}/send/text`.
- `SendMedia(ctx, code, SendMediaParams{To, Type, MediaURL, Caption, Filename, ScheduledAt})` —
  `POST /whatsapp/{code}/send/media`.
- `SendButtons(ctx, code, SendButtonsParams{To, Body, Buttons, Header, Footer, ScheduledAt})` —
  `POST /whatsapp/{code}/send/buttons`.
- `SendLocation(ctx, code, SendLocationParams{To, Latitude, Longitude, Name, Address, ScheduledAt})` —
  `POST /whatsapp/{code}/send/location`. `Latitude`/`Longitude` are always sent (0 is a valid
  coordinate).
- `SendList(ctx, code, SendListParams{To, Body, ButtonText, Sections, Header, Footer, ScheduledAt})` —
  `POST /whatsapp/{code}/send/list`.

### Notify

```go
_, err := client.Notify.Send(ctx, callisto.NotifyParams{
	Topic: "orders",
	Email: []any{map[string]any{"to": "a@b.com", "subject": "Shipped"}},
})
```

- `Notify.Send(ctx, NotifyParams{Topic, Email, SMS, MobilePush, WebPush, Webhook, Messaging, RealTime}) (*NotifyResult, error)`
  — `POST /notify/send`. At least one event block must be present; otherwise a `*ValidationError`
  is returned before any request. JSON keys are snake_case (`mobile_push`, `web_push`, `real_time`).

## Pagination

List methods return `*Paginated[T]`:

```go
type Paginated[T any] struct {
	Items       []T
	Total       int
	PerPage     int
	CurrentPage int
	Next        *int // nil on the last page
	Previous    *int
	TotalPages  int
}
```

Read models tolerate unknown fields (Go's `encoding/json` ignores them), so new API fields won't
break decoding. `WhatsApp.GetQR` / `GetStatus` return the raw `json.RawMessage` payload.

## Enums

String-typed constants; send params accept the typed constant or a raw string:

- `OtpType`: `OtpTypeDigit`, `OtpTypeAlpha`, `OtpTypeAlphanumeric`
- `OtpProvider`: `OtpProviderSMS`, `OtpProviderWhatsApp`
- `WhatsAppMediaType`: `WhatsAppMediaTypeImage`, `…Video`, `…Document`, `…Audio`
- `MessageStatus`, `OtpStatus`

## Errors

Every SDK error embeds `*CallistoError`, which carries `Message`, `StatusCode`, and the decoded
response `Body`. Branch with `errors.As`:

```go
_, err := client.SMS.Send(ctx, params)

var rle *callisto.RateLimitError
var auth *callisto.AuthenticationError
switch {
case errors.As(err, &rle):
	time.Sleep(time.Duration(rle.RetryAfter) * time.Second)
case errors.As(err, &auth):
	// bad credentials
}

// Or read the base fields generically:
var ce *callisto.CallistoError
if errors.As(err, &ce) {
	log.Printf("callisto error %d: %s", ce.StatusCode, ce.Message)
}
```

| Type                   | Condition                              |
| ---------------------- | -------------------------------------- |
| `AuthenticationError`  | HTTP 401                               |
| `ValidationError`      | HTTP 400/422, or client-side validation |
| `NotFoundError`        | HTTP 404                               |
| `RateLimitError`       | HTTP 429 (`RetryAfter` in seconds)     |
| `APIError`             | other non-2xx                          |
| `NetworkError`         | transport failure (`StatusCode == 0`)  |

## Error reporting

The SDK ships with an opt-in, Sentry-style error reporter that POSTs captured errors to a Callisto
error-tracking ingest endpoint (a **DSN**). It auto-captures the SDK's own errors (API, network,
and client-side validation) and exposes a small API for your own exceptions.

```go
client, _ := callisto.NewClient(callisto.Options{
	ClientID: "...", APIKey: "...",
	ErrorDSN:    "https://app.callistosignal.com/ingest/<app-id>?key=<public-key>",
	Environment: "production",
})
defer client.Close() // flushes pending events

client.SetUser(map[string]any{"id": "user-123"})
client.CaptureMessage("checkout started", "info")
client.CaptureException(err, callisto.WithLevel("warning"), callisto.WithContext(map[string]any{"order": 42}))
```

Configure the DSN via `Options.ErrorDSN` or `CALLISTO_APP_ERROR_DSN`. The DSN must be `https`
(plain `http` only for `localhost`, `127.0.0.1`, `::1` and `*.localhost`); any other DSN is ignored
like an absent one. Absent a DSN, reporting is a
complete no-op and the SDK behaves exactly as before. Delivery is **background and best-effort** —
it never alters or delays the original error (which is still returned), and the reporter's own
failures are silently swallowed.

### Without the API client (DSN only)

Error reporting is independent of the API client. When you only want to report errors — no SMS / OTP
/ WhatsApp calls — build a standalone reporter with `NewErrorReporter`. It needs **only a DSN** (no
`ClientID` / `APIKey`) and exposes the same capture API:

```go
reporter := callisto.NewErrorReporter(callisto.ReporterOptions{
	DSN:         "https://app.callistosignal.com/ingest/<app-id>?key=<public-key>",
	Environment: "production",
})
defer reporter.Close() // flushes pending events

reporter.SetUser(map[string]any{"id": "user-123"})
reporter.CaptureMessage("checkout started", "info", nil)
reporter.CaptureException(err, callisto.WithLevel("warning"), callisto.WithContext(map[string]any{"order": 42}))
```

An empty/invalid `DSN` yields a no-op reporter, so it is safe to construct unconditionally.

### Capturing panics

`Recover` is available on both the `Client` and a standalone reporter — `defer reporter.Recover()`
works identically to `defer client.Recover()`.

Go has no process-wide uncaught-exception hook, so instead of an auto-installed global handler the
SDK provides an explicit helper. Defer it at the top of a goroutine or request handler:

```go
func handle() {
	defer client.Recover() // reports an in-flight panic at level "fatal", then re-panics
	// ... code that may panic ...
}
```

### PII guarantee

The reporter uses its own HTTP client (never the authenticated transport) and **never** transmits
your client ID, API key, the `Authorization` header, or the outgoing request body (which carries
phone numbers and message content). Only the server's error `body`, `status_code`, HTTP `method`,
and request `path` ever leave the process.

## Logs

The SDK can ship your application's own log lines to **callisto-logs** (OTLP/HTTP JSON), with no
OpenTelemetry SDK. Records are queued in memory and sent in batches by one background goroutine. A
log call never blocks on the network and never panics. If you already run an OpenTelemetry SDK or
Collector, keep using it. This feature is for apps that do not.

### Create a Logs key

In the dashboard, open **Organization → API keys** (or **Monitoring → Logs → Ingest keys**) and create
an API key with the **Logs** scope. You get an access key `pk_…` (32 hex characters) and a secret
`sk_…` (48 hex characters). A messaging API key cannot ship logs, and a Logs key cannot call the
messaging API.

### Configuration

Every setting is optional and falls back to an environment variable:

| `LoggerConfig` field | `Options` field    | Env var                                         | Default                                   |
| -------------------- | ------------------ | ----------------------------------------------- | ----------------------------------------- |
| `AccessKey`          | `LogsAccessKey`    | `CALLISTO_LOGS_ACCESS_KEY`                      | none                                      |
| `Secret`             | `LogsSecret`       | `CALLISTO_LOGS_SECRET`                          | none                                      |
| `Endpoint`           | `LogsEndpoint`     | `CALLISTO_LOGS_ENDPOINT`                        | `https://logs.callistosignal.com/v1/logs` |
| `ServiceName`        | `ServiceName`      | `CALLISTO_SERVICE_NAME`, then `OTEL_SERVICE_NAME` | `unknown_service`                       |
| `Environment`        | `Environment`      | `CALLISTO_ENVIRONMENT`                          | none (attribute omitted)                  |
| `FlushTimeout`       | `LogsFlushTimeout` | none                                            | `5s`                                      |
| `HTTPClient`         | none               | none                                            | its own `*http.Client`, 10s timeout       |
| `Sender`             | `LogSender`        | none                                            | default HTTP sender (mainly for testing)  |
| `Clock`              | none               | none                                            | `time.Now` (mainly for testing)           |

- No access key and no secret: logging is **disabled**. Every call is a cheap no-op, nothing is
  sent, and no goroutine is started.
- **HTTPS only.** The endpoint must be an absolute `https` URL: the secret travels in the
  `Authorization` header. Plain `http` is accepted only for `localhost`, `127.0.0.1`, `::1` and
  `*.localhost` (local development). The error DSN follows the same rule.
- Invalid settings are: only one of the two, a value that is not `pk_` + 32 lowercase hex /
  `sk_` + 48 lowercase hex, or an endpoint that breaks the HTTPS rule. What happens then depends on
  who builds the logger:
  - **`NewLogger` (standalone)** returns a configuration error, so a typo cannot silently lose
    every log.
  - **`NewClient`** never fails because of the Logs settings. It writes **one** warning through the
    standard `log` package (`log.Printf`, so it follows `log.SetOutput` / `slog.SetDefault`), and
    `client.Logs` is a disabled no-op logger; SMS, OTP and WhatsApp keep working. The warning never
    contains the key or the secret. Missing messaging credentials still return an error.

### Quick start

Standalone (no messaging credentials needed):

```go
logger, err := callisto.NewLogger(callisto.LoggerConfig{
	AccessKey:   "pk_…", // or CALLISTO_LOGS_ACCESS_KEY
	Secret:      "sk_…", // or CALLISTO_LOGS_SECRET
	ServiceName: "checkout",
	Environment: "production",
})
if err != nil {
	log.Fatal(err)
}
defer logger.Close(context.Background()) // sends what is queued, then stops

logger.Info("order paid", map[string]any{"order_id": 42, "paid": true})
logger.Warn("slow payment provider", map[string]any{"provider": "hub2", "ms": 2300})
logger.Log(callisto.LogLevelError, "refund failed", nil)
```

On the messaging client, the same logger is `client.Logs`, built from the `Logs*` options (or the
env vars). It is a no-op logger when no Logs key resolves or the Logs settings are invalid (one
warning, see above), and `client.Close()` also closes it. The
messaging credentials are never used for logs, and the Logs secret never goes to the messaging API.

```go
client, _ := callisto.NewClient(callisto.Options{
	ClientID: "...", APIKey: "...",
	LogsAccessKey: "pk_…", LogsSecret: "sk_…",
	ServiceName: "checkout",
})
defer client.Close()

client.Logs.Info("sms sent", map[string]any{"count": 3})
```

Methods: `Debug`, `Info`, `Warn`, `Error`, `Fatal(msg string, attrs map[string]any)`,
`Log(level, msg, attrs)`, `Flush(ctx) error`, `Close(ctx) error`, `DroppedCount() uint64`,
`Enabled() bool`. `Fatal` records a `FATAL` line; unlike `log.Fatal` it does **not** exit the
process. `attrs` may be nil or nested: strings, bools, integers (sent as OTLP `intValue`), floats,
slices and maps are mapped to OTLP values, nil values are left out, and anything else is turned
into a string. The body is cut at 32 768 characters, string values at 8 192 (the cut ends with
`…`), a record keeps at most 128 attributes (the first 128 by key), and each nested slice or map
keeps at most 128 items (the first 128; map entries by key). A record encodes at most 2 048 values
in total (scalars and containers, at every level; a slice or map referenced several times counts
each time): once that budget is spent, the remaining values and attributes are dropped. Values
nested deeper than 5 levels are written as JSON within the same budget.

### Trace correlation

Two reserved attributes tie a log to a trace, and so to an error issue (which carries
`context.trace.trace_id`):

- `trace_id`: 32 hex characters, any case, not all zeros;
- `span_id`: 16 hex characters, not all zeros.

When valid they become the record's `traceId` / `spanId` (lowercased) and are removed from the
attributes. Otherwise they stay as ordinary attributes.

```go
logger.Error("payment declined", map[string]any{
	"trace_id": span.SpanContext().TraceID().String(),
	"span_id":  span.SpanContext().SpanID().String(),
})
```

### The `log/slog` handler

`(*Logger).Handler()` returns a standard-library `slog.Handler`:

```go
slog.SetDefault(slog.New(logger.Handler()))

slog.With("app", "checkout").WithGroup("http").
	Info("request done", "status", 201, "trace_id", traceID)
slog.Error("config failed", "err", err)
```

- Levels: below `Info` → debug, `Info` → info, `Warn` → warn, `Error` and above → error.
- Attributes become record attributes and groups become nested maps (`http` → `{status: 201}`).
  `WithAttrs` / `WithGroup`, inline groups (`slog.Group("", …)`) and `slog.LogValuer` work.
  Empty groups are left out.
- A top-level `trace_id` / `span_id` attribute is used for trace correlation, as above.
- The first top-level attribute holding an `error` also sets `exception.type` (the Go type, for
  example `*fs.PathError`) and `exception.message`.
- Records emitted from inside the logger's own send (for example by an HTTP round-tripper that
  logs through the same handler) are dropped, never shipped again.
- The handler does not filter by level. To ship only `Info` and above, filter before it.

### Delivery semantics

- The queue holds at most **2 000** records. When it is full, new records are dropped and counted
  in `DroppedCount()`, which also counts records lost to a rejected or failed batch.
- A batch is sent when 100 records are queued, every **5 s** otherwise, and on `Flush` / `Close`.
  A batch holds at most 500 records or about 1 MB of JSON.
- `429` and `503` are retried after `Retry-After` (seconds, capped at 60 s, 5 s when missing),
  and network errors after 1 s then 5 s, up to **3** attempts in total. Then the batch is dropped.
  New records keep queueing while a batch waits for its retry.
- `400`, `413`, `415` and any other unexpected status drop the batch at once.
- `401` / `403` (bad key, or a plan without logs or suspended) drop the batch and **pause sending
  for 60 s**. Records logged during the pause are dropped, then one new attempt is allowed.
- `Flush(ctx)` and `Close(ctx)` are bounded by `ctx` and `FlushTimeout` (5 s by default). They
  return the context error when the bound was hit, never a delivery error. Call `Close` before the
  process exits, or the last records (up to 5 s of logs) are lost.
- Each request has a 10 s HTTP timeout and carries `Authorization: Basic …`,
  `Content-Type: application/json` and `User-Agent: callisto-sdk-go/<version>`.
- The logger's own failures are swallowed. They never reach the error reporter and are never
  logged through the logger itself.

### Do not log secrets

**Do not log passwords, tokens, card numbers or other secrets.** The SDK does not scrub what you
send, and callisto-logs stores it as received: nothing is masked. Remove secrets and personal data
you do not need before logging. This mirrors the platform terms (CGU 7.6).

## License

MIT.
