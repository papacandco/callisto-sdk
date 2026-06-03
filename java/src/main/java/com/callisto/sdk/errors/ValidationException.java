package com.callisto.sdk.errors;

/**
 * HTTP 400 / 422 — invalid request. Also raised client-side before a request
 * (e.g. {@code notify.send} with no event block, or {@code otp.send} with
 * {@code provider=whatsapp} and no {@code instanceCode}).
 */
public class ValidationException extends CallistoException {
    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, int statusCode, Object body) {
        super(message, statusCode, body);
    }
}
