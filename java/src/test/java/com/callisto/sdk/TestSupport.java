package com.callisto.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

/** Shared helpers for building a client wired to a {@link StubHttpClient}. */
final class TestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private TestSupport() {
    }

    static CallistoClient client(StubHttpClient stub) {
        return new CallistoClient(
                "cid", "key", "https://api.example.com/v1", Duration.ofSeconds(5), stub, MAPPER);
    }

    static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
