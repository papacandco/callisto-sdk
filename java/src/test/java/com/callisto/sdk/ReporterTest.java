package com.callisto.sdk;

import com.callisto.sdk.errors.NetworkException;
import com.callisto.sdk.errors.ValidationException;
import com.callisto.sdk.reporting.ErrorReporter;
import com.callisto.sdk.reporting.ErrorSender;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReporterTest {

    private static final String DSN = "https://app.callistosignal.com/ingest/uuid?key=abc123";

    /** Fake sender that records the last DSN + JSON and returns a configurable status. */
    static final class FakeSender implements ErrorSender {
        volatile String lastDsn;
        volatile String lastJson;
        final AtomicInteger calls = new AtomicInteger();
        int status = 202;
        boolean throwOnSend = false;

        @Override
        public int send(String dsn, String json) throws Exception {
            calls.incrementAndGet();
            this.lastDsn = dsn;
            this.lastJson = json;
            if (throwOnSend) {
                throw new RuntimeException("boom");
            }
            return status;
        }
    }

    private static CallistoClient client(StubHttpClient stub, FakeSender sender) {
        return new CallistoClient(
                "cid", "key", "https://api.example.com/v1", Duration.ofSeconds(5),
                stub, TestSupport.MAPPER,
                DSN, false, "production", sender);
    }

    private JsonNode payload(FakeSender sender) {
        assertNotNull(sender.lastJson, "expected a payload to have been sent");
        return TestSupport.json(sender.lastJson);
    }

    @Test
    void capturedApiErrorPostsToDsnWithMessageTypeLevel() {
        StubHttpClient stub = new StubHttpClient(404, "{\"message\": \"not found\"}");
        FakeSender sender = new FakeSender();
        try (CallistoClient client = client(stub, sender)) {
            assertThrows(com.callisto.sdk.errors.NotFoundException.class,
                    () -> client.balance().get());
            client.errorReporter().flush();

            assertEquals(DSN, sender.lastDsn);
            JsonNode p = payload(sender);
            assertEquals("not found", p.get("message").asText());
            assertEquals("com.callisto.sdk.errors.NotFoundException", p.get("type").asText());
            assertEquals("error", p.get("level").asText());
        }
    }

    @Test
    void contextSdkAndStatusCodeAndRequestPresentForTransportErrors() {
        StubHttpClient stub = new StubHttpClient(404, "{\"message\": \"not found\"}");
        FakeSender sender = new FakeSender();
        try (CallistoClient client = client(stub, sender)) {
            assertThrows(RuntimeException.class, () -> client.balance().get());
            client.errorReporter().flush();

            JsonNode p = payload(sender);
            JsonNode sdk = p.get("context").get("sdk");
            assertEquals(ErrorReporter.SDK_NAME, sdk.get("name").asText());
            assertEquals(ErrorReporter.SDK_LANGUAGE, sdk.get("language").asText());
            assertEquals(ErrorReporter.SDK_VERSION, sdk.get("version").asText());
            assertEquals("production", p.get("context").get("environment").asText());
            assertEquals(404, p.get("context").get("status_code").asInt());

            JsonNode request = p.get("request");
            assertEquals("GET", request.get("method").asText());
            assertEquals("/sms/balance", request.get("path").asText());
            assertEquals("GET /sms/balance", p.get("culprit").asText());
        }
    }

    @Test
    void networkErrorIsCaptured() {
        StubHttpClient stub = new StubHttpClient.Throwing();
        FakeSender sender = new FakeSender();
        try (CallistoClient client = client(stub, sender)) {
            assertThrows(NetworkException.class, () -> client.balance().get());
            client.errorReporter().flush();

            JsonNode p = payload(sender);
            assertEquals("com.callisto.sdk.errors.NetworkException", p.get("type").asText());
            assertEquals("GET", p.get("request").get("method").asText());
            assertEquals("/sms/balance", p.get("request").get("path").asText());
        }
    }

    @Test
    void noCredentialOrRequestBodyLeak() {
        StubHttpClient stub = new StubHttpClient(401, "{\"message\": \"invalid_key\"}");
        FakeSender sender = new FakeSender();
        try (CallistoClient client = client(stub, sender)) {
            assertThrows(RuntimeException.class,
                    () -> client.sms().send(com.callisto.sdk.resources.SmsSendRequest.builder()
                            .sender("Acme").to("+2250700000000").message("secret code 1234").build()));
            client.errorReporter().flush();

            String raw = sender.lastJson;
            assertNotNull(raw);
            // Credentials / auth must never appear.
            assertFalse(raw.contains("cid"), raw);
            assertFalse(raw.contains("\"key\""), raw);
            assertFalse(raw.toLowerCase().contains("authorization"), raw);
            assertFalse(raw.contains("Basic "), raw);
            // The outgoing request body (phone numbers, message content) must never leak.
            assertFalse(raw.contains("+2250700000000"), raw);
            assertFalse(raw.contains("secret code 1234"), raw);
        }
    }

    @Test
    void senderFailuresAreSwallowed() {
        StubHttpClient stub = new StubHttpClient(404, "{\"message\": \"nope\"}");
        FakeSender sender = new FakeSender();
        sender.throwOnSend = true;
        try (CallistoClient client = client(stub, sender)) {
            // Original error still propagates; capture path never throws.
            assertThrows(RuntimeException.class, () -> client.balance().get());
            client.errorReporter().flush();
            assertTrue(sender.calls.get() >= 1);
        }
    }

    @Test
    void nonAcceptedStatusIsSwallowed() {
        StubHttpClient stub = new StubHttpClient(404, "{\"message\": \"nope\"}");
        FakeSender sender = new FakeSender();
        sender.status = 401; // ingest rejects; reporter swallows
        try (CallistoClient client = client(stub, sender)) {
            assertThrows(RuntimeException.class, () -> client.balance().get());
            client.errorReporter().flush();
            assertEquals(1, sender.calls.get());
        }
    }

    @Test
    void captureNeverThrowsOnNull() {
        FakeSender sender = new FakeSender();
        ErrorReporter reporter = new ErrorReporter(DSN, null, sender, TestSupport.MAPPER);
        reporter.captureException(null);
        reporter.captureMessage(null);
        reporter.flush();
        reporter.close();
        assertEquals(0, sender.calls.get());
    }

    @Test
    void noOpWithoutDsn() {
        StubHttpClient stub = new StubHttpClient(404, "{\"message\": \"nope\"}");
        FakeSender sender = new FakeSender();
        // No DSN -> reporter disabled; behaves exactly as before.
        try (CallistoClient client = new CallistoClient(
                "cid", "key", "https://api.example.com/v1", Duration.ofSeconds(5),
                stub, TestSupport.MAPPER, null, false, null, sender)) {
            assertFalse(client.errorReporter().isEnabled());
            assertThrows(RuntimeException.class, () -> client.balance().get());
            client.errorReporter().flush();
            assertEquals(0, sender.calls.get());
            assertNull(sender.lastJson);
        }
    }

    @Test
    void invalidDsnIsNoOp() {
        FakeSender sender = new FakeSender();
        ErrorReporter reporter = new ErrorReporter("not a url", null, sender, TestSupport.MAPPER);
        assertFalse(reporter.isEnabled());
        reporter.captureMessage("hello", "info", null);
        reporter.flush();
        assertEquals(0, sender.calls.get());
    }

    @Test
    void publicCaptureMessageWorks() {
        FakeSender sender = new FakeSender();
        StubHttpClient stub = new StubHttpClient(200, "{}");
        try (CallistoClient client = client(stub, sender)) {
            client.captureMessage("something happened");
            client.errorReporter().flush();

            JsonNode p = payload(sender);
            assertEquals("something happened", p.get("message").asText());
            assertEquals("info", p.get("level").asText());
            assertNotNull(p.get("context").get("sdk"));
        }
    }

    @Test
    void publicCaptureExceptionWithLevelAndExtraAndUser() {
        FakeSender sender = new FakeSender();
        StubHttpClient stub = new StubHttpClient(200, "{}");
        try (CallistoClient client = client(stub, sender)) {
            client.setUser(Map.of("id", "u_1", "email", "user@example.com"));
            client.captureException(new IllegalStateException("bad state"),
                    "warning", Map.of("order_id", "ord_9"));
            client.errorReporter().flush();

            JsonNode p = payload(sender);
            assertEquals("bad state", p.get("message").asText());
            assertEquals("java.lang.IllegalStateException", p.get("type").asText());
            assertEquals("warning", p.get("level").asText());
            assertEquals("ord_9", p.get("context").get("order_id").asText());
            assertEquals("u_1", p.get("user").get("id").asText());
        }
    }

    @Test
    void clientSideValidationErrorIsCaptured() {
        FakeSender sender = new FakeSender();
        StubHttpClient stub = new StubHttpClient(200, "{}");
        try (CallistoClient client = client(stub, sender)) {
            assertThrows(ValidationException.class,
                    () -> client.notifications().send(
                            com.callisto.sdk.resources.NotifyRequest.builder().topic("welcome").build()));
            client.errorReporter().flush();

            JsonNode p = payload(sender);
            assertEquals("com.callisto.sdk.errors.ValidationException", p.get("type").asText());
        }
    }

    @Test
    void uncaughtHandlerInstallsAndChains() {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        AtomicInteger priorCalls = new AtomicInteger();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> priorCalls.incrementAndGet());
        FakeSender sender = new FakeSender();
        StubHttpClient stub = new StubHttpClient(200, "{}");
        try (CallistoClient client = new CallistoClient(
                "cid", "key", "https://api.example.com/v1", Duration.ofSeconds(5),
                stub, TestSupport.MAPPER, DSN, true, null, sender)) {
            Thread.UncaughtExceptionHandler installed = Thread.getDefaultUncaughtExceptionHandler();
            assertNotNull(installed);
            // Invoke directly (without crashing the test process); it should report + chain.
            installed.uncaughtException(Thread.currentThread(), new RuntimeException("fatal boom"));
            client.errorReporter().flush();

            JsonNode p = payload(sender);
            assertEquals("fatal", p.get("level").asText());
            assertEquals(1, priorCalls.get(), "pre-existing handler must be chained");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }

    @Test
    void rateLimitContextCarriesRetryAfter() {
        StubHttpClient stub = new StubHttpClient(429, "{\"message\": \"slow down\"}",
                Map.of("Retry-After", List.of("30")));
        FakeSender sender = new FakeSender();
        try (CallistoClient client = client(stub, sender)) {
            assertThrows(com.callisto.sdk.errors.RateLimitException.class,
                    () -> client.balance().get());
            client.errorReporter().flush();

            JsonNode p = payload(sender);
            assertEquals(429, p.get("context").get("status_code").asInt());
            assertEquals(30, p.get("context").get("retry_after").asInt());
        }
    }
}
