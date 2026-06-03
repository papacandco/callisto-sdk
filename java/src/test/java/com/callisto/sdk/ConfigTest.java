package com.callisto.sdk;

import com.callisto.sdk.errors.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigTest {

    @Test
    void resolvesExplicitValuesAndTrimsTrailingSlash() {
        Config cfg = Config.resolve("cid", "key", "https://host/v1///", Duration.ofSeconds(10));
        assertEquals("cid", cfg.getClientId());
        assertEquals("key", cfg.getApiKey());
        assertEquals("https://host/v1", cfg.getBaseUrl());
        assertEquals(Duration.ofSeconds(10), cfg.getTimeout());
    }

    @Test
    void appliesDefaultBaseUrlAndTimeout() {
        Config cfg = Config.resolve("cid", "key", null, null);
        assertEquals(Config.DEFAULT_BASE_URL, cfg.getBaseUrl());
        assertEquals(Config.DEFAULT_TIMEOUT, cfg.getTimeout());
    }

    @Test
    void throwsWhenClientIdMissing() {
        assertThrows(ValidationException.class, () -> Config.resolve(null, "key", null, null));
    }

    @Test
    void throwsWhenApiKeyMissing() {
        assertThrows(ValidationException.class, () -> Config.resolve("cid", null, null, null));
    }
}
