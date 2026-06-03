# Callisto Go SDK

Official Go client for the **Callisto messaging API** — send SMS, one-time passwords (OTP), and
WhatsApp messages, publish multi-channel notifications, and check your account balance.

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
| `ErrorDSN`         | `CALLISTO_ERROR_DSN`  | — (error reporting disabled when absent) |
| `Environment`      | `CALLISTO_ENVIRONMENT`| —                                        |
| `ErrorSender`      | —                     | default HTTP sender (mainly for testing) |

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
- `SMS.GetStatus(ctx, messageID) (*SMSMessage, error)` — `GET /sms/{id}`.

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

Configure the DSN via `Options.ErrorDSN` or `CALLISTO_ERROR_DSN`. Absent a DSN, reporting is a
complete no-op and the SDK behaves exactly as before. Delivery is **background and best-effort** —
it never alters or delays the original error (which is still returned), and the reporter's own
failures are silently swallowed.

### Capturing panics

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

## License

MIT.
