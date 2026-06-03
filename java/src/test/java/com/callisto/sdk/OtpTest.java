package com.callisto.sdk;

import com.callisto.sdk.enums.OtpProvider;
import com.callisto.sdk.enums.OtpType;
import com.callisto.sdk.errors.ValidationException;
import com.callisto.sdk.models.SendOtpResult;
import com.callisto.sdk.models.VerifyOtpResult;
import com.callisto.sdk.resources.OtpSendRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtpTest {

    private static final String SEND_RESPONSE =
            "{\"id\": \"otp_1\", \"provider\": \"sms\", \"recipient\": {}, "
                    + "\"expires_at\": \"2026\", \"expires_in\": 300}";

    @Test
    void sendBuildsBodyWithEnumValues() {
        StubHttpClient stub = new StubHttpClient(200, SEND_RESPONSE);
        try (CallistoClient client = TestSupport.client(stub)) {
            SendOtpResult result = client.otp().send(OtpSendRequest.builder()
                    .to("+225070").message("Code {code}")
                    .type(OtpType.DIGIT).digitSize(6).expiredIn(300).build());

            assertEquals("POST", stub.method());
            assertEquals("https://api.example.com/v1/otp/send", stub.uri().toString());
            JsonNode body = TestSupport.json(stub.lastBody);
            assertEquals("digit", body.get("type").asText());
            assertEquals(6, body.get("digit_size").asInt());
            assertEquals(300, body.get("expired_in").asInt());
            assertEquals("otp_1", result.getId());
        }
    }

    @Test
    void sendWhatsappRequiresInstanceCode() {
        StubHttpClient stub = new StubHttpClient(200, SEND_RESPONSE);
        try (CallistoClient client = TestSupport.client(stub)) {
            assertThrows(ValidationException.class, () -> client.otp().send(
                    OtpSendRequest.builder().to("+1").message("m")
                            .provider(OtpProvider.WHATSAPP).build()));
        }
    }

    @Test
    void sendWhatsappSerializesInstanceCodeKey() {
        StubHttpClient stub = new StubHttpClient(200, SEND_RESPONSE);
        try (CallistoClient client = TestSupport.client(stub)) {
            client.otp().send(OtpSendRequest.builder().to("+1").message("m")
                    .provider("whatsapp").instanceCode("inst_1").build());
            JsonNode body = TestSupport.json(stub.lastBody);
            assertEquals("whatsapp", body.get("provider").asText());
            assertEquals("inst_1", body.get("instanceCode").asText());
        }
    }

    @Test
    void verifyPostsBody() {
        StubHttpClient stub = new StubHttpClient(200,
                "{\"id\": \"otp_1\", \"status\": \"verified\", \"verified\": true}");
        try (CallistoClient client = TestSupport.client(stub)) {
            VerifyOtpResult result = client.otp().verify("otp_1", "123456");
            assertEquals("https://api.example.com/v1/otp/verify", stub.uri().toString());
            JsonNode body = TestSupport.json(stub.lastBody);
            assertEquals("otp_1", body.get("otp_id").asText());
            assertEquals("123456", body.get("code").asText());
            assertTrue(result.isVerified());
        }
    }

    @Test
    void getStatusUsesOtpsPath() {
        StubHttpClient stub = new StubHttpClient(200, "{\"otp_id\": \"otp_1\", \"status\": \"pending\"}");
        try (CallistoClient client = TestSupport.client(stub)) {
            client.otp().getStatus("otp_1");
            assertEquals("https://api.example.com/v1/otps/otp_1", stub.uri().toString());
        }
    }

    @Test
    void listUsesLimitQueryKey() {
        StubHttpClient stub = new StubHttpClient(200,
                "{\"items\": [], \"total\": 0, \"per_page\": 0, \"current_page\": 1, "
                        + "\"next\": null, \"previous\": null, \"total_pages\": 0}");
        try (CallistoClient client = TestSupport.client(stub)) {
            client.otp().list(null, null, 1, 20);
            String uri = stub.uri().toString();
            assertTrue(uri.contains("/otps?"), uri);
            assertTrue(uri.contains("limit=20"), uri);
            assertFalse(uri.contains("per_page"), uri);
        }
    }
}
