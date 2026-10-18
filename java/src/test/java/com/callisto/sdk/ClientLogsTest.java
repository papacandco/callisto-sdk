package com.callisto.sdk;

import com.callisto.sdk.logs.CallistoLogger;
import com.callisto.sdk.logs.FakeLogSender;
import com.callisto.sdk.reporting.ErrorReporter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientLogsTest {

    private static final String CLIENT_ID = "messaging-client-id-7f3a";
    private static final String API_KEY = "messaging-api-key-9c1e";

    @Test
    void clientLogsIsANoOpWithoutALogsKey() {
        StubHttpClient stub = new StubHttpClient(200, "{}");
        try (CallistoClient client = TestSupport.client(stub)) {
            assertNotNull(client.logs());
            if (System.getenv("CALLISTO_LOGS_ACCESS_KEY") == null
                    && System.getenv("CALLISTO_LOGS_SECRET") == null) {
                assertFalse(client.logs().isEnabled());
            }
            client.logs().info("ignored");
        }
    }

    @Test
    void messagingCredentialsNeverReachALogsRequestAndViceVersa() throws Exception {
        StubHttpClient stub = new StubHttpClient(200, "{\"balance\": 10}");
        FakeLogSender logSender = new FakeLogSender();
        CallistoLogger.Builder logs = CallistoLogger.builder()
                .accessKey(FakeLogSender.PK).secret(FakeLogSender.SK)
                .endpoint("https://logs.example.test/v1/logs")
                .serviceName("checkout")
                .sender(logSender);
        CallistoClient client = new CallistoClient(CLIENT_ID, API_KEY, "https://api.example.com/v1",
                Duration.ofSeconds(5), stub, TestSupport.MAPPER, null, false, "production", null, logs);
        try {
            assertTrue(client.logs().isEnabled());
            assertEquals("production", client.logs().environment(),
                    "the logger inherits the client's environment");
            client.logs().info("order paid", Map.of("order_id", 42));
            client.balance().get();
            client.logs().flush();
        } finally {
            client.close();
        }

        String basicMessaging = Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + API_KEY).getBytes(StandardCharsets.UTF_8));
        assertEquals(1, logSender.requests.size());
        FakeLogSender.Request r = logSender.requests.get(0);
        String everything = r.endpoint + r.headers + r.body;
        assertFalse(everything.contains(CLIENT_ID));
        assertFalse(everything.contains(API_KEY));
        assertFalse(everything.contains(basicMessaging));

        String messagingRequest = stub.lastRequest.uri() + " " + stub.lastRequest.headers().map()
                + " " + stub.lastBody;
        String basicLogs = Base64.getEncoder().encodeToString(
                (FakeLogSender.PK + ":" + FakeLogSender.SK).getBytes(StandardCharsets.UTF_8));
        assertFalse(messagingRequest.contains(FakeLogSender.SK));
        assertFalse(messagingRequest.contains(FakeLogSender.PK));
        assertFalse(messagingRequest.contains(basicLogs));
    }

    @Test
    void closingTheClientClosesTheLogger() {
        StubHttpClient stub = new StubHttpClient(200, "{}");
        FakeLogSender logSender = new FakeLogSender();
        CallistoClient client = new CallistoClient("cid", "key", "https://api.example.com/v1",
                Duration.ofSeconds(5), stub, TestSupport.MAPPER, null, false, null, null,
                CallistoLogger.builder().accessKey(FakeLogSender.PK).secret(FakeLogSender.SK)
                        .endpoint("https://logs.example.test/v1/logs").sender(logSender));
        client.logs().info("queued before close");
        client.close();
        assertEquals(1, logSender.requests.size(), "close flushes the queue");
        client.logs().info("after close");
        client.logs().flush();
        assertEquals(1, logSender.requests.size(), "a closed logger is a no-op");
    }

    @Test
    void sdkVersionMatchesThePomVersion() throws Exception {
        String pom = new String(Files.readAllBytes(Paths.get("pom.xml")), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<artifactId>callisto-sdk</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(pom);
        assertTrue(m.find());
        assertEquals(m.group(1), ErrorReporter.SDK_VERSION);
        assertEquals(m.group(1), CallistoLogger.SDK_VERSION);
        assertEquals("callisto-sdk-java/" + m.group(1), CallistoLogger.USER_AGENT);
    }
}
