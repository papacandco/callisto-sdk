# @callisto/sdk

Official Callisto messaging API SDK for JavaScript / TypeScript (Node 18+, ESM).

## Requirements

- **Node 18+** — the SDK uses the built-in global `fetch`, so no HTTP dependency is required.
- **ESM only** — the package is `"type": "module"`. Import it with `import`; `require()` is not supported.
- **TypeScript types ship in the box** (`dist/index.d.ts`). No `@types/...` package needed.

## Install

```bash
npm install @callisto/sdk
```

## Configuration

Create a client with `new CallistoClient(options)`:

```ts
import { CallistoClient } from "@callisto/sdk";

const client = new CallistoClient({
  clientId: "your_client_id",
  apiKey: "your_api_key",
});
```

### Options

| Option             | Type      | Required | Default                             | Description                                                                                                        |
| ------------------ | --------- | -------- | ----------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `clientId`         | `string`  | yes\*    | `process.env.CALLISTO_CLIENT_ID`    | Your API client id.                                                                                                |
| `apiKey`           | `string`  | yes\*    | `process.env.CALLISTO_API_KEY`      | Your API key (secret).                                                                                             |
| `baseUrl`          | `string`  | no       | `https://api.callistosignal.com/v1` | API base URL. Trailing slashes are stripped.                                                                      |
| `timeoutMs`        | `number`  | no       | `30000`                             | Per-request timeout in milliseconds (via `AbortController`).                                                       |
| `errorDsn`         | `string`  | no       | none                                | Error-reporting ingest DSN. Absent → error reporting is disabled (no-op). See [Error reporting](#error-reporting). |
| `captureUnhandled` | `boolean` | no       | `false`                             | Install a global `uncaughtException` / `unhandledRejection` handler.                                              |
| `environment`      | `string`  | no       | none                                | Optional tag attached to reported events as `context.environment`.                                                |

\* `clientId` and `apiKey` are required, but may be supplied via constructor options **or** environment variables. If neither is resolved, the constructor throws.

### Environment-variable fallbacks

Any option you omit is read from the environment:

| Env var               | Falls back for |
| --------------------- | -------------- |
| `CALLISTO_CLIENT_ID`  | `clientId`     |
| `CALLISTO_API_KEY`    | `apiKey`       |
| `CALLISTO_BASE_URL`   | `baseUrl`      |
| `CALLISTO_ERROR_DSN`  | `errorDsn`     |
| `CALLISTO_CAPTURE_UNHANDLED` | `captureUnhandled` (truthy: `1`/`true`) |
| `CALLISTO_ENVIRONMENT` | `environment` |

```ts
// With CALLISTO_CLIENT_ID and CALLISTO_API_KEY set in the environment:
const client = new CallistoClient();
```

### Authentication

Auth is **HTTP Basic**: the SDK sends `Authorization: Basic base64(client_id:api_key)` on every request. This is handled automatically — you never build the header yourself.

## Quick start

```ts
import { CallistoClient } from "@callisto/sdk";

const client = new CallistoClient({
  clientId: "your_client_id",
  apiKey: "your_api_key",
});

// Check your balance
const balance = await client.balance.get();
console.log(`${balance.credit} ${balance.currency}`);

// Send an SMS
const result = await client.sms.send({
  sender: "Acme",
  to: "+2250700000000",
  message: "Welcome to Acme!",
});
console.log(result.status, result.recipient_count);
```

## Resources

The client exposes five resources: `client.balance`, `client.sms`, `client.otp`, `client.whatsapp`, and `client.notify`. All methods return `Promise`s.

### `balance`

#### `balance.get(params?)` → [`Balance`](#balance-1)

| Param      | Type                  | Required | Description                                              |
| ---------- | --------------------- | -------- | -------------------------------------------------------- |
| `format`   | `"full" \| "short"`   | no       | Response detail level. Defaults to `"full"`.             |
| `currency` | `string`              | no       | Currency to express the balance in.                      |

```ts
const balance = await client.balance.get({ format: "full", currency: "XOF" });
console.log(balance.credit, balance.currency);
```

### `sms`

#### `sms.send(params)` → [`SendSmsResult`](#sendsmsresult)

| Param          | Type                  | Required | Description                                              |
| -------------- | --------------------- | -------- | -------------------------------------------------------- |
| `sender`       | `string`              | yes      | Registered sender name shown to recipients.              |
| `to`           | `string \| string[]`  | yes      | One recipient (E.164) or an array of recipients.         |
| `message`      | `string`              | yes      | Message body.                                            |
| `notify_url`   | `string`              | no       | Webhook URL to receive delivery callbacks.               |
| `scheduled_at` | `string`              | no       | ISO 8601 timestamp to schedule delivery.                 |

```ts
const result = await client.sms.send({
  sender: "Acme",
  to: ["+2250700000000", "+2250700000001"],
  message: "Your order has shipped.",
});
console.log(result.status, result.recipient_count, result.available_credit);
```

#### `sms.list(params?)` → [`Paginated<SmsMessage>`](#paginatedt)

| Param        | Type     | Required | Description                                     |
| ------------ | -------- | -------- | ----------------------------------------------- |
| `started_at` | `string` | no       | Filter: lower bound (ISO 8601).                 |
| `ended_at`   | `string` | no       | Filter: upper bound (ISO 8601).                 |
| `page`       | `number` | no       | Page number.                                    |
| `per_page`   | `number` | no       | Items per page.                                 |

```ts
const page = await client.sms.list({ page: 1, per_page: 50 });
for (const msg of page.items) {
  console.log(msg.id, msg.status);
}
```

#### `sms.getStatus(id)` → [`SmsMessage`](#smsmessage)

| Param | Type     | Required | Description     |
| ----- | -------- | -------- | --------------- |
| `id`  | `string` | yes      | SMS message id. |

```ts
const msg = await client.sms.getStatus("sms_abc123");
console.log(msg.status);
```

### `otp`

#### `otp.send(params)` → [`SendOtpResult`](#sendotpresult)

| Param           | Type                        | Required | Description                                                        |
| --------------- | --------------------------- | -------- | ------------------------------------------------------------------ |
| `to`            | `string`                    | yes      | Recipient (E.164).                                                 |
| `message`       | `string`                    | yes      | Message template/body for the OTP.                                 |
| `sender`        | `string`                    | no       | Sender name.                                                       |
| `expired_in`    | `number`                    | no       | Expiry window in seconds.                                          |
| `type`          | [`OtpType`](#otptype)       | no       | Code charset: `digit`, `alpha`, or `alphanumeric`.                 |
| `digit_size`    | `number`                    | no       | Code length.                                                       |
| `provider`      | [`OtpProvider`](#otpprovider) | no     | Delivery channel: `sms` or `whatsapp`.                             |
| `instance_code` | `string`                    | cond.    | **Required when `provider` is `whatsapp`** — the SDK throws a `ValidationError` before the network call otherwise. |

> Note: `instance_code` is sent to the API as `instanceCode` on the wire; you always pass the snake_case `instance_code`.

```ts
import { OtpType, OtpProvider } from "@callisto/sdk";

const otp = await client.otp.send({
  to: "+2250700000000",
  message: "Your Acme code is {{code}}",
  type: OtpType.Digit,
  digit_size: 6,
  expired_in: 300,
  provider: OtpProvider.Sms,
});
console.log(otp.id, otp.expires_at);
```

#### `otp.verify(params)` → [`VerifyOtpResult`](#verifyotpresult)

| Param    | Type     | Required | Description                          |
| -------- | -------- | -------- | ------------------------------------ |
| `otp_id` | `string` | yes      | The `id` returned by `otp.send`.     |
| `code`   | `string` | yes      | The code entered by the user.        |

```ts
const verify = await client.otp.verify({ otp_id: otp.id, code: "123456" });
console.log(verify.verified, verify.status);
```

#### `otp.getStatus(id)` → [`Otp`](#otp)

| Param | Type     | Required | Description |
| ----- | -------- | -------- | ----------- |
| `id`  | `string` | yes      | OTP id.     |

```ts
const status = await client.otp.getStatus("otp_abc123");
console.log(status.status, status.attempts);
```

#### `otp.list(params?)` → [`Paginated<Otp>`](#paginatedt)

| Param        | Type     | Required | Description                      |
| ------------ | -------- | -------- | -------------------------------- |
| `started_at` | `string` | no       | Filter: lower bound (ISO 8601).  |
| `ended_at`   | `string` | no       | Filter: upper bound (ISO 8601).  |
| `page`       | `number` | no       | Page number.                     |
| `limit`      | `number` | no       | Items per page. (This endpoint uses `limit`, not `per_page`.) |

```ts
const page = await client.otp.list({ page: 1, limit: 25 });
for (const otp of page.items) {
  console.log(otp.id, otp.status);
}
```

### `whatsapp`

WhatsApp messages are sent through an **instance**. Create one, fetch its QR code to link a device, then send messages using the instance `code`.

#### `whatsapp.createInstance(params)` → [`WhatsAppInstance`](#whatsappinstance)

| Param             | Type     | Required | Description                                             |
| ----------------- | -------- | -------- | ------------------------------------------------------- |
| `name`            | `string` | yes      | Human-friendly instance name.                           |
| `phone_number`    | `string` | no       | Phone number to bind to the instance.                   |
| `webhook_url`     | `string` | no       | Webhook URL for inbound/status callbacks.               |
| `idempotency_key` | `string` | no       | Key to make creation idempotent.                        |

```ts
const instance = await client.whatsapp.createInstance({
  name: "Acme Support",
  webhook_url: "https://acme.example/wa/webhook",
});
console.log(instance.code, instance.status);
```

#### `whatsapp.listInstances(page?)` → [`Paginated<WhatsAppInstance>`](#paginatedt)

| Param  | Type     | Required | Description                     |
| ------ | -------- | -------- | ------------------------------- |
| `page` | `number` | no       | Page number. Defaults to `1`.   |

```ts
const page = await client.whatsapp.listInstances(1);
console.log(page.items.map((i) => i.code));
```

#### `whatsapp.getInstance(code)` → [`WhatsAppInstance`](#whatsappinstance)

| Param  | Type     | Required | Description    |
| ------ | -------- | -------- | -------------- |
| `code` | `string` | yes      | Instance code. |

```ts
const instance = await client.whatsapp.getInstance("inst_abc123");
```

#### `whatsapp.getQr(code)` → `Record<string, unknown>`

Returns the raw QR-code payload used to link a device.

| Param  | Type     | Required | Description    |
| ------ | -------- | -------- | -------------- |
| `code` | `string` | yes      | Instance code. |

```ts
const qr = await client.whatsapp.getQr("inst_abc123");
```

#### `whatsapp.getStatus(code)` → `Record<string, unknown>`

Returns the raw connection/status payload for the instance.

| Param  | Type     | Required | Description    |
| ------ | -------- | -------- | -------------- |
| `code` | `string` | yes      | Instance code. |

```ts
const status = await client.whatsapp.getStatus("inst_abc123");
```

#### `whatsapp.listMessages(code, params?)` → [`Paginated<WhatsAppMessage>`](#paginatedt)

| Param        | Type     | Required | Description                      |
| ------------ | -------- | -------- | -------------------------------- |
| `code`       | `string` | yes      | Instance code.                   |
| `started_at` | `string` | no       | Filter: lower bound (ISO 8601).  |
| `ended_at`   | `string` | no       | Filter: upper bound (ISO 8601).  |
| `page`       | `number` | no       | Page number.                     |
| `per_page`   | `number` | no       | Items per page.                  |

```ts
const page = await client.whatsapp.listMessages("inst_abc123", { per_page: 50 });
```

#### `whatsapp.getMessage(messageId)` → [`WhatsAppMessage`](#whatsappmessage)

| Param       | Type     | Required | Description           |
| ----------- | -------- | -------- | --------------------- |
| `messageId` | `string` | yes      | WhatsApp message id.  |

```ts
const msg = await client.whatsapp.getMessage("wamsg_abc123");
```

#### `whatsapp.sendText(code, params)` → [`SendWaResult`](#sendwaresult)

| Param          | Type     | Required | Description                              |
| -------------- | -------- | -------- | ---------------------------------------- |
| `code`         | `string` | yes      | Instance code.                           |
| `to`           | `string` | yes      | Recipient (E.164).                       |
| `message`      | `string` | yes      | Text body.                               |
| `scheduled_at` | `string` | no       | ISO 8601 timestamp to schedule delivery. |

```ts
const res = await client.whatsapp.sendText("inst_abc123", {
  to: "+2250700000000",
  message: "Hello from Acme!",
});
```

#### `whatsapp.sendMedia(code, params)` → [`SendWaResult`](#sendwaresult)

| Param          | Type                                       | Required | Description                              |
| -------------- | ------------------------------------------ | -------- | ---------------------------------------- |
| `code`         | `string`                                   | yes      | Instance code.                           |
| `to`           | `string`                                   | yes      | Recipient (E.164).                       |
| `type`         | [`WhatsAppMediaType`](#whatsappmediatype)  | yes      | `image`, `video`, `document`, or `audio`.|
| `media_url`    | `string`                                   | yes      | Publicly reachable media URL.            |
| `caption`      | `string`                                   | no       | Caption shown with the media.            |
| `filename`     | `string`                                   | no       | File name (useful for documents).        |
| `scheduled_at` | `string`                                   | no       | ISO 8601 timestamp to schedule delivery. |

```ts
import { WhatsAppMediaType } from "@callisto/sdk";

const res = await client.whatsapp.sendMedia("inst_abc123", {
  to: "+2250700000000",
  type: WhatsAppMediaType.Image,
  media_url: "https://acme.example/banner.png",
  caption: "New arrivals",
});
```

#### `whatsapp.sendButtons(code, params)` → [`SendWaResult`](#sendwaresult)

| Param          | Type                      | Required | Description                              |
| -------------- | ------------------------- | -------- | ---------------------------------------- |
| `code`         | `string`                  | yes      | Instance code.                           |
| `to`           | `string`                  | yes      | Recipient (E.164).                       |
| `body`         | `string`                  | yes      | Main message body.                       |
| `buttons`      | [`WaButton[]`](#wabutton) | yes      | Reply buttons (each `{ id, title }`).    |
| `header`       | `string`                  | no       | Optional header text.                    |
| `footer`       | `string`                  | no       | Optional footer text.                    |
| `scheduled_at` | `string`                  | no       | ISO 8601 timestamp to schedule delivery. |

```ts
const res = await client.whatsapp.sendButtons("inst_abc123", {
  to: "+2250700000000",
  body: "Confirm your order?",
  buttons: [
    { id: "yes", title: "Yes" },
    { id: "no", title: "No" },
  ],
});
```

#### `whatsapp.sendLocation(code, params)` → [`SendWaResult`](#sendwaresult)

| Param          | Type     | Required | Description                              |
| -------------- | -------- | -------- | ---------------------------------------- |
| `code`         | `string` | yes      | Instance code.                           |
| `to`           | `string` | yes      | Recipient (E.164).                       |
| `latitude`     | `number` | yes      | Latitude.                                |
| `longitude`    | `number` | yes      | Longitude.                               |
| `name`         | `string` | no       | Place name.                              |
| `address`      | `string` | no       | Place address.                           |
| `scheduled_at` | `string` | no       | ISO 8601 timestamp to schedule delivery. |

```ts
const res = await client.whatsapp.sendLocation("inst_abc123", {
  to: "+2250700000000",
  latitude: 5.3599,
  longitude: -4.0083,
  name: "Acme HQ",
  address: "Plateau, Abidjan",
});
```

#### `whatsapp.sendList(code, params)` → [`SendWaResult`](#sendwaresult)

| Param          | Type                                   | Required | Description                              |
| -------------- | -------------------------------------- | -------- | ---------------------------------------- |
| `code`         | `string`                               | yes      | Instance code.                           |
| `to`           | `string`                               | yes      | Recipient (E.164).                       |
| `body`         | `string`                               | yes      | Main message body.                       |
| `button_text`  | `string`                               | yes      | Text on the button that opens the list.  |
| `sections`     | [`WaListSection[]`](#walistsection)    | yes      | List sections (each with `title` + `rows`). |
| `header`       | `string`                               | no       | Optional header text.                    |
| `footer`       | `string`                               | no       | Optional footer text.                    |
| `scheduled_at` | `string`                               | no       | ISO 8601 timestamp to schedule delivery. |

```ts
const res = await client.whatsapp.sendList("inst_abc123", {
  to: "+2250700000000",
  body: "Pick a category",
  button_text: "Browse",
  sections: [
    {
      title: "Drinks",
      rows: [
        { id: "coffee", title: "Coffee", description: "Hot & fresh" },
        { id: "tea", title: "Tea" },
      ],
    },
  ],
});
```

### `notify`

#### `notify.send(params)` → [`NotifyResult`](#notifyresult)

Publishes a multi-channel notification under a `topic`. **At least one event block** (`email`, `sms`, `mobile_push`, `web_push`, `webhook`, `messaging`, or `real_time`) must be a non-empty array — otherwise the SDK throws a `ValidationError` before any network call.

| Param         | Type                        | Required | Description                                  |
| ------------- | --------------------------- | -------- | -------------------------------------------- |
| `topic`       | `string`                    | yes      | Notification topic.                          |
| `email`       | `Record<string, unknown>[]` | cond.\*  | Email events.                                |
| `sms`         | `Record<string, unknown>[]` | cond.\*  | SMS events.                                  |
| `mobile_push` | `Record<string, unknown>[]` | cond.\*  | Mobile push events.                          |
| `web_push`    | `Record<string, unknown>[]` | cond.\*  | Web push events.                             |
| `webhook`     | `Record<string, unknown>[]` | cond.\*  | Webhook events.                              |
| `messaging`   | `Record<string, unknown>[]` | cond.\*  | Messaging (e.g. SMS/WhatsApp) events.        |
| `real_time`   | `Record<string, unknown>[]` | cond.\*  | Real-time events.                            |

\* At least one event block must be a non-empty array.

```ts
const res = await client.notify.send({
  topic: "welcome",
  sms: [{ to: "+2250700000000", message: "Welcome to Acme!" }],
});
console.log(res.status, res.queued_events);
```

## Pagination

List methods return a `Paginated<T>`:

```ts
interface Paginated<T> {
  items: T[];
  total: number;
  per_page: number;
  current_page: number;
  next: number | null;      // next page number, or null on the last page
  previous: number | null;  // previous page number, or null on the first page
  total_pages: number;
}
```

Iterate the current page via `result.items`, and follow `result.next` to page forward:

```ts
let page = await client.sms.list({ per_page: 50 });
while (true) {
  for (const msg of page.items) {
    console.log(msg.id, msg.status);
  }
  if (page.next === null) break;
  page = await client.sms.list({ per_page: 50, page: page.next });
}
```

## Typed models

All models below are exported from the package root. Read models are typed but **tolerant**: they are plain structural interfaces, so any extra fields the API returns are simply ignored at runtime (no validation or stripping happens).

### Result models

#### `Balance`

| Field                     | Type      | Description                                  |
| ------------------------- | --------- | -------------------------------------------- |
| `credit`                  | `number`  | Available credit.                            |
| `currency`                | `string`  | Currency code.                               |
| `sms_price_local`         | `number?` | Per-message price for local SMS.             |
| `sms_price_international` | `number?` | Per-message price for international SMS.     |

#### `SendSmsResult`

| Field             | Type        | Description                              |
| ----------------- | ----------- | ---------------------------------------- |
| `total_amount`    | `number`    | Total cost of the send.                  |
| `available_credit`| `number`    | Remaining credit after the send.         |
| `status`          | `string`    | Overall send status.                     |
| `recipient_count` | `number`    | Number of recipients.                    |
| `scheduled`       | `boolean`   | Whether the send was scheduled.          |
| `messages`        | `unknown[]` | Per-message details.                     |

#### `SendOtpResult`

| Field        | Type                       | Description                          |
| ------------ | -------------------------- | ------------------------------------ |
| `id`         | `string`                   | OTP id (use with `otp.verify`).      |
| `provider`   | `string`                   | Delivery channel used.               |
| `recipient`  | `Record<string, unknown>`  | Recipient details.                   |
| `expires_at` | `string`                   | Expiry timestamp (ISO 8601).         |
| `expires_in` | `number`                   | Seconds until expiry.                |

#### `VerifyOtpResult`

| Field         | Type              | Description                          |
| ------------- | ----------------- | ------------------------------------ |
| `id`          | `string`          | OTP id.                              |
| `status`      | `string`          | OTP status.                          |
| `verified`    | `boolean`         | Whether the code was correct.        |
| `verified_at` | `string \| null`  | Verification timestamp, or `null`.   |

#### `SendWaResult`

| Field          | Type        | Description                          |
| -------------- | ----------- | ------------------------------------ |
| `id`           | `string`    | Message id.                          |
| `instance_id`  | `string`    | Instance the message was sent from.  |
| `recipient`    | `unknown`   | Recipient details.                   |
| `message_type` | `string`    | Message type (text, media, …).       |
| `status`       | `string`    | Send status.                         |
| `scheduled`    | `boolean`   | Whether the send was scheduled.      |
| `media_url`    | `string?`   | Media URL, when applicable.          |

#### `NotifyResult`

| Field            | Type      | Description                          |
| ---------------- | --------- | ------------------------------------ |
| `status`         | `string`  | Overall status.                      |
| `topic`          | `unknown` | Topic details.                       |
| `queued_events`  | `unknown` | Queued-event details.                |
| `topic_messages` | `unknown` | Topic message details.               |

### Read models

#### `SmsMessage`

| Field         | Type             | Description                |
| ------------- | ---------------- | -------------------------- |
| `id`          | `string`         | Message id.                |
| `sender_name` | `string \| null` | Sender name.               |
| `recipient`   | `string \| null` | Recipient number.          |
| `content`     | `string`         | Message body.              |
| `status`      | `string`         | Delivery status.           |
| `created_at`  | `string`         | Created timestamp.         |
| `updated_at`  | `string`         | Last-updated timestamp.    |

#### `Otp`

`getStatus` returns the OTP id as `otp_id`; list rows return it as `id`. Both fields are therefore optional on the interface — read whichever is populated for your call.

| Field         | Type             | Description                                    |
| ------------- | ---------------- | ---------------------------------------------- |
| `otp_id`      | `string?`        | OTP id (populated by `otp.getStatus`).         |
| `id`          | `string?`        | OTP id (populated by `otp.list` rows).         |
| `status`      | `string`         | OTP status.                                    |
| `recipient`   | `string \| null` | Recipient.                                     |
| `expires_at`  | `string \| null` | Expiry timestamp.                              |
| `verified_at` | `string \| null` | Verification timestamp.                        |
| `attempts`    | `number \| null` | Number of verification attempts.               |
| `created_at`  | `string \| null` | Created timestamp.                             |

#### `WhatsAppInstance`

| Field                   | Type             | Description                          |
| ----------------------- | ---------------- | ------------------------------------ |
| `id`                    | `string`         | Instance id.                         |
| `code`                  | `string \| null` | Instance code (used in send calls).  |
| `client_id`             | `string`         | Owning client id.                    |
| `name`                  | `string`         | Instance name.                       |
| `phone_number`          | `string \| null` | Bound phone number.                  |
| `phone_name`            | `string \| null` | Phone display name.                  |
| `status`                | `string`         | Connection status.                   |
| `billing_status`        | `string`         | Billing status.                      |
| `trial_days_remaining`  | `number`         | Trial days left.                     |
| `monthly_fee`           | `number`         | Monthly fee.                         |
| `messages_sent_today`   | `number`         | Messages sent today.                 |
| `messages_sent_month`   | `number`         | Messages sent this month.            |
| `daily_limit`           | `number`         | Daily message limit.                 |
| `last_message_at`       | `string \| null` | Last message timestamp.              |
| `webhook_url`           | `string \| null` | Configured webhook URL.              |
| `is_active`             | `boolean`        | Whether the instance is active.      |
| `created_at`            | `string`         | Created timestamp.                   |
| `updated_at`            | `string`         | Last-updated timestamp.              |

#### `WhatsAppMessage`

| Field                  | Type                              | Description                       |
| ---------------------- | --------------------------------- | --------------------------------- |
| `id`                   | `string`                          | Message id.                       |
| `instance_id`          | `string`                          | Instance id.                      |
| `client_id`            | `string`                          | Client id.                        |
| `api_client_id`        | `string \| null`                  | API client id.                    |
| `recipient`            | `string`                          | Recipient.                        |
| `recipient_name`       | `string \| null`                  | Recipient name.                   |
| `message_type`         | `string`                          | Message type.                     |
| `content`              | `string \| null`                  | Text content.                     |
| `media_url`            | `string \| null`                  | Media URL.                        |
| `media_mimetype`       | `string \| null`                  | Media MIME type.                  |
| `media_filename`       | `string \| null`                  | Media file name.                  |
| `extra_data`           | `Record<string, unknown> \| null` | Extra payload.                    |
| `direction`            | `string`                          | `inbound` / `outbound`.           |
| `status`               | `string`                          | Delivery status.                  |
| `whatsapp_message_id`  | `string \| null`                  | Provider message id.              |
| `error_code`           | `number \| null`                  | Error code, if failed.            |
| `error_message`        | `string \| null`                  | Error message, if failed.         |
| `retry_count`          | `number`                          | Retry count.                      |
| `is_billable`          | `boolean`                         | Whether the message is billable.  |
| `cost`                 | `number`                          | Message cost.                     |
| `sent_at`              | `string \| null`                  | Sent timestamp.                   |
| `delivered_at`         | `string \| null`                  | Delivered timestamp.              |
| `read_at`              | `string \| null`                  | Read timestamp.                   |
| `scheduled_at`         | `string \| null`                  | Scheduled timestamp.              |
| `created_at`           | `string`                          | Created timestamp.                |
| `updated_at`           | `string`                          | Last-updated timestamp.           |
| `processor_identifier` | `string \| null`                  | Processor identifier.             |

### Helper shapes

#### `WaButton`

| Field   | Type     | Required | Description       |
| ------- | -------- | -------- | ----------------- |
| `id`    | `string` | yes      | Button id.        |
| `title` | `string` | yes      | Button label.     |

#### `WaListSection`

| Field   | Type           | Required | Description       |
| ------- | -------------- | -------- | ----------------- |
| `title` | `string`       | yes      | Section title.    |
| `rows`  | `WaListRow[]`  | yes      | Section rows.     |

#### `WaListRow`

| Field         | Type      | Required | Description       |
| ------------- | --------- | -------- | ----------------- |
| `id`          | `string`  | yes      | Row id.           |
| `title`       | `string`  | yes      | Row title.        |
| `description` | `string?` | no       | Row description.  |

### `Paginated<T>`

See [Pagination](#pagination).

## Enums

All enums are exported from the package root.

### `MessageStatus`

| Member      | Value         |
| ----------- | ------------- |
| `Pending`   | `"pending"`   |
| `Sent`      | `"sent"`      |
| `Delivered` | `"delivered"` |
| `Failed`    | `"failed"`    |

### `OtpStatus`

| Member     | Value        |
| ---------- | ------------ |
| `Pending`  | `"pending"`  |
| `Verified` | `"verified"` |
| `Expired`  | `"expired"`  |
| `Failed`   | `"failed"`   |

### `OtpType`

| Member         | Value            |
| -------------- | ---------------- |
| `Digit`        | `"digit"`        |
| `Alpha`        | `"alpha"`        |
| `Alphanumeric` | `"alphanumeric"` |

### `OtpProvider`

| Member     | Value         |
| ---------- | ------------- |
| `Sms`      | `"sms"`       |
| `Whatsapp` | `"whatsapp"`  |

### `WhatsAppMediaType`

| Member     | Value         |
| ---------- | ------------- |
| `Image`    | `"image"`     |
| `Video`    | `"video"`     |
| `Document` | `"document"`  |
| `Audio`    | `"audio"`     |

## Error handling

All errors extend the base `CallistoError`, which carries:

- `message: string`
- `statusCode: number`
- `body: unknown` — the parsed response body, when available.

| Class                 | When it's thrown                                            |
| --------------------- | ---------------------------------------------------------- |
| `CallistoError`       | Base class for all SDK errors.                             |
| `AuthenticationError` | HTTP `401`.                                                |
| `ValidationError`     | HTTP `400` or `422` (also thrown locally for invalid input). |
| `NotFoundError`       | HTTP `404`.                                                |
| `RateLimitError`      | HTTP `429`. Also exposes `retryAfter?: number` (seconds, parsed from the `Retry-After` header). |
| `ApiError`            | Any other non-2xx status.                                  |
| `NetworkError`        | Transport failure (e.g. timeout/abort, DNS). `statusCode` is `0` and the original error is on `cause`. |

All error classes are exported from the package root.

```ts
import {
  CallistoClient,
  RateLimitError,
  ValidationError,
  NetworkError,
} from "@callisto/sdk";

const client = new CallistoClient();

try {
  await client.sms.send({
    sender: "Acme",
    to: "+2250700000000",
    message: "Hello!",
  });
} catch (err) {
  if (err instanceof RateLimitError) {
    console.warn(`Rate limited. Retry after ${err.retryAfter ?? "?"}s`);
  } else if (err instanceof ValidationError) {
    console.error("Invalid request:", err.message, err.body);
  } else if (err instanceof NetworkError) {
    console.error("Network problem:", err.message, err.cause);
  } else {
    throw err;
  }
}
```

## Error reporting

The SDK ships an opt-in, Sentry-style error reporter. When you configure a **DSN**, the SDK auto-captures its own `CallistoError`s (API + network + client-side validation failures) and POSTs them to the Callisto error-tracking ingest endpoint. You can also report your application's own exceptions. Delivery is **background and best-effort** — it never delays, alters, or swallows the original error.

When no DSN is configured the reporter is a complete no-op: nothing is sent and the SDK behaves exactly as before.

### Enabling

Pass `errorDsn` (or set `CALLISTO_ERROR_DSN`). The DSN **is** the full ingest POST URL, e.g. `https://app.callistosignal.com/ingest/<id>?key=<public_key>`:

```ts
const client = new CallistoClient({
  clientId: "your_client_id",
  apiKey: "your_api_key",
  errorDsn: "https://app.callistosignal.com/ingest/<id>?key=<public_key>",
  environment: "production", // optional, tagged as context.environment
});
```

### Environment variables

| Env var                      | Falls back for     | Notes                                       |
| ---------------------------- | ------------------ | ------------------------------------------- |
| `CALLISTO_ERROR_DSN`         | `errorDsn`         | Absent → reporting disabled.                |
| `CALLISTO_CAPTURE_UNHANDLED` | `captureUnhandled` | Truthy (`1` / `true`) installs the handler. |
| `CALLISTO_ENVIRONMENT`       | `environment`      | Optional environment tag.                   |

### Public API

```ts
client.captureException(error, level?, extra?); // level defaults to "error"
client.captureMessage("something happened", level?, extra?); // level defaults to "info"
client.setUser({ id: "u1", email: "a@b.com" }); // attach a user to subsequent events; pass null to clear
await client.close(); // flush in-flight reports (and remove any installed global handlers)
```

`level` is constrained to `fatal | error | warning | info` (anything else falls back to `error`). `extra` is merged into `context`. The reporter is also reachable directly as `client.errorReporter` for advanced use.

### Opt-in global handler

Set `captureUnhandled: true` (or `CALLISTO_CAPTURE_UNHANDLED=true`) **and** a DSN to install a global handler that captures uncaught errors at `level = "fatal"`:

```ts
const client = new CallistoClient({
  errorDsn: process.env.CALLISTO_ERROR_DSN,
  captureUnhandled: true,
});
```

This registers `process.on("uncaughtException")` and `process.on("unhandledRejection")` **without removing any existing listeners** (it chains, preserving Node's default behavior). It is only installed in Node-like environments (where `process.on` exists); in browsers/workers it is silently skipped. `client.close()` removes the handlers the SDK added.

### PII / secrets guarantee

The reporter uses its **own** minimal `fetch` path straight to the DSN — never the main API transport — so it never inherits or transmits your credentials. It will **never** send your `clientId`, `apiKey`, the `Authorization` header, or the **outgoing request body** (which carries phone numbers and message content). Only the server's error `body`, `status_code`, HTTP `method`, and request `path` leave the process. This is enforced by tests.

## TypeScript

- Full type definitions ship with the package (`dist/index.d.ts`); no separate `@types` install is needed.
- The package is **ESM-only** (`"type": "module"`). Use `import`, target Node 18+, and ensure your project is configured for ES modules.
