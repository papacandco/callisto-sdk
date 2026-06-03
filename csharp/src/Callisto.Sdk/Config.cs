using System;

namespace Callisto.Sdk;

/// <summary>
/// Resolved client configuration. Credentials fall back to environment variables
/// (<c>CALLISTO_CLIENT_ID</c>, <c>CALLISTO_API_KEY</c>, <c>CALLISTO_BASE_URL</c>) when an
/// argument is not supplied.
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

    private Config(string clientId, string apiKey, string baseUrl, TimeSpan timeout)
    {
        ClientId = clientId;
        ApiKey = apiKey;
        BaseUrl = baseUrl;
        Timeout = timeout;
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
        TimeSpan? timeout = null)
    {
        clientId = Coalesce(clientId, Environment.GetEnvironmentVariable("CALLISTO_CLIENT_ID"));
        apiKey = Coalesce(apiKey, Environment.GetEnvironmentVariable("CALLISTO_API_KEY"));

        if (string.IsNullOrEmpty(clientId) || string.IsNullOrEmpty(apiKey))
        {
            throw new ArgumentException(
                "Callisto: clientId and apiKey are required " +
                "(pass arguments or set CALLISTO_CLIENT_ID / CALLISTO_API_KEY).");
        }

        var resolvedBase = Coalesce(
            baseUrl,
            Environment.GetEnvironmentVariable("CALLISTO_BASE_URL"),
            DefaultBaseUrl)!.TrimEnd('/');

        return new Config(clientId!, apiKey!, resolvedBase, timeout ?? TimeSpan.FromSeconds(30));
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
