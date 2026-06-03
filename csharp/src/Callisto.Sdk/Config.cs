using System;

namespace Callisto.Sdk;

/// <summary>
/// Resolved client configuration. Credentials fall back to environment variables
/// (<c>CALLISTO_CLIENT_ID</c>, <c>CALLISTO_API_KEY</c>, <c>CALLISTO_BASE_URL</c>) when an
/// argument is not supplied. Error-reporting settings fall back to
/// <c>CALLISTO_ERROR_DSN</c>, <c>CALLISTO_CAPTURE_UNHANDLED</c>, and
/// <c>CALLISTO_ENVIRONMENT</c>.
/// </summary>
public sealed class Config
{
    /// <summary>The default API base URL.</summary>
    public const string DefaultBaseUrl = "https://api.callistosignal.com/v1";

    /// <summary>Your Callisto client ID.</summary>
    public string ClientId { get; }

    /// <summary>Your Callisto API key.</summary>
    public string ApiKey { get; }

    /// <summary>API base URL, with any trailing slash trimmed.</summary>
    public string BaseUrl { get; }

    /// <summary>Request timeout.</summary>
    public TimeSpan Timeout { get; }

    /// <summary>
    /// Error-reporting ingest DSN. When <c>null</c>, error reporting is fully disabled (no-op).
    /// </summary>
    public string? ErrorDsn { get; }

    /// <summary>
    /// When <c>true</c> and a DSN is set, installs the global unhandled-exception handler.
    /// Defaults to <c>false</c>.
    /// </summary>
    public bool CaptureUnhandled { get; }

    /// <summary>Optional environment tag included in <c>context.environment</c>.</summary>
    public string? Environment { get; }

    private Config(
        string clientId,
        string apiKey,
        string baseUrl,
        TimeSpan timeout,
        string? errorDsn,
        bool captureUnhandled,
        string? environment)
    {
        ClientId = clientId;
        ApiKey = apiKey;
        BaseUrl = baseUrl;
        Timeout = timeout;
        ErrorDsn = errorDsn;
        CaptureUnhandled = captureUnhandled;
        Environment = environment;
    }

    /// <summary>
    /// Resolves configuration from explicit arguments, falling back to environment variables.
    /// Fails fast with <see cref="ArgumentException"/> if the client ID or API key cannot be
    /// resolved.
    /// </summary>
    public static Config Resolve(
        string? clientId = null,
        string? apiKey = null,
        string? baseUrl = null,
        TimeSpan? timeout = null,
        string? errorDsn = null,
        bool? captureUnhandled = null,
        string? environment = null)
    {
        clientId = Coalesce(clientId, System.Environment.GetEnvironmentVariable("CALLISTO_CLIENT_ID"));
        apiKey = Coalesce(apiKey, System.Environment.GetEnvironmentVariable("CALLISTO_API_KEY"));

        if (string.IsNullOrEmpty(clientId) || string.IsNullOrEmpty(apiKey))
        {
            throw new ArgumentException(
                "Callisto: clientId and apiKey are required " +
                "(pass arguments or set CALLISTO_CLIENT_ID / CALLISTO_API_KEY).");
        }

        var resolvedBase = Coalesce(
            baseUrl,
            System.Environment.GetEnvironmentVariable("CALLISTO_BASE_URL"),
            DefaultBaseUrl)!.TrimEnd('/');

        var resolvedDsn = Coalesce(
            errorDsn,
            System.Environment.GetEnvironmentVariable("CALLISTO_ERROR_DSN"));

        var resolvedCapture = captureUnhandled
            ?? ParseBool(System.Environment.GetEnvironmentVariable("CALLISTO_CAPTURE_UNHANDLED"));

        var resolvedEnvironment = Coalesce(
            environment,
            System.Environment.GetEnvironmentVariable("CALLISTO_ENVIRONMENT"));

        return new Config(
            clientId!,
            apiKey!,
            resolvedBase,
            timeout ?? TimeSpan.FromSeconds(30),
            resolvedDsn,
            resolvedCapture,
            resolvedEnvironment);
    }

    private static bool ParseBool(string? value)
    {
        if (string.IsNullOrEmpty(value))
        {
            return false;
        }

        return value.Trim().ToLowerInvariant() switch
        {
            "1" or "true" or "yes" or "on" => true,
            _ => false,
        };
    }

    private static string? Coalesce(params string?[] values)
    {
        foreach (var value in values)
        {
            if (!string.IsNullOrEmpty(value))
            {
                return value;
            }
        }

        return null;
    }
}
