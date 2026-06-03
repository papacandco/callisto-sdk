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

    private Config(String clientId, String apiKey, String baseUrl, Duration timeout) {
        this.clientId = clientId;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.timeout = timeout;
    }

    /**
     * Resolves configuration from explicit arguments, falling back to environment
     * variables, and applying defaults for base URL and timeout.
     *
     * @throws ValidationException if neither argument nor environment variable supplies
     *                             a client id or api key.
     */
    public static Config resolve(String clientId, String apiKey, String baseUrl, Duration timeout) {
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
        return new Config(resolvedClientId, resolvedApiKey, resolvedBaseUrl, resolvedTimeout);
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
}
