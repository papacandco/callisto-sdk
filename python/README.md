# callisto-sdk (Python)

Official Callisto messaging API SDK for Python 3.9+.

## Requirements

- Python 3.9+
- Synchronous HTTP client built on [`httpx`](https://www.python-httpx.org/).

## Install

```bash
pip install callisto-sdk
```

## Configuration

Create a `Client` with your credentials. Authentication is HTTP Basic (`client_id` / `api_key`) and is applied automatically to every request.

```python
from callisto_sdk import Client

callisto = Client(
    client_id="your-client-id",
    api_key="your-api-key",
)
```

### Constructor

```python
Client(
    client_id: Optional[str] = None,
    api_key: Optional[str] = None,
    base_url: Optional[str] = None,
    timeout: float = 30.0,
    http_client: Optional[httpx.Client] = None,
    error_dsn: Optional[str] = None,
    capture_unhandled: Optional[bool] = None,
    environment: Optional[str] = None,
)
```

| Param | Type | Default | Description |
| --- | --- | --- | --- |
| `client_id` | `str` | `None` | Your Callisto client ID. Falls back to env `CALLISTO_CLIENT_ID`. Required. |
| `api_key` | `str` | `None` | Your Callisto API key. Falls back to env `CALLISTO_API_KEY`. Required. |
| `base_url` | `str` | `https://api.callistosignal.com/v1` | API base URL. Falls back to env `CALLISTO_BASE_URL`. Trailing slash is stripped. |
| `timeout` | `float` | `30.0` | Request timeout in seconds. |
| `http_client` | `httpx.Client` | `None` | Optional pre-configured `httpx.Client` to inject (advanced use, e.g. custom transport, proxies, or testing). When provided, the SDK uses it as-is and Basic auth/timeout are **not** applied automatically — configure those on the client you pass in. |
| `error_dsn` | `str` | `None` | Error-reporting ingest DSN. Falls back to env `CALLISTO_APP_ERROR_DSN`. Absent → error reporting is fully disabled (no-op). See [Error reporting](#error-reporting). |
| `capture_unhandled` | `bool` | `False` | Install a global unhandled-exception handler. Falls back to env `CALLISTO_CAPTURE_UNHANDLED`. Requires `error_dsn`. |
| `environment` | `str` | `None` | Optional environment tag (e.g. `"production"`) attached to reported errors. Falls back to env `CALLISTO_ENVIRONMENT`. |

`client_id` and `api_key` are required: pass them as arguments or via the `CALLISTO_CLIENT_ID` / `CALLISTO_API_KEY` environment variables. If neither is available, the constructor raises `ValueError`.

### Environment variables

| Variable | Maps to |
| --- | --- |
| `CALLISTO_CLIENT_ID` | `client_id` |
| `CALLISTO_API_KEY` | `api_key` |
| `CALLISTO_BASE_URL` | `base_url` |
| `CALLISTO_APP_ERROR_DSN` | `error_dsn` |
| `CALLISTO_CAPTURE_UNHANDLED` | `capture_unhandled` |
| `CALLISTO_ENVIRONMENT` | `environment` |

```python
import os
os.environ["CALLISTO_CLIENT_ID"] = "your-client-id"
os.environ["CALLISTO_API_KEY"] = "your-api-key"

from callisto_sdk import Client
callisto = Client()  # reads credentials from the environment
```

### Lifecycle

`Client` is a context manager and owns an `httpx.Client`. Use a `with` block, or call `.close()` explicitly to release the connection pool.

```python
with Client(client_id="...", api_key="...") as callisto:
    balance = callisto.balance.get()
# connection closed automatically on exit
```

```python
callisto = Client(client_id="...", api_key="...")
try:
    balance = callisto.balance.get()
finally:
    callisto.close()
```

## Quick start

```python
from callisto_sdk import Client

with Client(client_id="your-client-id", api_key="your-api-key") as callisto:
    # Check your balance
    balance = callisto.balance.get()
    print(f"Credit: {balance.credit} {balance.currency}")

    # Send an SMS
    result = callisto.sms.send(
        sender="Acme",
        to="+2250700000000",
        message="Welcome to Acme!",
    )
    print(result.status)
```

## Resources

Each resource is accessed as an attribute on the client: `callisto.balance`, `callisto.sms`, `callisto.otp`, `callisto.whatsapp`, `callisto.notify`.

### balance

#### `balance.get(format="full", currency=None)`

Returns the account balance. Returns [`Balance`](#balance-1).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `format` | `str` | No | Response format. Defaults to `"full"`. |
| `currency` | `str` | No | Filter the balance by currency code. |

```python
balance = callisto.balance.get()
print(balance.credit, balance.currency)
```

### sms

#### `sms.send(sender, to, message, notify_url=None, scheduled_at=None)`

Sends an SMS to one or more recipients. Returns [`SendSmsResult`](#sendsmsresult).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `sender` | `str` | Yes | Approved sender name. |
| `to` | `str` \| `list` | Yes | A single recipient number, or a list of numbers. |
| `message` | `str` | Yes | Message body. |
| `notify_url` | `str` | No | Webhook URL for delivery status callbacks. |
| `scheduled_at` | `str` | No | Schedule delivery (e.g. `"2026-06-02 10:00:00"`). |

```python
result = callisto.sms.send(
    sender="Acme",
    to="+2250700000000",
    message="Your code is 1234",
)

# Bulk + scheduled
callisto.sms.send(
    sender="Acme",
    to=["+2250700000000", "+2250700000001"],
    message="Sale starts tomorrow!",
    notify_url="https://example.com/webhooks/sms",
    scheduled_at="2026-06-02 10:00:00",
)
```

#### `sms.list(started_at=None, ended_at=None, page=None, per_page=None)`

Lists sent SMS messages. Returns [`Paginated[SmsMessage]`](#paginated).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `started_at` | `str` | No | Filter from this date/time. |
| `ended_at` | `str` | No | Filter up to this date/time. |
| `page` | `int` | No | Page number. |
| `per_page` | `int` | No | Items per page. |

```python
page = callisto.sms.list(page=1, per_page=50)
for msg in page.items:
    print(msg.id, msg.status)
```

#### `sms.get_status(message_id)`

Fetches a single SMS by ID. Returns [`SmsMessage`](#smsmessage).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `message_id` | `str` | Yes | The message ID. |

```python
msg = callisto.sms.get_status("abc")
print(msg.status)
```

### otp

#### `otp.send(to, message, sender=None, expired_in=None, type=None, digit_size=None, provider=None, instance_code=None)`

Generates and sends a one-time password. Returns [`SendOtpResult`](#sendotpresult).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `to` | `str` | Yes | Recipient number. |
| `message` | `str` | Yes | Message template (the generated code is interpolated by the API). |
| `sender` | `str` | No | Sender name. |
| `expired_in` | `int` | No | Code lifetime in seconds. |
| `type` | [`OtpType`](#enums) \| `str` | No | Code character set (`digit`, `alpha`, `alphanumeric`). Accepts the enum or a raw string. |
| `digit_size` | `int` | No | Number of characters in the code. |
| `provider` | [`OtpProvider`](#enums) \| `str` | No | Delivery channel (`sms` or `whatsapp`). Accepts the enum or a raw string. |
| `instance_code` | `str` | No | WhatsApp instance code. **Required when `provider` is `whatsapp`** — otherwise raises [`ValidationError`](#error-handling) before any request is made. Sent to the API as `instanceCode`. |

```python
from callisto_sdk import OtpType, OtpProvider

result = callisto.otp.send(
    to="+2250700000000",
    message="Your Acme code is {code}",
    type=OtpType.DIGIT,
    digit_size=6,
    expired_in=300,
)
print(result.id)

# Over WhatsApp (instance_code required)
callisto.otp.send(
    to="+2250700000000",
    message="Your Acme code is {code}",
    provider=OtpProvider.WHATSAPP,
    instance_code="inst_1",
)
```

#### `otp.verify(otp_id, code)`

Verifies a code against an OTP. Returns [`VerifyOtpResult`](#verifyotpresult).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `otp_id` | `str` | Yes | The OTP ID returned by `send`. |
| `code` | `str` | Yes | The code submitted by the user. |

```python
result = callisto.otp.verify(otp_id="otp_123", code="123456")
if result.verified:
    print("Verified!")
```

#### `otp.get_status(otp_id)`

Fetches a single OTP by ID. Returns [`Otp`](#otp).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `otp_id` | `str` | Yes | The OTP ID. |

```python
otp = callisto.otp.get_status("otp_123")
print(otp.status)
```

#### `otp.list(started_at=None, ended_at=None, page=None, limit=None)`

Lists OTPs. Returns [`Paginated[Otp]`](#paginated).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `started_at` | `str` | No | Filter from this date/time. |
| `ended_at` | `str` | No | Filter up to this date/time. |
| `page` | `int` | No | Page number. |
| `limit` | `int` | No | Items per page. |

```python
page = callisto.otp.list(page=1, limit=20)
for otp in page.items:
    print(otp.id, otp.status)
```

### whatsapp

#### `whatsapp.create_instance(name, phone_number=None, webhook_url=None, idempotency_key=None)`

Creates a WhatsApp instance. Returns [`WhatsAppInstance`](#whatsappinstance).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | `str` | Yes | Instance display name. |
| `phone_number` | `str` | No | Phone number to attach. |
| `webhook_url` | `str` | No | Webhook URL for incoming events. |
| `idempotency_key` | `str` | No | Key to safely retry creation. |

```python
instance = callisto.whatsapp.create_instance(
    name="Main",
    phone_number="+2250700000000",
    webhook_url="https://example.com/webhooks/whatsapp",
)
print(instance.code, instance.status)
```

#### `whatsapp.list_instances(page=1)`

Lists WhatsApp instances. Returns [`Paginated[WhatsAppInstance]`](#paginated).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `page` | `int` | No | Page number. Defaults to `1`. |

```python
page = callisto.whatsapp.list_instances(page=1)
for inst in page.items:
    print(inst.code, inst.name)
```

#### `whatsapp.get_instance(code)`

Fetches a single instance. Returns [`WhatsAppInstance`](#whatsappinstance).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `str` | Yes | Instance code. |

```python
instance = callisto.whatsapp.get_instance("inst_1")
```

#### `whatsapp.get_qr(code)`

Fetches the QR code used to link the instance. Returns the **raw `dict`** from the API (no typed model).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `str` | Yes | Instance code. |

```python
qr = callisto.whatsapp.get_qr("inst_1")
print(qr["qr_code"])
```

#### `whatsapp.get_status(code)`

Fetches the connection status of an instance. Returns the **raw `dict`** from the API (no typed model).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `str` | Yes | Instance code. |

```python
status = callisto.whatsapp.get_status("inst_1")
print(status["status"])
```

#### `whatsapp.list_messages(code, started_at=None, ended_at=None, page=None, per_page=None)`

Lists messages for an instance. Returns [`Paginated[WhatsAppMessage]`](#paginated).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `str` | Yes | Instance code. |
| `started_at` | `str` | No | Filter from this date/time. |
| `ended_at` | `str` | No | Filter up to this date/time. |
| `page` | `int` | No | Page number. |
| `per_page` | `int` | No | Items per page. |

```python
page = callisto.whatsapp.list_messages("inst_1", page=1)
for msg in page.items:
    print(msg.id, msg.status)
```

#### `whatsapp.get_message(message_id)`

Fetches a single WhatsApp message. Returns [`WhatsAppMessage`](#whatsappmessage).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `message_id` | `str` | Yes | The message ID. |

```python
msg = callisto.whatsapp.get_message("msg_9")
print(msg.status, msg.cost)
```

#### `whatsapp.send_text(code, to, message, scheduled_at=None)`

Sends a text message. Returns [`SendWaResult`](#sendwaresult).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `str` | Yes | Instance code. |
| `to` | `str` | Yes | Recipient number. |
| `message` | `str` | Yes | Message body. |
| `scheduled_at` | `str` | No | Schedule delivery. |

```python
result = callisto.whatsapp.send_text("inst_1", to="+2250700000000", message="Hi!")
```

#### `whatsapp.send_media(code, to, type, media_url, caption=None, filename=None, scheduled_at=None)`

Sends a media message. Returns [`SendWaResult`](#sendwaresult).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `str` | Yes | Instance code. |
| `to` | `str` | Yes | Recipient number. |
| `type` | [`WhatsAppMediaType`](#enums) \| `str` | Yes | Media type (`image`, `video`, `document`, `audio`). Accepts the enum or a raw string. |
| `media_url` | `str` | Yes | Publicly accessible media URL. |
| `caption` | `str` | No | Caption text. |
| `filename` | `str` | No | File name (useful for documents). |
| `scheduled_at` | `str` | No | Schedule delivery. |

```python
from callisto_sdk import WhatsAppMediaType

callisto.whatsapp.send_media(
    "inst_1",
    to="+2250700000000",
    type=WhatsAppMediaType.IMAGE,
    media_url="https://example.com/promo.jpg",
    caption="New arrivals",
)
```

#### `whatsapp.send_buttons(code, to, body, buttons, header=None, footer=None, scheduled_at=None)`

Sends an interactive buttons message. Returns [`SendWaResult`](#sendwaresult).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `str` | Yes | Instance code. |
| `to` | `str` | Yes | Recipient number. |
| `body` | `str` | Yes | Message body. |
| `buttons` | `list` | Yes | List of button objects (e.g. `{"id": "1", "title": "Yes"}`). |
| `header` | `str` | No | Header text. |
| `footer` | `str` | No | Footer text. |
| `scheduled_at` | `str` | No | Schedule delivery. |

```python
callisto.whatsapp.send_buttons(
    "inst_1",
    to="+2250700000000",
    body="Confirm your order?",
    buttons=[{"id": "yes", "title": "Yes"}, {"id": "no", "title": "No"}],
)
```

#### `whatsapp.send_location(code, to, latitude, longitude, name=None, address=None, scheduled_at=None)`

Sends a location message. Returns [`SendWaResult`](#sendwaresult).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `str` | Yes | Instance code. |
| `to` | `str` | Yes | Recipient number. |
| `latitude` | `float` | Yes | Latitude. |
| `longitude` | `float` | Yes | Longitude. |
| `name` | `str` | No | Location name. |
| `address` | `str` | No | Address text. |
| `scheduled_at` | `str` | No | Schedule delivery. |

```python
callisto.whatsapp.send_location(
    "inst_1",
    to="+2250700000000",
    latitude=5.3599517,
    longitude=-4.0082563,
    name="Acme HQ",
    address="Abidjan, Côte d'Ivoire",
)
```

#### `whatsapp.send_list(code, to, body, button_text, sections, header=None, footer=None, scheduled_at=None)`

Sends an interactive list message. Returns [`SendWaResult`](#sendwaresult).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `str` | Yes | Instance code. |
| `to` | `str` | Yes | Recipient number. |
| `body` | `str` | Yes | Message body. |
| `button_text` | `str` | Yes | Label of the list trigger button. |
| `sections` | `list` | Yes | List of section objects (each with rows). |
| `header` | `str` | No | Header text. |
| `footer` | `str` | No | Footer text. |
| `scheduled_at` | `str` | No | Schedule delivery. |

```python
callisto.whatsapp.send_list(
    "inst_1",
    to="+2250700000000",
    body="Pick a plan",
    button_text="View plans",
    sections=[{
        "title": "Plans",
        "rows": [
            {"id": "basic", "title": "Basic"},
            {"id": "pro", "title": "Pro"},
        ],
    }],
)
```

### notify

#### `notify.send(topic, email=None, sms=None, mobile_push=None, web_push=None, webhook=None, messaging=None, real_time=None)`

Sends a multi-channel notification to a topic. Returns [`NotifyResult`](#notifyresult).

At least one event block must be provided — otherwise a [`ValidationError`](#error-handling) is raised before any request is made. Each event block is a list of channel-specific event objects.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `topic` | `str` | Yes | Topic identifier. |
| `email` | `list` | No* | Email events. |
| `sms` | `list` | No* | SMS events. |
| `mobile_push` | `list` | No* | Mobile push events. |
| `web_push` | `list` | No* | Web push events. |
| `webhook` | `list` | No* | Webhook events. |
| `messaging` | `list` | No* | Messaging events. |
| `real_time` | `list` | No* | Real-time events. |

\* At least one event block is required.

```python
result = callisto.notify.send(
    topic="welcome",
    sms=[{"to": "+2250700000000"}],
    email=[{"to": "user@example.com"}],
)
print(result.status)
```

## Pagination

List methods return a `Paginated[T]` dataclass. Iterate the typed rows via `.items`; the other fields describe the page window.

| Field | Type | Description |
| --- | --- | --- |
| `items` | `list[T]` | The rows on this page (typed model instances). |
| `total` | `int` | Total number of items across all pages. |
| `per_page` | `int` | Items per page. |
| `current_page` | `int` | Current page number. |
| `next` | `Optional[int]` | Next page number, or `None`. |
| `previous` | `Optional[int]` | Previous page number, or `None`. |
| `total_pages` | `int` | Total number of pages. |

```python
page = callisto.sms.list(page=1, per_page=50)
for msg in page.items:
    print(msg.id, msg.status)

print(f"Page {page.current_page} of {page.total_pages} ({page.total} total)")

# Manual paging
next_page = page.next
while next_page is not None:
    page = callisto.sms.list(page=next_page, per_page=50)
    for msg in page.items:
        print(msg.id)
    next_page = page.next
```

## Typed models

All models are dataclasses. **Read models** (those returned by `get`/`list` methods) tolerate unknown/extra fields returned by the API: an internal `_from_dict` helper keeps only the fields declared on the dataclass and silently drops the rest, so new API fields will not break deserialization.

### Result models

#### `Balance`

| Field | Type |
| --- | --- |
| `credit` | `float` |
| `currency` | `str` |
| `sms_price_local` | `Optional[float]` |
| `sms_price_international` | `Optional[float]` |

#### `SendSmsResult`

| Field | Type |
| --- | --- |
| `total_amount` | `float` |
| `available_credit` | `float` |
| `status` | `str` |
| `recipient_count` | `int` |
| `scheduled` | `bool` |
| `messages` | `list` |

#### `SendOtpResult`

| Field | Type |
| --- | --- |
| `id` | `str` |
| `provider` | `str` |
| `recipient` | `dict` |
| `expires_at` | `str` |
| `expires_in` | `int` |

#### `VerifyOtpResult`

| Field | Type |
| --- | --- |
| `id` | `str` |
| `status` | `str` |
| `verified` | `bool` |
| `verified_at` | `Optional[str]` |

#### `SendWaResult`

| Field | Type |
| --- | --- |
| `id` | `str` |
| `instance_id` | `str` |
| `recipient` | `Any` |
| `message_type` | `str` |
| `status` | `str` |
| `scheduled` | `bool` |
| `media_url` | `Optional[str]` |

#### `NotifyResult`

| Field | Type |
| --- | --- |
| `status` | `str` |
| `topic` | `Any` |
| `queued_events` | `Any` |
| `topic_messages` | `Any` |

### Read models

#### `SmsMessage`

| Field | Type |
| --- | --- |
| `id` | `str` |
| `sender_name` | `Optional[str]` |
| `recipient` | `Optional[str]` |
| `content` | `Optional[str]` |
| `status` | `Optional[str]` |
| `created_at` | `Optional[str]` |
| `updated_at` | `Optional[str]` |

#### `Otp`

Carries both `otp_id` (populated by `get_status`) and `id` (populated by `list` rows) — depending on the endpoint, one or the other may be set.

| Field | Type |
| --- | --- |
| `otp_id` | `Optional[str]` |
| `id` | `Optional[str]` |
| `status` | `Optional[str]` |
| `recipient` | `Optional[str]` |
| `expires_at` | `Optional[str]` |
| `verified_at` | `Optional[str]` |
| `attempts` | `Optional[int]` |
| `created_at` | `Optional[str]` |

#### `WhatsAppInstance`

| Field | Type |
| --- | --- |
| `id` | `str` |
| `code` | `Optional[str]` |
| `client_id` | `Optional[str]` |
| `name` | `Optional[str]` |
| `phone_number` | `Optional[str]` |
| `phone_name` | `Optional[str]` |
| `status` | `Optional[str]` |
| `billing_status` | `Optional[str]` |
| `trial_days_remaining` | `Optional[int]` |
| `monthly_fee` | `Optional[float]` |
| `messages_sent_today` | `Optional[int]` |
| `messages_sent_month` | `Optional[int]` |
| `daily_limit` | `Optional[int]` |
| `last_message_at` | `Optional[str]` |
| `webhook_url` | `Optional[str]` |
| `is_active` | `Optional[bool]` |
| `created_at` | `Optional[str]` |
| `updated_at` | `Optional[str]` |

#### `WhatsAppMessage`

| Field | Type |
| --- | --- |
| `id` | `str` |
| `instance_id` | `Optional[str]` |
| `client_id` | `Optional[str]` |
| `client_api_id` | `Optional[str]` |
| `recipient` | `Optional[str]` |
| `recipient_name` | `Optional[str]` |
| `message_type` | `Optional[str]` |
| `content` | `Optional[str]` |
| `media_url` | `Optional[str]` |
| `media_mimetype` | `Optional[str]` |
| `media_filename` | `Optional[str]` |
| `extra_data` | `Optional[dict]` |
| `direction` | `Optional[str]` |
| `status` | `Optional[str]` |
| `whatsapp_message_id` | `Optional[str]` |
| `error_code` | `Optional[int]` |
| `error_message` | `Optional[str]` |
| `retry_count` | `Optional[int]` |
| `is_billable` | `Optional[bool]` |
| `cost` | `Optional[float]` |
| `sent_at` | `Optional[str]` |
| `delivered_at` | `Optional[str]` |
| `read_at` | `Optional[str]` |
| `scheduled_at` | `Optional[str]` |
| `created_at` | `Optional[str]` |
| `updated_at` | `Optional[str]` |
| `processor_identifier` | `Optional[str]` |

### `Paginated`

Generic page container. See [Pagination](#pagination) for fields and usage.

### Raw responses

`whatsapp.get_qr(code)` and `whatsapp.get_status(code)` return the raw `dict` decoded from the API response — they are not mapped to a typed model.

## Enums

All enums are `str` enums (subclasses of `str`), so they compare equal to and serialize as their string value. Anywhere an enum is accepted, you may also pass the raw string.

```python
from callisto_sdk import MessageStatus, OtpStatus, OtpType, OtpProvider, WhatsAppMediaType
```

| Enum | Members (value) |
| --- | --- |
| `MessageStatus` | `PENDING` (`"pending"`), `SENT` (`"sent"`), `DELIVERED` (`"delivered"`), `FAILED` (`"failed"`) |
| `OtpStatus` | `PENDING` (`"pending"`), `VERIFIED` (`"verified"`), `EXPIRED` (`"expired"`), `FAILED` (`"failed"`) |
| `OtpType` | `DIGIT` (`"digit"`), `ALPHA` (`"alpha"`), `ALPHANUMERIC` (`"alphanumeric"`) |
| `OtpProvider` | `SMS` (`"sms"`), `WHATSAPP` (`"whatsapp"`) |
| `WhatsAppMediaType` | `IMAGE` (`"image"`), `VIDEO` (`"video"`), `DOCUMENT` (`"document"`), `AUDIO` (`"audio"`) |

## Error handling

All SDK errors derive from `CallistoError` and are importable from `callisto_sdk`. Every error carries `.message` (`str`) and `.status_code` (`int`, `0` for transport-level failures), plus `.body` (the decoded response body, when available).

| Exception | Raised when |
| --- | --- |
| `CallistoError` | Base class for all SDK errors. |
| `AuthenticationError` | HTTP 401 — invalid credentials. |
| `ValidationError` | HTTP 400 / 422 — invalid request. Also raised client-side before a request (e.g. `notify.send` with no event block, or `otp.send` with `provider="whatsapp"` and no `instance_code`). |
| `NotFoundError` | HTTP 404 — resource not found. |
| `RateLimitError` | HTTP 429 — rate limited. Adds `.retry_after` (`Optional[int]`, seconds, parsed from the `Retry-After` header). |
| `ApiError` | Any other non-2xx HTTP status. |
| `NetworkError` | Transport-level failure (connection error, timeout, DNS, etc.). `status_code` is `0`. |

```python
import time
from callisto_sdk import (
    Client,
    RateLimitError,
    AuthenticationError,
    ValidationError,
    NetworkError,
    CallistoError,
)

with Client(client_id="...", api_key="...") as callisto:
    try:
        callisto.sms.send(sender="Acme", to="+2250700000000", message="Hi")
    except RateLimitError as exc:
        wait = exc.retry_after or 5
        print(f"Rate limited, retrying in {wait}s")
        time.sleep(wait)
    except AuthenticationError:
        print("Check your client_id / api_key")
    except ValidationError as exc:
        print(f"Invalid request: {exc.message}")
    except NetworkError as exc:
        print(f"Network problem: {exc.message}")
    except CallistoError as exc:
        print(f"API error ({exc.status_code}): {exc.message}")
```

## Error reporting

The SDK ships with an opt-in, Sentry-style error reporter. When you provide an **error DSN**, the SDK automatically captures its own `CallistoError`s (API + network failures, and client-side validation errors) and POSTs them to the Callisto error-tracking ingest endpoint. You can also report your application's own exceptions through the same channel.

Delivery is **background, best-effort, and isolated**: it never delays or alters the original error, runs on a daemon thread, and swallows all of its own failures. When no DSN is set, every reporting method is a cheap no-op and the SDK behaves exactly as before.

### Enabling

Pass the DSN to the constructor, or set `CALLISTO_APP_ERROR_DSN`. The DSN is the full ingest URL:

```python
from callisto_sdk import Client

callisto = Client(
    client_id="your-client-id",
    api_key="your-api-key",
    error_dsn="https://app.callistosignal.com/ingest/<uuid>?key=<public-key>",
    environment="production",
)
```

```bash
export CALLISTO_APP_ERROR_DSN="https://app.callistosignal.com/ingest/<uuid>?key=<public-key>"
export CALLISTO_ENVIRONMENT="production"
```

### Public API

```python
# Report a caught exception (level defaults to "error")
try:
    risky()
except Exception as exc:
    callisto.capture_exception(exc, level="error", extra={"order_id": "123"})

# Report a plain message (level defaults to "info")
callisto.capture_message("checkout completed", level="info")

# Attach a user context to subsequent events (pass None to clear)
callisto.set_user({"id": "user_42", "email": "user@example.com"})
```

`level` is one of `"fatal" | "error" | "warning" | "info"` (anything else is coerced to `"error"`). The reporter is also reachable directly as `callisto.error_reporter` for advanced use.

Pending events are flushed on `close()` / context-manager exit:

```python
with Client(error_dsn="...", client_id="...", api_key="...") as callisto:
    callisto.capture_message("starting up")
# flushed and closed on exit
```

### Opt-in global handler

When `capture_unhandled=True` (or `CALLISTO_CAPTURE_UNHANDLED=true`) **and** a DSN is set, the SDK installs a global unhandled-exception handler that reports uncaught exceptions at level `fatal`, then **chains** the previous `sys.excepthook` / `threading.excepthook` so the interpreter's default behavior (printing the traceback, exit code) is preserved.

```python
callisto = Client(
    client_id="...",
    api_key="...",
    error_dsn="...",
    capture_unhandled=True,
)
```

### PII / secrets guarantee

The reporter **never** transmits your `client_id`, `api_key`, the `Authorization` header, or the **outgoing request body** (which carries phone numbers and message content). Only the server's error response `body`, `status_code`, the HTTP `method`, and the request `path` are ever sent. The reporter uses its own HTTP client and never inherits your Basic-auth credentials.
