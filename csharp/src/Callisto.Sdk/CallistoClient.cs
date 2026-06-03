using System;
using System.Net.Http;
using Callisto.Sdk.Http;
using Callisto.Sdk.Resources;

namespace Callisto.Sdk;

/// <summary>
/// The Callisto API client. Construct with credentials (or let them resolve from environment
/// variables) and access resources via properties: <see cref="Balance"/>, <see cref="Sms"/>,
/// <see cref="Otp"/>, <see cref="WhatsApp"/>, <see cref="Notify"/>.
/// </summary>
public sealed class CallistoClient : IDisposable
{
    private readonly Transport _transport;

    /// <summary>Account balance operations.</summary>
    public BalanceResource Balance { get; }

    /// <summary>SMS operations.</summary>
    public SmsResource Sms { get; }

    /// <summary>One-time-password operations.</summary>
    public OtpResource Otp { get; }

    /// <summary>WhatsApp operations.</summary>
    public WhatsAppResource WhatsApp { get; }

    /// <summary>Multi-channel notification operations.</summary>
    public NotifyResource Notify { get; }

    /// <summary>
    /// Creates a client. Any of <paramref name="clientId"/>, <paramref name="apiKey"/>, and
    /// <paramref name="baseUrl"/> fall back to <c>CALLISTO_CLIENT_ID</c>,
    /// <c>CALLISTO_API_KEY</c>, and <c>CALLISTO_BASE_URL</c> respectively. Throws
    /// <see cref="ArgumentException"/> if the client ID or API key cannot be resolved.
    /// </summary>
    /// <param name="clientId">Your Callisto client ID.</param>
    /// <param name="apiKey">Your Callisto API key.</param>
    /// <param name="baseUrl">API base URL. Defaults to <see cref="Config.DefaultBaseUrl"/>.</param>
    /// <param name="timeout">Request timeout. Defaults to 30 seconds.</param>
    /// <param name="httpClient">Optional pre-configured <see cref="HttpClient"/> to inject.</param>
    /// <param name="handler">
    /// Optional <see cref="HttpMessageHandler"/> to build the internal client on (useful for
    /// tests). Ignored when <paramref name="httpClient"/> is supplied.
    /// </param>
    public CallistoClient(
        string? clientId = null,
        string? apiKey = null,
        string? baseUrl = null,
        TimeSpan? timeout = null,
        HttpClient? httpClient = null,
        HttpMessageHandler? handler = null)
    {
        var config = Config.Resolve(clientId, apiKey, baseUrl, timeout);
        _transport = new Transport(config, httpClient, handler);
        Balance = new BalanceResource(_transport);
        Sms = new SmsResource(_transport);
        Otp = new OtpResource(_transport);
        WhatsApp = new WhatsAppResource(_transport);
        Notify = new NotifyResource(_transport);
    }

    /// <summary>Releases the underlying HTTP resources.</summary>
    public void Dispose() => _transport.Dispose();
}
