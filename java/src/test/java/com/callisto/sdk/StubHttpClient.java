package com.callisto.sdk;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * A hand-rolled {@link HttpClient} stub that captures the last outgoing {@link HttpRequest}
 * (method, URI, headers, body) and returns a canned response.
 */
public class StubHttpClient extends HttpClient {

    private final int status;
    private final String responseBody;
    private final Map<String, List<String>> responseHeaders;

    public volatile HttpRequest lastRequest;
    public volatile String lastBody;

    public StubHttpClient(int status, String responseBody) {
        this(status, responseBody, Map.of());
    }

    public StubHttpClient(int status, String responseBody, Map<String, List<String>> responseHeaders) {
        this.status = status;
        this.responseBody = responseBody;
        this.responseHeaders = responseHeaders;
    }

    public String authorizationHeader() {
        return lastRequest.headers().firstValue("Authorization").orElse(null);
    }

    public String acceptHeader() {
        return lastRequest.headers().firstValue("Accept").orElse(null);
    }

    public String method() {
        return lastRequest.method();
    }

    public URI uri() {
        return lastRequest.uri();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        this.lastRequest = request;
        this.lastBody = BodyCapture.extract(request);
        return (HttpResponse<T>) new StubResponse(request, status, responseBody, responseHeaders);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        try {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        } catch (IOException | InterruptedException e) {
            CompletableFuture<HttpResponse<T>> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return sendAsync(request, responseBodyHandler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    /** A stub {@link HttpClient} that throws an {@link IOException} on send (network failure). */
    public static class Throwing extends StubHttpClient {
        public Throwing() {
            super(0, null);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException {
            throw new IOException("connection refused");
        }
    }

    private static final class StubResponse implements HttpResponse<String> {
        private final HttpRequest request;
        private final int status;
        private final String body;
        private final HttpHeaders headers;

        StubResponse(HttpRequest request, int status, String body,
                     Map<String, List<String>> headers) {
            this.request = request;
            this.status = status;
            this.body = body;
            this.headers = HttpHeaders.of(headers, (a, b) -> true);
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }
}
