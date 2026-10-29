# callisto-sdk (C#)

Official Callisto messaging API SDK for .NET.

## Requirements

- .NET 8 (`net8.0`).
- No third-party runtime dependencies — built on `System.Net.Http.HttpClient` and `System.Text.Json`.

## Install

```bash
dotnet add package Callisto.Sdk
```

NuGet package id: `Callisto.Sdk`. Root namespace: `Callisto.Sdk`.

## Configuration

Create a `CallistoClient` with your credentials. Authentication is HTTP Basic (`clientId` / `apiKey`) and is applied automatically to every request.

```csharp
using Callisto.Sdk;

using var callisto = new CallistoClient(
    clientId: "your-client-id",
    apiKey: "your-api-key");
```

### Constructor

```csharp
new CallistoClient(
    string? clientId = null,
    string? apiKey = null,
    string? baseUrl = null,
    TimeSpan? timeout = null,
    HttpClient? httpClient = null,
    HttpMessageHandler? handler = null)
```

| Param | Type | Default | Description |
| --- | --- | --- | --- |
| `clientId` | `string?` | `null` | Your Callisto client ID. Falls back to env `CALLISTO_CLIENT_ID`. Required. |
| `apiKey` | `string?` | `null` | Your Callisto API key. Falls back to env `CALLISTO_API_KEY`. Required. |
| `baseUrl` | `string?` | `https://api.callistosignal.com/v1` | API base URL. Falls back to env `CALLISTO_BASE_URL`. Trailing slash is stripped. |
| `timeout` | `TimeSpan?` | `30s` | Request timeout. |
| `httpClient` | `HttpClient?` | `null` | Optional pre-configured `HttpClient` to inject. Basic auth, `Accept`, and timeout are still applied to it. |
| `handler` | `HttpMessageHandler?` | `null` | Optional message handler the internal `HttpClient` is built on (useful for testing with a fake handler). Ignored when `httpClient` is supplied. |

`clientId` and `apiKey` are required: pass them as arguments or via the `CALLISTO_CLIENT_ID` / `CALLISTO_API_KEY` environment variables. If neither is available, the constructor throws `ArgumentException`.

### Environment variables

