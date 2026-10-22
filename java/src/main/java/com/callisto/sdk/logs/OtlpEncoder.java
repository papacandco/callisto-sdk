package com.callisto.sdk.logs;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Builds OTLP JSON log records ({@code LogRecord} / {@code AnyValue}) with the size guards. */
final class OtlpEncoder {

    static final int MAX_BODY_CHARS = 32_768;
    static final int MAX_ATTRIBUTE_CHARS = 8_192;
    static final int MAX_ATTRIBUTES = 128;
    /** Items kept per nested list / map / array; the extra ones are dropped. */
    static final int MAX_NESTED_ITEMS = 128;
    /**
     * Encoded values (scalars + containers) per record, across all attributes and nesting levels.
     * A sub-list or sub-map referenced several times counts each time it is visited.
     */
    static final int MAX_RECORD_VALUES = 2_048;
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
        Budget budget = new Budget();
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
                if (value == null || attrs.size() >= MAX_ATTRIBUTES || budget.spent()) {
                    continue; // once the budget is spent, later attributes are dropped
                }
                ObjectNode mapped = anyValue(value, 1, budget);
                if (mapped != null) {
                    attrs.add(keyValue(key, mapped));
                }
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

    /** The encoded values left for one record. */
    static final class Budget {
        private int left = MAX_RECORD_VALUES;

        boolean take() {
            if (left <= 0) {
                return false;
            }
            left--;
            return true;
        }

        boolean spent() {
            return left <= 0;
        }
    }

    /** {@link #anyValue(Object, int, Budget)} with a fresh record budget. */
    static ObjectNode anyValue(Object v, int depth) {
        return anyValue(v, depth, new Budget());
    }

    /**
     * Maps a Java value to an OTLP {@code AnyValue}. {@code null} maps to {@code {}}. Returns
     * {@code null} (the value is dropped) once the record's value budget is spent.
     */
    static ObjectNode anyValue(Object v, int depth, Budget budget) {
        if (!budget.take()) {
            return null;
        }
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
            return string(BoundedString.of(v, budget));
        }
        if (v instanceof Map) {
            ArrayNode values = F.arrayNode();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (values.size() >= MAX_NESTED_ITEMS || budget.spent()) {
                    break;
                }
                if (e.getValue() == null) {
                    continue;
                }
                ObjectNode mapped = anyValue(e.getValue(), depth + 1, budget);
                if (mapped == null) {
                    break;
                }
                values.add(keyValue(String.valueOf(e.getKey()), mapped));
            }
            return F.objectNode().set("kvlistValue", F.objectNode().set("values", values));
        }
        ArrayNode values = F.arrayNode();
        if (v instanceof Collection) {
            for (Object item : (Collection<?>) v) {
                if (values.size() >= MAX_NESTED_ITEMS) {
                    break;
                }
                ObjectNode mapped = anyValue(item, depth + 1, budget);
                if (mapped == null) {
                    break;
                }
                values.add(mapped);
            }
        } else {
            int n = Math.min(Array.getLength(v), MAX_NESTED_ITEMS);
            for (int i = 0; i < n; i++) {
                ObjectNode mapped = anyValue(Array.get(v, i), depth + 1, budget);
                if (mapped == null) {
                    break;
                }
                values.add(mapped);
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

    /**
     * {@code toString()} of a container nested too deep, built piece by piece ({@code [a, b]},
     * {@code {k=v}}; arrays like lists). Every nested item takes one value from the record budget
     * and the output stops past {@link #MAX_ATTRIBUTE_CHARS}, so a shared or cyclic structure
     * costs a bounded amount of work. A cut ends with {@code …}.
     */
    static final class BoundedString {
        private final StringBuilder sb = new StringBuilder();
        private final Budget budget;
        private final Set<Object> active = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean cut;

        private BoundedString(Budget budget) {
            this.budget = budget;
        }

        static String of(Object v, Budget budget) {
            BoundedString w = new BoundedString(budget);
            if (!w.value(v, false) && w.cut) {
                w.sb.append(ELLIPSIS);
            }
            return w.sb.toString();
        }

        private boolean write(String s) {
            if (sb.length() > MAX_ATTRIBUTE_CHARS) {
                return false;
            }
            sb.append(s);
            return true;
        }

        private boolean value(Object v, boolean count) {
            if (count && !budget.take()) {
                cut = true;
                return false;
            }
            boolean container = v instanceof Map || v instanceof Collection
                    || (v != null && v.getClass().isArray());
            if (!container) {
                return write(v == null ? "null" : stringify(v));
            }
            if (active.contains(v) || active.size() >= 64) {
                return write(v instanceof Map ? "{...}" : "[...]");
            }
            active.add(v);
            try {
                if (v instanceof Map) {
                    if (!write("{")) {
                        return false;
                    }
                    boolean first = true;
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                        if (!first && !write(", ")) {
                            return false;
                        }
                        first = false;
                        if (!write(stringify(e.getKey()) + "=") || !value(e.getValue(), true)) {
                            return false;
                        }
                    }
                    return write("}");
                }
                if (!write("[")) {
                    return false;
                }
                Iterator<?> it = v instanceof Collection ? ((Collection<?>) v).iterator() : null;
                int n = it == null ? Array.getLength(v) : -1;
                for (int i = 0; it != null ? it.hasNext() : i < n; i++) {
                    if (i > 0 && !write(", ")) {
                        return false;
                    }
                    if (!value(it != null ? it.next() : Array.get(v, i), true)) {
                        return false;
                    }
                }
                return write("]");
            } finally {
                active.remove(v);
            }
        }
    }
}
