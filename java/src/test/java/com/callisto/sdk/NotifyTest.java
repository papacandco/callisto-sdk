package com.callisto.sdk;

import com.callisto.sdk.errors.ValidationException;
import com.callisto.sdk.models.NotifyResult;
import com.callisto.sdk.resources.NotifyRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotifyTest {

    @Test
    void sendRequiresAtLeastOneEventBlock() {
        StubHttpClient stub = new StubHttpClient(200, "{}");
        try (CallistoClient client = TestSupport.client(stub)) {
            assertThrows(ValidationException.class,
                    () -> client.notifications().send(NotifyRequest.builder().topic("welcome").build()));
        }
    }

    @Test
    void sendSerializesSnakeCaseBlocks() {
        StubHttpClient stub = new StubHttpClient(200,
                "{\"status\": \"ok\", \"topic\": \"welcome\", \"queued_events\": 2, "
                        + "\"topic_messages\": []}");
        try (CallistoClient client = TestSupport.client(stub)) {
            NotifyResult result = client.notifications().send(NotifyRequest.builder()
                    .topic("welcome")
                    .sms(List.of(Map.of("to", "+225070")))
                    .mobilePush(List.of(Map.of("token", "abc")))
                    .build());

            assertEquals("POST", stub.method());
            assertEquals("https://api.example.com/v1/notify/send", stub.uri().toString());
            JsonNode body = TestSupport.json(stub.lastBody);
            assertEquals("welcome", body.get("topic").asText());
            assertTrue(body.has("sms"));
            assertTrue(body.has("mobile_push"));
            assertFalse(body.has("email"));
            assertEquals("ok", result.getStatus());
        }
    }
}
