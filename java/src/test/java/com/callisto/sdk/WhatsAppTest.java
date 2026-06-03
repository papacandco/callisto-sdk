package com.callisto.sdk;

import com.callisto.sdk.enums.WhatsAppMediaType;
import com.callisto.sdk.models.Paginated;
import com.callisto.sdk.models.SendWaResult;
import com.callisto.sdk.models.WhatsAppInstance;
import com.callisto.sdk.models.WhatsAppMessage;
import com.callisto.sdk.resources.WaButtonsRequest;
import com.callisto.sdk.resources.WaListRequest;
import com.callisto.sdk.resources.WaLocationRequest;
import com.callisto.sdk.resources.WaMediaRequest;
import com.callisto.sdk.resources.WaTextRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatsAppTest {

    private static final String SEND_RESPONSE =
            "{\"id\": \"w1\", \"instance_id\": \"i1\", \"recipient\": \"+1\", "
                    + "\"message_type\": \"text\", \"status\": \"queued\", \"scheduled\": false}";

    @Test
    void createInstanceDropsNulls() {
        StubHttpClient stub = new StubHttpClient(200, "{\"id\": \"i1\", \"code\": \"inst_1\"}");
        try (CallistoClient client = TestSupport.client(stub)) {
            WhatsAppInstance inst = client.whatsapp().createInstance("Main");
            assertEquals("POST", stub.method());
            assertEquals("https://api.example.com/v1/whatsapp/instances", stub.uri().toString());
            JsonNode body = TestSupport.json(stub.lastBody);
            assertEquals("Main", body.get("name").asText());
            assertFalseHas(body, "phone_number");
            assertEquals("inst_1", inst.getCode());
        }
    }

    @Test
    void listInstancesDefaultsToPageOne() {
        StubHttpClient stub = new StubHttpClient(200,
                "{\"items\": [{\"id\": \"i1\"}], \"total\": 1, \"per_page\": 10, "
                        + "\"current_page\": 1, \"next\": null, \"previous\": null, \"total_pages\": 1}");
        try (CallistoClient client = TestSupport.client(stub)) {
            Paginated<WhatsAppInstance> page = client.whatsapp().listInstances();
            assertTrue(stub.uri().toString().contains("page=1"));
            assertEquals(1, page.getItems().size());
        }
    }

    @Test
    void getInstanceUsesCodePath() {
        StubHttpClient stub = new StubHttpClient(200, "{\"id\": \"i1\", \"code\": \"inst_1\"}");
        try (CallistoClient client = TestSupport.client(stub)) {
            client.whatsapp().getInstance("inst_1");
            assertEquals("https://api.example.com/v1/whatsapp/inst_1", stub.uri().toString());
        }
    }

    @Test
    void getQrReturnsRawJson() {
        StubHttpClient stub = new StubHttpClient(200, "{\"qr_code\": \"data:image/png;base64,XX\"}");
        try (CallistoClient client = TestSupport.client(stub)) {
            JsonNode qr = client.whatsapp().getQr("inst_1");
            assertEquals("https://api.example.com/v1/whatsapp/inst_1/qr", stub.uri().toString());
            assertEquals("data:image/png;base64,XX", qr.get("qr_code").asText());
        }
    }

    @Test
    void getStatusReturnsRawJson() {
        StubHttpClient stub = new StubHttpClient(200, "{\"status\": \"connected\"}");
        try (CallistoClient client = TestSupport.client(stub)) {
            JsonNode status = client.whatsapp().getStatus("inst_1");
            assertEquals("https://api.example.com/v1/whatsapp/inst_1/status", stub.uri().toString());
            assertEquals("connected", status.get("status").asText());
        }
    }

    @Test
    void listMessagesBuildsQuery() {
        StubHttpClient stub = new StubHttpClient(200,
                "{\"items\": [], \"total\": 0, \"per_page\": 0, \"current_page\": 1, "
                        + "\"next\": null, \"previous\": null, \"total_pages\": 0}");
        try (CallistoClient client = TestSupport.client(stub)) {
            client.whatsapp().listMessages("inst_1", null, null, 2, 25);
            String uri = stub.uri().toString();
            assertTrue(uri.contains("/whatsapp/inst_1/messages?"), uri);
            assertTrue(uri.contains("page=2"), uri);
            assertTrue(uri.contains("per_page=25"), uri);
        }
    }

    @Test
    void getMessageUsesMessagesPath() {
        StubHttpClient stub = new StubHttpClient(200, "{\"id\": \"msg_9\", \"status\": \"sent\"}");
        try (CallistoClient client = TestSupport.client(stub)) {
            WhatsAppMessage msg = client.whatsapp().getMessage("msg_9");
            assertEquals("https://api.example.com/v1/whatsapp/messages/msg_9", stub.uri().toString());
            assertEquals("sent", msg.getStatus());
        }
    }

    @Test
    void sendText() {
        StubHttpClient stub = new StubHttpClient(200, SEND_RESPONSE);
        try (CallistoClient client = TestSupport.client(stub)) {
            SendWaResult result = client.whatsapp().sendText(WaTextRequest.builder()
                    .code("inst_1").to("+1").message("Hi").build());
            assertEquals("https://api.example.com/v1/whatsapp/inst_1/send/text", stub.uri().toString());
            JsonNode body = TestSupport.json(stub.lastBody);
            assertEquals("+1", body.get("to").asText());
            assertEquals("Hi", body.get("message").asText());
            assertEquals("w1", result.getId());
        }
    }

    @Test
    void sendMediaSerializesEnumValue() {
        StubHttpClient stub = new StubHttpClient(200, SEND_RESPONSE);
        try (CallistoClient client = TestSupport.client(stub)) {
            client.whatsapp().sendMedia(WaMediaRequest.builder()
                    .code("inst_1").to("+1").type(WhatsAppMediaType.IMAGE)
                    .mediaUrl("https://img").caption("Hi").build());
            assertEquals("https://api.example.com/v1/whatsapp/inst_1/send/media", stub.uri().toString());
            JsonNode body = TestSupport.json(stub.lastBody);
            assertEquals("image", body.get("type").asText());
            assertEquals("https://img", body.get("media_url").asText());
            assertEquals("Hi", body.get("caption").asText());
        }
    }

    @Test
    void sendButtons() {
        StubHttpClient stub = new StubHttpClient(200, SEND_RESPONSE);
        try (CallistoClient client = TestSupport.client(stub)) {
            client.whatsapp().sendButtons(WaButtonsRequest.builder()
                    .code("inst_1").to("+1").body("Confirm?")
                    .buttons(List.of(Map.of("id", "yes", "title", "Yes"))).build());
            assertEquals("https://api.example.com/v1/whatsapp/inst_1/send/buttons",
                    stub.uri().toString());
            JsonNode body = TestSupport.json(stub.lastBody);
            assertTrue(body.get("buttons").isArray());
            assertEquals("yes", body.get("buttons").get(0).get("id").asText());
        }
    }

    @Test
    void sendLocation() {
        StubHttpClient stub = new StubHttpClient(200, SEND_RESPONSE);
        try (CallistoClient client = TestSupport.client(stub)) {
            client.whatsapp().sendLocation(WaLocationRequest.builder()
                    .code("inst_1").to("+1").latitude(5.36).longitude(-4.0).name("HQ").build());
            assertEquals("https://api.example.com/v1/whatsapp/inst_1/send/location",
                    stub.uri().toString());
            JsonNode body = TestSupport.json(stub.lastBody);
            assertEquals(5.36, body.get("latitude").asDouble());
            assertEquals(-4.0, body.get("longitude").asDouble());
            assertEquals("HQ", body.get("name").asText());
        }
    }

    @Test
    void sendList() {
        StubHttpClient stub = new StubHttpClient(200, SEND_RESPONSE);
        try (CallistoClient client = TestSupport.client(stub)) {
            client.whatsapp().sendList(WaListRequest.builder()
                    .code("inst_1").to("+1").body("Pick").buttonText("View")
                    .sections(List.of(Map.of("title", "Plans"))).build());
            assertEquals("https://api.example.com/v1/whatsapp/inst_1/send/list",
                    stub.uri().toString());
            JsonNode body = TestSupport.json(stub.lastBody);
            assertEquals("View", body.get("button_text").asText());
            assertTrue(body.get("sections").isArray());
        }
    }

    private static void assertFalseHas(JsonNode node, String field) {
        assertTrue(!node.has(field), "expected no field " + field);
    }
}
