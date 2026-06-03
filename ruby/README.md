# callisto-sdk (Ruby)

Official Callisto messaging API SDK for Ruby 3.0+.

## Requirements

- Ruby 3.0+
- Synchronous HTTP client built on the standard library `Net::HTTP` and `json` — **no runtime gem dependencies**.

## Install

Add to your `Gemfile`:

```ruby
gem "callisto-sdk"
```

Then:

```bash
bundle install
```

Or install directly:

```bash
gem install callisto-sdk
```

## Configuration

Create a `Callisto::Client` with your credentials. Authentication is HTTP Basic (`client_id` / `api_key`) and is applied automatically to every request.

```ruby
require "callisto"

callisto = Callisto::Client.new(
  client_id: "your-client-id",
  api_key: "your-api-key"
)
```

### Constructor

```ruby
Callisto::Client.new(
  client_id: nil,   # String, falls back to ENV["CALLISTO_CLIENT_ID"]
  api_key:   nil,   # String, falls back to ENV["CALLISTO_API_KEY"]
  base_url:  nil,   # String, falls back to ENV["CALLISTO_BASE_URL"], then the default
  timeout:   30.0,  # Float, request timeout in seconds
  transport: nil    # Callisto::Transport, optional seam for testing
)
```

| Param | Type | Default | Description |
| --- | --- | --- | --- |
| `client_id` | `String` | `nil` | Your Callisto client ID. Falls back to env `CALLISTO_CLIENT_ID`. Required. |
| `api_key` | `String` | `nil` | Your Callisto API key. Falls back to env `CALLISTO_API_KEY`. Required. |
| `base_url` | `String` | `https://api.callistosignal.com/v1` | API base URL. Falls back to env `CALLISTO_BASE_URL`. Trailing slash is stripped. |
| `timeout` | `Float` | `30.0` | Request timeout in seconds (applied to open, read, and write). |
| `transport` | `Callisto::Transport` | `nil` | Optional pre-built transport to inject (advanced use, e.g. testing). |

`client_id` and `api_key` are required: pass them as keyword arguments or via the `CALLISTO_CLIENT_ID` / `CALLISTO_API_KEY` environment variables. If neither is available, the constructor raises `Callisto::ValidationError`.

### Environment variables

| Variable | Maps to |
| --- | --- |
| `CALLISTO_CLIENT_ID` | `client_id` |
| `CALLISTO_API_KEY` | `api_key` |
| `CALLISTO_BASE_URL` | `base_url` |

```ruby
ENV["CALLISTO_CLIENT_ID"] = "your-client-id"
ENV["CALLISTO_API_KEY"]   = "your-api-key"

require "callisto"
callisto = Callisto::Client.new # reads credentials from the environment
```

### Lifecycle

`Callisto::Client` exposes `#close` and a block form that auto-closes when the block returns (even on error).

```ruby
# Block form — auto-closes
Callisto::Client.new(client_id: "...", api_key: "...") do |callisto|
  balance = callisto.balance.get
end
# closed automatically

# Explicit close
callisto = Callisto::Client.new(client_id: "...", api_key: "...")
begin
  balance = callisto.balance.get
ensure
  callisto.close
end
```

## Quick start

```ruby
require "callisto"

Callisto::Client.new(client_id: "your-client-id", api_key: "your-api-key") do |callisto|
  # Check your balance
  balance = callisto.balance.get
  puts "Credit: #{balance.credit} #{balance.currency}"

  # Send an SMS
  result = callisto.sms.send(
    sender: "Acme",
    to: "+2250700000000",
    message: "Welcome to Acme!"
  )
  puts result.status
end
```

## Resources

Each resource is accessed via a reader on the client: `callisto.balance`, `callisto.sms`, `callisto.otp`, `callisto.whatsapp`, `callisto.notify`. All methods use snake_case names and keyword arguments.

### balance

#### `balance.get(format: "full", currency: nil)`

