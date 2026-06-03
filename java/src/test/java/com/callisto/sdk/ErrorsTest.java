package com.callisto.sdk;

import com.callisto.sdk.errors.ApiException;
import com.callisto.sdk.errors.AuthenticationException;
import com.callisto.sdk.errors.CallistoException;
import com.callisto.sdk.errors.NotFoundException;
import com.callisto.sdk.errors.RateLimitException;
import com.callisto.sdk.errors.ValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ErrorsTest {

    private CallistoClient clientFor(int status, String body) {
        return TestSupport.client(new StubHttpClient(status, body));
    }

    @Test
    void maps401ToAuthentication() {
        try (CallistoClient client = clientFor(401, "{\"message\": \"bad creds\"}")) {
            AuthenticationException ex = assertThrows(AuthenticationException.class,
                    () -> client.balance().get());
            assertEquals(401, ex.getStatusCode());
            assertEquals("bad creds", ex.getMessage());
            assertNotNull(ex.getBody());
        }
    }

    @Test
    void maps400ToValidation() {
        try (CallistoClient client = clientFor(400, "{\"message\": \"invalid\"}")) {
            ValidationException ex = assertThrows(ValidationException.class,
                    () -> client.balance().get());
            assertEquals(400, ex.getStatusCode());
        }
    }

    @Test
    void maps422ToValidation() {
        try (CallistoClient client = clientFor(422, "{\"message\": \"unprocessable\"}")) {
            assertThrows(ValidationException.class, () -> client.balance().get());
        }
    }

    @Test
    void maps404ToNotFound() {
        try (CallistoClient client = clientFor(404, "{\"message\": \"nope\"}")) {
            NotFoundException ex = assertThrows(NotFoundException.class,
                    () -> client.balance().get());
            assertEquals(404, ex.getStatusCode());
        }
    }

    @Test
    void maps429ToRateLimit() {
        try (CallistoClient client = clientFor(429, "{\"message\": \"rate\"}")) {
            assertThrows(RateLimitException.class, () -> client.balance().get());
        }
    }

    @Test
    void mapsOtherToApiError() {
        try (CallistoClient client = clientFor(500, "{\"message\": \"boom\"}")) {
            ApiException ex = assertThrows(ApiException.class, () -> client.balance().get());
            assertEquals(500, ex.getStatusCode());
            assertEquals("boom", ex.getMessage());
        }
    }

    @Test
    void fallsBackToHttpStatusMessageWhenNoMessageField() {
        try (CallistoClient client = clientFor(503, "{\"error\": \"unavailable\"}")) {
            ApiException ex = assertThrows(ApiException.class, () -> client.balance().get());
            assertEquals("HTTP 503", ex.getMessage());
        }
    }

    @Test
    void allErrorsDeriveFromCallistoException() {
        try (CallistoClient client = clientFor(401, "{\"message\": \"x\"}")) {
            assertThrows(CallistoException.class, () -> client.balance().get());
        }
    }
}
