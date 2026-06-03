package com.callisto.sdk.errors;

/** Maps an HTTP status code to the appropriate {@link CallistoException} subtype. */
public final class Errors {

    private Errors() {
    }

    public static CallistoException fromStatus(int status, String message, Object body, Integer retryAfter) {
        if (status == 401) {
            return new AuthenticationException(message, status, body);
        }
        if (status == 400 || status == 422) {
            return new ValidationException(message, status, body);
        }
        if (status == 404) {
            return new NotFoundException(message, status, body);
        }
        if (status == 429) {
            return new RateLimitException(message, status, body, retryAfter);
        }
        return new ApiException(message, status, body);
    }
}
