# Callisto SDK

Official client SDKs for the **Callisto messaging API** — send SMS, one-time passwords (OTP), and WhatsApp messages, publish multi-channel notifications, and check your account balance.

This repository contains three feature-equivalent implementations:

| Language                | Package         | Min. version | Detailed docs                         |
| ----------------------- | --------------- | ------------ | ------------------------------------- |
| JavaScript / TypeScript | `@callisto/sdk` | Node 18+     | [js/README.md](js/README.md)          |
| Python                  | `callisto-sdk`  | Python 3.9+  | [python/README.md](python/README.md)  |
| PHP                     | `callisto/sdk`  | PHP 8.1+     | [php/README.md](php/README.md)        |

All three target the same API (`https://api.callistosignal.com/v1`) and expose the same surface, so concepts transfer directly between languages.

## What's in the API

Every SDK exposes five resources:

- **`balance`** — check available credit and SMS pricing.
- **`sms`** — send SMS to one or many recipients (with scheduling and delivery webhooks), list sent messages, and look up delivery status.
- **`otp`** — generate, send, and verify one-time passwords over SMS or WhatsApp.
- **`whatsapp`** — manage WhatsApp instances (create, QR pairing, status) and send text, media, buttons, location, and list messages.
- **`notify`** — publish a single notification across multiple channels (email, SMS, push, webhook, messaging, real-time) under a topic.

## Install

```bash
# JavaScript / TypeScript (ESM, Node 18+)
npm install callisto-sdk

# Python
pip install callisto-sdk

# PHP
composer require callisto/sdk
```

## Configuration

Each client takes a **client ID** and **API key**, which it sends as HTTP Basic auth (`base64(client_id:api_key)`) on every request — you never build the header yourself. Both credentials can be passed to the constructor or read from the environment:

| Environment variable | Maps to     |
| -------------------- | ----------- |
| `CALLISTO_CLIENT_ID` | `client_id` |
| `CALLISTO_API_KEY`   | `api_key`   |
| `CALLISTO_BASE_URL`  | `base_url`  |

If neither the argument nor its environment variable resolves a credential, the constructor fails fast. The base URL defaults to `https://api.callistosignal.com/v1` and the request timeout defaults to 30 seconds.

## Quick start

### JavaScript / TypeScript

```ts
import { CallistoClient } from "@callisto/sdk";

const client = new CallistoClient({ clientId: "your_client_id", apiKey: "your_api_key" });

const balance = await client.balance.get();
console.log(`${balance.credit} ${balance.currency}`);

await client.sms.send({ sender: "Acme", to: "+2250700000000", message: "Welcome to Acme!" });
```

### Python

```python
from callisto_sdk import Client

with Client(client_id="your-client-id", api_key="your-api-key") as callisto:
    balance = callisto.balance.get()
    print(f"{balance.credit} {balance.currency}")

    callisto.sms.send(sender="Acme", to="+2250700000000", message="Welcome to Acme!")
```

### PHP

```php
use Callisto\Sdk\Client;

$callisto = new Client(clientId: 'your-client-id', apiKey: 'your-api-key');

$balance = $callisto->balance()->get();

$callisto->sms()->send(sender: 'Acme', to: '+2250700000000', message: 'Welcome to Acme!');
```

## Shared concepts

These behave the same across all three SDKs (see each language README for the exact types and method names):

- **Pagination** — `list` methods return a `Paginated` container exposing `items`, `total`, `per_page`, `current_page`, `next`, `previous`, and `total_pages`. Follow `next` (null on the last page) to walk forward.
- **Typed read models** — `get*`/`list*` methods return typed models; they tolerate unknown fields, so new API fields won't break deserialization. (`whatsapp.getQr` / `whatsapp.getStatus` return the raw payload.)
- **Client-side validation** — some invalid calls fail before any network request: a WhatsApp OTP without an `instance_code`, or a `notify.send` with no event block, raise a validation error locally.
- **Errors** — every error derives from a base `CallistoError` carrying `message`, `status_code`, and the decoded response `body`. HTTP statuses map to specific subclasses: `AuthenticationError` (401), `ValidationError` (400/422), `NotFoundError` (404), `RateLimitError` (429, with `retry_after`), `ApiError` (other non-2xx), and `NetworkError` (transport failures, status `0`).

## Per-language guides

The READMEs below document every method, parameter, model field, enum, and error for each implementation:

- **[JavaScript / TypeScript →](js/README.md)**
- **[Python →](python/README.md)**
- **[PHP →](php/README.md)**

## License

MIT.
