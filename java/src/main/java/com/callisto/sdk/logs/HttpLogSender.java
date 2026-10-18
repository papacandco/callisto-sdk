package com.callisto.sdk.logs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Default {@link LogSender}: its own {@link HttpClient} (10 s per request) running on SDK-owned
 * daemon threads, so any logging the HTTP stack does while sending is recognised as internal and
 * dropped instead of being shipped again.
 */
final class HttpLogSender implements LogSender, AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ExecutorService executor;
    private final HttpClient httpClient;

    HttpLogSender() {
        this.executor = Executors.newCachedThreadPool(
                InternalThreads.factory("callisto-logs-http"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .executor(executor)
                .build();
    }

    @Override
    public Response send(String endpoint, Map<String, String> headers, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        for (Map.Entry<String, String> h : headers.entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }
        HttpResponse<Void> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.discarding());
        return new Response(response.statusCode(),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
