package com.callisto.sdk.logs;

import com.callisto.sdk.CallistoClient;
import com.callisto.sdk.errors.ValidationException;
import com.callisto.sdk.http.SecureUrl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK-04 (https only) and SDK-05 (the messaging client never throws on Logs settings). */
class ClientLogsConfigTest {

    private static final Function<String, String> NONE = n -> null;

    private final Logger clientLog = Logger.getLogger(CallistoClient.class.getName());
    private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            warnings.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private boolean parentHandlers;

    @BeforeEach
    void captureWarnings() {
        parentHandlers = clientLog.getUseParentHandlers();
        clientLog.setUseParentHandlers(false);
        clientLog.addHandler(capture);
    }

    @AfterEach
    void restoreLogging() {
        clientLog.removeHandler(capture);
        clientLog.setUseParentHandlers(parentHandlers);
    }

    private static CallistoClient client(CallistoLogger.Builder logs) {
        return new CallistoClient("cid", "key", "https://api.example.com/v1", Duration.ofSeconds(5),
                null, null, null, false, null, null, logs);
    }

    private void assertOneWarningWithoutSecrets() {
        assertEquals(1, warnings.size(), "exactly one warning");
        LogRecord w = warnings.get(0);
        assertEquals(Level.WARNING, w.getLevel());
        String text = w.getMessage();
        assertTrue(text.contains("Logs are disabled"), text);
        assertFalse(text.contains(FakeLogSender.PK), text);
        assertFalse(text.contains(FakeLogSender.SK), text);
        assertFalse(text.contains(FakeLogSender.SK.substring(0, 40)), text);
    }

    @Test
    void clientWithOnlyTheSecretInTheEnvironmentWarnsOnceAndDisablesLogs() {
        try (CallistoClient c = client(CallistoLogger.builder()
                .env(Map.of("CALLISTO_LOGS_SECRET", FakeLogSender.SK)::get))) {
            assertFalse(c.logs().isEnabled());
            c.logs().info("dropped");
        }
        assertOneWarningWithoutSecrets();
    }

    @Test
    void clientWithAMalformedSecretInTheEnvironmentWarnsOnce() {
        try (CallistoClient c = client(CallistoLogger.builder().env(Map.of(
                "CALLISTO_LOGS_ACCESS_KEY", FakeLogSender.PK,
                "CALLISTO_LOGS_SECRET", FakeLogSender.SK.substring(0, 40))::get))) {
            assertFalse(c.logs().isEnabled());
        }
        assertOneWarningWithoutSecrets();
    }

    @Test
    void clientWithAnHttpEndpointInTheEnvironmentWarnsOnce() {
        try (CallistoClient c = client(CallistoLogger.builder().env(Map.of(
                "CALLISTO_LOGS_ACCESS_KEY", FakeLogSender.PK,
                "CALLISTO_LOGS_SECRET", FakeLogSender.SK,
                "CALLISTO_LOGS_ENDPOINT", "http://logs.example.test/v1/logs")::get))) {
            assertFalse(c.logs().isEnabled());
        }
        assertOneWarningWithoutSecrets();
        assertTrue(warnings.get(0).getMessage().contains("https"));
    }

    @Test
    void clientWithHalfALogsKeyAsArgumentsWarnsOnce() {
        try (CallistoClient c = client(CallistoLogger.builder().env(NONE).accessKey(FakeLogSender.PK))) {
            assertFalse(c.logs().isEnabled());
        }
        assertOneWarningWithoutSecrets();
    }

    @Test
    void clientWithValidLogsSettingsDoesNotWarn() {
        try (CallistoClient c = client(CallistoLogger.builder().env(NONE)
                .accessKey(FakeLogSender.PK).secret(FakeLogSender.SK)
                .endpoint("http://localhost:4318/v1/logs").sender(new FakeLogSender()))) {
            assertTrue(c.logs().isEnabled());
        }
        assertEquals(0, warnings.size());
    }

    @Test
    void clientWithoutMessagingCredentialsStillThrows() {
        assertThrows(ValidationException.class, () -> new CallistoClient(null, null, null,
                Duration.ofSeconds(5), null, null, null, false, null, null,
                CallistoLogger.builder().env(NONE)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://logs.example.test/v1/logs",
            "http://logs.callistosignal.com/v1/logs",
            "http://localhost.evil.test/v1/logs",
            "http://127.0.0.2/v1/logs",
            "https:///v1/logs",
    })
    void standaloneRejectsANonHttpsEndpoint(String endpoint) {
        assertThrows(ValidationException.class, () -> CallistoLogger.builder().env(NONE)
                .accessKey(FakeLogSender.PK).secret(FakeLogSender.SK).endpoint(endpoint).build());
    }

    @Test
    void standaloneRejectsAnHttpEndpointFromTheEnvironment() {
        assertThrows(ValidationException.class, () -> CallistoLogger.builder().env(Map.of(
                "CALLISTO_LOGS_ACCESS_KEY", FakeLogSender.PK,
                "CALLISTO_LOGS_SECRET", FakeLogSender.SK,
                "CALLISTO_LOGS_ENDPOINT", "http://logs.example.test/v1/logs")::get).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://logs.example.test/v1/logs",
            "HTTPS://logs.example.test/v1/logs",
            "http://localhost/v1/logs",
            "http://localhost:4318/v1/logs",
            "http://127.0.0.1:4318/v1/logs",
            "http://[::1]:4318/v1/logs",
            "http://logs.localhost/v1/logs",
    })
    void httpsAndLoopbackHttpEndpointsAreAccepted(String endpoint) {
        FakeLogSender sender = new FakeLogSender();
        try (CallistoLogger logs = CallistoLogger.builder().env(NONE).accessKey(FakeLogSender.PK)
                .secret(FakeLogSender.SK).endpoint(endpoint).sender(sender).build()) {
            assertTrue(logs.isEnabled());
            logs.info("hi");
            logs.flush();
        }
        assertEquals(endpoint, sender.requests.get(0).endpoint);
    }

    @Test
    void secureUrlRule() {
        assertTrue(SecureUrl.isSecure("https://ingest.example/e/1"));
        assertTrue(SecureUrl.isSecure("http://LOCALHOST:8080/e/1"));
        assertTrue(SecureUrl.isSecure("http://[::1]/e/1"));
        assertFalse(SecureUrl.isSecure("http://ingest.example/e/1"));
        assertFalse(SecureUrl.isSecure("ftp://ingest.example/e/1"));
        assertFalse(SecureUrl.isSecure("not a url"));
        assertFalse(SecureUrl.isSecure(null));
    }
}
