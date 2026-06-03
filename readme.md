# Callisto SDK

Official client SDKs for the **Callisto messaging API** — send SMS, one-time passwords (OTP), and WhatsApp messages, publish multi-channel notifications, and check your account balance.

This repository contains six feature-equivalent implementations:

| Language                | Package                     | Min. version | Detailed docs                        |
| ----------------------- | --------------------------- | ------------ | ------------------------------------ |
| JavaScript / TypeScript | `@callisto/sdk`             | Node 18+     | [js/README.md](js/README.md)         |
| Python                  | `callisto-sdk`              | Python 3.9+  | [python/README.md](python/README.md) |
| PHP                     | `callisto/sdk`              | PHP 8.1+     | [php/README.md](php/README.md)       |
| C#                      | `Callisto.Sdk`              | .NET 8+      | [csharp/README.md](csharp/README.md) |
| Ruby                    | `callisto-sdk`              | Ruby 3.0+    | [ruby/README.md](ruby/README.md)     |
| Java                    | `com.callisto:callisto-sdk` | Java 11+     | [java/README.md](java/README.md)     |

All six target the same API (`https://api.callistosignal.com/v1`) and expose the same surface, so concepts transfer directly between languages.

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

# C# (.NET 8+)
dotnet add package Callisto.Sdk

# Ruby
gem install callisto-sdk

# Java (Maven)
# <dependency>
#   <groupId>com.callisto</groupId>
#   <artifactId>callisto-sdk</artifactId>
#   <version>0.1.0</version>
# </dependency>
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

### C#

```csharp
using Callisto.Sdk;

using var client = new CallistoClient(clientId: "your-client-id", apiKey: "your-api-key");

var balance = client.Balance.Get();
Console.WriteLine($"{balance.Credit} {balance.Currency}");

client.Sms.Send(sender: "Acme", to: "+2250700000000", message: "Welcome to Acme!");
```

### Ruby

```ruby
require "callisto"

Callisto::Client.new(client_id: "your-client-id", api_key: "your-api-key") do |callisto|
  balance = callisto.balance.get
  puts "#{balance.credit} #{balance.currency}"

  callisto.sms.send(sender: "Acme", to: "+2250700000000", message: "Welcome to Acme!")
end
```

### Java

```java
import com.callisto.sdk.CallistoClient;
import com.callisto.sdk.resources.SmsSendRequest;

try (CallistoClient client = new CallistoClient("your-client-id", "your-api-key")) {
    var balance = client.balance().get();
    System.out.println(balance.getCredit() + " " + balance.getCurrency());

    client.sms().send(SmsSendRequest.builder()
        .sender("Acme").to("+2250700000000").message("Welcome to Acme!").build());
}
```

> **Java note:** the multi-channel resource accessor is `client.notifications()` rather than `notify()`, because `notify()` is reserved by `java.lang.Object`. Every other resource keeps its shared name.

## Shared concepts

These behave the same across all six SDKs (see each language README for the exact types and method names):

- **Pagination** — `list` methods return a `Paginated` container exposing `items`, `total`, `per_page`, `current_page`, `next`, `previous`, and `total_pages`. Follow `next` (null on the last page) to walk forward.
- **Typed read models** — `get*`/`list*` methods return typed models; they tolerate unknown fields, so new API fields won't break deserialization. (`whatsapp.getQr` / `whatsapp.getStatus` return the raw payload.)
- **Client-side validation** — some invalid calls fail before any network request: a WhatsApp OTP without an `instance_code`, or a `notify.send` with no event block, raise a validation error locally.
- **Errors** — every error derives from a base `CallistoError` carrying `message`, `status_code`, and the decoded response `body`. HTTP statuses map to specific subclasses: `AuthenticationError` (401), `ValidationError` (400/422), `NotFoundError` (404), `RateLimitError` (429, with `retry_after`), `ApiError` (other non-2xx), and `NetworkError` (transport failures, status `0`).

## Error reporting

Every SDK ships with an opt-in, Sentry-style **error reporter** that POSTs captured errors to a
Callisto error-tracking ingest endpoint (a **DSN**). It auto-captures the SDK's own errors (API,
network, and client-side validation) and exposes a small public API for your own exceptions:

```
client.captureException(error[, level][, extra])
client.captureMessage(text[, level][, extra])
client.setUser({ id, email, ... })
```

Configure it via constructor argument or environment variable; absent a DSN, reporting is fully
disabled (a no-op) and the SDK behaves exactly as before.

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `CALLISTO_ERROR_DSN` | none | Ingest DSN (the full POST URL). Absent → reporting disabled. |
| `CALLISTO_CAPTURE_UNHANDLED` | `false` | Install the platform's global unhandled-exception handler. |
| `CALLISTO_ENVIRONMENT` | none | Optional tag included in `context.environment`. |

Delivery is background and best-effort — it never alters or delays the original error, and the
reporter's own failures are silently swallowed. **PII guarantee:** the reporter never transmits your
client ID, API key, the `Authorization` header, or the outgoing request body (phone numbers and
message content); only the server's error `body`, `status_code`, HTTP `method`, and request `path`
leave the process. See each language README for the exact API and the opt-in handler details.

## Per-language guides

The READMEs below document every method, parameter, model field, enum, and error for each implementation:

- **[JavaScript / TypeScript →](js/README.md)**
- **[Python →](python/README.md)**
- **[PHP →](php/README.md)**
- **[C# →](csharp/README.md)**
- **[Ruby →](ruby/README.md)**
- **[Java →](java/README.md)**

## License

MIT.
