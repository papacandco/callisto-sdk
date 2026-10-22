package com.callisto.sdk.logs;

import com.callisto.sdk.errors.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallistoLoggerTest {

    static final Instant T0 = Instant.ofEpochSecond(1760745600L);
    static final String ENDPOINT = "https://logs.example.test/v1/logs";

    final FakeLogSender sender = new FakeLogSender();
    final FakeLogSender.MutableClock clock = new FakeLogSender.MutableClock(T0);
    final List<Long> sleeps = new CopyOnWriteArrayList<>();

    CallistoLogger.Builder builder() {
        return CallistoLogger.builder()
                .accessKey(FakeLogSender.PK).secret(FakeLogSender.SK)
                .endpoint(ENDPOINT)
                .serviceName("checkout").environment("production")
                .sender(sender).clock(clock)
                .sleeper(sleeps::add)
                .env(name -> null);
    }

    static Map<String, Object> attrs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static JsonNode tree(String json) {
        try {
            return FakeLogSender.MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    JsonNode onlyRecord(CallistoLogger logs) {
        logs.flush();
        assertEquals(1, sender.requests.size());
        JsonNode records = sender.requests.get(0).records();
        assertEquals(1, records.size());
        return records.get(0);
    }

    static Map<String, JsonNode> attrMap(JsonNode record) {
        Map<String, JsonNode> m = new HashMap<>();
        JsonNode a = record.get("attributes");
        if (a != null) {
            for (JsonNode kv : a) {
                m.put(kv.get("key").asText(), kv.get("value"));
            }
        }
        return m;
    }

    // 1. golden payload ---------------------------------------------------------------------

    @Test
    void goldenPayload() {
        try (CallistoLogger logs = builder().build()) {
            logs.info("order paid", attrs("order_id", 42, "paid", true,
                    "trace_id", "5B8EFFF798038103D269B633813FC60C",
                    "span_id", "EEE19B7EC3C1B174"));
            logs.flush();
        }
        String expected = "{\"resourceLogs\":[{"
                + "\"resource\":{\"attributes\":["
                + "{\"key\":\"service.name\",\"value\":{\"stringValue\":\"checkout\"}},"
                + "{\"key\":\"deployment.environment.name\",\"value\":{\"stringValue\":\"production\"}},"
                + "{\"key\":\"telemetry.sdk.name\",\"value\":{\"stringValue\":\"callisto-sdk\"}},"
                + "{\"key\":\"telemetry.sdk.language\",\"value\":{\"stringValue\":\"java\"}},"
                + "{\"key\":\"telemetry.sdk.version\",\"value\":{\"stringValue\":\"" + CallistoLogger.SDK_VERSION + "\"}}"
                + "]},"
                + "\"scopeLogs\":[{"
                + "\"scope\":{\"name\":\"callisto-sdk\",\"version\":\"" + CallistoLogger.SDK_VERSION + "\"},"
                + "\"logRecords\":[{"
                + "\"timeUnixNano\":\"1760745600000000000\","
                + "\"observedTimeUnixNano\":\"1760745600000000000\","
                + "\"severityNumber\":9,"
                + "\"severityText\":\"INFO\","
                + "\"body\":{\"stringValue\":\"order paid\"},"
                + "\"attributes\":["
                + "{\"key\":\"order_id\",\"value\":{\"intValue\":\"42\"}},"
                + "{\"key\":\"paid\",\"value\":{\"boolValue\":true}}"
                + "],"
                + "\"traceId\":\"5b8efff798038103d269b633813fc60c\","
                + "\"spanId\":\"eee19b7ec3c1b174\""
                + "}]}]}]}";
        assertEquals(1, sender.requests.size());
        assertEquals(tree(expected), sender.requests.get(0).json());
    }

    @Test
    void noEnvironmentMeansNoEnvironmentAttributeAndDefaultServiceName() {
        try (CallistoLogger logs = builder().environment(null).serviceName(null).build()) {
            logs.info("x");
            logs.flush();
        }
        JsonNode res = sender.requests.get(0).json().get("resourceLogs").get(0)
                .get("resource").get("attributes");
        assertEquals("service.name", res.get(0).get("key").asText());
        assertEquals("unknown_service", res.get(0).get("value").get("stringValue").asText());
        for (JsonNode kv : res) {
            assertFalse(kv.get("key").asText().equals("deployment.environment.name"));
        }
        assertNull(sender.requests.get(0).records().get(0).get("attributes"),
                "empty attributes are omitted");
    }

    @Test
    void serviceNameAndKeysFallBackToEnvironmentVariables() {
        Map<String, String> env = Map.of(
                "CALLISTO_LOGS_ACCESS_KEY", FakeLogSender.PK,
                "CALLISTO_LOGS_SECRET", FakeLogSender.SK,
                "CALLISTO_LOGS_ENDPOINT", ENDPOINT,
                "OTEL_SERVICE_NAME", "otel-svc",
                "CALLISTO_ENVIRONMENT", "staging");
        try (CallistoLogger logs = CallistoLogger.builder().sender(sender).env(env::get).build()) {
            assertTrue(logs.isEnabled());
            assertEquals("otel-svc", logs.serviceName());
            assertEquals("staging", logs.environment());
            assertEquals(ENDPOINT, logs.endpoint());
        }
        Map<String, String> env2 = new HashMap<>(env);
        env2.put("CALLISTO_SERVICE_NAME", "callisto-svc");
        try (CallistoLogger logs = CallistoLogger.builder().sender(sender).env(env2::get).build()) {
            assertEquals("callisto-svc", logs.serviceName());
        }
        try (CallistoLogger logs = CallistoLogger.builder().sender(sender).env(n -> null)
                .accessKey(FakeLogSender.PK).secret(FakeLogSender.SK).build()) {
            assertEquals(CallistoLogger.DEFAULT_ENDPOINT, logs.endpoint());
        }
    }

    // 2. auth header and endpoint ----------------------------------------------------------

    @Test
    void authorizationHeaderUsesTheSharedVectorAndTheConfiguredEndpoint() {
        try (CallistoLogger logs = builder().build()) {
            logs.info("hello");
            logs.flush();
        }
        FakeLogSender.Request r = sender.requests.get(0);
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (FakeLogSender.PK + ":" + FakeLogSender.SK).getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, r.headers.get("Authorization"));
        assertEquals("Basic cGtfMDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmY6c2tfMDAwMTAyMDMwNDA1MDYwNzA4MDkwYTBiMGMwZDBlMGYxMDExMTIxMzE0MTUxNjE3",
                r.headers.get("Authorization"));
        assertEquals("application/json", r.headers.get("Content-Type"));
        assertEquals("callisto-sdk-java/" + CallistoLogger.SDK_VERSION, r.headers.get("User-Agent"));
        assertEquals(ENDPOINT, r.endpoint);
    }

    // 3. severity and AnyValue ---------------------------------------------------------------

    @Test
    void severityMappingForAllFiveLevels() {
        try (CallistoLogger logs = builder().build()) {
            logs.debug("d");
            logs.info("i");
            logs.warn("w");
            logs.error("e");
            logs.fatal("f");
            logs.log("warning", "w2", null);
            logs.log(LogLevel.FATAL, "f2");
            logs.flush();
        }
        JsonNode records = sender.requests.get(0).records();
        int[] numbers = {5, 9, 13, 17, 21, 13, 21};
        String[] texts = {"DEBUG", "INFO", "WARN", "ERROR", "FATAL", "WARN", "FATAL"};
        assertEquals(numbers.length, records.size());
        for (int i = 0; i < numbers.length; i++) {
            assertEquals(numbers[i], records.get(i).get("severityNumber").asInt());
            assertEquals(texts[i], records.get(i).get("severityText").asText());
        }
    }

    @Test
    void anyValueMapping() {
        List<Object> list = new ArrayList<>(Arrays.asList(1, "two", null, 3.5));
        Map<String, Object> nested = attrs("a", 1L, "b", attrs("c", false), "gone", null);
        Object deep = attrs("l2", attrs("l3", attrs("l4", attrs("l5", attrs("l6", "x")))));
        JsonNode rec;
        try (CallistoLogger logs = builder().build()) {
            logs.info("v", attrs(
                    "int", 7, "long", 9_007_199_254_740_993L, "float", 1.5,
                    "nan", Double.NaN, "inf", Double.POSITIVE_INFINITY,
                    "ninf", Float.NEGATIVE_INFINITY,
                    "str", "s", "bool", false, "null", null,
                    "list", list, "map", nested, "deep", deep,
                    "other", LogLevel.WARN, "ints", new int[]{1, 2}));
            rec = onlyRecord(logs);
        }
        Map<String, JsonNode> a = attrMap(rec);
        assertEquals("7", a.get("int").get("intValue").asText());
        assertTrue(a.get("int").get("intValue").isTextual(), "int is a decimal string");
        assertEquals("9007199254740993", a.get("long").get("intValue").asText());
        assertEquals(1.5, a.get("float").get("doubleValue").asDouble());
        assertTrue(a.get("float").get("doubleValue").isNumber());
        assertEquals("NaN", a.get("nan").get("stringValue").asText());
        assertEquals("Infinity", a.get("inf").get("stringValue").asText());
        assertEquals("-Infinity", a.get("ninf").get("stringValue").asText());
        assertEquals("s", a.get("str").get("stringValue").asText());
        assertFalse(a.get("bool").get("boolValue").asBoolean());
        assertFalse(a.containsKey("null"), "null attribute is omitted");
        assertEquals("WARN", a.get("other").get("stringValue").asText());

        JsonNode values = a.get("list").get("arrayValue").get("values");
        assertEquals(4, values.size());
        assertEquals("1", values.get(0).get("intValue").asText());
        assertEquals("two", values.get(1).get("stringValue").asText());
        assertEquals(0, values.get(2).size(), "null list element is {}");
        assertEquals(3.5, values.get(3).get("doubleValue").asDouble());

        JsonNode kv = a.get("map").get("kvlistValue").get("values");
        assertEquals(2, kv.size(), "null map entry is dropped");
        assertEquals("a", kv.get(0).get("key").asText());
        assertEquals("1", kv.get(0).get("value").get("intValue").asText());
        assertFalse(kv.get(1).get("value").get("kvlistValue").get("values").get(0)
                .get("value").get("boolValue").asBoolean());

        JsonNode level = a.get("deep");
        for (int i = 2; i <= 5; i++) {
            level = level.get("kvlistValue").get("values").get(0).get("value");
        }
        // level 5 is still a map; its value (level 6) is stringified
        JsonNode sixth = level.get("kvlistValue").get("values").get(0).get("value");
        assertTrue(sixth.has("stringValue"), "deeper than 5 levels is stringified: " + sixth);

        JsonNode ints = a.get("ints").get("arrayValue").get("values");
        assertEquals("2", ints.get(1).get("intValue").asText());
    }

    @Test
    void nestedContainersAreCappedAt128Items() {
        List<Object> big = new ArrayList<>();
        Map<String, Object> bigMap = new LinkedHashMap<>();
        for (int i = 0; i < 300; i++) {
            big.add(i);
            bigMap.put("k" + i, i);
        }
        int[] bigArray = new int[300];
        JsonNode rec;
        try (CallistoLogger logs = builder().build()) {
            logs.info("v", attrs("list", big, "map", bigMap, "array", bigArray,
                    "small", Arrays.asList(1, 2)));
            rec = onlyRecord(logs);
        }
        Map<String, JsonNode> a = attrMap(rec);
        JsonNode list = a.get("list").get("arrayValue").get("values");
        assertEquals(OtlpEncoder.MAX_NESTED_ITEMS, list.size());
        assertEquals("127", list.get(127).get("intValue").asText());
        JsonNode map = a.get("map").get("kvlistValue").get("values");
        assertEquals(OtlpEncoder.MAX_NESTED_ITEMS, map.size());
        assertEquals("k127", map.get(127).get("key").asText());
        assertEquals(OtlpEncoder.MAX_NESTED_ITEMS, a.get("array").get("arrayValue").get("values").size());
        assertEquals(2, a.get("small").get("arrayValue").get("values").size());
    }

    private static int countValues(JsonNode anyValue) {
        int n = 1;
        if (anyValue.has("arrayValue")) {
            for (JsonNode e : anyValue.get("arrayValue").get("values")) {
                n += countValues(e);
            }
        } else if (anyValue.has("kvlistValue")) {
            for (JsonNode kv : anyValue.get("kvlistValue").get("values")) {
                n += countValues(kv.get("value"));
            }
        }
        return n;
    }

    @Test
    void sharedSubListsCannotExplodeTheRecord() {
        for (int levels : new int[]{5, 12}) {
            List<Object> leaf = new ArrayList<>();
            for (int i = 0; i < 128; i++) {
                leaf.add(i);
            }
            Object shared = leaf;
            for (int l = 1; l < levels; l++) {
                shared = Collections.nCopies(128, shared); // the same list, 128 times, per level
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("a", shared);
            attributes.put("b", Map.of("c", shared));
            attributes.put("later", "dropped");
            long start = System.nanoTime();
            JsonNode rec = OtlpEncoder.record(T0, LogLevel.INFO, "bomb", attributes);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 100, levels + " levels took " + elapsedMs + " ms");
            int total = 0;
            for (JsonNode kv : rec.get("attributes")) {
                assertFalse("later".equals(kv.get("key").asText()), "later attributes are dropped");
                total += countValues(kv.get("value"));
            }
            assertTrue(total > 0 && total <= OtlpEncoder.MAX_RECORD_VALUES, "values = " + total);
        }
    }

    @Test
    void valueBudgetDropsLaterAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            attributes.put(String.format("k%02d", i), new int[100]);
        }
        JsonNode attrs = OtlpEncoder.record(T0, LogLevel.INFO, "m", attributes).get("attributes");
        int total = 0;
        for (JsonNode kv : attrs) {
            total += countValues(kv.get("value"));
        }
        assertEquals(OtlpEncoder.MAX_RECORD_VALUES, total);
        // 20 full arrays (20 x 101 = 2020 values), then the 21st is cut after 27 items.
        assertEquals(21, attrs.size());
        assertEquals(27, attrs.get(20).get("value").get("arrayValue").get("values").size());
    }

    @Test
    void tooDeepContainerIsStringifiedWithinBounds() {
        List<Object> huge = new ArrayList<>(Collections.nCopies(10_000, 0));
        Object deep = attrs("l2", attrs("l3", attrs("l4", attrs("l5", attrs("l6", huge)))));
        JsonNode v = OtlpEncoder.anyValue(deep, 1);
        for (int i = 0; i < OtlpEncoder.MAX_DEPTH; i++) {
            v = v.get("kvlistValue").get("values").get(0).get("value");
        }
        String s = v.get("stringValue").asText();
        assertTrue(s.length() <= OtlpEncoder.MAX_ATTRIBUTE_CHARS, "length " + s.length());
        assertTrue(s.startsWith("[0, 0, 0") && s.endsWith("…"), s.substring(0, 10));
    }

    // 4. trace ids ---------------------------------------------------------------------------

    @Test
    void invalidTraceAndSpanIdsStayAttributes() {
        JsonNode rec;
        try (CallistoLogger logs = builder().build()) {
            logs.info("t", attrs("trace_id", "00000000000000000000000000000000",
                    "span_id", "xyz"));
            rec = onlyRecord(logs);
        }
        assertNull(rec.get("traceId"));
        assertNull(rec.get("spanId"));
        Map<String, JsonNode> a = attrMap(rec);
        assertEquals("00000000000000000000000000000000", a.get("trace_id").get("stringValue").asText());
        assertEquals("xyz", a.get("span_id").get("stringValue").asText());
    }

    @Test
    void shortTraceIdStaysAnAttribute() {
        JsonNode rec;
        try (CallistoLogger logs = builder().build()) {
            logs.info("t", attrs("trace_id", "abc123", "span_id", "eee19b7ec3c1b174"));
            rec = onlyRecord(logs);
        }
        assertNull(rec.get("traceId"));
        assertEquals("eee19b7ec3c1b174", rec.get("spanId").asText());
        assertTrue(attrMap(rec).containsKey("trace_id"));
        assertFalse(attrMap(rec).containsKey("span_id"));
    }

    // 5. batching ----------------------------------------------------------------------------

    @Test
    void hundredRecordsTriggerASendWithoutFlush() throws Exception {
        try (CallistoLogger logs = builder().build()) {
            for (int i = 0; i < 99; i++) {
                logs.info("r" + i);
            }
            Thread.sleep(50);
            assertEquals(0, sender.requests.size(), "99 records wait for the interval");
            logs.info("r99");
            sender.awaitRequests(1);
            assertEquals(1, sender.requests.size());
            assertEquals(100, sender.requests.get(0).records().size());

            logs.info("rest-1");
            logs.info("rest-2");
            logs.flush();
            assertEquals(2, sender.requests.size());
            assertEquals(2, sender.requests.get(1).records().size());
        }
    }

    @Test
    void batchIsCutAt500Records() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        sender.entered = new CountDownLatch(1);
        sender.release = release;
        try (CallistoLogger logs = builder().build()) {
            // Hold the worker on a first batch so that 1200 records accumulate.
            for (int i = 0; i < 100; i++) {
                logs.info("warm" + i);
            }
            sender.entered.await();
            for (int i = 0; i < 1200; i++) {
                logs.info("r" + i);
            }
            sender.release = null;
            release.countDown();
            logs.flush();
        }
        assertEquals(1300, sender.totalRecords());
        assertEquals(100, sender.requests.get(0).records().size());
        assertEquals(500, sender.requests.get(1).records().size());
        assertEquals(500, sender.requests.get(2).records().size());
        assertEquals(200, sender.requests.get(3).records().size());
    }

    @Test
    void batchIsCutAtAboutOneMegabyte() {
        String big = "x".repeat(30_000);
        try (CallistoLogger logs = builder().build()) {
            for (int i = 0; i < 60; i++) {
                logs.info(big);
            }
            logs.flush();
        }
        assertEquals(60, sender.totalRecords());
        assertTrue(sender.requests.size() >= 2, "~1.8 MB must be split");
        for (FakeLogSender.Request r : sender.requests) {
            assertTrue(r.body.length() < 1_100_000, "batch too large: " + r.body.length());
        }
    }

    // 6. truncation ---------------------------------------------------------------------------

    @Test
    void bodyAndAttributeValuesAreTruncatedAndAttributeCountCapped() {
        Map<String, Object> many = new LinkedHashMap<>();
        many.put("long", "y".repeat(10_000));
        for (int i = 0; i < 200; i++) {
            many.put("k" + i, i);
        }
        JsonNode rec;
        try (CallistoLogger logs = builder().build()) {
            logs.info("x".repeat(40_000), many);
            rec = onlyRecord(logs);
        }
        String body = rec.get("body").get("stringValue").asText();
        assertEquals(32_768, body.length());
        assertTrue(body.endsWith("…"));
        String value = attrMap(rec).get("long").get("stringValue").asText();
        assertEquals(8_192, value.length());
        assertTrue(value.endsWith("…"));
        assertEquals(128, rec.get("attributes").size());

        assertEquals("short", OtlpEncoder.truncate("short", 10));
    }

    // 7. responses ------------------------------------------------------------------------------

    @Test
    void badRequestDropsWithoutRetry() {
        sender.then(400, null);
        try (CallistoLogger logs = builder().build()) {
            logs.info("a");
            logs.info("b");
            logs.flush();
            assertEquals(1, sender.requests.size());
            assertTrue(sleeps.isEmpty());
            assertEquals(2, logs.droppedCount());
        }
    }

    @Test
    void tooManyRequestsWithRetryAfterRetriesThenSucceeds() {
        sender.then(429, "1");
        try (CallistoLogger logs = builder().build()) {
            logs.info("a");
            logs.flush();
            assertEquals(2, sender.requests.size());
            assertEquals(List.of(1000L), sleeps);
            assertEquals(sender.requests.get(0).body, sender.requests.get(1).body);
            assertEquals(0, logs.droppedCount());
        }
    }

    @Test
    void retryAfterParsing() {
        assertEquals(5_000, CallistoLogger.retryAfterMs(null));
        assertEquals(5_000, CallistoLogger.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(60_000, CallistoLogger.retryAfterMs("600"));
        assertEquals(2_000, CallistoLogger.retryAfterMs(" 2 "));
    }

    @Test
    void threeFailedAttemptsDropTheBatch() {
        sender.then(503, null).then(503, null).then(503, null);
        try (CallistoLogger logs = builder().build()) {
            logs.info("a");
            logs.flush();
            assertEquals(3, sender.requests.size());
            assertEquals(List.of(5000L, 5000L), sleeps);
            assertEquals(1, logs.droppedCount());
        }
    }

    @Test
    void networkErrorsRetryAfterOneThenFiveSeconds() {
        sender.thenNetworkError().thenNetworkError().thenNetworkError();
        try (CallistoLogger logs = builder().build()) {
            logs.info("a");
            logs.flush();
            assertEquals(3, sender.requests.size());
            assertEquals(List.of(1000L, 5000L), sleeps);
            assertEquals(1, logs.droppedCount());
        }
    }

    @Test
    void unauthorizedPausesSendingForSixtySeconds() {
        sender.then(401, null);
        try (CallistoLogger logs = builder().build()) {
            logs.info("a");
            logs.flush();
            assertEquals(1, sender.requests.size());

            logs.info("during pause");
            logs.flush();
            assertEquals(1, sender.requests.size(), "nothing is sent during the pause");
            assertEquals(2, logs.droppedCount());

            clock.advanceSeconds(61);
            logs.info("after pause");
            logs.flush();
            assertEquals(2, sender.requests.size(), "one new attempt after the pause");
        }
    }

    @Test
    void forbiddenAlsoPauses() {
        sender.then(403, null);
        try (CallistoLogger logs = builder().build()) {
            logs.info("a");
            logs.flush();
            logs.info("b");
            logs.flush();
            assertEquals(1, sender.requests.size());
        }
    }

    // 8. full queue ------------------------------------------------------------------------------

    @Test
    void fullQueueDropsAndCounts() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        sender.entered = new CountDownLatch(1);
        sender.release = release;
        try (CallistoLogger logs = builder().build()) {
            for (int i = 0; i < 100; i++) {
                logs.info("warm" + i);
            }
            sender.entered.await();
            for (int i = 0; i < CallistoLogger.MAX_QUEUE + 25; i++) {
                logs.info("r" + i);
            }
            assertEquals(25, logs.droppedCount());
            sender.release = null;
            release.countDown();
            logs.flush();
        }
        assertEquals(100 + CallistoLogger.MAX_QUEUE, sender.totalRecords());
    }

    // 9. disabled -----------------------------------------------------------------------------------

    @Test
    void disabledLoggerSendsNothingAndStartsNoThread() {
        long before = countLoggerThreads();
        CallistoLogger logs = CallistoLogger.builder().sender(sender).env(n -> null).build();
        assertFalse(logs.isEnabled());
        assertFalse(logs.hasWorker());
        logs.info("ignored", attrs("a", 1));
        logs.error("ignored");
        logs.flush();
        logs.close();
        assertEquals(0, sender.requests.size());
        assertEquals(before, countLoggerThreads());
        assertFalse(CallistoLogger.noop().isEnabled());
    }

    private static long countLoggerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("callisto-logs"))
                .count();
    }

    // 10. configuration errors -----------------------------------------------------------------------

    @Test
    void halfOrMalformedCredentialsFailFast() {
        Function<String, String> none = n -> null;
        assertThrows(ValidationException.class, () ->
                CallistoLogger.builder().env(none).accessKey(FakeLogSender.PK).build());
        assertThrows(ValidationException.class, () ->
                CallistoLogger.builder().env(none).secret(FakeLogSender.SK).build());
        assertThrows(ValidationException.class, () ->
                CallistoLogger.builder().env(none).accessKey("pk_123").secret(FakeLogSender.SK).build());
        assertThrows(ValidationException.class, () ->
                CallistoLogger.builder().env(none).accessKey(FakeLogSender.PK.toUpperCase())
                        .secret(FakeLogSender.SK).build());
        assertThrows(ValidationException.class, () ->
                CallistoLogger.builder().env(none).accessKey(FakeLogSender.PK)
                        .secret("sk_" + "0".repeat(47)).build());
        assertThrows(ValidationException.class, () ->
                CallistoLogger.builder().env(none).accessKey(FakeLogSender.SK)
                        .secret(FakeLogSender.PK).build());
        assertThrows(ValidationException.class, () ->
                CallistoLogger.builder().env(Map.of("CALLISTO_LOGS_SECRET", FakeLogSender.SK)::get)
                        .accessKey(null).build());
        assertThrows(ValidationException.class, () ->
                CallistoLogger.builder().env(none).accessKey(FakeLogSender.PK)
                        .secret(FakeLogSender.SK).endpoint("ftp://logs.example/v1/logs").build());
        assertThrows(ValidationException.class, () ->
                CallistoLogger.builder().env(none).accessKey(FakeLogSender.PK)
                        .secret(FakeLogSender.SK).endpoint("/v1/logs").build());
    }

    // never throws ---------------------------------------------------------------------------------

    @Test
    void aLogCallNeverThrows() {
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", Collections.singletonList(cyclic));
        Object evil = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("boom");
            }
        };
        try (CallistoLogger logs = builder().build()) {
            assertDoesNotThrow(() -> {
                logs.info(null);
                logs.info("cyclic", cyclic);
                logs.info("evil", attrs("evil", evil));
                logs.log((String) null, "lvl", null);
                logs.log((LogLevel) null, "lvl", null);
                logs.flush();
            });
        }
        assertTrue(sender.totalRecords() >= 4);
    }

    @Test
    void closeFlushesAndLaterCallsAreNoOps() {
        CallistoLogger logs = builder().build();
        logs.info("queued");
        logs.close();
        assertEquals(1, sender.totalRecords());
        logs.info("after close");
        logs.flush();
        logs.close();
        assertEquals(1, sender.totalRecords());
    }
}
