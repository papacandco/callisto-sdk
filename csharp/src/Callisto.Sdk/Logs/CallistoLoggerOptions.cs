using System;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace Callisto.Sdk.Logs;

/// <summary>
/// Settings for a <see cref="CallistoLogger"/>. Every setting is optional and falls back to an
/// environment variable: <c>CALLISTO_LOGS_ACCESS_KEY</c>, <c>CALLISTO_LOGS_SECRET</c>,
/// <c>CALLISTO_LOGS_ENDPOINT</c>, <c>CALLISTO_SERVICE_NAME</c> (then <c>OTEL_SERVICE_NAME</c>) and
/// <c>CALLISTO_ENVIRONMENT</c>. With neither a key nor a secret the logger is disabled (no-op).
/// </summary>
public sealed class CallistoLoggerOptions
{
    /// <summary>The <c>pk_…</c> access key of a Logs API key. Env: <c>CALLISTO_LOGS_ACCESS_KEY</c>.</summary>
    public string? LogsAccessKey { get; set; }

    /// <summary>The <c>sk_…</c> secret of that key. Env: <c>CALLISTO_LOGS_SECRET</c>.</summary>
    public string? LogsSecret { get; set; }

    /// <summary>
    /// Full OTLP/HTTP logs POST URL. Env: <c>CALLISTO_LOGS_ENDPOINT</c>. Defaults to
    /// <see cref="CallistoLogger.DefaultEndpoint"/>. Must be <c>https</c>; <c>http</c> is accepted
    /// only for <c>localhost</c>, <c>127.0.0.1</c>, <c>::1</c> and <c>*.localhost</c>.
    /// </summary>
    public string? LogsEndpoint { get; set; }

    /// <summary>
    /// Resource <c>service.name</c>. Env: <c>CALLISTO_SERVICE_NAME</c>, then
    /// <c>OTEL_SERVICE_NAME</c>. Defaults to <c>unknown_service</c>.
    /// </summary>
    public string? ServiceName { get; set; }

    /// <summary>Resource <c>deployment.environment.name</c>. Env: <c>CALLISTO_ENVIRONMENT</c>.</summary>
    public string? Environment { get; set; }

    /// <summary>Upper bound for <c>Flush</c> and <c>Dispose</c>. Defaults to 5 seconds.</summary>
    public TimeSpan? FlushTimeout { get; set; }

    /// <summary>Injectable sender (tests). When set, <see cref="HttpMessageHandler"/> is ignored.</summary>
    public ILogSender? Sender { get; set; }

    /// <summary>
    /// Optional handler the logger's own <see cref="HttpClient"/> is built on (tests, proxies).
    /// The logger never shares the messaging client's HTTP stack.
    /// </summary>
    public HttpMessageHandler? HttpMessageHandler { get; set; }

    /// <summary>Clock used for record timestamps and the auth-failure pause (tests).</summary>
    public Func<DateTimeOffset>? Clock { get; set; }

    /// <summary>Delay used between retries (tests replace it to avoid real waits).</summary>
    internal Func<TimeSpan, CancellationToken, Task>? Delay { get; set; }

    /// <summary>Time between timer-driven sends. Defaults to 5 seconds.</summary>
    internal TimeSpan? FlushInterval { get; set; }

    /// <summary>Builds a disabled no-op logger without reading the key settings.</summary>
    internal bool Disabled { get; set; }
}
