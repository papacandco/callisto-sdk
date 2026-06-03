package com.callisto.sdk.errors;

/** Transport-level failure (connection error, timeout, DNS, etc.). {@code statusCode} is {@code 0}. */
public class NetworkException extends CallistoException {
    public NetworkException(String message) {
        super(message, 0, null);
    }

    public NetworkException(String message, Throwable cause) {
        super(message, 0, null);
        initCause(cause);
    }
}
