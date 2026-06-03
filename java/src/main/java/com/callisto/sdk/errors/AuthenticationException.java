package com.callisto.sdk.errors;

/** HTTP 401 — invalid credentials. */
public class AuthenticationException extends CallistoException {
    public AuthenticationException(String message, int statusCode, Object body) {
        super(message, statusCode, body);
    }
}
