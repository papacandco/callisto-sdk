package com.callisto.sdk;

import com.callisto.sdk.http.Transport;
import com.callisto.sdk.logs.CallistoLogger;
import com.callisto.sdk.reporting.ErrorReporter;
import com.callisto.sdk.reporting.ErrorSender;
import com.callisto.sdk.resources.Balance;
import com.callisto.sdk.resources.Notify;
import com.callisto.sdk.resources.Otp;
import com.callisto.sdk.resources.Sms;
import com.callisto.sdk.resources.WhatsApp;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

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
    private final ErrorReporter errorReporter;
    private final CallistoLogger logs;
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
        this(Config.resolve(clientId, apiKey, baseUrl, timeout), null, null, null, null);
    }

    /**
     * Creates a client with an injectable {@link HttpClient} and {@link ObjectMapper}
     * (advanced use, e.g. testing or custom transport configuration). Pass {@code null} to
     * use the defaults.
     */
    public CallistoClient(String clientId, String apiKey, String baseUrl, Duration timeout,
                          HttpClient httpClient, ObjectMapper mapper) {
        this(Config.resolve(clientId, apiKey, baseUrl, timeout), httpClient, mapper, null, null);
    }

    /**
     * Creates a client with full error-reporting configuration and an injectable
     * {@link ErrorSender} (advanced use / testing). Pass {@code null} for any optional argument
     * to fall back to defaults / environment variables.
     */
    public CallistoClient(String clientId, String apiKey, String baseUrl, Duration timeout,
                          HttpClient httpClient, ObjectMapper mapper,
                          String errorDsn, Boolean captureUnhandled, String environment,
                          ErrorSender errorSender) {
        this(Config.resolve(clientId, apiKey, baseUrl, timeout, errorDsn, captureUnhandled, environment),
                httpClient, mapper, errorSender, null);
    }

    /**
     * Creates a client that also ships logs to callisto-logs. {@code logs} carries the Logs key
     * settings ({@code accessKey}, {@code secret}, {@code endpoint}, {@code serviceName}, ...);
     * anything it leaves unset falls back to the {@code CALLISTO_LOGS_*} environment variables,
     * and its environment defaults to this client's {@code environment}. Pass {@code null} to
     * configure logs from the environment only. The messaging credentials are never used for logs.
     *
     * @throws com.callisto.sdk.errors.ValidationException when the Logs key is half set or
     *                                                     malformed.
     */
    public CallistoClient(String clientId, String apiKey, String baseUrl, Duration timeout,
                          HttpClient httpClient, ObjectMapper mapper,
                          String errorDsn, Boolean captureUnhandled, String environment,
                          ErrorSender errorSender, CallistoLogger.Builder logs) {
        this(Config.resolve(clientId, apiKey, baseUrl, timeout, errorDsn, captureUnhandled, environment),
                httpClient, mapper, errorSender, logs);
    }

    private CallistoClient(Config config, HttpClient httpClient, ObjectMapper mapper,
                           ErrorSender errorSender, CallistoLogger.Builder logsBuilder) {
        CallistoLogger.Builder lb = logsBuilder != null ? logsBuilder : CallistoLogger.builder();
        if (lb.getEnvironment() == null && config.getEnvironment() != null) {
            lb.environment(config.getEnvironment());
        }
        this.logs = lb.build();

        ObjectMapper resolvedMapper = mapper != null ? mapper : new ObjectMapper();
        this.transport = httpClient != null
                ? new Transport(config, httpClient, resolvedMapper)
                : new Transport(config);

        this.errorReporter = new ErrorReporter(
                config.getErrorDsn(), config.getEnvironment(), errorSender, resolvedMapper);
        if (errorReporter.isEnabled()) {
            transport.setReporter(errorReporter);
        }

        this.balance = new Balance(transport);
        this.sms = new Sms(transport);
        this.otp = new Otp(transport);
        this.whatsapp = new WhatsApp(transport);
        this.notify = new Notify(transport);

        if (config.isCaptureUnhandled()) {
            errorReporter.installUncaughtHandler();
        }
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

    /**
     * The log shipper for callisto-logs. Always non-null; a no-op when no Logs key
     * ({@code CALLISTO_LOGS_ACCESS_KEY} / {@code CALLISTO_LOGS_SECRET}) resolves. Closed by
     * {@link #close()}.
     */
    public CallistoLogger logs() {
        return logs;
    }

    /**
     * The error reporter (advanced use). Reachable for direct configuration; the three
     * {@code capture*} / {@code setUser} client methods are the supported surface. Always
     * non-null; a no-op when no DSN is configured.
     */
    public ErrorReporter errorReporter() {
        return errorReporter;
    }

    /**
     * Reports an exception to the error-tracking endpoint at {@code level = error}.
     * No-op when no DSN is configured. Never throws.
     */
    public void captureException(Throwable t) {
        errorReporter.captureException(t);
    }

    /** Reports an exception at the given level. No-op without a DSN. Never throws. */
    public void captureException(Throwable t, String level, Map<String, Object> extra) {
        errorReporter.captureException(t, level, extra);
    }

    /**
     * Reports a plain message to the error-tracking endpoint at {@code level = info}.
     * No-op when no DSN is configured. Never throws.
     */
    public void captureMessage(String message) {
        errorReporter.captureMessage(message);
    }

    /** Reports a plain message at the given level. No-op without a DSN. Never throws. */
    public void captureMessage(String message, String level, Map<String, Object> extra) {
        errorReporter.captureMessage(message, level, extra);
    }

    /** Sets or clears the {@code user} context attached to subsequent reported events. */
    public void setUser(Map<String, Object> user) {
        errorReporter.setUser(user);
    }

    @Override
    public void close() {
        logs.close();
        errorReporter.close();
        transport.close();
    }
}
