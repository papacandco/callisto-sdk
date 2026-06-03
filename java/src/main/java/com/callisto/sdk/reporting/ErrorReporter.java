package com.callisto.sdk.reporting;

import com.callisto.sdk.errors.CallistoException;
import com.callisto.sdk.errors.RateLimitException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in, Sentry-style error reporter that POSTs captured errors to the Callisto error-tracking
 * ingest endpoint (the DSN).
 *
 * <p>Delivery is background, best-effort, and never alters or delays the original error.
 * The reporter uses its own minimal HTTP path (never the main {@code Transport}), so it never
 * recurses and never inherits the SDK's Basic-auth credentials. Its own failures (any exception,
 * any non-202) are swallowed and never re-captured.
 *
 * <p><b>PII rule (hard requirement):</b> the reporter never transmits {@code client_id},
 * {@code api_key}, the {@code Authorization} header, or the outgoing request body.
 */
public final class ErrorReporter {

    /** SDK metadata reported under {@code context.sdk}. */
    public static final String SDK_NAME = "callisto-sdk";
    public static final String SDK_LANGUAGE = "java";
    public static final String SDK_VERSION = "0.1.0";

    private static final List<String> LEVELS = List.of("fatal", "error", "warning", "info");

    private final String dsn;
    private final boolean enabled;
    private final String environment;
    private final ErrorSender sender;
    private final ObjectMapper mapper;
    private final ExecutorService dispatcher;

    private volatile Map<String, Object> user;

