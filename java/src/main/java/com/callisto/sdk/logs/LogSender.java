package com.callisto.sdk.logs;

import java.util.Map;

/**
 * Pluggable HTTP sink for OTLP log batches.
 *
 * <p>The default implementation POSTs to the logs endpoint over a dedicated
 * {@link java.net.http.HttpClient}, never the messaging {@code Transport}. Tests inject a fake
 * to capture requests and script responses.
 */
public interface LogSender {

    /**
     * POSTs one batch.
     *
     * @param endpoint the full logs URL.
     * @param headers  the request headers ({@code Authorization}, {@code Content-Type},
     *                 {@code User-Agent}).
     * @param json     the serialized OTLP JSON body.
     * @return the HTTP response status and its {@code Retry-After} header.
     * @throws Exception on a network error or timeout; the logger retries it.
     */
    Response send(String endpoint, Map<String, String> headers, String json) throws Exception;

    /** The parts of an HTTP response the logger acts on. */
    final class Response {
        private final int status;
        private final String retryAfter;

        public Response(int status, String retryAfter) {
            this.status = status;
            this.retryAfter = retryAfter;
        }

        public Response(int status) {
            this(status, null);
        }

        public int status() {
            return status;
        }

        /** The raw {@code Retry-After} header, or {@code null}. */
        public String retryAfter() {
            return retryAfter;
        }
    }
}
