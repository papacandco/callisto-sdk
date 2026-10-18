package com.callisto.sdk.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Records every logs request and replays scripted responses (default 200). */
public final class FakeLogSender implements LogSender {

    public static final String PK = "pk_00112233445566778899aabbccddeeff";
    public static final String SK = "sk_000102030405060708090a0b0c0d0e0f1011121314151617";
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** One captured request. */
    public static final class Request {
        public final String endpoint;
        public final Map<String, String> headers;
        public final String body;

        Request(String endpoint, Map<String, String> headers, String body) {
            this.endpoint = endpoint;
            this.headers = headers;
            this.body = body;
        }

        public JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public JsonNode records() {
            return json().get("resourceLogs").get(0).get("scopeLogs").get(0).get("logRecords");
        }
    }

    public final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Deque<Supplier<Response>> script = new ArrayDeque<>();
    volatile CountDownLatch entered;
    volatile CountDownLatch release;
    volatile Runnable onSend;

    /** Queues the next responses; a {@code null} supplier result means "throw a network error". */
    synchronized FakeLogSender then(int status, String retryAfter) {
        script.add(() -> new Response(status, retryAfter));
        return this;
    }

    synchronized FakeLogSender thenNetworkError() {
        script.add(() -> null);
        return this;
    }

    @Override
    public Response send(String endpoint, Map<String, String> headers, String json) throws Exception {
        requests.add(new Request(endpoint, headers, json));
        if (onSend != null) {
            onSend.run();
        }
        if (entered != null) {
            entered.countDown();
        }
        if (release != null) {
            release.await(5, TimeUnit.SECONDS);
        }
        Supplier<Response> next;
        synchronized (this) {
            next = script.poll();
        }
        if (next == null) {
            return new Response(200);
        }
        Response r = next.get();
        if (r == null) {
            throw new java.io.IOException("connection refused");
        }
        return r;
    }

    int totalRecords() {
        int n = 0;
        for (Request r : requests) {
            n += r.records().size();
        }
        return n;
    }

    /** Waits (bounded) until at least {@code n} requests were made. */
    void awaitRequests(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (requests.size() < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    /** A clock the test can move. */
    static final class MutableClock extends Clock {
        volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