Returns the account balance. Returns [`Balance`](#balance-1). → `GET /sms/balance`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `format` | `String` | No | Response format. Defaults to `"full"`. |
| `currency` | `String` | No | Filter the balance by currency code. |

```ruby
balance = callisto.balance.get
puts "#{balance.credit} #{balance.currency}"
```

### sms

#### `sms.send(sender:, to:, message:, notify_url: nil, scheduled_at: nil)`

Sends an SMS to one or more recipients. Returns [`SendSmsResult`](#sendsmsresult). → `POST /sms/send`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `sender` | `String` | Yes | Approved sender name. |
| `to` | `String` \| `Array<String>` | Yes | A single recipient number, or an array of numbers. |
| `message` | `String` | Yes | Message body. |
| `notify_url` | `String` | No | Webhook URL for delivery status callbacks. |
| `scheduled_at` | `String` | No | Schedule delivery (e.g. `"2026-06-02 10:00:00"`). |

```ruby
result = callisto.sms.send(
  sender: "Acme",
  to: "+2250700000000",
  message: "Your code is 1234"
)

# Bulk + scheduled
callisto.sms.send(
  sender: "Acme",
  to: ["+2250700000000", "+2250700000001"],
  message: "Sale starts tomorrow!",
  notify_url: "https://example.com/webhooks/sms",
  scheduled_at: "2026-06-02 10:00:00"
)
```

#### `sms.list(started_at: nil, ended_at: nil, page: nil, per_page: nil)`

Lists sent SMS messages. Returns [`Paginated`](#paginated) of [`SmsMessage`](#smsmessage). → `GET /sms/messages`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `started_at` | `String` | No | Filter from this date/time. |
| `ended_at` | `String` | No | Filter up to this date/time. |
| `page` | `Integer` | No | Page number. |
| `per_page` | `Integer` | No | Items per page. |

```ruby
page = callisto.sms.list(page: 1, per_page: 50)
page.items.each { |msg| puts "#{msg.id} #{msg.status}" }
```

#### `sms.get_status(message_id)`

Fetches a single SMS by ID. Returns [`SmsMessage`](#smsmessage). → `GET /sms/{id}`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `message_id` | `String` | Yes | The message ID. |

```ruby
msg = callisto.sms.get_status("abc")
puts msg.status
```

### otp

#### `otp.send(to:, message:, sender: nil, expired_in: nil, type: nil, digit_size: nil, provider: nil, instance_code: nil)`

Generates and sends a one-time password. Returns [`SendOtpResult`](#sendotpresult). → `POST /otp/send`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `to` | `String` | Yes | Recipient number. |
| `message` | `String` | Yes | Message template (the generated code is interpolated by the API). |
| `sender` | `String` | No | Sender name. |
| `expired_in` | `Integer` | No | Code lifetime in seconds. |
| `type` | [`OtpType`](#enums) \| `String` | No | Code character set (`digit`, `alpha`, `alphanumeric`). Accepts an enum constant or a raw string. |
| `digit_size` | `Integer` | No | Number of characters in the code. |
| `provider` | [`OtpProvider`](#enums) \| `String` | No | Delivery channel (`sms` or `whatsapp`). Accepts an enum constant or a raw string. |
| `instance_code` | `String` | No | WhatsApp instance code. **Required when `provider` is `whatsapp`** — otherwise raises [`Callisto::ValidationError`](#error-handling) before any request is made. Sent to the API as `instanceCode`. |

```ruby
result = callisto.otp.send(
  to: "+2250700000000",
  message: "Your Acme code is {code}",
  type: Callisto::OtpType::DIGIT,
  digit_size: 6,
  expired_in: 300
)
puts result.id

# Over WhatsApp (instance_code required)
callisto.otp.send(
  to: "+2250700000000",
  message: "Your Acme code is {code}",
  provider: Callisto::OtpProvider::WHATSAPP,
  instance_code: "inst_1"
)
```

#### `otp.verify(otp_id:, code:)`

Verifies a code against an OTP. Returns [`VerifyOtpResult`](#verifyotpresult). → `POST /otp/verify`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `otp_id` | `String` | Yes | The OTP ID returned by `send`. |
| `code` | `String` | Yes | The code submitted by the user. |

```ruby
result = callisto.otp.verify(otp_id: "otp_123", code: "123456")
puts "Verified!" if result.verified
```

#### `otp.get_status(otp_id)`

Fetches a single OTP by ID. Returns [`Otp`](#otp). → `GET /otps/{id}`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `otp_id` | `String` | Yes | The OTP ID. |

```ruby
otp = callisto.otp.get_status("otp_123")
puts otp.status
```

#### `otp.list(started_at: nil, ended_at: nil, page: nil, limit: nil)`

Lists OTPs. Returns [`Paginated`](#paginated) of [`Otp`](#otp). → `GET /otps`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `started_at` | `String` | No | Filter from this date/time. |
| `ended_at` | `String` | No | Filter up to this date/time. |
| `page` | `Integer` | No | Page number. |
| `limit` | `Integer` | No | Items per page (query key `limit`). |

```ruby
page = callisto.otp.list(page: 1, limit: 20)
page.items.each { |otp| puts "#{otp.id} #{otp.status}" }
```

### whatsapp

#### `whatsapp.create_instance(name:, phone_number: nil, webhook_url: nil, idempotency_key: nil)`

Creates a WhatsApp instance. Returns [`WhatsAppInstance`](#whatsappinstance). → `POST /whatsapp/instances`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | `String` | Yes | Instance display name. |
| `phone_number` | `String` | No | Phone number to attach. |
| `webhook_url` | `String` | No | Webhook URL for incoming events. |
| `idempotency_key` | `String` | No | Key to safely retry creation. |

```ruby
instance = callisto.whatsapp.create_instance(
  name: "Main",
  phone_number: "+2250700000000",
  webhook_url: "https://example.com/webhooks/whatsapp"
)
puts "#{instance.code} #{instance.status}"
```

#### `whatsapp.list_instances(page: 1)`

Lists WhatsApp instances. Returns [`Paginated`](#paginated) of [`WhatsAppInstance`](#whatsappinstance). → `GET /whatsapp/instances`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `page` | `Integer` | No | Page number. Defaults to `1`. |

```ruby
page = callisto.whatsapp.list_instances(page: 1)
page.items.each { |inst| puts "#{inst.code} #{inst.name}" }
```

#### `whatsapp.get_instance(code)`

Fetches a single instance. Returns [`WhatsAppInstance`](#whatsappinstance). → `GET /whatsapp/{code}`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `String` | Yes | Instance code. |

```ruby
instance = callisto.whatsapp.get_instance("inst_1")
```

#### `whatsapp.get_qr(code)`

Fetches the QR code used to link the instance. Returns the **raw decoded payload** (no typed model). → `GET /whatsapp/{code}/qr`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `String` | Yes | Instance code. |

```ruby
qr = callisto.whatsapp.get_qr("inst_1")
puts qr["qr_code"]
```

#### `whatsapp.get_status(code)`

Fetches the connection status of an instance. Returns the **raw decoded payload** (no typed model). → `GET /whatsapp/{code}/status`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `String` | Yes | Instance code. |

```ruby
status = callisto.whatsapp.get_status("inst_1")
puts status["status"]
```

#### `whatsapp.list_messages(code, started_at: nil, ended_at: nil, page: nil, per_page: nil)`

Lists messages for an instance. Returns [`Paginated`](#paginated) of [`WhatsAppMessage`](#whatsappmessage). → `GET /whatsapp/{code}/messages`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `String` | Yes | Instance code (positional). |
| `started_at` | `String` | No | Filter from this date/time. |
| `ended_at` | `String` | No | Filter up to this date/time. |
| `page` | `Integer` | No | Page number. |
| `per_page` | `Integer` | No | Items per page. |

```ruby
page = callisto.whatsapp.list_messages("inst_1", page: 1)
page.items.each { |msg| puts "#{msg.id} #{msg.status}" }
```

#### `whatsapp.get_message(message_id)`

Fetches a single WhatsApp message. Returns [`WhatsAppMessage`](#whatsappmessage). → `GET /whatsapp/messages/{id}`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `message_id` | `String` | Yes | The message ID. |

```ruby
msg = callisto.whatsapp.get_message("msg_9")
puts "#{msg.status} #{msg.cost}"
```

#### `whatsapp.send_text(code, to:, message:, scheduled_at: nil)`

Sends a text message. Returns [`SendWaResult`](#sendwaresult). → `POST /whatsapp/{code}/send/text`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `String` | Yes | Instance code (positional). |
| `to` | `String` | Yes | Recipient number. |
| `message` | `String` | Yes | Message body. |
| `scheduled_at` | `String` | No | Schedule delivery. |

```ruby
result = callisto.whatsapp.send_text("inst_1", to: "+2250700000000", message: "Hi!")
```

#### `whatsapp.send_media(code, to:, type:, media_url:, caption: nil, filename: nil, scheduled_at: nil)`

Sends a media message. Returns [`SendWaResult`](#sendwaresult). → `POST /whatsapp/{code}/send/media`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `String` | Yes | Instance code (positional). |
| `to` | `String` | Yes | Recipient number. |
| `type` | [`WhatsAppMediaType`](#enums) \| `String` | Yes | Media type (`image`, `video`, `document`, `audio`). Accepts an enum constant or a raw string. |
| `media_url` | `String` | Yes | Publicly accessible media URL. |
| `caption` | `String` | No | Caption text. |
| `filename` | `String` | No | File name (useful for documents). |
| `scheduled_at` | `String` | No | Schedule delivery. |

```ruby
callisto.whatsapp.send_media(
  "inst_1",
  to: "+2250700000000",
  type: Callisto::WhatsAppMediaType::IMAGE,
  media_url: "https://example.com/promo.jpg",
  caption: "New arrivals"
)
```

#### `whatsapp.send_buttons(code, to:, body:, buttons:, header: nil, footer: nil, scheduled_at: nil)`

Sends an interactive buttons message. Returns [`SendWaResult`](#sendwaresult). → `POST /whatsapp/{code}/send/buttons`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `String` | Yes | Instance code (positional). |
| `to` | `String` | Yes | Recipient number. |
| `body` | `String` | Yes | Message body. |
| `buttons` | `Array` | Yes | Array of button hashes (e.g. `{ "id" => "1", "title" => "Yes" }`). |
| `header` | `String` | No | Header text. |
| `footer` | `String` | No | Footer text. |
| `scheduled_at` | `String` | No | Schedule delivery. |

```ruby
callisto.whatsapp.send_buttons(
  "inst_1",
  to: "+2250700000000",
  body: "Confirm your order?",
  buttons: [{ "id" => "yes", "title" => "Yes" }, { "id" => "no", "title" => "No" }]
)
```

#### `whatsapp.send_location(code, to:, latitude:, longitude:, name: nil, address: nil, scheduled_at: nil)`

Sends a location message. Returns [`SendWaResult`](#sendwaresult). → `POST /whatsapp/{code}/send/location`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `String` | Yes | Instance code (positional). |
| `to` | `String` | Yes | Recipient number. |
| `latitude` | `Float` | Yes | Latitude. |
| `longitude` | `Float` | Yes | Longitude. |
| `name` | `String` | No | Location name. |
| `address` | `String` | No | Address text. |
| `scheduled_at` | `String` | No | Schedule delivery. |

```ruby
callisto.whatsapp.send_location(
  "inst_1",
  to: "+2250700000000",
  latitude: 5.3599517,
  longitude: -4.0082563,
  name: "Acme HQ",
  address: "Abidjan, Côte d'Ivoire"
)
```

#### `whatsapp.send_list(code, to:, body:, button_text:, sections:, header: nil, footer: nil, scheduled_at: nil)`

Sends an interactive list message. Returns [`SendWaResult`](#sendwaresult). → `POST /whatsapp/{code}/send/list`

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `String` | Yes | Instance code (positional). |
| `to` | `String` | Yes | Recipient number. |
| `body` | `String` | Yes | Message body. |
| `button_text` | `String` | Yes | Label of the list trigger button. |
| `sections` | `Array` | Yes | Array of section hashes (each with rows). |
| `header` | `String` | No | Header text. |
| `footer` | `String` | No | Footer text. |
| `scheduled_at` | `String` | No | Schedule delivery. |

```ruby
callisto.whatsapp.send_list(
  "inst_1",
  to: "+2250700000000",
  body: "Pick a plan",
  button_text: "View plans",
  sections: [{
    "title" => "Plans",
    "rows" => [
      { "id" => "basic", "title" => "Basic" },
      { "id" => "pro", "title" => "Pro" }
    ]
  }]
)
```

### notify

#### `notify.send(topic:, email: nil, sms: nil, mobile_push: nil, web_push: nil, webhook: nil, messaging: nil, real_time: nil)`

Sends a multi-channel notification to a topic. Returns [`NotifyResult`](#notifyresult). → `POST /notify/send`

At least one event block must be provided — otherwise a [`Callisto::ValidationError`](#error-handling) is raised before any request is made. An empty array counts as absent. Each event block is an array of channel-specific event hashes. JSON keys are snake_case.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `topic` | `String` | Yes | Topic identifier. |
| `email` | `Array` | No* | Email events. |
| `sms` | `Array` | No* | SMS events. |
| `mobile_push` | `Array` | No* | Mobile push events. |
| `web_push` | `Array` | No* | Web push events. |
| `webhook` | `Array` | No* | Webhook events. |
| `messaging` | `Array` | No* | Messaging events. |
| `real_time` | `Array` | No* | Real-time events. |

\* At least one event block is required.

```ruby
result = callisto.notify.send(
  topic: "welcome",
  sms: [{ "to" => "+2250700000000" }],
  email: [{ "to" => "user@example.com" }]
)
puts result.status
```

## Pagination

List methods return a `Callisto::Paginated`. Iterate the typed rows via `#items`; the other readers describe the page window.

| Field | Type | Description |
| --- | --- | --- |
| `items` | `Array<T>` | The rows on this page (typed model instances). |
| `total` | `Integer` | Total number of items across all pages. |
| `per_page` | `Integer` | Items per page. |
| `current_page` | `Integer` | Current page number. |
| `next` | `Integer` \| `nil` | Next page number, or `nil`. |
| `previous` | `Integer` \| `nil` | Previous page number, or `nil`. |
| `total_pages` | `Integer` | Total number of pages. |

```ruby
page = callisto.sms.list(page: 1, per_page: 50)
page.items.each { |msg| puts "#{msg.id} #{msg.status}" }

puts "Page #{page.current_page} of #{page.total_pages} (#{page.total} total)"

# Manual paging
next_page = page.next
until next_page.nil?
  page = callisto.sms.list(page: next_page, per_page: 50)
  page.items.each { |msg| puts msg.id }
  next_page = page.next
end
```

## Typed models

All read models inherit from `Callisto::Model` and expose their fields as readers. **Read models** (those returned by `get`/`list` methods) tolerate unknown/extra fields returned by the API: `from_hash` keeps only the declared fields and silently drops the rest, so new API fields will not break deserialization. Each model also exposes `#to_h`.

### Result models

#### `Balance`

| Field | Type |
| --- | --- |
| `credit` | `Float` |
| `currency` | `String` |
| `sms_price_local` | `Float` \| `nil` |
| `sms_price_international` | `Float` \| `nil` |

#### `SendSmsResult`

| Field | Type |
| --- | --- |
| `total_amount` | `Float` |
| `available_credit` | `Float` |
| `status` | `String` |
| `recipient_count` | `Integer` |
| `scheduled` | `Boolean` |
| `messages` | `Array` |

#### `SendOtpResult`

| Field | Type |
| --- | --- |
| `id` | `String` |
| `provider` | `String` |
| `recipient` | `Hash` |
| `expires_at` | `String` |
| `expires_in` | `Integer` |

#### `VerifyOtpResult`

| Field | Type |
| --- | --- |
| `id` | `String` |
| `status` | `String` |
| `verified` | `Boolean` |
| `verified_at` | `String` \| `nil` |

#### `SendWaResult`

| Field | Type |
| --- | --- |
| `id` | `String` |
| `instance_id` | `String` |
| `recipient` | `Object` |
| `message_type` | `String` |
| `status` | `String` |
| `scheduled` | `Boolean` |
| `media_url` | `String` \| `nil` |

#### `NotifyResult`

| Field | Type |
| --- | --- |
| `status` | `String` |
| `topic` | `Object` |
| `queued_events` | `Object` |
| `topic_messages` | `Object` |

### Read models

#### `SmsMessage`

| Field | Type |
| --- | --- |
| `id` | `String` |
| `sender_name` | `String` \| `nil` |
| `recipient` | `String` \| `nil` |
| `content` | `String` \| `nil` |
| `status` | `String` \| `nil` |
| `created_at` | `String` \| `nil` |
| `updated_at` | `String` \| `nil` |

#### `Otp`

Carries both `otp_id` (populated by `get_status`) and `id` (populated by `list` rows) — depending on the endpoint, one or the other may be set.

| Field | Type |
| --- | --- |
| `otp_id` | `String` \| `nil` |
| `id` | `String` \| `nil` |
| `status` | `String` \| `nil` |
| `recipient` | `String` \| `nil` |
| `expires_at` | `String` \| `nil` |
| `verified_at` | `String` \| `nil` |
| `attempts` | `Integer` \| `nil` |
| `created_at` | `String` \| `nil` |

#### `WhatsAppInstance`

| Field | Type |
| --- | --- |
| `id` | `String` |
| `code` | `String` \| `nil` |
| `client_id` | `String` \| `nil` |
| `name` | `String` \| `nil` |
| `phone_number` | `String` \| `nil` |
| `phone_name` | `String` \| `nil` |
| `status` | `String` \| `nil` |
| `billing_status` | `String` \| `nil` |
| `trial_days_remaining` | `Integer` \| `nil` |
| `monthly_fee` | `Float` \| `nil` |
| `messages_sent_today` | `Integer` \| `nil` |
| `messages_sent_month` | `Integer` \| `nil` |
| `daily_limit` | `Integer` \| `nil` |
| `last_message_at` | `String` \| `nil` |
| `webhook_url` | `String` \| `nil` |
| `is_active` | `Boolean` \| `nil` |
| `created_at` | `String` \| `nil` |
| `updated_at` | `String` \| `nil` |

#### `WhatsAppMessage`

| Field | Type |
| --- | --- |
| `id` | `String` |
| `instance_id` | `String` \| `nil` |
| `client_id` | `String` \| `nil` |
| `api_client_id` | `String` \| `nil` |
| `recipient` | `String` \| `nil` |
| `recipient_name` | `String` \| `nil` |
| `message_type` | `String` \| `nil` |
| `content` | `String` \| `nil` |
| `media_url` | `String` \| `nil` |
| `media_mimetype` | `String` \| `nil` |
| `media_filename` | `String` \| `nil` |
| `extra_data` | `Hash` \| `nil` |
| `direction` | `String` \| `nil` |
| `status` | `String` \| `nil` |
| `whatsapp_message_id` | `String` \| `nil` |
| `error_code` | `Integer` \| `nil` |
| `error_message` | `String` \| `nil` |
| `retry_count` | `Integer` \| `nil` |
| `is_billable` | `Boolean` \| `nil` |
| `cost` | `Float` \| `nil` |
| `sent_at` | `String` \| `nil` |
| `delivered_at` | `String` \| `nil` |
| `read_at` | `String` \| `nil` |
| `scheduled_at` | `String` \| `nil` |
| `created_at` | `String` \| `nil` |
| `updated_at` | `String` \| `nil` |
| `processor_identifier` | `String` \| `nil` |

### `Paginated`

Generic page container. See [Pagination](#pagination) for fields and usage.

### Raw responses

`whatsapp.get_qr(code)` and `whatsapp.get_status(code)` return the raw payload decoded from the API response (typically a `Hash`) — they are not mapped to a typed model.

## Enums

Enums are plain frozen `String` constants grouped under a module, so they compare equal to and serialize as their string value. Anywhere an enum is accepted, you may also pass the raw string.

| Enum module | Members (value) |
| --- | --- |
| `Callisto::MessageStatus` | `PENDING` (`"pending"`), `SENT` (`"sent"`), `DELIVERED` (`"delivered"`), `FAILED` (`"failed"`) |
| `Callisto::OtpStatus` | `PENDING` (`"pending"`), `VERIFIED` (`"verified"`), `EXPIRED` (`"expired"`), `FAILED` (`"failed"`) |
| `Callisto::OtpType` | `DIGIT` (`"digit"`), `ALPHA` (`"alpha"`), `ALPHANUMERIC` (`"alphanumeric"`) |
| `Callisto::OtpProvider` | `SMS` (`"sms"`), `WHATSAPP` (`"whatsapp"`) |
| `Callisto::WhatsAppMediaType` | `IMAGE` (`"image"`), `VIDEO` (`"video"`), `DOCUMENT` (`"document"`), `AUDIO` (`"audio"`) |

```ruby
callisto.otp.send(to: "+225", message: "{code}", type: Callisto::OtpType::DIGIT)
# equivalent to:
callisto.otp.send(to: "+225", message: "{code}", type: "digit")
```

## Error handling

All SDK errors derive from `Callisto::CallistoError` (a `StandardError`). Every error carries `#message` (`String`), `#status_code` (`Integer`, `0` for transport-level failures), and `#body` (the decoded response body, when available).

| Class | Raised when |
| --- | --- |
| `Callisto::CallistoError` | Base class for all SDK errors. |
| `Callisto::AuthenticationError` | HTTP 401 — invalid credentials. |
| `Callisto::ValidationError` | HTTP 400 / 422 — invalid request. Also raised client-side before a request (e.g. `notify.send` with no event block, `otp.send` with `provider: "whatsapp"` and no `instance_code`, or missing credentials). |
| `Callisto::NotFoundError` | HTTP 404 — resource not found. |
| `Callisto::RateLimitError` | HTTP 429 — rate limited. Adds `#retry_after` (`Integer` \| `nil`, seconds, parsed from the `Retry-After` header). |
| `Callisto::ApiError` | Any other non-2xx HTTP status. |
| `Callisto::NetworkError` | Transport-level failure (connection error, timeout, DNS, etc.). `#status_code` is `0`. |

```ruby
Callisto::Client.new(client_id: "...", api_key: "...") do |callisto|
  begin
    callisto.sms.send(sender: "Acme", to: "+2250700000000", message: "Hi")
  rescue Callisto::RateLimitError => e
    wait = e.retry_after || 5
    puts "Rate limited, retrying in #{wait}s"
    sleep(wait)
  rescue Callisto::AuthenticationError
    puts "Check your client_id / api_key"
  rescue Callisto::ValidationError => e
    puts "Invalid request: #{e.message}"
  rescue Callisto::NetworkError => e
    puts "Network problem: #{e.message}"
  rescue Callisto::CallistoError => e
    puts "API error (#{e.status_code}): #{e.message}"
  end
end
```

## Development

```bash
bundle install
bundle exec rspec
```

Tests use RSpec and WebMock; no network access is required.

## License

MIT
