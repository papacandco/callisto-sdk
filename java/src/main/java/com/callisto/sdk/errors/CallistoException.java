package com.callisto.sdk.errors;

/**
 * Base class for all Callisto SDK errors.
 *
 * <p>Carries the human-readable {@code message}, the HTTP {@code statusCode}
 * ({@code 0} for transport-level failures), and the decoded response {@code body}
 * (when available).
 */
public class CallistoException extends RuntimeException {

    private final int statusCode;
    private final Object body;

    public CallistoException(String message) {
        this(message, 0, null);
    }

    public CallistoException(String message, int statusCode, Object body) {
        super(message);
        this.statusCode = statusCode;
        this.body = body;
    }

    /** The HTTP status code, or {@code 0} for transport-level failures. */
    public int getStatusCode() {
        return statusCode;
    }

    /** The decoded response body, or {@code null} when unavailable. */
    public Object getBody() {
        return body;
    }
}
