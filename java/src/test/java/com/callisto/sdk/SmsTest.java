package com.callisto.sdk;

import com.callisto.sdk.models.Paginated;
import com.callisto.sdk.models.SendSmsResult;
import com.callisto.sdk.models.SmsMessage;
import com.callisto.sdk.resources.SmsSendRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsTest {

    @Test
    void sendSingleRecipient() {
        StubHttpClient stub = new StubHttpClient(200,
                "{\"total_amount\": 5.0, \"available_credit\": 95.0, \"status\": \"queued\", "
                        + "\"recipient_count\": 1, \"scheduled\": false, \"messages\": []}");
        try (CallistoClient client = TestSupport.client(stub)) {
            SendSmsResult result = client.sms().send(SmsSendRequest.builder()
                    .sender("Acme").to("+225070").message("Hi").build());

            assertEquals("POST", stub.method());
            assertEquals("https://api.example.com/v1/sms/send", stub.uri().toString());
            JsonNode body = TestSupport.json(stub.lastBody);
            assertEquals("Acme", body.get("sender").asText());
            assertEquals("+225070", body.get("to").asText());
            assertEquals("Hi", body.get("message").asText());
            assertEquals("queued", result.getStatus());
            assertEquals(1, result.getRecipientCount());
        }
    }

    @Test
    void sendListWithOptionalFields() {
        StubHttpClient stub = new StubHttpClient(200,
                "{\"total_amount\": 0, \"available_credit\": 0, \"status\": \"queued\", "
                        + "\"recipient_count\": 2, \"scheduled\": true, \"messages\": []}");
        try (CallistoClient client = TestSupport.client(stub)) {
            client.sms().send(SmsSendRequest.builder()
                    .sender("Acme")
                    .to(List.of("+1", "+2"))
                    .message("Sale")
                    .notifyUrl("https://hook")
                    .scheduledAt("2026-06-02 10:00:00")
                    .build());

            JsonNode body = TestSupport.json(stub.lastBody);
            assertTrue(body.get("to").isArray());
            assertEquals(2, body.get("to").size());
            assertEquals("https://hook", body.get("notify_url").asText());
            assertEquals("2026-06-02 10:00:00", body.get("scheduled_at").asText());
        }
    }

    @Test
    void listBuildsQueryAndDecodesPagination() {
        StubHttpClient stub = new StubHttpClient(200,
                "{\"items\": [{\"id\": \"m1\", \"status\": \"sent\"}], \"total\": 1, "
                        + "\"per_page\": 50, \"current_page\": 1, \"next\": null, "
                        + "\"previous\": null, \"total_pages\": 1}");
        try (CallistoClient client = TestSupport.client(stub)) {
            Paginated<SmsMessage> page = client.sms().list(null, null, 1, 50);

            String uri = stub.uri().toString();
            assertTrue(uri.contains("page=1"), uri);
            assertTrue(uri.contains("per_page=50"), uri);
            assertEquals(1, page.getItems().size());
            assertEquals("m1", page.getItems().get(0).getId());
            assertEquals(1, page.getTotal());
            assertEquals(50, page.getPerPage());
            assertEquals(1, page.getCurrentPage());
            assertEquals(1, page.getTotalPages());
        }
    }

    @Test
    void getStatusUsesPathId() {
        StubHttpClient stub = new StubHttpClient(200, "{\"id\": \"abc\", \"status\": \"delivered\"}");
        try (CallistoClient client = TestSupport.client(stub)) {
            SmsMessage msg = client.sms().getStatus("abc");
            assertEquals("https://api.example.com/v1/sms/abc", stub.uri().toString());
            assertEquals("delivered", msg.getStatus());
        }
    }
}
