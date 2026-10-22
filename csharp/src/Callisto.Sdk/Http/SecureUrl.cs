using System;

namespace Callisto.Sdk.Http;

/// <summary>
/// The HTTPS rule for URLs that carry a secret (the Logs endpoint, the error DSN): an absolute
/// <c>https</c> URL, or <c>http</c> only on a loopback host (<c>localhost</c>, <c>127.0.0.1</c>,
/// <c>::1</c>, <c>*.localhost</c>) for local development.
/// </summary>
internal static class SecureUrl
{
    public static bool IsSecure(string? url)
    {
        if (string.IsNullOrWhiteSpace(url) ||
            !Uri.TryCreate(url.Trim(), UriKind.Absolute, out var uri) ||
            string.IsNullOrEmpty(uri.Host))
        {
            return false;
        }

        if (uri.Scheme == Uri.UriSchemeHttps)
        {
            return true;
        }

        if (uri.Scheme != Uri.UriSchemeHttp)
        {
            return false;
        }

        var host = uri.Host.ToLowerInvariant().Trim('[', ']');
        return host is "localhost" or "127.0.0.1" or "::1" || host.EndsWith(".localhost", StringComparison.Ordinal);
    }
}
