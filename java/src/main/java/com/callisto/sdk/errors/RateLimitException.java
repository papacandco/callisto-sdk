package com.callisto.sdk.errors;

/** HTTP 429 — rate limited. Carries {@code retryAfter} parsed from the {@code Retry-After} header. */
public class RateLimitException extends CallistoException {

    private final Integer retryAfter;

    public RateLimitException(String message, int statusCode, Object body, Integer retryAfter) {
        super(message, statusCode, body);
        this.retryAfter = retryAfter;
    }

    /** Seconds to wait before retrying, parsed from the {@code Retry-After} header, or {@code null}. */
    public Integer getRetryAfter() {
        return retryAfter;
    }
}
