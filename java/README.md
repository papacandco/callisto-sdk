# callisto-sdk (Java)

Official Callisto messaging API SDK for Java 11+.

## Requirements

- Java 11 or newer.
- Synchronous HTTP client built on the JDK's `java.net.http.HttpClient`.
- JSON via [Jackson](https://github.com/FasterXML/jackson) (`jackson-databind`).

## Install

Maven coordinates:

```xml
<dependency>
    <groupId>com.callisto</groupId>
    <artifactId>callisto-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle:

```groovy
implementation "com.callisto:callisto-sdk:0.1.0"
```

## Configuration

Create a `CallistoClient` with your credentials. Authentication is HTTP Basic (`clientId` / `apiKey`) and is applied automatically to every request.

```java
import com.callisto.sdk.CallistoClient;

CallistoClient callisto = new CallistoClient("your-client-id", "your-api-key");
```

### Constructors

```java
new CallistoClient(String clientId, String apiKey);
new CallistoClient(String clientId, String apiKey, String baseUrl, java.time.Duration timeout);
new CallistoClient(String clientId, String apiKey, String baseUrl, java.time.Duration timeout,
                   java.net.http.HttpClient httpClient, com.fasterxml.jackson.databind.ObjectMapper mapper);
```

| Param | Type | Default | Description |
| --- | --- | --- | --- |
| `clientId` | `String` | `null` | Your Callisto client ID. Falls back to env `CALLISTO_CLIENT_ID`. Required. |
| `apiKey` | `String` | `null` | Your Callisto API key. Falls back to env `CALLISTO_API_KEY`. Required. |
| `baseUrl` | `String` | `https://api.callistosignal.com/v1` | API base URL. Falls back to env `CALLISTO_BASE_URL`. Trailing slashes are trimmed. |
| `timeout` | `java.time.Duration` | `30s` | Request timeout. |
| `httpClient` | `java.net.http.HttpClient` | `null` | Optional pre-configured client to inject (advanced use, e.g. custom transport, proxies, or testing). |
| `mapper` | `com.fasterxml.jackson.databind.ObjectMapper` | `null` | Optional Jackson `ObjectMapper` to inject. |

`clientId` and `apiKey` are required: pass them as arguments or via the `CALLISTO_CLIENT_ID` / `CALLISTO_API_KEY` environment variables. If neither is available, the constructor throws `ValidationException`.

### Environment variables

| Variable | Maps to |
| --- | --- |
| `CALLISTO_CLIENT_ID` | `clientId` |
| `CALLISTO_API_KEY` | `apiKey` |
| `CALLISTO_BASE_URL` | `baseUrl` |

```java
// With CALLISTO_CLIENT_ID and CALLISTO_API_KEY set in the environment:
CallistoClient callisto = new CallistoClient(null, null);
```

### Lifecycle

`CallistoClient` implements `AutoCloseable`. Use a try-with-resources block, or call `close()` explicitly.

```java
try (CallistoClient callisto = new CallistoClient("...", "...")) {
    Balance balance = callisto.balance().get();
} // closed automatically on exit
```

## Quick start

```java
import com.callisto.sdk.CallistoClient;
import com.callisto.sdk.models.Balance;
import com.callisto.sdk.models.SendSmsResult;
import com.callisto.sdk.resources.SmsSendRequest;

try (CallistoClient callisto = new CallistoClient("your-client-id", "your-api-key")) {
    // Check your balance
    Balance balance = callisto.balance().get();
    System.out.println("Credit: " + balance.getCredit() + " " + balance.getCurrency());

    // Send an SMS
    SendSmsResult result = callisto.sms().send(SmsSendRequest.builder()
            .sender("Acme")
            .to("+2250700000000")
            .message("Welcome to Acme!")
            .build());
    System.out.println(result.getStatus());
}
```

## Resources

Each resource is accessed via an accessor method on the client: `callisto.balance()`, `callisto.sms()`, `callisto.otp()`, `callisto.whatsapp()`, `callisto.notifications()`.

> **Note:** the notify resource is accessed via `callisto.notifications()` rather than `notify()`, because Java reserves `Object.notify()` (a `final` method that cannot be overridden). The behavior is otherwise identical to the other SDKs.

Send methods with many optional parameters (`sms.send`, `otp.send`, and the `whatsapp.send*` family) take a small immutable **request object** built with a fluent builder. Simple methods take plain parameters.

### balance

#### `balance().get()` / `balance().get(String format, String currency)`

Returns the account balance. Maps to `GET /sms/balance`. Returns [`Balance`](#balance-1).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `format` | `String` | No | Response format. Defaults to `"full"`. |
| `currency` | `String` | No | Filter the balance by currency code. |

```java
Balance balance = callisto.balance().get();
System.out.println(balance.getCredit() + " " + balance.getCurrency());
```

### sms

#### `sms().send(SmsSendRequest request)`

Sends an SMS to one or more recipients. Maps to `POST /sms/send`. Returns [`SendSmsResult`](#sendsmsresult).

Build the request with `SmsSendRequest.builder()`:

| Builder method | Type | Required | Description |
| --- | --- | --- | --- |
| `sender(String)` | `String` | Yes | Approved sender name. |
| `to(String)` / `to(List<String>)` | `String` \| `List<String>` | Yes | A single recipient number, or a list of numbers. |
| `message(String)` | `String` | Yes | Message body. |
| `notifyUrl(String)` | `String` | No | Webhook URL for delivery status callbacks (sent as `notify_url`). |
| `scheduledAt(String)` | `String` | No | Schedule delivery, e.g. `"2026-06-02 10:00:00"` (sent as `scheduled_at`). |

```java
SendSmsResult result = callisto.sms().send(SmsSendRequest.builder()
        .sender("Acme")
        .to("+2250700000000")
        .message("Your code is 1234")
        .build());

// Bulk + scheduled
callisto.sms().send(SmsSendRequest.builder()
        .sender("Acme")
        .to(List.of("+2250700000000", "+2250700000001"))
        .message("Sale starts tomorrow!")
        .notifyUrl("https://example.com/webhooks/sms")
        .scheduledAt("2026-06-02 10:00:00")
        .build());
```

#### `sms().list()` / `sms().list(String startedAt, String endedAt, Integer page, Integer perPage)`

Lists sent SMS messages. Maps to `GET /sms/messages`. Returns [`Paginated<SmsMessage>`](#paginated).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `startedAt` | `String` | No | Filter from this date/time (query `started_at`). |
| `endedAt` | `String` | No | Filter up to this date/time (query `ended_at`). |
| `page` | `Integer` | No | Page number. |
| `perPage` | `Integer` | No | Items per page (query `per_page`). |

```java
Paginated<SmsMessage> page = callisto.sms().list(null, null, 1, 50);
for (SmsMessage msg : page.getItems()) {
    System.out.println(msg.getId() + " " + msg.getStatus());
}
```

#### `sms().getStatus(String messageId)`

Fetches a single SMS by ID. Maps to `GET /sms/{id}`. Returns [`SmsMessage`](#smsmessage).

```java
SmsMessage msg = callisto.sms().getStatus("abc");
System.out.println(msg.getStatus());
```

### otp

#### `otp().send(OtpSendRequest request)`

Generates and sends a one-time password. Maps to `POST /otp/send`. Returns [`SendOtpResult`](#sendotpresult).

Build with `OtpSendRequest.builder()`:

| Builder method | Type | Required | Description |
| --- | --- | --- | --- |
| `to(String)` | `String` | Yes | Recipient number. |
| `message(String)` | `String` | Yes | Message template (the generated code is interpolated by the API). |
| `sender(String)` | `String` | No | Sender name. |
| `expiredIn(Integer)` | `Integer` | No | Code lifetime in seconds (sent as `expired_in`). |
| `type(OtpType)` / `type(String)` | [`OtpType`](#enums) \| `String` | No | Code character set (`digit`, `alpha`, `alphanumeric`). |
| `digitSize(Integer)` | `Integer` | No | Number of characters in the code (sent as `digit_size`). |
| `provider(OtpProvider)` / `provider(String)` | [`OtpProvider`](#enums) \| `String` | No | Delivery channel (`sms` or `whatsapp`). |
| `instanceCode(String)` | `String` | No | WhatsApp instance code. **Required when `provider` is `whatsapp`** — otherwise throws [`ValidationException`](#error-handling) before any request is made. Sent to the API as `instanceCode`. |

```java
import com.callisto.sdk.enums.OtpType;
import com.callisto.sdk.enums.OtpProvider;

SendOtpResult result = callisto.otp().send(OtpSendRequest.builder()
        .to("+2250700000000")
        .message("Your Acme code is {code}")
        .type(OtpType.DIGIT)
        .digitSize(6)
        .expiredIn(300)
        .build());
System.out.println(result.getId());

// Over WhatsApp (instanceCode required)
callisto.otp().send(OtpSendRequest.builder()
        .to("+2250700000000")
        .message("Your Acme code is {code}")
        .provider(OtpProvider.WHATSAPP)
        .instanceCode("inst_1")
        .build());
```

#### `otp().verify(String otpId, String code)`

Verifies a code against an OTP. Maps to `POST /otp/verify`. Returns [`VerifyOtpResult`](#verifyotpresult).

```java
VerifyOtpResult result = callisto.otp().verify("otp_123", "123456");
if (result.isVerified()) {
    System.out.println("Verified!");
}
```

#### `otp().getStatus(String otpId)`

Fetches a single OTP by ID. Maps to `GET /otps/{id}`. Returns [`Otp`](#otp).

```java
Otp otp = callisto.otp().getStatus("otp_123");
System.out.println(otp.getStatus());
```

#### `otp().list()` / `otp().list(String startedAt, String endedAt, Integer page, Integer limit)`

Lists OTPs. Maps to `GET /otps`. Returns [`Paginated<Otp>`](#paginated).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `startedAt` | `String` | No | Filter from this date/time (query `started_at`). |
| `endedAt` | `String` | No | Filter up to this date/time (query `ended_at`). |
| `page` | `Integer` | No | Page number. |
| `limit` | `Integer` | No | Items per page (query `limit`). |

```java
Paginated<Otp> page = callisto.otp().list(null, null, 1, 20);
for (Otp otp : page.getItems()) {
    System.out.println(otp.getId() + " " + otp.getStatus());
}
```

### whatsapp

#### `whatsapp().createInstance(String name)` / `createInstance(String name, String phoneNumber, String webhookUrl, String idempotencyKey)`

Creates a WhatsApp instance. Maps to `POST /whatsapp/instances`. Returns [`WhatsAppInstance`](#whatsappinstance).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | `String` | Yes | Instance display name. |
| `phoneNumber` | `String` | No | Phone number to attach (sent as `phone_number`). |
| `webhookUrl` | `String` | No | Webhook URL for incoming events (sent as `webhook_url`). |
| `idempotencyKey` | `String` | No | Key to safely retry creation (sent as `idempotency_key`). |

```java
WhatsAppInstance instance = callisto.whatsapp().createInstance(
        "Main", "+2250700000000", "https://example.com/webhooks/whatsapp", null);
System.out.println(instance.getCode() + " " + instance.getStatus());
```

#### `whatsapp().listInstances()` / `whatsapp().listInstances(int page)`

Lists WhatsApp instances. Maps to `GET /whatsapp/instances`. Returns [`Paginated<WhatsAppInstance>`](#paginated). `page` defaults to `1`.

```java
Paginated<WhatsAppInstance> page = callisto.whatsapp().listInstances(1);
for (WhatsAppInstance inst : page.getItems()) {
    System.out.println(inst.getCode() + " " + inst.getName());
}
```

#### `whatsapp().getInstance(String code)`

Fetches a single instance. Maps to `GET /whatsapp/{code}`. Returns [`WhatsAppInstance`](#whatsappinstance).

```java
WhatsAppInstance instance = callisto.whatsapp().getInstance("inst_1");
```

#### `whatsapp().getQr(String code)`

Fetches the QR code used to link the instance. Maps to `GET /whatsapp/{code}/qr`. Returns the **raw `com.fasterxml.jackson.databind.JsonNode`** from the API (no typed model).

```java
JsonNode qr = callisto.whatsapp().getQr("inst_1");
System.out.println(qr.get("qr_code").asText());
```

#### `whatsapp().getStatus(String code)`

Fetches the connection status of an instance. Maps to `GET /whatsapp/{code}/status`. Returns the **raw `JsonNode`** from the API (no typed model).

```java
JsonNode status = callisto.whatsapp().getStatus("inst_1");
System.out.println(status.get("status").asText());
```

#### `whatsapp().listMessages(String code)` / `listMessages(String code, String startedAt, String endedAt, Integer page, Integer perPage)`

Lists messages for an instance. Maps to `GET /whatsapp/{code}/messages`. Returns [`Paginated<WhatsAppMessage>`](#paginated).

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | `String` | Yes | Instance code. |
| `startedAt` | `String` | No | Filter from this date/time (query `started_at`). |
| `endedAt` | `String` | No | Filter up to this date/time (query `ended_at`). |
| `page` | `Integer` | No | Page number. |
| `perPage` | `Integer` | No | Items per page (query `per_page`). |

```java
Paginated<WhatsAppMessage> page = callisto.whatsapp().listMessages("inst_1", null, null, 1, null);
for (WhatsAppMessage msg : page.getItems()) {
    System.out.println(msg.getId() + " " + msg.getStatus());
}
```

#### `whatsapp().getMessage(String messageId)`

Fetches a single WhatsApp message. Maps to `GET /whatsapp/messages/{id}`. Returns [`WhatsAppMessage`](#whatsappmessage).

```java
WhatsAppMessage msg = callisto.whatsapp().getMessage("msg_9");
System.out.println(msg.getStatus() + " " + msg.getCost());
```

#### `whatsapp().sendText(WaTextRequest request)`

Sends a text message. Maps to `POST /whatsapp/{code}/send/text`. Returns [`SendWaResult`](#sendwaresult).

| Builder method | Type | Required | Description |
| --- | --- | --- | --- |
| `code(String)` | `String` | Yes | Instance code. |
| `to(String)` | `String` | Yes | Recipient number. |
| `message(String)` | `String` | Yes | Message body. |
| `scheduledAt(String)` | `String` | No | Schedule delivery (sent as `scheduled_at`). |

```java
SendWaResult result = callisto.whatsapp().sendText(WaTextRequest.builder()
        .code("inst_1").to("+2250700000000").message("Hi!").build());
```

#### `whatsapp().sendMedia(WaMediaRequest request)`

Sends a media message. Maps to `POST /whatsapp/{code}/send/media`. Returns [`SendWaResult`](#sendwaresult).

| Builder method | Type | Required | Description |
| --- | --- | --- | --- |
| `code(String)` | `String` | Yes | Instance code. |
| `to(String)` | `String` | Yes | Recipient number. |
| `type(WhatsAppMediaType)` / `type(String)` | [`WhatsAppMediaType`](#enums) \| `String` | Yes | Media type (`image`, `video`, `document`, `audio`). |
| `mediaUrl(String)` | `String` | Yes | Publicly accessible media URL (sent as `media_url`). |
| `caption(String)` | `String` | No | Caption text. |
| `filename(String)` | `String` | No | File name (useful for documents). |
| `scheduledAt(String)` | `String` | No | Schedule delivery (sent as `scheduled_at`). |

```java
import com.callisto.sdk.enums.WhatsAppMediaType;

callisto.whatsapp().sendMedia(WaMediaRequest.builder()
        .code("inst_1")
        .to("+2250700000000")
        .type(WhatsAppMediaType.IMAGE)
        .mediaUrl("https://example.com/promo.jpg")
        .caption("New arrivals")
        .build());
```

#### `whatsapp().sendButtons(WaButtonsRequest request)`

Sends an interactive buttons message. Maps to `POST /whatsapp/{code}/send/buttons`. Returns [`SendWaResult`](#sendwaresult).

| Builder method | Type | Required | Description |
| --- | --- | --- | --- |
| `code(String)` | `String` | Yes | Instance code. |
| `to(String)` | `String` | Yes | Recipient number. |
| `body(String)` | `String` | Yes | Message body. |
| `buttons(List<?>)` | `List<?>` | Yes | List of button objects (e.g. `Map.of("id", "1", "title", "Yes")`). |
| `header(String)` | `String` | No | Header text. |
| `footer(String)` | `String` | No | Footer text. |
| `scheduledAt(String)` | `String` | No | Schedule delivery (sent as `scheduled_at`). |

```java
callisto.whatsapp().sendButtons(WaButtonsRequest.builder()
        .code("inst_1")
        .to("+2250700000000")
        .body("Confirm your order?")
        .buttons(List.of(
                Map.of("id", "yes", "title", "Yes"),
                Map.of("id", "no", "title", "No")))
        .build());
```

#### `whatsapp().sendLocation(WaLocationRequest request)`

Sends a location message. Maps to `POST /whatsapp/{code}/send/location`. Returns [`SendWaResult`](#sendwaresult).

| Builder method | Type | Required | Description |
| --- | --- | --- | --- |
| `code(String)` | `String` | Yes | Instance code. |
| `to(String)` | `String` | Yes | Recipient number. |
| `latitude(double)` | `double` | Yes | Latitude. |
| `longitude(double)` | `double` | Yes | Longitude. |
| `name(String)` | `String` | No | Location name. |
| `address(String)` | `String` | No | Address text. |
| `scheduledAt(String)` | `String` | No | Schedule delivery (sent as `scheduled_at`). |

```java
callisto.whatsapp().sendLocation(WaLocationRequest.builder()
        .code("inst_1")
        .to("+2250700000000")
        .latitude(5.3599517)
        .longitude(-4.0082563)
        .name("Acme HQ")
        .address("Abidjan, Cote d'Ivoire")
        .build());
```

#### `whatsapp().sendList(WaListRequest request)`

Sends an interactive list message. Maps to `POST /whatsapp/{code}/send/list`. Returns [`SendWaResult`](#sendwaresult).

| Builder method | Type | Required | Description |
| --- | --- | --- | --- |
| `code(String)` | `String` | Yes | Instance code. |
| `to(String)` | `String` | Yes | Recipient number. |
| `body(String)` | `String` | Yes | Message body. |
| `buttonText(String)` | `String` | Yes | Label of the list trigger button (sent as `button_text`). |
| `sections(List<?>)` | `List<?>` | Yes | List of section objects (each with rows). |
| `header(String)` | `String` | No | Header text. |
| `footer(String)` | `String` | No | Footer text. |
| `scheduledAt(String)` | `String` | No | Schedule delivery (sent as `scheduled_at`). |

```java
callisto.whatsapp().sendList(WaListRequest.builder()
        .code("inst_1")
        .to("+2250700000000")
        .body("Pick a plan")
        .buttonText("View plans")
        .sections(List.of(Map.of(
                "title", "Plans",
                "rows", List.of(
                        Map.of("id", "basic", "title", "Basic"),
                        Map.of("id", "pro", "title", "Pro")))))
        .build());
```

### notify

#### `notifications().send(NotifyRequest request)`

Sends a multi-channel notification to a topic. Maps to `POST /notify/send`. Returns [`NotifyResult`](#notifyresult).

At least one event block must be provided — otherwise a [`ValidationException`](#error-handling) is thrown before any request is made. Each event block is a list of channel-specific event objects. JSON keys are snake_case.

| Builder method | JSON key | Required | Description |
| --- | --- | --- | --- |
| `topic(String)` | `topic` | Yes | Topic identifier. |
| `email(List<?>)` | `email` | No* | Email events. |
| `sms(List<?>)` | `sms` | No* | SMS events. |
| `mobilePush(List<?>)` | `mobile_push` | No* | Mobile push events. |
| `webPush(List<?>)` | `web_push` | No* | Web push events. |
| `webhook(List<?>)` | `webhook` | No* | Webhook events. |
| `messaging(List<?>)` | `messaging` | No* | Messaging events. |
| `realTime(List<?>)` | `real_time` | No* | Real-time events. |

\* At least one event block is required.

```java
NotifyResult result = callisto.notifications().send(NotifyRequest.builder()
        .topic("welcome")
        .sms(List.of(Map.of("to", "+2250700000000")))
        .email(List.of(Map.of("to", "user@example.com")))
        .build());
System.out.println(result.getStatus());
```

## Pagination

List methods return a `Paginated<T>`. Iterate the typed rows via `getItems()`; the other fields describe the page window.

| Getter | Type | Description |
| --- | --- | --- |
| `getItems()` | `List<T>` | The rows on this page (typed model instances). |
| `getTotal()` | `int` | Total number of items across all pages. |
| `getPerPage()` | `int` | Items per page. |
| `getCurrentPage()` | `int` | Current page number. |
| `getNext()` | `Integer` | Next page number, or `null`. |
| `getPrevious()` | `Integer` | Previous page number, or `null`. |
| `getTotalPages()` | `int` | Total number of pages. |

```java
Paginated<SmsMessage> page = callisto.sms().list(null, null, 1, 50);
for (SmsMessage msg : page.getItems()) {
    System.out.println(msg.getId() + " " + msg.getStatus());
}
System.out.printf("Page %d of %d (%d total)%n",
        page.getCurrentPage(), page.getTotalPages(), page.getTotal());

// Manual paging
Integer next = page.getNext();
while (next != null) {
    page = callisto.sms().list(null, null, next, 50);
    for (SmsMessage msg : page.getItems()) {
        System.out.println(msg.getId());
    }
    next = page.getNext();
}
```

## Typed models

All models are POJOs with getters. **Read models** (those returned by `get`/`list` methods) are annotated `@JsonIgnoreProperties(ignoreUnknown = true)` and tolerate unknown/extra fields returned by the API, so new API fields will not break deserialization. `@JsonProperty` maps snake_case JSON keys onto camelCase getters.

### Result models

#### `Balance`

| Getter | Type |
| --- | --- |
| `getCredit()` | `double` |
| `getCurrency()` | `String` |
| `getSmsPriceLocal()` | `Double` |
| `getSmsPriceInternational()` | `Double` |

#### `SendSmsResult`

| Getter | Type |
| --- | --- |
| `getTotalAmount()` | `double` |
| `getAvailableCredit()` | `double` |
| `getStatus()` | `String` |
| `getRecipientCount()` | `int` |
| `isScheduled()` | `boolean` |
| `getMessages()` | `List<Object>` |

#### `SendOtpResult`

| Getter | Type |
| --- | --- |
| `getId()` | `String` |
| `getProvider()` | `String` |
| `getRecipient()` | `Map<String, Object>` |
| `getExpiresAt()` | `String` |
| `getExpiresIn()` | `int` |

#### `VerifyOtpResult`

| Getter | Type |
| --- | --- |
| `getId()` | `String` |
| `getStatus()` | `String` |
| `isVerified()` | `boolean` |
| `getVerifiedAt()` | `String` |

#### `SendWaResult`

| Getter | Type |
| --- | --- |
| `getId()` | `String` |
| `getInstanceId()` | `String` |
| `getRecipient()` | `Object` |
| `getMessageType()` | `String` |
| `getStatus()` | `String` |
| `isScheduled()` | `boolean` |
| `getMediaUrl()` | `String` |

#### `NotifyResult`

| Getter | Type |
| --- | --- |
| `getStatus()` | `String` |
| `getTopic()` | `Object` |
| `getQueuedEvents()` | `Object` |
| `getTopicMessages()` | `Object` |

### Read models

#### `SmsMessage`

| Getter | Type |
| --- | --- |
| `getId()` | `String` |
| `getSenderName()` | `String` |
| `getRecipient()` | `String` |
| `getContent()` | `String` |
| `getStatus()` | `String` |
| `getCreatedAt()` | `String` |
| `getUpdatedAt()` | `String` |

#### `Otp`

Carries both `otpId` (populated by `getStatus`) and `id` (populated by `list` rows) — depending on the endpoint, one or the other may be set.

| Getter | Type |
| --- | --- |
| `getOtpId()` | `String` |
| `getId()` | `String` |
| `getStatus()` | `String` |
| `getRecipient()` | `String` |
| `getExpiresAt()` | `String` |
| `getVerifiedAt()` | `String` |
| `getAttempts()` | `Integer` |
| `getCreatedAt()` | `String` |

#### `WhatsAppInstance`

| Getter | Type |
| --- | --- |
| `getId()` | `String` |
| `getCode()` | `String` |
| `getClientId()` | `String` |
| `getName()` | `String` |
| `getPhoneNumber()` | `String` |
| `getPhoneName()` | `String` |
| `getStatus()` | `String` |
| `getBillingStatus()` | `String` |
| `getTrialDaysRemaining()` | `Integer` |
| `getMonthlyFee()` | `Double` |
| `getMessagesSentToday()` | `Integer` |
| `getMessagesSentMonth()` | `Integer` |
| `getDailyLimit()` | `Integer` |
| `getLastMessageAt()` | `String` |
| `getWebhookUrl()` | `String` |
| `getIsActive()` | `Boolean` |
| `getCreatedAt()` | `String` |
| `getUpdatedAt()` | `String` |

#### `WhatsAppMessage`

| Getter | Type |
| --- | --- |
| `getId()` | `String` |
| `getInstanceId()` | `String` |
| `getClientId()` | `String` |
| `getApiClientId()` | `String` |
| `getRecipient()` | `String` |
| `getRecipientName()` | `String` |
| `getMessageType()` | `String` |
| `getContent()` | `String` |
| `getMediaUrl()` | `String` |
| `getMediaMimetype()` | `String` |
| `getMediaFilename()` | `String` |
| `getExtraData()` | `Map<String, Object>` |
| `getDirection()` | `String` |
| `getStatus()` | `String` |
| `getWhatsappMessageId()` | `String` |
| `getErrorCode()` | `Integer` |
| `getErrorMessage()` | `String` |
| `getRetryCount()` | `Integer` |
| `getIsBillable()` | `Boolean` |
| `getCost()` | `Double` |
| `getSentAt()` | `String` |
| `getDeliveredAt()` | `String` |
| `getReadAt()` | `String` |
| `getScheduledAt()` | `String` |
| `getCreatedAt()` | `String` |
| `getUpdatedAt()` | `String` |
| `getProcessorIdentifier()` | `String` |

### `Paginated<T>`

Generic page container. See [Pagination](#pagination) for fields and usage.

### Raw responses

`whatsapp().getQr(code)` and `whatsapp().getStatus(code)` return the raw `com.fasterxml.jackson.databind.JsonNode` decoded from the API response — they are not mapped to a typed model.

## Enums

Each enum carries a string wire value (`getValue()`) and serializes/deserializes via Jackson's `@JsonValue` / `@JsonCreator`. Anywhere an enum is accepted by a builder, an overload also accepts the raw `String`.

```java
import com.callisto.sdk.enums.MessageStatus;
import com.callisto.sdk.enums.OtpStatus;
import com.callisto.sdk.enums.OtpType;
import com.callisto.sdk.enums.OtpProvider;
import com.callisto.sdk.enums.WhatsAppMediaType;
```

| Enum | Members (value) |
| --- | --- |
| `MessageStatus` | `PENDING` (`"pending"`), `SENT` (`"sent"`), `DELIVERED` (`"delivered"`), `FAILED` (`"failed"`) |
| `OtpStatus` | `PENDING` (`"pending"`), `VERIFIED` (`"verified"`), `EXPIRED` (`"expired"`), `FAILED` (`"failed"`) |
| `OtpType` | `DIGIT` (`"digit"`), `ALPHA` (`"alpha"`), `ALPHANUMERIC` (`"alphanumeric"`) |
| `OtpProvider` | `SMS` (`"sms"`), `WHATSAPP` (`"whatsapp"`) |
| `WhatsAppMediaType` | `IMAGE` (`"image"`), `VIDEO` (`"video"`), `DOCUMENT` (`"document"`), `AUDIO` (`"audio"`) |

## Error handling

All SDK errors derive from `CallistoException` (an unchecked `RuntimeException`) in `com.callisto.sdk.errors`. Every error carries `getMessage()` (`String`), `getStatusCode()` (`int`, `0` for transport-level failures), and `getBody()` (the decoded response body, when available).

| Exception | Raised when |
| --- | --- |
| `CallistoException` | Base class for all SDK errors. |
| `AuthenticationException` | HTTP 401 — invalid credentials. |
| `ValidationException` | HTTP 400 / 422 — invalid request. Also thrown client-side before a request (e.g. `notifications().send` with no event block, `otp().send` with `provider=whatsapp` and no `instanceCode`, or missing credentials in the constructor). |
| `NotFoundException` | HTTP 404 — resource not found. |
| `RateLimitException` | HTTP 429 — rate limited. Adds `getRetryAfter()` (`Integer`, seconds, parsed from the `Retry-After` header). |
| `ApiException` | Any other non-2xx HTTP status. |
| `NetworkException` | Transport-level failure (connection error, timeout, interruption). `getStatusCode()` is `0`. |

```java
import com.callisto.sdk.CallistoClient;
import com.callisto.sdk.errors.*;
import com.callisto.sdk.resources.SmsSendRequest;

try (CallistoClient callisto = new CallistoClient("...", "...")) {
    try {
        callisto.sms().send(SmsSendRequest.builder()
                .sender("Acme").to("+2250700000000").message("Hi").build());
    } catch (RateLimitException ex) {
        int wait = ex.getRetryAfter() != null ? ex.getRetryAfter() : 5;
        System.out.println("Rate limited, retrying in " + wait + "s");
    } catch (AuthenticationException ex) {
        System.out.println("Check your clientId / apiKey");
    } catch (ValidationException ex) {
        System.out.println("Invalid request: " + ex.getMessage());
    } catch (NetworkException ex) {
        System.out.println("Network problem: " + ex.getMessage());
    } catch (CallistoException ex) {
        System.out.println("API error (" + ex.getStatusCode() + "): " + ex.getMessage());
    }
}
```

## Error reporting

The SDK ships with an opt-in, Sentry-style error reporter that POSTs captured errors to a Callisto
error-tracking **ingest endpoint** (the DSN). It auto-captures the SDK's own `CallistoException`s
(API and network errors, plus client-side validation errors) and exposes a public API so your
application can report its own exceptions. Delivery is **background, best-effort, and never alters
or delays the original error** — when no DSN is configured, every method is a cheap no-op and the
SDK behaves exactly as before.

### Enabling

Set the DSN via the constructor or the `CALLISTO_APP_ERROR_DSN` environment variable. The DSN **is** the
full POST URL (e.g. `https://app.callistosignal.com/ingest/<uuid>?key=<key>`).

```java
CallistoClient callisto = new CallistoClient(
        "your-client-id", "your-api-key",
        null, null,            // baseUrl, timeout
        null, null,            // httpClient, mapper
        "https://app.callistosignal.com/ingest/<uuid>?key=<key>", // errorDsn
        false,                 // captureUnhandled
        "production",          // environment
        null);                 // errorSender (advanced/testing)
```

### Environment variables

| Variable | Maps to | Default | Meaning |
| --- | --- | --- | --- |
| `CALLISTO_APP_ERROR_DSN` | `errorDsn` | none | Ingest DSN. Absent → reporting fully disabled (no-op). |
| `CALLISTO_CAPTURE_UNHANDLED` | `captureUnhandled` | `false` | Install the global unhandled-exception handler. |
| `CALLISTO_ENVIRONMENT` | `environment` | none | Optional tag included in `context.environment`. |

`captureUnhandled` accepts `1`, `true`, `yes`, or `on` (case-insensitive). Resolution order matches
the credentials: explicit constructor argument first, then the environment variable.

### Public API

```java
callisto.captureException(throwable);
callisto.captureException(throwable, "warning", Map.of("order_id", "ord_9"));
callisto.captureMessage("something happened");
callisto.captureMessage("something happened", "info", Map.of("k", "v"));
callisto.setUser(Map.of("id", "u_1", "email", "user@example.com"));
```

`level` is constrained to `fatal | error | warning | info`. All methods are no-ops without a DSN and
never throw. The reporter is also reachable via `callisto.errorReporter()` for advanced use.

### Opt-in unhandled-exception handler

When `captureUnhandled` is enabled (and a DSN is set), the client installs a
`Thread.setDefaultUncaughtExceptionHandler` that reports uncaught exceptions at `level = fatal`,
**chaining** (not clobbering) any pre-existing default handler so the platform's behavior is
preserved.

### What is sent

Each event carries `message`, `type`, `level`, `culprit`, `stacktrace`, `context`
(`{ sdk: { name, version, language }, environment? }` plus per-call `extra`; for API errors also
`status_code`, the response `body`, and rate-limit `retry_after`), and — for transport errors —
`request: { method, path }`. `setUser` data is attached as `user`.

### PII guarantee

The reporter **never** transmits your `clientId`, `apiKey`, the `Authorization` header, or the
**outgoing request body** (which carries phone numbers and message content). Only the server's error
`body`, `status_code`, HTTP `method`, and request `path` leave the process. The reporter uses its
own dedicated HTTP client, never the main transport, so it never inherits the Basic-auth
credentials. Its own failures (any exception or non-`202` response) are silently swallowed and never
re-captured.

## Testing seam

`Transport` accepts an injectable `java.net.http.HttpClient` and `ObjectMapper`, exposed through the advanced `CallistoClient` constructor:

```java
new CallistoClient(clientId, apiKey, baseUrl, timeout, httpClient, mapper);
```

Pass a stub/mock `HttpClient` that captures the outgoing `HttpRequest` and returns canned `HttpResponse`s to test request shaping and response decoding without network access.

## Building from source

```bash
mvn -DskipTests compile   # compile
mvn test                  # run the test suite
mvn package               # build the jar
```