    /**
     * Builds a reporter.
     *
     * @param dsn         the ingest DSN (the full POST URL); {@code null}/blank or non-well-formed
     *                    disables the reporter (every method becomes a cheap no-op).
     * @param environment optional environment tag for {@code context.environment}.
     * @param sender      injectable HTTP sender; when {@code null} a default sender is used.
     * @param mapper      Jackson mapper for serializing payloads; when {@code null} a private
     *                    {@link ObjectMapper} is used.
     */
    public ErrorReporter(String dsn, String environment, ErrorSender sender, ObjectMapper mapper) {
        this.dsn = dsn;
        this.enabled = isValidDsn(dsn);
        this.environment = environment;
        this.sender = sender != null ? sender : new HttpErrorSender();
        this.mapper = mapper != null ? mapper : new ObjectMapper();
        this.dispatcher = enabled
                ? Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "callisto-error-reporter");
                    t.setDaemon(true);
                    return t;
                })
                : null;
    }

    private static boolean isValidDsn(String dsn) {
        if (dsn == null || dsn.trim().isEmpty()) {
            return false;
        }
        try {
            URI uri = URI.create(dsn.trim());
            return uri.getScheme() != null && uri.getHost() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether the reporter is active (a valid DSN was supplied). */
    public boolean isEnabled() {
        return enabled;
    }

    /** Sets or clears the {@code user} context attached to subsequent events. */
    public void setUser(Map<String, Object> user) {
        this.user = user == null ? null : new LinkedHashMap<>(user);
    }

    public void captureException(Throwable t) {
        captureException(t, "error", null);
    }

    public void captureException(Throwable t, String level) {
        captureException(t, level, null);
    }

    /** Captures an exception/throwable and enqueues a background send. Never throws. */
    public void captureException(Throwable t, String level, Map<String, Object> extra) {
        if (!enabled || t == null) {
            return;
        }
        try {
            Map<String, Object> payload = buildExceptionPayload(t, level, extra);
            enqueue(payload);
        } catch (Throwable ignored) {
            // Never let our own capture path disturb the caller.
        }
    }

    public void captureMessage(String message) {
        captureMessage(message, "info", null);
    }

    public void captureMessage(String message, String level) {
        captureMessage(message, level, null);
    }

    /** Captures a plain message and enqueues a background send. Never throws. */
    public void captureMessage(String message, String level, Map<String, Object> extra) {
        if (!enabled || message == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", message);
            payload.put("level", normalizeLevel(level, "info"));
            payload.put("context", buildContext(extra, null));
            if (user != null) {
                payload.put("user", user);
            }
            enqueue(payload);
        } catch (Throwable ignored) {
            // Swallow.
        }
    }

    private Map<String, Object> buildExceptionPayload(Throwable t, String level,
                                                      Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();

        String message = t.getMessage();
        payload.put("message", message != null ? message : t.getClass().getName());
        payload.put("type", t.getClass().getName());
        payload.put("level", normalizeLevel(level, "error"));

        // Transport hook stashes "{METHOD} {path}" so culprit/request reflect the API call.
        String method = null;
        String path = null;
        if (extra != null) {
            Object m = extra.get("__method");
            Object p = extra.get("__path");
            method = m != null ? String.valueOf(m) : null;
            path = p != null ? String.valueOf(p) : null;
        }

        List<Map<String, Object>> stacktrace = buildStacktrace(t);
        if (method != null && path != null) {
            payload.put("culprit", method + " " + path);
        } else {
            String culprit = topFrameCulprit(stacktrace);
            if (culprit != null) {
                payload.put("culprit", culprit);
            }
        }
        if (!stacktrace.isEmpty()) {
            payload.put("stacktrace", stacktrace);
        }

        // Strip the transport-internal hint keys from user-visible extra.
        Map<String, Object> cleanExtra = stripInternal(extra);
        Map<String, Object> context = buildContext(cleanExtra, t);
        payload.put("context", context);

        if (method != null && path != null) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("method", method);
            request.put("path", path);
            payload.put("request", request);
        }

        if (user != null) {
            payload.put("user", user);
        }
        return payload;
    }

    private Map<String, Object> buildContext(Map<String, Object> extra, Throwable t) {
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> sdk = new LinkedHashMap<>();
        sdk.put("name", SDK_NAME);
        sdk.put("version", SDK_VERSION);
        sdk.put("language", SDK_LANGUAGE);
        context.put("sdk", sdk);
        if (environment != null && !environment.isEmpty()) {
            context.put("environment", environment);
        }
        if (t instanceof CallistoException) {
            CallistoException ce = (CallistoException) t;
            context.put("status_code", ce.getStatusCode());
            if (ce.getBody() != null) {
                context.put("body", ce.getBody());
            }
            if (t instanceof RateLimitException) {
                Integer retryAfter = ((RateLimitException) t).getRetryAfter();
                if (retryAfter != null) {
                    context.put("retry_after", retryAfter);
                }
            }
        }
        if (extra != null) {
            for (Map.Entry<String, Object> e : extra.entrySet()) {
                context.put(e.getKey(), e.getValue());
            }
        }
        return context;
    }

    private static List<Map<String, Object>> buildStacktrace(Throwable t) {
        List<Map<String, Object>> frames = new ArrayList<>();
        StackTraceElement[] elements = t.getStackTrace();
        if (elements == null) {
            return frames;
        }
        for (StackTraceElement el : elements) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("function", el.getClassName() + "." + el.getMethodName());
            frame.put("file", el.getFileName());
            frame.put("line", el.getLineNumber());
            frames.add(frame);
        }
        return frames;
    }

    private static String topFrameCulprit(List<Map<String, Object>> stacktrace) {
        if (stacktrace.isEmpty()) {
            return null;
        }
        Map<String, Object> top = stacktrace.get(0);
        Object fn = top.get("function");
        Object file = top.get("file");
        Object line = top.get("line");
        if (fn == null) {
            return null;
        }
        if (file != null && line != null) {
            return fn + " (" + file + ":" + line + ")";
        }
        return String.valueOf(fn);
    }

    private static Map<String, Object> stripInternal(Map<String, Object> extra) {
        if (extra == null) {
            return null;
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : extra.entrySet()) {
            String key = e.getKey();
            if (key != null && key.startsWith("__")) {
                continue;
            }
            clean.put(key, e.getValue());
        }
        return clean.isEmpty() ? null : clean;
    }

    private static String normalizeLevel(String level, String fallback) {
        if (level != null && LEVELS.contains(level.toLowerCase())) {
            return level.toLowerCase();
        }
        return fallback;
    }

    private void enqueue(Map<String, Object> payload) {
        if (dispatcher == null) {
            return;
        }
        try {
            dispatcher.submit(() -> deliver(payload));
        } catch (RuntimeException ignored) {
            // Dispatcher rejected (e.g. shutting down) — swallow.
        }
    }

    private void deliver(Map<String, Object> payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            sender.send(dsn, json);
            // Any non-202 is a swallowed failure; we don't inspect or retry.
        } catch (Throwable ignored) {
            // Swallow ALL own failures; never re-capture.
        }
    }

    /** Drains pending sends with a short bound. Best-effort. */
    public void flush() {
        awaitIdle(2);
    }

    /** Shuts the dispatcher down with a short {@code awaitTermination}. Best-effort. */
    public void close() {
        if (dispatcher == null) {
            return;
        }
        dispatcher.shutdown();
        try {
            dispatcher.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitIdle(long seconds) {
        if (dispatcher == null) {
            return;
        }
        // Submit a barrier task and wait for it; this drains everything queued before it.
        try {
            dispatcher.submit(() -> {
            }).get(seconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best-effort.
        }
    }
}
