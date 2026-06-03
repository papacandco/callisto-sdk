package com.callisto.sdk;

import com.callisto.sdk.models.Balance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceTest {

    @Test
    void getUsesDefaultsAndDecodes() {
        StubHttpClient stub = new StubHttpClient(200,
                "{\"credit\": 42.5, \"currency\": \"XOF\", \"sms_price_local\": 12.0}");
        try (CallistoClient client = TestSupport.client(stub)) {
            Balance balance = client.balance().get();

            assertEquals("GET", stub.method());
            assertEquals("https://api.example.com/v1/sms/balance?format=full", stub.uri().toString());
            assertEquals(42.5, balance.getCredit());
            assertEquals("XOF", balance.getCurrency());
            assertEquals(12.0, balance.getSmsPriceLocal());
        }
    }

    @Test
    void getPassesFormatAndCurrency() {
        StubHttpClient stub = new StubHttpClient(200, "{\"credit\": 1, \"currency\": \"USD\"}");
        try (CallistoClient client = TestSupport.client(stub)) {
            client.balance().get("compact", "USD");
            String uri = stub.uri().toString();
            assertTrue(uri.contains("format=compact"), uri);
            assertTrue(uri.contains("currency=USD"), uri);
        }
    }

    @Test
    void appliesBasicAuthAndAcceptHeaders() {
        StubHttpClient stub = new StubHttpClient(200, "{\"credit\": 1, \"currency\": \"USD\"}");
        try (CallistoClient client = TestSupport.client(stub)) {
            client.balance().get();
            // base64("cid:key") = Y2lkOmtleQ==
            assertEquals("Basic Y2lkOmtleQ==", stub.authorizationHeader());
            assertEquals("application/json", stub.acceptHeader());
        }
    }
}
