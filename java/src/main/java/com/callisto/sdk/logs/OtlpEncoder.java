package com.callisto.sdk.logs;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Builds OTLP JSON log records ({@code LogRecord} / {@code AnyValue}) with the size guards. */
final class OtlpEncoder {

    static final int MAX_BODY_CHARS = 32_768;
    static final int MAX_ATTRIBUTE_CHARS = 8_192;
    static final int MAX_ATTRIBUTES = 128;
    static final int MAX_DEPTH = 5;

    private static final String ELLIPSIS = "…";
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-fA-F]{16}$");
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private OtlpEncoder() {
    }

    /** One {@code LogRecord}. {@code attributes} may be {@code null}. */
    static ObjectNode record(Instant time, LogLevel level, String message,
                             Map<String, ?> attributes) {
        String nanos = epochNanos(time);
        ObjectNode rec = F.objectNode();
        rec.put("timeUnixNano", nanos);
        rec.put("observedTimeUnixNano", nanos);
        rec.put("severityNumber", level.severityNumber());
        rec.put("severityText", level.severityText());
        rec.set("body", F.objectNode().put("stringValue",
                truncate(message == null ? "null" : message, MAX_BODY_CHARS)));

        String traceId = null;
        String spanId = null;
        ArrayNode attrs = F.arrayNode();
        if (attributes != null) {
            for (Map.Entry<String, ?> e : attributes.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object value = e.getValue();
                if ("trace_id".equals(key) && traceId == null && isId(value, TRACE_ID, 32)) {
                    traceId = ((String) value).toLowerCase();
                    continue;
                }
                if ("span_id".equals(key) && spanId == null && isId(value, SPAN_ID, 16)) {
                    spanId = ((String) value).toLowerCase();
                    continue;
                }
                if (value == null || attrs.size() >= MAX_ATTRIBUTES) {
                    continue;
                }
                attrs.add(keyValue(key, anyValue(value, 1)));
            }
        }
        if (attrs.size() > 0) {
            rec.set("attributes", attrs);
        }
        if (traceId != null) {
            rec.put("traceId", traceId);
        }
        if (spanId != null) {
            rec.put("spanId", spanId);
        }
        return rec;
    }

    /** The envelope around one batch: one resourceLogs, one scopeLogs. */
    static ObjectNode envelope(String serviceName, String environment, ArrayNode records) {
        ArrayNode resourceAttrs = F.arrayNode();
        resourceAttrs.add(keyValue("service.name", string(serviceName)));
        if (environment != null && !environment.isEmpty()) {
            resourceAttrs.add(keyValue("deployment.environment.name", string(environment)));
        }
        resourceAttrs.add(keyValue("telemetry.sdk.name", string(CallistoLogger.SDK_NAME)));
        resourceAttrs.add(keyValue("telemetry.sdk.language", string(CallistoLogger.SDK_LANGUAGE)));
        resourceAttrs.add(keyValue("telemetry.sdk.version", string(CallistoLogger.SDK_VERSION)));

        ObjectNode scope = F.objectNode();
        scope.put("name", CallistoLogger.SDK_NAME);
        scope.put("version", CallistoLogger.SDK_VERSION);

        ObjectNode scopeLogs = F.objectNode();
        scopeLogs.set("scope", scope);
        scopeLogs.set("logRecords", records);

        ObjectNode resourceLogs = F.objectNode();
        resourceLogs.set("resource", F.objectNode().set("attributes", resourceAttrs));
        resourceLogs.set("scopeLogs", F.arrayNode().add(scopeLogs));

        ObjectNode root = F.objectNode();
        root.set("resourceLogs", F.arrayNode().add(resourceLogs));
        return root;
    }

    static String epochNanos(Instant t) {
        return BigInteger.valueOf(t.getEpochSecond())
                .multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(t.getNano()))
                .toString();
    }

    static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int cut = max - 1;
        if (Character.isHighSurrogate(s.charAt(cut - 1))) {
            cut--;
        }
        return s.substring(0, cut) + ELLIPSIS;
    }

    private static boolean isId(Object value, Pattern pattern, int length) {
        if (!(value instanceof String)) {
            return false;
        }
        String s = (String) value;
        if (!pattern.matcher(s).matches()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (s.charAt(i) != '0') {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode keyValue(String key, ObjectNode value) {
        ObjectNode kv = F.objectNode();
        kv.put("key", key);
        kv.set("value", value);
        return kv;
    }

    private static ObjectNode string(String s) {
        return F.objectNode().put("stringValue", truncate(s, MAX_ATTRIBUTE_CHARS));
    }

    /** Maps a Java value to an OTLP {@code AnyValue}. {@code null} maps to {@code {}}. */
    static ObjectNode anyValue(Object v, int depth) {
        if (v == null) {
            return F.objectNode();
        }
        if (v instanceof String || v instanceof Character || v instanceof CharSequence) {
            return string(v.toString());
        }
        if (v instanceof Boolean) {
            return F.objectNode().put("boolValue", (Boolean) v);
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte
                || v instanceof AtomicInteger || v instanceof AtomicLong) {
            return F.objectNode().put("intValue", Long.toString(((Number) v).longValue()));
        }
        if (v instanceof BigInteger) {
            BigInteger b = (BigInteger) v;
            if (b.compareTo(LONG_MIN) >= 0 && b.compareTo(LONG_MAX) <= 0) {
                return F.objectNode().put("intValue", b.toString());
            }
            return string(b.toString());
        }
        if (v instanceof Double || v instanceof Float || v instanceof BigDecimal) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d)) {
                return string("NaN");
            }
            if (Double.isInfinite(d)) {
                return string(d > 0 ? "Infinity" : "-Infinity");
            }
            return F.objectNode().put("doubleValue", d);
        }
        boolean container = v instanceof Map || v instanceof Collection || v.getClass().isArray();
        if (!container) {
            return string(stringify(v));
        }
        if (depth > MAX_DEPTH) {
            return string(stringify(v));
        }
        if (v instanceof Map) {
            ArrayNode values = F.arrayNode();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                values.add(keyValue(String.valueOf(e.getKey()), anyValue(e.getValue(), depth + 1)));
            }
            return F.objectNode().set("kvlistValue", F.objectNode().set("values", values));
        }
        ArrayNode values = F.arrayNode();
        if (v instanceof Collection) {
            for (Object item : (Collection<?>) v) {
                values.add(anyValue(item, depth + 1));
            }
        } else {
            int n = Array.getLength(v);
            for (int i = 0; i < n; i++) {
                values.add(anyValue(Array.get(v, i), depth + 1));
            }
        }
        return F.objectNode().set("arrayValue", F.objectNode().set("values", values));
    }

    private static String stringify(Object v) {
        try {
            return String.valueOf(v);
        } catch (Throwable t) {
            return "<unprintable " + v.getClass().getName() + ">";
        }
    }
}
