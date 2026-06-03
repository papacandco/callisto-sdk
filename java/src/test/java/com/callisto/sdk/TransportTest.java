package com.callisto.sdk;

import com.callisto.sdk.errors.NetworkException;
import com.callisto.sdk.errors.RateLimitException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportTest {

    @Test
    void dropsNullQueryParams() {
        StubHttpClient stub = new StubHttpClient(200, "{\"items\": [], \"total\": 0, "
                + "\"per_page\": 0, \"current_page\": 1, \"next\": null, \"previous\": null, "
                + "\"total_pages\": 0}");
        try (CallistoClient client = TestSupport.client(stub)) {
            client.sms().list(null, null, 3, null);
            String uri = stub.uri().toString();
            assertTrue(uri.contains("page=3"), uri);
            assertFalse(uri.contains("started_at"), uri);
            assertFalse(uri.contains("per_page"), uri);
            assertFalse(uri.contains("ended_at"), uri);
        }
    }

    @Test
    void urlEncodesQueryValues() {
        StubHttpClient stub = new StubHttpClient(200, "{\"items\": [], \"total\": 0, "
                + "\"per_page\": 0, \"current_page\": 1, \"next\": null, \"previous\": null, "
                + "\"total_pages\": 0}");
        try (CallistoClient client = TestSupport.client(stub)) {
            client.sms().list("2026-06-01 10:00:00", null, null, null);
            String uri = stub.uri().toString();
            // space must be percent-encoded
            assertTrue(uri.contains("started_at=2026-06-01+10%3A00%3A00")
                    || uri.contains("started_at=2026-06-01%2010%3A00%3A00"), uri);
        }
    }

    @Test
    void noQueryStringWhenAllNull() {
        StubHttpClient stub = new StubHttpClient(200, "{\"items\": [], \"total\": 0, "
                + "\"per_page\": 0, \"current_page\": 1, \"next\": null, \"previous\": null, "
                + "\"total_pages\": 0}");
        try (CallistoClient client = TestSupport.client(stub)) {
            client.sms().list();
            assertEquals("https://api.example.com/v1/sms/messages", stub.uri().toString());
        }
    }

    @Test
    void rateLimitParsesRetryAfter() {
        StubHttpClient stub = new StubHttpClient(429,
                "{\"message\": \"slow down\"}",
                Map.of("Retry-After", List.of("12")));
        try (CallistoClient client = TestSupport.client(stub)) {
            RateLimitException ex = assertThrows(RateLimitException.class,
                    () -> client.balance().get());
            assertEquals(429, ex.getStatusCode());
            assertEquals("slow down", ex.getMessage());
            assertEquals(12, ex.getRetryAfter());
        }
    }

    @Test
    void rateLimitWithoutRetryAfterHeaderIsNull() {
        StubHttpClient stub = new StubHttpClient(429, "{\"message\": \"slow down\"}");
        try (CallistoClient client = TestSupport.client(stub)) {
            RateLimitException ex = assertThrows(RateLimitException.class,
                    () -> client.balance().get());
            assertNull(ex.getRetryAfter());
        }
    }

    @Test
    void networkFailureWrapsAsNetworkException() {
        StubHttpClient stub = new StubHttpClient.Throwing();
        try (CallistoClient client = TestSupport.client(stub)) {
            NetworkException ex = assertThrows(NetworkException.class,
                    () -> client.balance().get());
            assertEquals(0, ex.getStatusCode());
        }
    }
}
