package com.callisto.sdk.reporting;

/**
 * Pluggable HTTP sink for error-reporting payloads.
 *
 * <p>The default implementation POSTs the JSON body to the DSN URL via a dedicated
 * {@link java.net.http.HttpClient}. Tests inject a fake to capture payloads deterministically.
 */
public interface ErrorSender {

    /**
     * Delivers a serialized JSON payload to the given DSN URL.
     *
     * @param dsn  the full ingest URL (the DSN is the POST target).
     * @param json the serialized JSON request body.
     * @return the HTTP status code returned by the endpoint (the reporter treats any
     *         non-202 as a swallowed failure).
     * @throws Exception any failure; the reporter swallows it and never re-captures.
     */
    int send(String dsn, String json) throws Exception;
}
