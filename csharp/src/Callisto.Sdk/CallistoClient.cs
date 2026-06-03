using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Threading.Tasks;
using Callisto.Sdk.Http;
using Callisto.Sdk.Reporting;
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
    private readonly ErrorReporter _reporter;
    private readonly bool _captureUnhandled;
    private UnhandledExceptionEventHandler? _domainHandler;
    private EventHandler<UnobservedTaskExceptionEventArgs>? _taskHandler;

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
    /// The built-in error reporter. Disabled (no-op) unless an error DSN is configured. The three
    /// client methods (<see cref="CaptureException"/>, <see cref="CaptureMessage"/>,
    /// <see cref="SetUser"/>) are the supported surface; this is exposed for advanced use.
    /// </summary>
    public ErrorReporter ErrorReporter => _reporter;

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
    /// <param name="errorDsn">
    /// Error-reporting ingest DSN. Falls back to <c>CALLISTO_ERROR_DSN</c>. Absent → reporting
    /// disabled.
    /// </param>
    /// <param name="captureUnhandled">
    /// Install the global unhandled-exception handler. Falls back to
    /// <c>CALLISTO_CAPTURE_UNHANDLED</c>. Defaults to <c>false</c>.
    /// </param>
    /// <param name="environment">Optional environment tag. Falls back to <c>CALLISTO_ENVIRONMENT</c>.</param>
    /// <param name="errorSender">Optional injectable sender for the reporter (useful for tests).</param>
    public CallistoClient(
        string? clientId = null,
        string? apiKey = null,
        string? baseUrl = null,
        TimeSpan? timeout = null,
        HttpClient? httpClient = null,
        HttpMessageHandler? handler = null,
        string? errorDsn = null,
        bool? captureUnhandled = null,
        string? environment = null,
        IErrorSender? errorSender = null)
    {
        var config = Config.Resolve(clientId, apiKey, baseUrl, timeout, errorDsn, captureUnhandled, environment);

        _reporter = new ErrorReporter(config.ErrorDsn, config.Environment, errorSender);
        _captureUnhandled = config.CaptureUnhandled;

        _transport = new Transport(config, httpClient, handler, _reporter);
        Balance = new BalanceResource(_transport);
        Sms = new SmsResource(_transport);
        Otp = new OtpResource(_transport);
        WhatsApp = new WhatsAppResource(_transport);
        Notify = new NotifyResource(_transport);

        if (_captureUnhandled && _reporter.Enabled)
        {
            InstallGlobalHandler();
        }
    }

    /// <summary>Captures an exception via the error reporter. No-op when reporting is disabled.</summary>
    public void CaptureException(
        Exception ex,
        string level = "error",
        IDictionary<string, object?>? extra = null)
        => _reporter.CaptureException(ex, level, extra);

    /// <summary>Captures a plain message via the error reporter. No-op when reporting is disabled.</summary>
    public void CaptureMessage(
        string message,
        string level = "info",
        IDictionary<string, object?>? extra = null)
        => _reporter.CaptureMessage(message, level, extra);

    /// <summary>Sets/clears the user context attached to subsequent captured events.</summary>
    public void SetUser(IDictionary<string, object?>? user) => _reporter.SetUser(user);

    private void InstallGlobalHandler()
    {
        _domainHandler = (_, args) =>
        {
            if (args.ExceptionObject is Exception ex)
            {
                _reporter.CaptureException(ex, "fatal");
                _reporter.Flush();
            }
        };
        AppDomain.CurrentDomain.UnhandledException += _domainHandler;

        _taskHandler = (_, args) =>
        {
            // Capture but do not mark observed — preserve default behavior.
            _reporter.CaptureException(args.Exception, "fatal");
        };
        TaskScheduler.UnobservedTaskException += _taskHandler;
    }

    /// <summary>Releases the underlying HTTP resources and flushes the error reporter.</summary>
    public void Dispose()
    {
        if (_domainHandler is not null)
        {
            AppDomain.CurrentDomain.UnhandledException -= _domainHandler;
            _domainHandler = null;
        }

        if (_taskHandler is not null)
        {
            TaskScheduler.UnobservedTaskException -= _taskHandler;
            _taskHandler = null;
        }

        _reporter.Dispose();
        _transport.Dispose();
    }
}
