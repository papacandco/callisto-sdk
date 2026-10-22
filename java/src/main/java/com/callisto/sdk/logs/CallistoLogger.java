package com.callisto.sdk.logs;

import com.callisto.sdk.errors.ValidationException;
import com.callisto.sdk.http.SecureUrl;
import com.callisto.sdk.reporting.ErrorReporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Ships application log lines to callisto-logs (OTLP/HTTP JSON), batched in the background.
 *
 * <p>Works on its own, without messaging credentials. It needs an API key of scope
 * <b>Logs</b>: an access key {@code pk_…} and a secret {@code sk_…}. With neither, the logger is
 * disabled and every call is a cheap no-op. A log call never throws and never waits on the network.
 *
 * <p>{@link Builder#build()} throws {@link ValidationException} on a half-set or malformed key,
 * or on an endpoint that is not {@code https} ({@code http} only for {@code localhost},
 * {@code 127.0.0.1}, {@code ::1} and {@code *.localhost}). {@link com.callisto.sdk.CallistoClient}
 * does not: it logs one warning and keeps a disabled logger.
 *
 * <pre>{@code
 * try (CallistoLogger logs = CallistoLogger.builder()
 *         .accessKey("pk_...").secret("sk_...")
 *         .serviceName("checkout").environment("production")
 *         .build()) {
 *     logs.info("order paid", Map.of("order_id", 42, "trace_id", traceId));
 * }
 * }</pre>
 *
 * <p>Delivery: a bounded queue of 2 000 records, sent by a daemon worker when 100 are queued,
 * every 5 s otherwise, and on {@link #flush()} / {@link #close()}. 429 / 503 and network errors
 * are retried (3 attempts in total); 401 / 403 pause sending for 60 s. The logger uses its own HTTP
 * path: it never sends messaging credentials, and its own failures are swallowed.
 */
public final class CallistoLogger implements AutoCloseable {

    public static final String SDK_NAME = "callisto-sdk";
    public static final String SDK_LANGUAGE = "java";
    /** The SDK version; kept equal to the Maven artifact version. */
    public static final String SDK_VERSION = ErrorReporter.SDK_VERSION;
    public static final String USER_AGENT = "callisto-sdk-" + SDK_LANGUAGE + "/" + SDK_VERSION;

    public static final String DEFAULT_ENDPOINT = "https://logs.callistosignal.com/v1/logs";
    public static final String DEFAULT_SERVICE_NAME = "unknown_service";
    public static final Duration DEFAULT_FLUSH_TIMEOUT = Duration.ofSeconds(5);

    static final int MAX_QUEUE = 2_000;
    static final int SEND_THRESHOLD = 100;
    static final int MAX_BATCH_RECORDS = 500;
    static final int MAX_BATCH_BYTES = 1_000_000;
    static final long INTERVAL_MS = 5_000;
    static final int MAX_ATTEMPTS = 3;
    static final long DEFAULT_RETRY_AFTER_MS = 5_000;
    static final long MAX_RETRY_AFTER_MS = 60_000;
    static final long[] NETWORK_RETRY_MS = {1_000, 5_000};
    static final long AUTH_PAUSE_MS = 60_000;

    private static final Pattern ACCESS_KEY = Pattern.compile("^pk_[0-9a-f]{32}$");
    private static final Pattern SECRET = Pattern.compile("^sk_[0-9a-f]{48}$");

    private final boolean enabled;
    private final String endpoint;
    private final String serviceName;
    private final String environment;
    private final Duration flushTimeout;
    private final Map<String, String> headers;
    private final LogSender sender;
    private final boolean ownsSender;
    private final Clock clock;
    private final Sleeper sleeper;
    private final ObjectMapper mapper = new ObjectMapper();

    private final BlockingQueue<ObjectNode> queue;
    private final ScheduledExecutorService worker;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean drainPending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long pausedUntilMs;

    /** Worker-thread only: a serialized record that did not fit in the previous batch. */
    private String carry;

    private CallistoLogger(Builder b) {
        Function<String, String> env = b.env != null ? b.env : System::getenv;
        String accessKey = orEnv(b.accessKey, env, "CALLISTO_LOGS_ACCESS_KEY");
        String secret = orEnv(b.secret, env, "CALLISTO_LOGS_SECRET");

        if (accessKey == null && secret == null) {
            this.enabled = false;
        } else if (accessKey == null || secret == null) {
            throw new ValidationException(
                    "Callisto logs: both logsAccessKey and logsSecret are required "
                            + "(pass both or set CALLISTO_LOGS_ACCESS_KEY / CALLISTO_LOGS_SECRET).");
        } else if (!ACCESS_KEY.matcher(accessKey).matches()) {
            throw new ValidationException(
                    "Callisto logs: logsAccessKey must be pk_ followed by 32 lowercase hex characters.");
        } else if (!SECRET.matcher(secret).matches()) {
            throw new ValidationException(
                    "Callisto logs: logsSecret must be sk_ followed by 48 lowercase hex characters.");
        } else {
            this.enabled = true;
        }

        String resolvedEndpoint = orEnv(b.endpoint, env, "CALLISTO_LOGS_ENDPOINT");
        this.endpoint = resolvedEndpoint != null ? resolvedEndpoint : DEFAULT_ENDPOINT;
        if (enabled && !SecureUrl.isSecure(endpoint)) {
            throw new ValidationException(
                    "Callisto logs: logsEndpoint must be an absolute https URL "
                            + "(http is accepted only for localhost, 127.0.0.1, ::1 and *.localhost).");
        }

        String service = orEnv(b.serviceName, env, "CALLISTO_SERVICE_NAME");
        if (service == null) {
            service = orEnv(null, env, "OTEL_SERVICE_NAME");
        }
        this.serviceName = service != null ? service : DEFAULT_SERVICE_NAME;
        this.environment = orEnv(b.environment, env, "CALLISTO_ENVIRONMENT");
        this.flushTimeout = b.flushTimeout != null && !b.flushTimeout.isNegative()
                ? b.flushTimeout : DEFAULT_FLUSH_TIMEOUT;
        this.clock = b.clock != null ? b.clock : Clock.systemUTC();
        this.sleeper = b.sleeper != null ? b.sleeper : Thread::sleep;

        if (!enabled) {
            this.headers = Map.of();
            this.sender = null;
            this.ownsSender = false;
            this.queue = null;
            this.worker = null;
            return;
        }

        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (accessKey + ":" + secret).getBytes(StandardCharsets.UTF_8)));
        h.put("Content-Type", "application/json");
        h.put("User-Agent", USER_AGENT);
        this.headers = java.util.Collections.unmodifiableMap(h);

        this.ownsSender = b.sender == null;
        this.sender = b.sender != null ? b.sender : new HttpLogSender();
        this.queue = new ArrayBlockingQueue<>(MAX_QUEUE);

        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(
                1, InternalThreads.factory("callisto-logs"));
        exec.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        exec.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        this.worker = exec;
        worker.scheduleWithFixedDelay(this::drainSafely, INTERVAL_MS, INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    /** A builder; every setting is optional and falls back to its environment variable. */
    public static Builder builder() {
        return new Builder();
    }

    /** A logger configured entirely from the environment (disabled when no Logs key is set). */
    public static CallistoLogger fromEnv() {
        return builder().build();
    }

    /** A disabled logger: every call is a no-op. */
    public static CallistoLogger noop() {
        Builder b = builder();
        b.env = name -> null;
        return b.build();
    }

    /** Whether a Logs key resolved (otherwise every call is a no-op). */
    public boolean isEnabled() {
        return enabled;
    }

    /** Records dropped so far: queue full, failed batch, or sent during a 401/403 pause. */
    public long droppedCount() {
        return dropped.get();
    }

    public String endpoint() {
        return endpoint;
    }

    public String serviceName() {
        return serviceName;
    }

    public String environment() {
        return environment;
    }

    boolean hasWorker() {
        return worker != null;
    }

    public void debug(String message) {
        log(LogLevel.DEBUG, message, null);
    }

    public void debug(String message, Map<String, ?> attributes) {
        log(LogLevel.DEBUG, message, attributes);
    }

    public void info(String message) {
        log(LogLevel.INFO, message, null);
    }

    public void info(String message, Map<String, ?> attributes) {
        log(LogLevel.INFO, message, attributes);
    }

    public void warn(String message) {
        log(LogLevel.WARN, message, null);
    }

    public void warn(String message, Map<String, ?> attributes) {
        log(LogLevel.WARN, message, attributes);
    }

    public void error(String message) {
        log(LogLevel.ERROR, message, null);
    }

    public void error(String message, Map<String, ?> attributes) {
        log(LogLevel.ERROR, message, attributes);
    }

    public void fatal(String message) {
        log(LogLevel.FATAL, message, null);
    }

    public void fatal(String message, Map<String, ?> attributes) {
        log(LogLevel.FATAL, message, attributes);
    }

    /** Logs at {@code debug|info|warn|error|fatal}; an unknown level is logged as info. */
    public void log(String level, String message, Map<String, ?> attributes) {
        LogLevel parsed = LogLevel.fromString(level);
        log(parsed != null ? parsed : LogLevel.INFO, message, attributes);
    }

    public void log(LogLevel level, String message) {
        log(level, message, null);
    }

    /** Queues one record. Never throws, never blocks on the network. */
    public void log(LogLevel level, String message, Map<String, ?> attributes) {
        if (!enabled || closed.get() || InternalThreads.isInternal()) {
            return;
        }
        try {
            if (clock.millis() < pausedUntilMs) {
                dropped.incrementAndGet();
                return;
            }
            ObjectNode record = OtlpEncoder.record(clock.instant(),
                    level != null ? level : LogLevel.INFO, message, attributes);
            if (!queue.offer(record)) {
                dropped.incrementAndGet();
                return;
            }
            if (queue.size() >= SEND_THRESHOLD && drainPending.compareAndSet(false, true)) {
                worker.execute(this::drainSafely);
            }
        } catch (Throwable ignored) {
            // A log call never throws.
        }
    }

    /** Sends what is queued now. Bounded by {@code flushTimeout}; never throws. */
    public void flush() {
        if (!enabled || closed.get() || InternalThreads.isInternal()) {
            return;
        }
        try {
            Future<?> done = worker.submit(this::drainSafely);
            done.get(flushTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // Best-effort.
        }
    }

    /** Flushes, then stops the worker. Bounded by {@code flushTimeout}; later calls are no-ops. */
    @Override
    public void close() {
        if (!enabled || InternalThreads.isInternal() || !closed.compareAndSet(false, true)) {
            return;
        }
        long deadline = System.nanoTime() + flushTimeout.toNanos();
        try {
            worker.execute(this::drainSafely);
            worker.shutdown();
            long left = deadline - System.nanoTime();
            if (left <= 0 || !worker.awaitTermination(left, TimeUnit.NANOSECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            worker.shutdownNow();
        } finally {
            if (ownsSender && sender instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) sender).close();
                } catch (Throwable ignored) {
                    // Swallow.
                }
            }
        }
    }

    // ---- worker ----------------------------------------------------------------------------

    private void drainSafely() {
        drainPending.set(false);
        try {
            drain();
        } catch (Throwable ignored) {
            // The logger's own failures are swallowed, never logged or reported.
        }
    }

    private void drain() throws InterruptedException {
        while (true) {
            if (clock.millis() < pausedUntilMs) {
                dropQueued();
                return;
            }
            List<String> batch = nextBatch();
            if (batch.isEmpty()) {
                return;
            }
            deliver(batch);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    private void dropQueued() {
        if (carry != null) {
            carry = null;
            dropped.incrementAndGet();
        }
        List<ObjectNode> sink = new ArrayList<>();
        queue.drainTo(sink);
        dropped.addAndGet(sink.size());
    }

    /** Up to 500 records or about 1 MB of serialized JSON, whichever comes first. */
    private List<String> nextBatch() {
        List<String> batch = new ArrayList<>();
        long bytes = 0;
        while (batch.size() < MAX_BATCH_RECORDS) {
            String json = carry;
            carry = null;
            if (json == null) {
                ObjectNode next = queue.poll();
                if (next == null) {
                    break;
                }
                try {
                    json = mapper.writeValueAsString(next);
                } catch (Exception e) {
                    dropped.incrementAndGet();
                    continue;
                }
            }
            long size = json.getBytes(StandardCharsets.UTF_8).length + 1;
            if (!batch.isEmpty() && bytes + size > MAX_BATCH_BYTES) {
                carry = json;
                break;
            }
            batch.add(json);
            bytes += size;
        }
        return batch;
    }

    String encodeBatch(List<String> records) throws Exception {
        ArrayNode placeholder = JsonNodeFactory.instance.arrayNode();
        String envelope = mapper.writeValueAsString(
                OtlpEncoder.envelope(serviceName, environment, placeholder));
        int at = envelope.lastIndexOf("\"logRecords\":[]");
        String head = envelope.substring(0, at + "\"logRecords\":[".length());
        String tail = envelope.substring(at + "\"logRecords\":[".length());
        return head + String.join(",", records) + tail;
    }

    private void deliver(List<String> records) throws InterruptedException {
        String body;
        try {
            body = encodeBatch(records);
        } catch (Exception e) {
            dropped.addAndGet(records.size());
            return;
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long delay;
            try {
                LogSender.Response response = sender.send(endpoint, headers, body);
                int status = response.status();
                if (status >= 200 && status < 300) {
                    return;
                }
                if (status == 401 || status == 403) {
                    pausedUntilMs = clock.millis() + AUTH_PAUSE_MS;
                    dropped.addAndGet(records.size());
                    return;
                }
                if (status != 429 && status != 503) {
                    dropped.addAndGet(records.size());
                    return;
                }
                delay = retryAfterMs(response.retryAfter());
            } catch (InterruptedException e) {
                dropped.addAndGet(records.size());
                throw e;
            } catch (Throwable networkError) {
                delay = NETWORK_RETRY_MS[Math.min(attempt - 1, NETWORK_RETRY_MS.length - 1)];
            }
            if (attempt == MAX_ATTEMPTS) {
                break;
            }
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException e) {
                dropped.addAndGet(records.size());
                throw e;
            }
        }
        dropped.addAndGet(records.size());
    }

    static long retryAfterMs(String header) {
        if (header == null) {
            return DEFAULT_RETRY_AFTER_MS;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds < 0) {
                return DEFAULT_RETRY_AFTER_MS;
            }
            return Math.min(seconds * 1000L, MAX_RETRY_AFTER_MS);
        } catch (NumberFormatException e) {
            return DEFAULT_RETRY_AFTER_MS;
        }
    }

    private static String orEnv(String value, Function<String, String> env, String name) {
        if (value != null && !value.isEmpty()) {
            return value;
        }
        String v = env.apply(name);
        return v == null || v.isEmpty() ? null : v;
    }

    /** Pauses the worker between retries; injectable so tests do not wait. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Builds a {@link CallistoLogger}. Every setting falls back to its environment variable. */
    public static final class Builder {
        private String accessKey;
        private String secret;
        private String endpoint;
        private String serviceName;
        private String environment;
        private Duration flushTimeout;
        private LogSender sender;
        private Clock clock;
        Sleeper sleeper;
        Function<String, String> env;

        private Builder() {
        }

        /** {@code pk_…} of a Logs key (env {@code CALLISTO_LOGS_ACCESS_KEY}). */
        public Builder accessKey(String accessKey) {
            this.accessKey = accessKey;
            return this;
        }

        /** {@code sk_…} of that key (env {@code CALLISTO_LOGS_SECRET}). */
        public Builder secret(String secret) {
            this.secret = secret;
            return this;
        }

        /**
         * Full POST URL (env {@code CALLISTO_LOGS_ENDPOINT}, default {@value #DEFAULT_ENDPOINT}).
         * Must be {@code https}; {@code http} is accepted only for a loopback host.
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** Resource {@code service.name} (env {@code CALLISTO_SERVICE_NAME}, then {@code OTEL_SERVICE_NAME}). */
        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        /** Resource {@code deployment.environment.name} (env {@code CALLISTO_ENVIRONMENT}). */
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /** The environment set on this builder, or {@code null}. */
        public String getEnvironment() {
            return environment;
        }

        /** Upper bound of {@code flush()} / {@code close()} (default 5 s). */
        public Builder flushTimeout(Duration flushTimeout) {
            this.flushTimeout = flushTimeout;
            return this;
        }

        /** Replaces the HTTP sender (tests, custom proxies). */
        public Builder sender(LogSender sender) {
            this.sender = sender;
            return this;
        }

        /** Replaces the clock used for record timestamps and the 401/403 pause (tests). */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        Builder sleeper(Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        Builder env(Function<String, String> env) {
            this.env = env;
            return this;
        }

        /**
         * Builds the logger.
         *
         * @throws ValidationException when only one of key / secret resolves, when either is
         *                             malformed, or when the endpoint is not an absolute http(s) URL.
         */
        public CallistoLogger build() {
            return new CallistoLogger(this);
        }
    }
}
