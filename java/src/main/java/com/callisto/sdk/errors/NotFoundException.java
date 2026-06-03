package com.callisto.sdk.errors;

/** HTTP 404 — resource not found. */
public class NotFoundException extends CallistoException {
    public NotFoundException(String message, int statusCode, Object body) {
        super(message, statusCode, body);
    }
}