| Variable | Maps to |
| --- | --- |
| `CALLISTO_CLIENT_ID` | `clientId` |
| `CALLISTO_API_KEY` | `apiKey` |
| `CALLISTO_BASE_URL` | `baseUrl` |
| `CALLISTO_LOGS_ACCESS_KEY`, `CALLISTO_LOGS_SECRET`, `CALLISTO_LOGS_ENDPOINT`, `CALLISTO_SERVICE_NAME` | `logsAccessKey`, `logsSecret`, `logsEndpoint`, `serviceName` (see [Logs](#logs)) |

```csharp
Environment.SetEnvironmentVariable("CALLISTO_CLIENT_ID", "your-client-id");
Environment.SetEnvironmentVariable("CALLISTO_API_KEY", "your-api-key");

using var callisto = new CallistoClient(); // reads credentials from the environment
```

### Lifecycle

`CallistoClient` implements `IDisposable` and owns an `HttpClient`. Use a `using` declaration/block, or call `.Dispose()` explicitly to release the connection pool.

```csharp
using (var callisto = new CallistoClient(clientId: "...", apiKey: "..."))
{
    var balance = callisto.Balance.Get();
}
// connection closed automatically on exit
```

## Quick start

```csharp
using Callisto.Sdk;

using var callisto = new CallistoClient(clientId: "your-client-id", apiKey: "your-api-key");

// Check your balance
var balance = callisto.Balance.Get();
Console.WriteLine($"Credit: {balance.Credit} {balance.Currency}");

// Send an SMS
var result = callisto.Sms.Send(
    sender: "Acme",
    to: "+2250700000000",
    message: "Welcome to Acme!");
Console.WriteLine(result.Status);
```

## Resources

Each resource is accessed as a property on the client: `callisto.Balance`, `callisto.Sms`, `callisto.Otp`, `callisto.WhatsApp`, `callisto.Notify`.

### Balance

#### `Balance.Get(string format = "full", string? currency = null)`

Returns the account balance. Returns [`Balance`](#balance-1). `GET /sms/balance`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `format` | `string` | No | Response format. Defaults to `"full"`. |
| `currency` | `string?` | No | Filter the balance by currency code. |

```csharp
var balance = callisto.Balance.Get();
Console.WriteLine($"{balance.Credit} {balance.Currency}");
```

### Sms

#### `Sms.Send(string sender, string to, string message, string? notifyUrl = null, string? scheduledAt = null)`
#### `Sms.Send(string sender, IEnumerable<string> to, string message, string? notifyUrl = null, string? scheduledAt = null)`

Sends an SMS to one or more recipients. Returns [`SendSmsResult`](#sendsmsresult). `POST /sms/send`. There are two overloads: one accepting a single recipient `string`, one accepting an `IEnumerable<string>` of recipients.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `sender` | `string` | Yes | Approved sender name. |
| `to` | `string` or `IEnumerable<string>` | Yes | A single recipient number, or a collection of numbers. |
| `message` | `string` | Yes | Message body. |
| `notifyUrl` | `string?` | No | Webhook URL for delivery status callbacks. |
| `scheduledAt` | `string?` | No | Schedule delivery (e.g. `"2026-06-02 10:00:00"`). |

```csharp
var result = callisto.Sms.Send(
    sender: "Acme",
    to: "+2250700000000",
    message: "Your code is 1234");

// Bulk + scheduled
callisto.Sms.Send(
    sender: "Acme",
    to: new[] { "+2250700000000", "+2250700000001" },
    message: "Sale starts tomorrow!",
    notifyUrl: "https://example.com/webhooks/sms",
    scheduledAt: "2026-06-02 10:00:00");
```

#### `Sms.List(string? startedAt = null, string? endedAt = null, int? page = null, int? perPage = null)`

Lists sent SMS messages. Returns [`Paginated<SmsMessage>`](#paginatedt). `GET /sms/messages`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `startedAt` | `string?` | No | Filter from this date/time. |
| `endedAt` | `string?` | No | Filter up to this date/time. |
| `page` | `int?` | No | Page number. |
| `perPage` | `int?` | No | Items per page. |

```csharp
var page = callisto.Sms.List(page: 1, perPage: 50);
foreach (var msg in page.Items)
    Console.WriteLine($"{msg.Id} {msg.Status}");
```

#### `Sms.GetStatus(string messageId)`

Fetches a single SMS by ID. Returns [`SmsMessage`](#smsmessage). `GET /sms/{id}`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `messageId` | `string` | Yes | The message ID. |

```csharp
var msg = callisto.Sms.GetStatus("abc");
Console.WriteLine(msg.Status);
```

An id that does not exist, belongs to another organization or is not a UUID throws `NotFoundException` (HTTP 404), the same in all three cases.

### Otp

#### `Otp.Send(string to, string message, string? sender = null, int? expiredIn = null, OtpType? type = null, int? digitSize = null, OtpProvider? provider = null, string? instanceCode = null)`

There is also an overload taking `string? type` and `string? provider` so you can pass the raw wire values. Generates and sends a one-time password. Returns [`SendOtpResult`](#sendotpresult). `POST /otp/send`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `to` | `string` | Yes | Recipient number. |
| `message` | `string` | Yes | Message template (the generated code is interpolated by the API). |
| `sender` | `string?` | No | Sender name. |
| `expiredIn` | `int?` | No | Code lifetime in seconds. |
| `type` | [`OtpType`](#enums)? or `string?` | No | Code character set (`digit`, `alpha`, `alphanumeric`). Accepts the enum or a raw string. |
| `digitSize` | `int?` | No | Number of characters in the code. |
| `provider` | [`OtpProvider`](#enums)? or `string?` | No | Delivery channel (`sms` or `whatsapp`). Accepts the enum or a raw string. |
| `instanceCode` | `string?` | No | WhatsApp instance code. **Required when `provider` is `whatsapp`** — otherwise throws [`ValidationException`](#error-handling) before any request is made. Sent to the API as `instanceCode`. |

```csharp
using Callisto.Sdk.Enums;

var result = callisto.Otp.Send(
    to: "+2250700000000",
    message: "Your Acme code is {code}",
    type: OtpType.Digit,
    digitSize: 6,
    expiredIn: 300);
Console.WriteLine(result.Id);

// Over WhatsApp (instanceCode required)
callisto.Otp.Send(
    to: "+2250700000000",
    message: "Your Acme code is {code}",
    provider: OtpProvider.WhatsApp,
    instanceCode: "inst_1");
```

#### `Otp.Verify(string otpId, string code)`

Verifies a code against an OTP. Returns [`VerifyOtpResult`](#verifyotpresult). `POST /otp/verify`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `otpId` | `string` | Yes | The OTP ID returned by `Send`. |
| `code` | `string` | Yes | The code submitted by the user. |

```csharp
var result = callisto.Otp.Verify(otpId: "otp_123", code: "123456");
if (result.Verified)
    Console.WriteLine("Verified!");
```

#### `Otp.GetStatus(string otpId)`

Fetches a single OTP by ID. Returns [`Otp`](#otp). `GET /otps/{id}`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `otpId` | `string` | Yes | The OTP ID. |

```csharp
var otp = callisto.Otp.GetStatus("otp_123");
Console.WriteLine(otp.Status);
```

#### `Otp.List(string? startedAt = null, string? endedAt = null, int? page = null, int? limit = null)`

Lists OTPs. Returns [`Paginated<Otp>`](#paginatedt). `GET /otps` (the page-size query key is `limit`).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `startedAt` | `string?` | No | Filter from this date/time. |
| `endedAt` | `string?` | No | Filter up to this date/time. |
| `page` | `int?` | No | Page number. |
| `limit` | `int?` | No | Items per page. |

```csharp
var page = callisto.Otp.List(page: 1, limit: 20);
foreach (var otp in page.Items)
    Console.WriteLine($"{otp.Id} {otp.Status}");
```

### WhatsApp

#### `WhatsApp.CreateInstance(string name, string? phoneNumber = null, string? webhookUrl = null, string? idempotencyKey = null)`

Creates a WhatsApp instance. Returns [`WhatsAppInstance`](#whatsappinstance). `POST /whatsapp/instances`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | `string` | Yes | Instance display name. |
| `phoneNumber` | `string?` | No | Phone number to attach. |
| `webhookUrl` | `string?` | No | Webhook URL for incoming events. |
| `idempotencyKey` | `string?` | No | Key to safely retry creation. |

```csharp
var instance = callisto.WhatsApp.CreateInstance(
    name: "Main",
    phoneNumber: "+2250700000000",
    webhookUrl: "https://example.com/webhooks/whatsapp");
Console.WriteLine($"{instance.Code} {instance.Status}");
```

#### `WhatsApp.ListInstances(int page = 1)`

Lists WhatsApp instances. Returns [`Paginated<WhatsAppInstance>`](#paginatedt). `GET /whatsapp/instances`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `page` | `int` | No | Page number. Defaults to `1`. |

```csharp
var page = callisto.WhatsApp.ListInstances(page: 1);
foreach (var inst in page.Items)
    Console.WriteLine($"{inst.Code} {inst.Name}");
```

#### `WhatsApp.GetInstance(string code)`

Fetches a single instance. Returns [`WhatsAppInstance`](#whatsappinstance). `GET /whatsapp/{code}`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `string` | Yes | Instance code. |

```csharp
var instance = callisto.WhatsApp.GetInstance("inst_1");
```

#### `WhatsApp.GetQr(string code)`

Fetches the QR code used to link the instance. Returns the **raw `JsonElement?`** from the API (no typed model). `GET /whatsapp/{code}/qr`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `string` | Yes | Instance code. |

```csharp
var qr = callisto.WhatsApp.GetQr("inst_1");
Console.WriteLine(qr?.GetProperty("qr_code").GetString());
```

#### `WhatsApp.GetStatus(string code)`

Fetches the connection status of an instance. Returns the **raw `JsonElement?`** from the API (no typed model). `GET /whatsapp/{code}/status`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `string` | Yes | Instance code. |

```csharp
var status = callisto.WhatsApp.GetStatus("inst_1");
Console.WriteLine(status?.GetProperty("status").GetString());
```

#### `WhatsApp.ListMessages(string code, string? startedAt = null, string? endedAt = null, int? page = null, int? perPage = null)`

Lists messages for an instance. Returns [`Paginated<WhatsAppMessage>`](#paginatedt). `GET /whatsapp/{code}/messages`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `string` | Yes | Instance code. |
| `startedAt` | `string?` | No | Filter from this date/time. |
| `endedAt` | `string?` | No | Filter up to this date/time. |
| `page` | `int?` | No | Page number. |
| `perPage` | `int?` | No | Items per page. |

```csharp
var page = callisto.WhatsApp.ListMessages("inst_1", page: 1);
foreach (var msg in page.Items)
    Console.WriteLine($"{msg.Id} {msg.Status}");
```

#### `WhatsApp.GetMessage(string messageId)`

Fetches a single WhatsApp message. Returns [`WhatsAppMessage`](#whatsappmessage). `GET /whatsapp/messages/{id}`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `messageId` | `string` | Yes | The message ID. |

```csharp
var msg = callisto.WhatsApp.GetMessage("msg_9");
Console.WriteLine($"{msg.Status} {msg.Cost}");
```

#### `WhatsApp.SendText(string code, string to, string message, string? scheduledAt = null)`

Sends a text message. Returns [`SendWaResult`](#sendwaresult). `POST /whatsapp/{code}/send/text`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `string` | Yes | Instance code. |
| `to` | `string` | Yes | Recipient number. |
| `message` | `string` | Yes | Message body. |
| `scheduledAt` | `string?` | No | Schedule delivery. |

```csharp
var result = callisto.WhatsApp.SendText("inst_1", to: "+2250700000000", message: "Hi!");
```

#### `WhatsApp.SendMedia(string code, string to, WhatsAppMediaType type, string mediaUrl, string? caption = null, string? filename = null, string? scheduledAt = null)`

Also has an overload taking `string type` for the raw wire value. Sends a media message. Returns [`SendWaResult`](#sendwaresult). `POST /whatsapp/{code}/send/media`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `string` | Yes | Instance code. |
| `to` | `string` | Yes | Recipient number. |
| `type` | [`WhatsAppMediaType`](#enums) or `string` | Yes | Media type (`image`, `video`, `document`, `audio`). Accepts the enum or a raw string. |
| `mediaUrl` | `string` | Yes | Publicly accessible media URL. |
| `caption` | `string?` | No | Caption text. |
| `filename` | `string?` | No | File name (useful for documents). |
| `scheduledAt` | `string?` | No | Schedule delivery. |

```csharp
using Callisto.Sdk.Enums;

callisto.WhatsApp.SendMedia(
    "inst_1",
    to: "+2250700000000",
    type: WhatsAppMediaType.Image,
    mediaUrl: "https://example.com/promo.jpg",
    caption: "New arrivals");
```

#### `WhatsApp.SendButtons(string code, string to, string body, IEnumerable<object> buttons, string? header = null, string? footer = null, string? scheduledAt = null)`

Sends an interactive buttons message. Returns [`SendWaResult`](#sendwaresult). `POST /whatsapp/{code}/send/buttons`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `string` | Yes | Instance code. |
| `to` | `string` | Yes | Recipient number. |
| `body` | `string` | Yes | Message body. |
| `buttons` | `IEnumerable<object>` | Yes | Button objects (e.g. `new { id = "1", title = "Yes" }`). |
| `header` | `string?` | No | Header text. |
| `footer` | `string?` | No | Footer text. |
| `scheduledAt` | `string?` | No | Schedule delivery. |

```csharp
callisto.WhatsApp.SendButtons(
    "inst_1",
    to: "+2250700000000",
    body: "Confirm your order?",
    buttons: new object[]
    {
        new { id = "yes", title = "Yes" },
        new { id = "no", title = "No" },
    });
```

#### `WhatsApp.SendLocation(string code, string to, double latitude, double longitude, string? name = null, string? address = null, string? scheduledAt = null)`

Sends a location message. Returns [`SendWaResult`](#sendwaresult). `POST /whatsapp/{code}/send/location`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `string` | Yes | Instance code. |
| `to` | `string` | Yes | Recipient number. |
| `latitude` | `double` | Yes | Latitude. |
| `longitude` | `double` | Yes | Longitude. |
| `name` | `string?` | No | Location name. |
| `address` | `string?` | No | Address text. |
| `scheduledAt` | `string?` | No | Schedule delivery. |

```csharp
callisto.WhatsApp.SendLocation(
    "inst_1",
    to: "+2250700000000",
    latitude: 5.3599517,
    longitude: -4.0082563,
    name: "Acme HQ",
    address: "Abidjan, Côte d'Ivoire");
```

#### `WhatsApp.SendList(string code, string to, string body, string buttonText, IEnumerable<object> sections, string? header = null, string? footer = null, string? scheduledAt = null)`

Sends an interactive list message. Returns [`SendWaResult`](#sendwaresult). `POST /whatsapp/{code}/send/list`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `string` | Yes | Instance code. |
| `to` | `string` | Yes | Recipient number. |
| `body` | `string` | Yes | Message body. |
| `buttonText` | `string` | Yes | Label of the list trigger button. |
| `sections` | `IEnumerable<object>` | Yes | Section objects (each with rows). |
| `header` | `string?` | No | Header text. |
| `footer` | `string?` | No | Footer text. |
| `scheduledAt` | `string?` | No | Schedule delivery. |

```csharp
callisto.WhatsApp.SendList(
    "inst_1",
    to: "+2250700000000",
    body: "Pick a plan",
    buttonText: "View plans",
    sections: new object[]
    {
        new
        {
            title = "Plans",
            rows = new object[]
            {
                new { id = "basic", title = "Basic" },
                new { id = "pro", title = "Pro" },
            },
        },
    });
```

### Notify

#### `Notify.Send(string topic, IEnumerable<object>? email = null, IEnumerable<object>? sms = null, IEnumerable<object>? mobilePush = null, IEnumerable<object>? webPush = null, IEnumerable<object>? webhook = null, IEnumerable<object>? messaging = null, IEnumerable<object>? realTime = null)`

Sends a multi-channel notification to a topic. Returns [`NotifyResult`](#notifyresult). `POST /notify/send`. JSON keys are snake_case (`email`, `sms`, `mobile_push`, `web_push`, `webhook`, `messaging`, `real_time`).

At least one (non-empty) event block must be provided — otherwise a [`ValidationException`](#error-handling) is thrown before any request is made. Each event block is a collection of channel-specific event objects.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `topic` | `string` | Yes | Topic identifier. |
| `email` | `IEnumerable<object>?` | No* | Email events. |
| `sms` | `IEnumerable<object>?` | No* | SMS events. |
| `mobilePush` | `IEnumerable<object>?` | No* | Mobile push events (`mobile_push`). |
| `webPush` | `IEnumerable<object>?` | No* | Web push events (`web_push`). |
| `webhook` | `IEnumerable<object>?` | No* | Webhook events. |
| `messaging` | `IEnumerable<object>?` | No* | Messaging events. |
| `realTime` | `IEnumerable<object>?` | No* | Real-time events (`real_time`). |

\* At least one event block is required.

```csharp
var result = callisto.Notify.Send(
    topic: "welcome",
    sms: new object[] { new { to = "+2250700000000" } },
    email: new object[] { new { to = "user@example.com" } });
Console.WriteLine(result.Status);
```

## Pagination

List methods return a `Paginated<T>`. Iterate the typed rows via `.Items`; the other fields describe the page window.

| Field | Type | Description |
| --- | --- | --- |
| `Items` | `List<T>` | The rows on this page (typed model instances). |
| `Total` | `int` | Total number of items across all pages. |
| `PerPage` | `int` | Items per page. |
| `CurrentPage` | `int` | Current page number. |
| `Next` | `int?` | Next page number, or `null`. |
| `Previous` | `int?` | Previous page number, or `null`. |
| `TotalPages` | `int` | Total number of pages. |

```csharp
var page = callisto.Sms.List(page: 1, perPage: 50);
foreach (var msg in page.Items)
    Console.WriteLine($"{msg.Id} {msg.Status}");

Console.WriteLine($"Page {page.CurrentPage} of {page.TotalPages} ({page.Total} total)");

// Manual paging
var nextPage = page.Next;
while (nextPage is not null)
{
    page = callisto.Sms.List(page: nextPage, perPage: 50);
    foreach (var msg in page.Items)
        Console.WriteLine(msg.Id);
    nextPage = page.Next;
}
```

## Typed models

All models are plain classes. **Read models** (those returned by `Get`/`List` methods) tolerate unknown/extra fields returned by the API: deserialization with `System.Text.Json` silently ignores JSON properties that are not declared on the class, so new API fields will not break decoding. Snake_case JSON keys are mapped to PascalCase properties via `[JsonPropertyName]`.

Models live in the `Callisto.Sdk.Models` namespace.

### Result models

#### `Balance`

| Property | Type | JSON key |
| --- | --- | --- |
| `Credit` | `double` | `credit` |
| `Currency` | `string` | `currency` |
| `SmsPriceLocal` | `double?` | `sms_price_local` |
| `SmsPriceInternational` | `double?` | `sms_price_international` |

#### `SendSmsResult`

| Property | Type | JSON key |
| --- | --- | --- |
| `TotalAmount` | `double` | `total_amount` |
| `AvailableCredit` | `double` | `available_credit` |
| `Status` | `string` | `status` |
| `RecipientCount` | `int` | `recipient_count` |
| `Scheduled` | `bool` | `scheduled` |
| `Messages` | `List<JsonElement>` | `messages` |

#### `SendOtpResult`

| Property | Type | JSON key |
| --- | --- | --- |
| `Id` | `string` | `id` |
| `Provider` | `string` | `provider` |
| `Recipient` | `JsonElement` | `recipient` |
| `ExpiresAt` | `string` | `expires_at` |
| `ExpiresIn` | `int` | `expires_in` |

#### `VerifyOtpResult`

| Property | Type | JSON key |
| --- | --- | --- |
| `Id` | `string` | `id` |
| `Status` | `string` | `status` |
| `Verified` | `bool` | `verified` |
| `VerifiedAt` | `string?` | `verified_at` |

#### `SendWaResult`

| Property | Type | JSON key |
| --- | --- | --- |
| `Id` | `string` | `id` |
| `InstanceId` | `string` | `instance_id` |
| `Recipient` | `JsonElement` | `recipient` |
| `MessageType` | `string` | `message_type` |
| `Status` | `string` | `status` |
| `Scheduled` | `bool` | `scheduled` |
| `MediaUrl` | `string?` | `media_url` |

#### `NotifyResult`

| Property | Type | JSON key |
| --- | --- | --- |
| `Status` | `string` | `status` |
| `Topic` | `JsonElement` | `topic` |
| `QueuedEvents` | `JsonElement` | `queued_events` |
| `TopicMessages` | `JsonElement` | `topic_messages` |

### Read models

#### `SmsMessage`

| Property | Type | JSON key |
| --- | --- | --- |
| `Id` | `string` | `id` |
| `SenderName` | `string?` | `sender_name` |
| `Recipient` | `string?` | `recipient` |
| `Content` | `string?` | `content` |
| `Status` | `string?` | `status` |
| `CreatedAt` | `string?` | `created_at` |
| `UpdatedAt` | `string?` | `updated_at` |

#### `Otp`

Carries both `OtpId` (populated by `GetStatus`) and `Id` (populated by `List` rows) — depending on the endpoint, one or the other may be set.

| Property | Type | JSON key |
| --- | --- | --- |
| `OtpId` | `string?` | `otp_id` |
| `Id` | `string?` | `id` |
| `Status` | `string?` | `status` |
| `Recipient` | `string?` | `recipient` |
| `ExpiresAt` | `string?` | `expires_at` |
| `VerifiedAt` | `string?` | `verified_at` |
| `Attempts` | `int?` | `attempts` |
| `CreatedAt` | `string?` | `created_at` |

#### `WhatsAppInstance`

| Property | Type | JSON key |
| --- | --- | --- |
| `Id` | `string` | `id` |
| `Code` | `string?` | `code` |
| `ClientId` | `string?` | `client_id` |
| `Name` | `string?` | `name` |
| `PhoneNumber` | `string?` | `phone_number` |
| `PhoneName` | `string?` | `phone_name` |
| `Status` | `string?` | `status` |
| `BillingStatus` | `string?` | `billing_status` |
| `TrialDaysRemaining` | `int?` | `trial_days_remaining` |
| `MonthlyFee` | `double?` | `monthly_fee` |
| `MessagesSentToday` | `int?` | `messages_sent_today` |
| `MessagesSentMonth` | `int?` | `messages_sent_month` |
| `DailyLimit` | `int?` | `daily_limit` |
| `LastMessageAt` | `string?` | `last_message_at` |
| `WebhookUrl` | `string?` | `webhook_url` |
| `IsActive` | `bool?` | `is_active` |
| `CreatedAt` | `string?` | `created_at` |
| `UpdatedAt` | `string?` | `updated_at` |

#### `WhatsAppMessage`

| Property | Type | JSON key |
| --- | --- | --- |
| `Id` | `string` | `id` |
| `InstanceId` | `string?` | `instance_id` |
| `ClientId` | `string?` | `client_id` |
| `ClientApiId` | `string?` | `client_api_id` |
| `Recipient` | `string?` | `recipient` |
| `RecipientName` | `string?` | `recipient_name` |
| `MessageType` | `string?` | `message_type` |
| `Content` | `string?` | `content` |
| `MediaUrl` | `string?` | `media_url` |
| `MediaMimetype` | `string?` | `media_mimetype` |
| `MediaFilename` | `string?` | `media_filename` |
| `ExtraData` | `JsonElement?` | `extra_data` |
| `Direction` | `string?` | `direction` |
| `Status` | `string?` | `status` |
| `WhatsAppMessageId` | `string?` | `whatsapp_message_id` |
| `ErrorCode` | `int?` | `error_code` |
| `ErrorMessage` | `string?` | `error_message` |
| `RetryCount` | `int?` | `retry_count` |
| `IsBillable` | `bool?` | `is_billable` |
| `Cost` | `double?` | `cost` |
| `SentAt` | `string?` | `sent_at` |
| `DeliveredAt` | `string?` | `delivered_at` |
| `ReadAt` | `string?` | `read_at` |
| `ScheduledAt` | `string?` | `scheduled_at` |
| `CreatedAt` | `string?` | `created_at` |
| `UpdatedAt` | `string?` | `updated_at` |
| `ProcessorIdentifier` | `string?` | `processor_identifier` |

### `Paginated<T>`

Generic page container. See [Pagination](#pagination) for fields and usage.

### Raw responses

`WhatsApp.GetQr(code)` and `WhatsApp.GetStatus(code)` return the raw `JsonElement?` decoded from the API response — they are not mapped to a typed model.

## Enums

Enums live in the `Callisto.Sdk.Enums` namespace. Each maps to a lower-case wire string via the `Value()` extension method; the SDK serializes the wire string for you. Anywhere an enum is accepted, an overload also accepts the raw `string`.

```csharp
using Callisto.Sdk.Enums;
```

| Enum | Members (wire value) |
| --- | --- |
| `MessageStatus` | `Pending` (`"pending"`), `Sent` (`"sent"`), `Delivered` (`"delivered"`), `Failed` (`"failed"`) |
| `OtpStatus` | `Pending` (`"pending"`), `Verified` (`"verified"`), `Expired` (`"expired"`), `Failed` (`"failed"`) |
| `OtpType` | `Digit` (`"digit"`), `Alpha` (`"alpha"`), `Alphanumeric` (`"alphanumeric"`) |
| `OtpProvider` | `Sms` (`"sms"`), `WhatsApp` (`"whatsapp"`) |
| `WhatsAppMediaType` | `Image` (`"image"`), `Video` (`"video"`), `Document` (`"document"`), `Audio` (`"audio"`) |

## Error handling

All SDK errors derive from `CallistoException` and live in the `Callisto.Sdk.Errors` namespace. Every error carries `.Message` (`string`) and `.StatusCode` (`int`, `0` for transport-level failures), plus `.Body` (the decoded response body as a `JsonElement` or raw `string`, when available).

| Exception | Raised when |
| --- | --- |
| `CallistoException` | Base class for all SDK errors. |
| `AuthenticationException` | HTTP 401 — invalid credentials. |
| `ValidationException` | HTTP 400 / 422 — invalid request. Also thrown client-side before a request (e.g. `Notify.Send` with no event block, or `Otp.Send` with `provider = OtpProvider.WhatsApp` and no `instanceCode`). |
| `NotFoundException` | HTTP 404 — resource not found. |
| `RateLimitException` | HTTP 429 — rate limited. Adds `.RetryAfter` (`int?`, seconds, parsed from the `Retry-After` header). |
| `ApiException` | Any other non-2xx HTTP status. |
| `NetworkException` | Transport-level failure (connection error, timeout, DNS, etc.). `StatusCode` is `0`. |

```csharp
using Callisto.Sdk;
using Callisto.Sdk.Errors;

using var callisto = new CallistoClient(clientId: "...", apiKey: "...");
try
{
    callisto.Sms.Send(sender: "Acme", to: "+2250700000000", message: "Hi");
}
catch (RateLimitException exc)
{
    var wait = exc.RetryAfter ?? 5;
    Console.WriteLine($"Rate limited, retrying in {wait}s");
    Thread.Sleep(TimeSpan.FromSeconds(wait));
}
catch (AuthenticationException)
{
    Console.WriteLine("Check your clientId / apiKey");
}
catch (ValidationException exc)
{
    Console.WriteLine($"Invalid request: {exc.Message}");
}
catch (NetworkException exc)
{
    Console.WriteLine($"Network problem: {exc.Message}");
}
catch (CallistoException exc)
{
    Console.WriteLine($"API error ({exc.StatusCode}): {exc.Message}");
}
```

## Error reporting

The SDK ships with an opt-in, Sentry-style error reporter that POSTs captured errors to a Callisto
error-tracking ingest endpoint (the DSN). It is **disabled by default**: with no DSN configured the
client behaves exactly as before and nothing is sent.

When a DSN is set, the SDK automatically captures its own `CallistoException`s (API + network
errors, and client-side validation errors from `Otp.Send` / `Notify.Send`). You can also report
your application's own exceptions and messages through the client.

Delivery is **background and best-effort**: capturing never blocks or delays your error path, never
alters the original exception (it still propagates), and the reporter swallows all of its own
failures.

### Enabling

Pass an `errorDsn` (or set the `CALLISTO_APP_ERROR_DSN` environment variable):

```csharp
using var callisto = new CallistoClient(
    clientId: "your-client-id",
    apiKey: "your-api-key",
    errorDsn: "https://app.callistosignal.com/ingest/<uuid>?key=<public_key>",
    environment: "production");
```

The DSN **is** the full ingest POST URL — the SDK posts directly to it (no parsing beyond a
well-formed-URL check). A missing or malformed DSN leaves reporting disabled.

### Environment variables

| Variable | Maps to | Default | Meaning |
| --- | --- | --- | --- |
| `CALLISTO_APP_ERROR_DSN` | `errorDsn` | none | Ingest DSN. Must be `https` (`http` only for `localhost`, `127.0.0.1`, `::1`, `*.localhost`). Absent or invalid → reporting fully disabled (no-op). |
| `CALLISTO_CAPTURE_UNHANDLED` | `captureUnhandled` | `false` | Install the global unhandled-exception handler. |
| `CALLISTO_ENVIRONMENT` | `environment` | none | Optional tag included in `context.environment`. |

Resolution order matches credentials: explicit constructor argument first, then the environment
variable.

### Public API

```csharp
callisto.CaptureException(exception, level: "error", extra: null);
callisto.CaptureMessage("something happened", level: "info", extra: null);
callisto.SetUser(new Dictionary<string, object?> { ["id"] = "u-123", ["email"] = "a@b.com" });
```

- `level` is constrained to `fatal | error | warning | info` (anything else falls back to `error`).
- `extra` is an optional `IDictionary<string, object?>` merged into the event's `context`.
- `SetUser(null)` clears the user context.
- The underlying reporter is also reachable via `callisto.ErrorReporter` for advanced use (e.g.
  `Flush()`), but the three client methods are the supported surface.

### Opt-in global handler (default off)

When `captureUnhandled` is `true` **and** a DSN is set, the client subscribes to
`AppDomain.CurrentDomain.UnhandledException` and `TaskScheduler.UnobservedTaskException`, capturing
uncaught errors at `level = fatal`. The handlers chain (they do not clobber existing handlers) and
the platform's default behavior is preserved.

```csharp
using var callisto = new CallistoClient(
    clientId: "...",
    apiKey: "...",
    errorDsn: "https://app.callistosignal.com/ingest/<uuid>?key=<public_key>",
    captureUnhandled: true);
```

### PII guarantee

The reporter **never** transmits your `clientId`, `apiKey`, the `Authorization` header, or the
**outgoing request body** (which carries phone numbers and message content). Only the server's
error response `body`, the HTTP `status_code`, the request `method`, and the request `path` may
leave the process. The reporter uses its own minimal `HttpClient` — it never reuses the
Basic-authenticated transport.

### Lifecycle

`Dispose()` on the client flushes pending events (briefly), stops the background worker, and
unsubscribes any global handlers.

## Logs

The SDK can ship your application's own log lines to **callisto-logs** (OTLP/HTTP JSON), with no
OpenTelemetry SDK and no extra package. Records are batched and sent by a background task; a log
call never throws and never waits on the network. If you already run an OpenTelemetry SDK or
Collector, keep using it: this is for apps that do not.

> **Do not log passwords, tokens, card numbers or other secrets.** The SDK does not scrub
> anything, and callisto-logs stores logs as received: nothing is masked. Remove secrets and personal
> data you do not need before logging (CGU 7.6).

### Create a Logs key

In the dashboard, open **Organization → API keys** (or **Monitoring → Logs → Ingest keys**) and create
an API key with the **Logs** scope. You get an access key (`pk_` + 32 hex) and a secret (`sk_` +
48 hex). A messaging API key is refused by callisto-logs, and a Logs key is refused by the
messaging API: the two never mix.

### Configuration

Each setting is optional and falls back to an environment variable.

| `CallistoLoggerOptions` | Env var | Default | Meaning |
| --- | --- | --- | --- |
| `LogsAccessKey` | `CALLISTO_LOGS_ACCESS_KEY` | none | `pk_…` of a Logs key |
| `LogsSecret` | `CALLISTO_LOGS_SECRET` | none | `sk_…` of that key |
| `LogsEndpoint` | `CALLISTO_LOGS_ENDPOINT` | `https://logs.callistosignal.com/v1/logs` | full POST URL |
| `ServiceName` | `CALLISTO_SERVICE_NAME`, then `OTEL_SERVICE_NAME` | `unknown_service` | resource `service.name` |
| `Environment` | `CALLISTO_ENVIRONMENT` | none | resource `deployment.environment.name` |
| `FlushTimeout` | — | 5 s | upper bound for `Flush` / `Dispose` |
| `Sender`, `HttpMessageHandler`, `Clock` | — | — | injection points for tests |

- No key and no secret: the logger is **disabled**. Every call is a cheap no-op, no background task
  is started, nothing is sent.
- **HTTPS only.** `LogsEndpoint` must be an absolute `https` URL: the secret travels in the
  `Authorization` header. Plain `http` is accepted only for `localhost`, `127.0.0.1`, `::1` and
  `*.localhost` (local development). The error DSN follows the same rule.
- Invalid settings are: only one of the two, a value that is not `pk_` + 32 lowercase hex /
  `sk_` + 48 lowercase hex, or an endpoint that breaks the HTTPS rule. What happens then depends on
  who builds the logger:
  - **`new CallistoLogger(...)` (standalone)** throws `ArgumentException` (the same error as
    missing messaging credentials). A typo fails at startup instead of silently losing every log.
  - **`new CallistoClient(...)`** never throws because of the Logs settings. It writes **one**
    warning through `System.Diagnostics.Trace.TraceWarning` (add a `TraceListener` to see it), and
    `Logs` is a disabled no-op logger; SMS, OTP and WhatsApp keep working. The warning never
    contains the key or the secret. Missing messaging credentials still throw `ArgumentException`.

### Quick start

Standalone (no messaging credentials needed):

```csharp
using Callisto.Sdk.Logs;

await using var logger = new CallistoLogger(new CallistoLoggerOptions
{
    LogsAccessKey = "pk_…",
    LogsSecret = "sk_…",
    ServiceName = "checkout",
    Environment = "production",
});

logger.Info("order paid", new Dictionary<string, object?>
{
    ["order_id"] = 42,
    ["paid"] = true,
    ["amount"] = 15000.0,
    ["items"] = new[] { "sku-1", "sku-2" },
});
logger.Warn("stock low", new Dictionary<string, object?> { ["sku"] = "sku-1", ["left"] = 3 });
```

Or from the messaging client, which builds `client.Logs` from the same constructor arguments and
environment variables (`environment` is shared with the error reporter):

```csharp
using var callisto = new CallistoClient(
    clientId: "your-client-id",
    apiKey: "your-api-key",
    environment: "production",
    logsAccessKey: "pk_…",
    logsSecret: "sk_…",
    serviceName: "checkout");

callisto.Logs.Error("payment failed", new Dictionary<string, object?> { ["order_id"] = 42 });
```

Without a Logs key, or with invalid Logs settings (one warning, see above), `callisto.Logs` is a
no-op logger. `callisto.Dispose()` also closes the logger
(flushing what is queued). The messaging credentials are never sent to callisto-logs, and the Logs
secret is never sent anywhere else.

Methods:

```csharp
logger.Debug(message, attributes);   // also Info, Warn, Error, Fatal
logger.Log(CallistoLogLevel.Warn, message, attributes);
logger.Log("warn", message, attributes);   // debug|info|warn|error|fatal; anything else → info
logger.Flush();                     // or await logger.FlushAsync(cancellationToken)
logger.Dispose();                   // or await logger.DisposeAsync(); later calls are no-ops
long lost = logger.DroppedCount;    // diagnostics
```

`attributes` is an `IReadOnlyDictionary<string, object?>`. Values may be `string`, `bool`, any
integer type, `float` / `double` / `decimal`, `null` (dropped), lists (any `IEnumerable`) and
maps (any `IDictionary`), nested up to 5 levels (deeper containers are written as a JSON string).
Other values are converted to strings.

| Level | `severityNumber` | `severityText` |
| --- | --- | --- |
| `Debug` | 5 | `DEBUG` |
| `Info` | 9 | `INFO` |
| `Warn` | 13 | `WARN` |
| `Error` | 17 | `ERROR` |
| `Fatal` | 21 | `FATAL` |

### Trace correlation

Two attribute keys are reserved. A `trace_id` of 32 hex characters (not all zeros) and a `span_id`
of 16 hex characters (not all zeros) are moved to the record's `traceId` / `spanId`, lowercased.
Use the same `trace_id` your error reports carry (`context.trace.trace_id`) and callisto-logs links
the log lines to the error issue. With `System.Diagnostics.Activity`:

```csharp
var activity = System.Diagnostics.Activity.Current;
logger.Info("charging card", new Dictionary<string, object?>
{
    ["trace_id"] = activity?.TraceId.ToHexString(),
    ["span_id"] = activity?.SpanId.ToHexString(),
});
```

An invalid value stays an ordinary attribute.

### Delivery semantics

- The queue holds at most **2 000** records. When it is full, new records are dropped and counted
  in `DroppedCount`.
- One background `Task` sends a batch when **100** records are queued, every **5 s** otherwise, and
  on `Flush` / `Dispose`. A batch is at most **500** records or about **1 MB** of JSON.
- `429` / `503` and network errors are retried, up to **3** attempts in total: after `Retry-After`
  seconds (capped at 60 s, 5 s when absent) for 429/503, after 1 s then 5 s for network errors.
  New records keep queueing while a batch waits.
- `400`, `413`, `415` and any other error status drop the batch.
- `401` / `403` (wrong key, no logs on the plan, suspended plan) drop the batch and pause sending
  for **60 s**; records logged during the pause are dropped, then sending resumes.
- `DroppedCount` counts every lost record: full queue, pause, or a dropped batch.
- `Flush` / `FlushAsync` / `Dispose` / `DisposeAsync` wait at most `FlushTimeout` (5 s) and never
  throw. HTTP timeout is 10 s per request.
- Guards: the message is cut to 32 768 characters, string values to 8 192 (marked with `…`), at
  most 128 attributes are kept per record, and at most 128 items per nested list or map. A record
  encodes at most 2 048 values in total (scalars and containers, at every level; a list or map
  referenced several times counts each time): once that budget is spent, the remaining values and
  attributes are dropped.
- The logger's own failures are swallowed: they are never logged, never sent to the error
  reporter, and never re-shipped.
- There is no `Microsoft.Extensions.Logging` adapter in this version (it would add a package
  dependency); call `CallistoLogger` directly or wrap it in your own `ILogger`.

## Development

```bash
dotnet build
dotnet test
```
