package com.callisto.sdk.errors;

/** Any other non-2xx HTTP status not covered by a more specific error. */
public class ApiException extends CallistoException {
    public ApiException(String message, int statusCode, Object body) {
        super(message, statusCode, body);
    }
}
