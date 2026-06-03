package com.callisto.sdk.reporting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Default {@link ErrorSender}: POSTs the JSON payload to the DSN URL over a dedicated
 * {@link HttpClient}, isolated from the SDK's main {@code Transport} so it never inherits
 * the Basic-auth credentials and never recurses.
 */
final class HttpErrorSender implements ErrorSender {

    private final HttpClient httpClient;
    private final Duration timeout;

    HttpErrorSender() {
        this.timeout = Duration.ofSeconds(5);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public int send(String dsn, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(dsn))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
