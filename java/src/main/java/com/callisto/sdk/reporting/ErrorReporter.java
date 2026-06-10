package com.callisto.sdk.reporting;

import com.callisto.sdk.errors.CallistoException;
import com.callisto.sdk.errors.RateLimitException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    /** Source lines captured on each side of a frame's error line. */
    private static final int CONTEXT_LINES = 10;

    /** Skip source capture for files larger than this (bytes). */
    private static final long MAX_SOURCE_BYTES = 2_000_000L;

    /** Source roots searched (relative to the working directory) to locate a frame's file. */
    private static final List<String> SOURCE_ROOTS =
            List.of("src/main/java", "src/main/kotlin", "src/test/java", "src", "");

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

    /**
     * Builds a reporter from just a DSN. Error reporting is independent of the API
     * client, so no client id / api key is needed. A {@code null}/blank or
     * non-well-formed DSN disables the reporter (every method becomes a no-op).
     *
     * @param dsn the ingest DSN (the full POST URL).
     */
    public ErrorReporter(String dsn) {
        this(dsn, null, null, null);
    }

    /**
     * Builds a reporter from a DSN and an environment tag.
     *
     * @param dsn         the ingest DSN (the full POST URL).
     * @param environment optional environment tag for {@code context.environment}.
     */
    public ErrorReporter(String dsn, String environment) {
        this(dsn, environment, null, null);
    }

    /**
     * Builds a reporter from the environment: {@code CALLISTO_APP_ERROR_DSN} (required
     * for reporting to be active) and {@code CALLISTO_ENVIRONMENT} (optional tag). When
     * the DSN is absent the reporter is a cheap no-op.
     */
    public static ErrorReporter fromEnv() {
        return new ErrorReporter(
                System.getenv("CALLISTO_APP_ERROR_DSN"),
                System.getenv("CALLISTO_ENVIRONMENT"));
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

        // Source context only for genuine application exceptions: a transport call site can embed
        // the outgoing request body as literal arguments, which would violate the hard
        // no-request-body guarantee. Transport errors already carry method/path as their culprit.
        boolean isTransport = method != null && path != null;
        List<Map<String, Object>> stacktrace = buildStacktrace(t, !isTransport);
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

    private static List<Map<String, Object>> buildStacktrace(Throwable t, boolean withSource) {
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
            if (withSource) {
                attachSource(frame, el);
            }
            frames.add(frame);
        }
        return frames;
    }

    /**
     * Best-effort: attach a {@code pre_context}/{@code context_line}/{@code post_context} window
     * (up to {@link #CONTEXT_LINES} lines each side) around the frame's error line, so the
     * dashboard can render the failing code with the error line in focus.
     *
     * <p>Java stack frames expose only the simple source file name and line number, not a path, and
     * compiled deployments rarely ship source. We therefore resolve the file under common source
     * roots relative to the working directory (dev / monorepo runs); when the source is absent or
     * unreadable the frame simply renders without a window.
     */
    private static void attachSource(Map<String, Object> frame, StackTraceElement el) {
        String fileName = el.getFileName();
        int line = el.getLineNumber();
        if (fileName == null || line < 1) {
            return;
        }
        Path path = resolveSource(el.getClassName(), fileName);
        if (path == null) {
            return;
        }
        try {
            if (Files.size(path) > MAX_SOURCE_BYTES) {
                return;
            }
            List<String> lines = Files.readAllLines(path);
            if (line > lines.size()) {
                return;
            }
            int index = line - 1;
            int start = Math.max(0, index - CONTEXT_LINES);
            int end = Math.min(lines.size(), index + 1 + CONTEXT_LINES);
            frame.put("pre_context", new ArrayList<>(lines.subList(start, index)));
            frame.put("context_line", lines.get(index));
            frame.put("post_context", new ArrayList<>(lines.subList(index + 1, end)));
        } catch (Exception ignored) {
            // Unreadable / decoding error — leave the frame without a window.
        }
    }

    /**
     * Locate a frame's source file under common source roots, deriving the package directory from
     * the (possibly inner) class name. Returns {@code null} when nothing readable is found.
     */
    private static Path resolveSource(String className, String fileName) {
        String packagePath = "";
        if (className != null) {
            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                packagePath = className.substring(0, lastDot).replace('.', '/');
            }
        }
        for (String root : SOURCE_ROOTS) {
            // Prefer the package-qualified path, then a flat fallback.
            Path qualified = Paths.get(root, packagePath, fileName);
            if (Files.isReadable(qualified)) {
                return qualified;
            }
            Path flat = Paths.get(root, fileName);
            if (Files.isReadable(flat)) {
                return flat;
            }
        }
        return null;
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

    /**
     * Installs a global handler that reports uncaught throwables at level {@code "fatal"}
     * and then delegates to the previously-installed handler (chaining, not replacing), so
     * the platform's default crash behavior is preserved. No-op when the reporter is
     * disabled. This is the Java equivalent of the other SDKs' opt-in global handler and
     * lets a DSN-only reporter auto-capture crashes without the full API client. The full
     * {@link com.callisto.sdk.CallistoClient} installs the same handler when configured
     * with {@code captureUnhandled = true}.
     */
    public void installUncaughtHandler() {
        if (!enabled) {
            return;
        }
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                captureException(throwable, "fatal", null);
                flush();
            } catch (Throwable ignored) {
                // Never let reporting disturb the platform's default behavior.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
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
