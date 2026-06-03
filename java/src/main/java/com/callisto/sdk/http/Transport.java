package com.callisto.sdk.http;

import com.callisto.sdk.Config;
import com.callisto.sdk.errors.CallistoException;
import com.callisto.sdk.errors.Errors;
import com.callisto.sdk.errors.NetworkException;
import com.callisto.sdk.reporting.ErrorReporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP transport layer.
 *
 * <p>Applies HTTP Basic auth ({@code Authorization: Basic base64(clientId:apiKey)}),
 * an {@code Accept: application/json} header, serializes request bodies as JSON,
 * drops {@code null} query params, parses error bodies, and maps status codes to
 * typed exceptions.
 */
public class Transport {

    private final Config config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String authHeader;
    private ErrorReporter reporter;

    public Transport(Config config) {
        this(config, defaultClient(config), new ObjectMapper());
    }

    /**
     * Constructs a transport with an injectable {@link HttpClient} (for testing) and
     * {@link ObjectMapper}.
     */
    public Transport(Config config, HttpClient httpClient, ObjectMapper mapper) {
        this.config = config;
        this.httpClient = httpClient;
        this.mapper = mapper;
        String credentials = config.getClientId() + ":" + config.getApiKey();
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Attaches the shared error reporter so the transport (the single API/network error choke
     * point) and resources can capture before throwing. Optional; {@code null} disables hooks.
     */
    public void setReporter(ErrorReporter reporter) {
        this.reporter = reporter;
    }

    /** The shared error reporter, or {@code null} when reporting is disabled. */
    public ErrorReporter reporter() {
        return reporter;
    }

    /**
     * Captures an exception via the reporter (if any), tagging it with the originating HTTP
     * {@code method} and {@code path} so the reporter sets {@code culprit} and {@code request}.
     * Never throws.
     */
    public void capture(Throwable t, String method, String path) {
        if (reporter == null) {
            return;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        if (method != null) {
            extra.put("__method", method);
        }
        if (path != null) {
            extra.put("__path", path);
        }
        reporter.captureException(t, "error", extra);
    }

    private static HttpClient defaultClient(Config config) {
        return HttpClient.newBuilder()
                .connectTimeout(config.getTimeout())
                .build();
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Executes a request and returns the decoded JSON response body as a {@link JsonNode}
     * (or {@code null} for an empty body).
     *
     * @param method HTTP method.
     * @param path   request path appended to the base URL.
     * @param body   request body (serialized as JSON) or {@code null}.
     * @param query  query parameters; entries with {@code null} values are dropped.
     */
    public JsonNode request(String method, String path, Object body, Map<String, Object> query) {
        String url = config.getBaseUrl() + path + buildQueryString(query);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(config.getTimeout())
                .header("Authorization", authHeader)
                .header("Accept", "application/json");

        HttpRequest.BodyPublisher publisher;
        if (body != null) {
            String json;
            try {
                json = mapper.writeValueAsString(body);
            } catch (IOException e) {
                throw new NetworkException("Failed to serialize request body: " + e.getMessage(), e);
            }
            publisher = HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
            builder.header("Content-Type", "application/json");
        } else {
            publisher = HttpRequest.BodyPublishers.noBody();
        }
        builder.method(method, publisher);

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            NetworkException ex = new NetworkException("Request to " + url + " failed: " + e.getMessage(), e);
            capture(ex, method, path);
            throw ex;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            NetworkException ex =
                    new NetworkException("Request to " + url + " was interrupted: " + e.getMessage(), e);
            capture(ex, method, path);
            throw ex;
        }

        int status = response.statusCode();
        String rawBody = response.body();

        JsonNode data = null;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                data = mapper.readTree(rawBody);
            } catch (IOException e) {
                // Non-JSON body: surface it as a text node.
                data = mapper.getNodeFactory().textNode(rawBody);
            }
        }

        if (status < 200 || status >= 300) {
            String message;
            if (data != null && data.isObject() && data.has("message")) {
                message = data.get("message").asText();
            } else {
                message = "HTTP " + status;
            }
            Integer retryAfter = null;
            if (status == 429) {
                retryAfter = response.headers().firstValue("Retry-After")
                        .map(raw -> {
                            try {
                                return Integer.parseInt(raw.trim());
                            } catch (NumberFormatException ex) {
                                return null;
                            }
                        })
                        .orElse(null);
            }
            CallistoException ex = Errors.fromStatus(status, message, data, retryAfter);
            capture(ex, method, path);
            throw ex;
        }
        return data;
    }

    private String buildQueryString(Map<String, Object> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : query.entrySet()) {
            if (e.getValue() != null) {
                filtered.put(e.getKey(), e.getValue());
            }
        }
        if (filtered.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, Object> e : filtered.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            first = false;
            sb.append(encode(e.getKey()))
                    .append("=")
                    .append(encode(String.valueOf(e.getValue())));
        }
        return sb.toString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public void close() {
        // java.net.http.HttpClient has no explicit close on Java 11; nothing to release.
    }
}
