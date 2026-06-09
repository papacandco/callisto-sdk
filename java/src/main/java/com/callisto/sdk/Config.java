package com.callisto.sdk;

import com.callisto.sdk.errors.ValidationException;

import java.time.Duration;

/**
 * Immutable resolved client configuration.
 *
 * <p>Credentials and base URL fall back to the {@code CALLISTO_CLIENT_ID},
 * {@code CALLISTO_API_KEY}, and {@code CALLISTO_BASE_URL} environment variables
 * when the corresponding argument is absent.
 */
public final class Config {

    /** Default API base URL. */
    public static final String DEFAULT_BASE_URL = "https://api.callistosignal.com/v1";

    /** Default request timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String clientId;
    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final String errorDsn;
    private final boolean captureUnhandled;
    private final String environment;

    private Config(String clientId, String apiKey, String baseUrl, Duration timeout,
                   String errorDsn, boolean captureUnhandled, String environment) {
        this.clientId = clientId;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.errorDsn = errorDsn;
        this.captureUnhandled = captureUnhandled;
        this.environment = environment;
    }

    /**
     * Resolves configuration from explicit arguments, falling back to environment
     * variables, and applying defaults for base URL and timeout.
     *
     * @throws ValidationException if neither argument nor environment variable supplies
     *                             a client id or api key.
     */
    public static Config resolve(String clientId, String apiKey, String baseUrl, Duration timeout) {
        return resolve(clientId, apiKey, baseUrl, timeout, null, null, null);
    }

    /**
     * Resolves configuration including error-reporting settings.
     *
     * <p>{@code errorDsn} falls back to {@code CALLISTO_APP_ERROR_DSN}; when absent, error
     * reporting is disabled. {@code captureUnhandled} falls back to
     * {@code CALLISTO_CAPTURE_UNHANDLED} (default {@code false}). {@code environment}
     * falls back to {@code CALLISTO_ENVIRONMENT}.
     *
     * @throws ValidationException if neither argument nor environment variable supplies
     *                             a client id or api key.
     */
    public static Config resolve(String clientId, String apiKey, String baseUrl, Duration timeout,
                                 String errorDsn, Boolean captureUnhandled, String environment) {
        String resolvedClientId = orEnv(clientId, "CALLISTO_CLIENT_ID");
        String resolvedApiKey = orEnv(apiKey, "CALLISTO_API_KEY");
        if (isBlank(resolvedClientId) || isBlank(resolvedApiKey)) {
            throw new ValidationException(
                    "Callisto: clientId and apiKey are required "
                            + "(pass arguments or set CALLISTO_CLIENT_ID / CALLISTO_API_KEY).");
        }
        String resolvedBaseUrl = orEnv(baseUrl, "CALLISTO_BASE_URL");
        if (isBlank(resolvedBaseUrl)) {
            resolvedBaseUrl = DEFAULT_BASE_URL;
        }
        resolvedBaseUrl = trimTrailingSlashes(resolvedBaseUrl);
        Duration resolvedTimeout = timeout != null ? timeout : DEFAULT_TIMEOUT;

        String resolvedErrorDsn = orEnv(errorDsn, "CALLISTO_APP_ERROR_DSN");
        boolean resolvedCaptureUnhandled = captureUnhandled != null
                ? captureUnhandled
                : parseBool(System.getenv("CALLISTO_CAPTURE_UNHANDLED"));
        String resolvedEnvironment = orEnv(environment, "CALLISTO_ENVIRONMENT");

        return new Config(resolvedClientId, resolvedApiKey, resolvedBaseUrl, resolvedTimeout,
                resolvedErrorDsn, resolvedCaptureUnhandled, resolvedEnvironment);
    }

    private static boolean parseBool(String s) {
        if (isBlank(s)) {
            return false;
        }
        String v = s.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    private static String orEnv(String value, String envName) {
        if (!isBlank(value)) {
            return value;
        }
        String env = System.getenv(envName);
        return isBlank(env) ? null : env;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    private static String trimTrailingSlashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    public String getClientId() {
        return clientId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    /** The error-reporting ingest DSN, or {@code null} when reporting is disabled. */
    public String getErrorDsn() {
        return errorDsn;
    }

    /** Whether to install the global unhandled-exception handler. */
    public boolean isCaptureUnhandled() {
        return captureUnhandled;
    }

    /** Optional environment tag included in {@code context.environment}, or {@code null}. */
    public String getEnvironment() {
        return environment;
    }
}
