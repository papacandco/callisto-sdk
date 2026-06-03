package com.callisto.sdk;

import com.callisto.sdk.http.Transport;
import com.callisto.sdk.resources.Balance;
import com.callisto.sdk.resources.Notify;
import com.callisto.sdk.resources.Otp;
import com.callisto.sdk.resources.Sms;
import com.callisto.sdk.resources.WhatsApp;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Entry point for the Callisto SDK.
 *
 * <p>Authentication is HTTP Basic ({@code clientId} / {@code apiKey}) and is applied
 * automatically to every request. Implements {@link AutoCloseable}.
 *
 * <pre>{@code
 * try (CallistoClient client = new CallistoClient("your-client-id", "your-api-key")) {
 *     Balance balance = client.balance().get();
 * }
 * }</pre>
 */
public class CallistoClient implements AutoCloseable {

    private final Transport transport;
    private final Balance balance;
    private final Sms sms;
    private final Otp otp;
    private final WhatsApp whatsapp;
    private final Notify notify;

    /**
     * Creates a client using the given credentials (or {@code null} to fall back to the
     * {@code CALLISTO_CLIENT_ID} / {@code CALLISTO_API_KEY} environment variables), the default
     * base URL, and a 30s timeout.
     */
    public CallistoClient(String clientId, String apiKey) {
        this(clientId, apiKey, null, null);
    }

    /**
     * Creates a client.
     *
     * @param clientId Callisto client ID (falls back to env {@code CALLISTO_CLIENT_ID}).
     * @param apiKey   Callisto API key (falls back to env {@code CALLISTO_API_KEY}).
     * @param baseUrl  optional API base URL (falls back to env {@code CALLISTO_BASE_URL},
     *                 then the default). Trailing slashes are trimmed.
     * @param timeout  optional request timeout (defaults to 30s).
     */
    public CallistoClient(String clientId, String apiKey, String baseUrl, Duration timeout) {
        this(Config.resolve(clientId, apiKey, baseUrl, timeout), null, null);
    }

    /**
     * Creates a client with an injectable {@link HttpClient} and {@link ObjectMapper}
     * (advanced use, e.g. testing or custom transport configuration). Pass {@code null} to
     * use the defaults.
     */
    public CallistoClient(String clientId, String apiKey, String baseUrl, Duration timeout,
                          HttpClient httpClient, ObjectMapper mapper) {
        this(Config.resolve(clientId, apiKey, baseUrl, timeout), httpClient, mapper);
    }

    private CallistoClient(Config config, HttpClient httpClient, ObjectMapper mapper) {
        ObjectMapper resolvedMapper = mapper != null ? mapper : new ObjectMapper();
        this.transport = httpClient != null
                ? new Transport(config, httpClient, resolvedMapper)
                : new Transport(config);
        this.balance = new Balance(transport);
        this.sms = new Sms(transport);
        this.otp = new Otp(transport);
        this.whatsapp = new WhatsApp(transport);
        this.notify = new Notify(transport);
    }

    public Balance balance() {
        return balance;
    }

    public Sms sms() {
        return sms;
    }

    public Otp otp() {
        return otp;
    }

    public WhatsApp whatsapp() {
        return whatsapp;
    }

    /**
     * The notify resource.
     *
     * <p>Named {@code notify()} would collide with the {@code final} {@link Object#notify()};
     * this accessor is therefore named {@code notifications()}.
     */
    public Notify notifications() {
        return notify;
    }

    @Override
    public void close() {
        transport.close();
    }
}
