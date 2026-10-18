using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;

namespace Callisto.Sdk.Logs;

/// <summary>
/// Ships application log lines to callisto-logs (OTLP/HTTP JSON). Records are queued in memory
/// (at most 2 000) and sent by one background task: when 100 records are queued, every 5 s, and on
/// <see cref="Flush"/> / <see cref="Dispose"/>. A log call never throws and never waits on the
/// network. Built without a Logs key and secret, the logger is disabled and every call is a no-op.
/// </summary>
public sealed class CallistoLogger : IDisposable, IAsyncDisposable
{
    /// <summary>The default callisto-logs endpoint.</summary>
    public const string DefaultEndpoint = "https://logs.callistosignal.com/v1/logs";

    internal const int MaxQueue = 2_000;
    internal const int BatchTrigger = 100;
    internal const int MaxBatchRecords = 500;
    internal const int MaxBatchBytes = 1_000_000;
    internal const int MaxAttempts = 3;
    internal static readonly TimeSpan DefaultInterval = TimeSpan.FromSeconds(5);
    internal static readonly TimeSpan DefaultFlushTimeout = TimeSpan.FromSeconds(5);
    internal static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(10);
    internal static readonly TimeSpan AuthPause = TimeSpan.FromSeconds(60);
    internal static readonly TimeSpan MaxRetryAfter = TimeSpan.FromSeconds(60);
    internal static readonly TimeSpan DefaultRetryAfter = TimeSpan.FromSeconds(5);
    internal static readonly TimeSpan[] NetworkBackoff = { TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(5) };

    private static readonly Regex AccessKeyPattern = new(@"^pk_[0-9a-f]{32}\z", RegexOptions.CultureInvariant);
    private static readonly Regex SecretPattern = new(@"^sk_[0-9a-f]{48}\z", RegexOptions.CultureInvariant);

    private readonly bool _enabled;
    private readonly string _endpoint = DefaultEndpoint;
    private readonly IReadOnlyDictionary<string, string> _headers = new Dictionary<string, string>();
    private readonly string _prefix = string.Empty;
    private readonly int _recordBudget;
    private readonly ILogSender? _sender;
    private readonly IDisposable? _ownedSender;
    private readonly Func<DateTimeOffset> _clock;
    private readonly Func<TimeSpan, CancellationToken, Task> _delay;
    private readonly TimeSpan _interval;
    private readonly TimeSpan _flushTimeout;

    private readonly Channel<QueuedRecord>? _channel;
    private readonly Task? _worker;
    private readonly CancellationTokenSource _stop = new();
    private readonly SemaphoreSlim _wake = new(0);
    private readonly ConcurrentQueue<TaskCompletionSource<bool>> _flushRequests = new();
    private int _wakeSignaled;
    private long _dropped;
    private long _pausedUntilTicks;
    private int _closed;
    private QueuedRecord? _carry;

    /// <summary>
    /// Creates a logger. Settings fall back to <c>CALLISTO_LOGS_ACCESS_KEY</c>,
    /// <c>CALLISTO_LOGS_SECRET</c>, <c>CALLISTO_LOGS_ENDPOINT</c>, <c>CALLISTO_SERVICE_NAME</c> /
    /// <c>OTEL_SERVICE_NAME</c> and <c>CALLISTO_ENVIRONMENT</c>. Throws
    /// <see cref="ArgumentException"/> when only one of key and secret resolves, when either is
    /// malformed, or when the endpoint is not an absolute http(s) URL.
    /// </summary>
    public CallistoLogger(CallistoLoggerOptions? options = null)
    {
        options ??= new CallistoLoggerOptions();
        _clock = options.Clock ?? (() => DateTimeOffset.UtcNow);
        _delay = options.Delay ?? ((delay, token) => Task.Delay(delay, token));
        _interval = options.FlushInterval is { } interval && interval > TimeSpan.Zero ? interval : DefaultInterval;
        _flushTimeout = options.FlushTimeout is { } timeout && timeout >= TimeSpan.Zero ? timeout : DefaultFlushTimeout;

        var accessKey = Coalesce(options.LogsAccessKey, Env("CALLISTO_LOGS_ACCESS_KEY"));
        var secret = Coalesce(options.LogsSecret, Env("CALLISTO_LOGS_SECRET"));

        ServiceName = Coalesce(
            options.ServiceName,
            Env("CALLISTO_SERVICE_NAME"),
            Env("OTEL_SERVICE_NAME"),
            "unknown_service")!;
        Environment = Coalesce(options.Environment, Env("CALLISTO_ENVIRONMENT"));

        if (accessKey is null && secret is null)
        {
            return; // disabled: no queue, no task, every call is a no-op
        }

        if (accessKey is null || secret is null)
        {
            throw new ArgumentException(
                "Callisto: logsAccessKey and logsSecret must be set together " +
                "(pass both or set CALLISTO_LOGS_ACCESS_KEY / CALLISTO_LOGS_SECRET).");
        }

        if (!AccessKeyPattern.IsMatch(accessKey))
        {
            throw new ArgumentException(
                "Callisto: logsAccessKey must be \"pk_\" followed by 32 lowercase hex characters (a Logs API key).");
        }

        if (!SecretPattern.IsMatch(secret))
        {
            throw new ArgumentException(
                "Callisto: logsSecret must be \"sk_\" followed by 48 lowercase hex characters (a Logs API key).");
        }

        var endpoint = Coalesce(options.LogsEndpoint, Env("CALLISTO_LOGS_ENDPOINT"), DefaultEndpoint)!;
        if (!Uri.TryCreate(endpoint, UriKind.Absolute, out var uri) ||
            (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            throw new ArgumentException("Callisto: logsEndpoint must be an absolute http(s) URL.");
        }

        _endpoint = endpoint;
        Endpoint = endpoint;
        _headers = new Dictionary<string, string>
        {
            ["Authorization"] = "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(accessKey + ":" + secret)),
            ["Content-Type"] = "application/json",
            ["User-Agent"] = SdkInfo.UserAgent,
        };
        _prefix = OtlpEncoder.Prefix(ServiceName, Environment);
        _recordBudget = MaxBatchBytes - Encoding.UTF8.GetByteCount(_prefix) - OtlpEncoder.Suffix.Length;

        if (options.Sender is not null)
        {
            _sender = options.Sender;
        }
        else
        {
            var http = new HttpLogSender(options.HttpMessageHandler, RequestTimeout);
            _sender = http;
            _ownedSender = http;
        }

        _channel = Channel.CreateBounded<QueuedRecord>(new BoundedChannelOptions(MaxQueue)
        {
            FullMode = BoundedChannelFullMode.Wait, // TryWrite returns false when full: we drop and count
            SingleReader = true,
            SingleWriter = false,
        });
        _enabled = true;
        _worker = Task.Run(WorkerLoopAsync);
    }

    /// <summary>Whether a Logs key resolved. A disabled logger sends nothing.</summary>
    public bool Enabled => _enabled;

    /// <summary>The resolved <c>service.name</c>.</summary>
    public string ServiceName { get; }

    /// <summary>The resolved <c>deployment.environment.name</c>, or <c>null</c>.</summary>
    public string? Environment { get; }

    /// <summary>The resolved endpoint (the default when disabled).</summary>
    public string Endpoint { get; } = DefaultEndpoint;

    /// <summary>
    /// Records lost so far: queue full, sending paused after a 401/403, or a batch dropped after a
    /// rejected or failed delivery. Read-only, for diagnostics and tests.
    /// </summary>
    public long DroppedCount => Interlocked.Read(ref _dropped);

    internal bool WorkerStarted => _worker is not null;

    public void Debug(string message, IReadOnlyDictionary<string, object?>? attributes = null)
        => Log(CallistoLogLevel.Debug, message, attributes);

    public void Info(string message, IReadOnlyDictionary<string, object?>? attributes = null)
        => Log(CallistoLogLevel.Info, message, attributes);

    public void Warn(string message, IReadOnlyDictionary<string, object?>? attributes = null)
        => Log(CallistoLogLevel.Warn, message, attributes);

    public void Error(string message, IReadOnlyDictionary<string, object?>? attributes = null)
        => Log(CallistoLogLevel.Error, message, attributes);

    public void Fatal(string message, IReadOnlyDictionary<string, object?>? attributes = null)
        => Log(CallistoLogLevel.Fatal, message, attributes);

    /// <summary>
    /// Logs at <paramref name="level"/> (<c>debug|info|warn|error|fatal</c>, any case; anything
    /// else is logged as <c>info</c>).
    /// </summary>
    public void Log(string level, string message, IReadOnlyDictionary<string, object?>? attributes = null)
    {
        OtlpEncoder.TryParseLevel(level, out var parsed);
        Log(parsed, message, attributes);
    }

    /// <summary>
    /// Queues one record. <paramref name="attributes"/> values may be string, bool, integer,
    /// floating point, null, lists or maps; other values are stringified. Valid <c>trace_id</c>
    /// (32 hex) and <c>span_id</c> (16 hex) attributes become the record's trace context. Never
    /// throws.
    /// </summary>
    public void Log(CallistoLogLevel level, string message, IReadOnlyDictionary<string, object?>? attributes = null)
    {
        if (!_enabled || _channel is null || Volatile.Read(ref _closed) != 0)
        {
            return;
        }

        try
        {
            var now = _clock();
            if (IsPaused(now))
            {
                Interlocked.Increment(ref _dropped);
                return;
            }

            var json = OtlpEncoder.Record(level, message, attributes, now);
            var record = new QueuedRecord(json, Encoding.UTF8.GetByteCount(json));
            if (!_channel.Writer.TryWrite(record))
            {
                Interlocked.Increment(ref _dropped);
                return;
            }

            if (_channel.Reader.Count >= BatchTrigger)
            {
                Wake();
            }
        }
        catch
        {
            // A log call never throws.
            Interlocked.Increment(ref _dropped);
        }
    }

    /// <summary>Sends what is queued now. Bounded by <c>FlushTimeout</c> (5 s); never throws.</summary>
    public async Task FlushAsync(CancellationToken cancellationToken = default)
    {
        if (!_enabled || Volatile.Read(ref _closed) != 0)
        {
            return;
        }

        await FlushCoreAsync(_flushTimeout, cancellationToken).ConfigureAwait(false);
    }

    /// <summary>Synchronous <see cref="FlushAsync"/>.</summary>
    public void Flush()
    {
        try
        {
            FlushAsync().GetAwaiter().GetResult();
        }
        catch
        {
            // never throws
        }
    }

    /// <summary>Flushes, then stops the background task. Later calls are no-ops.</summary>
    public async Task CloseAsync()
    {
        if (!_enabled || Interlocked.Exchange(ref _closed, 1) != 0)
        {
            return;
        }

        var watch = Stopwatch.StartNew();
        try
        {
            await FlushCoreAsync(_flushTimeout, CancellationToken.None).ConfigureAwait(false);
            _channel!.Writer.TryComplete();
            _stop.Cancel();
            Wake();

            var remaining = _flushTimeout - watch.Elapsed;
            if (remaining < TimeSpan.FromMilliseconds(100))
            {
                remaining = TimeSpan.FromMilliseconds(100);
            }

            await _worker!.WaitAsync(remaining).ConfigureAwait(false);
        }
        catch
        {
            // best-effort
        }
        finally
        {
            try
            {
                _ownedSender?.Dispose();
            }
            catch
            {
                // ignore
            }
        }
    }

    /// <summary>Synchronous <see cref="CloseAsync"/>.</summary>
    public void Close()
    {
        try
        {
            CloseAsync().GetAwaiter().GetResult();
        }
        catch
        {
            // never throws
        }
    }

    /// <inheritdoc cref="Close"/>
    public void Dispose() => Close();

    /// <inheritdoc cref="CloseAsync"/>
    public async ValueTask DisposeAsync()
    {
        try
        {
            await CloseAsync().ConfigureAwait(false);
        }
        catch
        {
            // never throws
        }
    }

    private async Task FlushCoreAsync(TimeSpan timeout, CancellationToken cancellationToken)
    {
        try
        {
            var done = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            _flushRequests.Enqueue(done);
            Wake();
            await done.Task.WaitAsync(timeout, cancellationToken).ConfigureAwait(false);
        }
        catch
        {
            // timeout or cancellation: best-effort
        }
    }

    private void Wake()
    {
        if (Interlocked.Exchange(ref _wakeSignaled, 1) == 0)
        {
            try
            {
                _wake.Release();
            }
            catch
            {
                // ignore
            }
        }
    }

    private bool IsPaused(DateTimeOffset now) => now.UtcTicks < Interlocked.Read(ref _pausedUntilTicks);

    private void Drop(int count) => Interlocked.Add(ref _dropped, count);

    private async Task WorkerLoopAsync()
    {
        var token = _stop.Token;
        try
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await _wake.WaitAsync(_interval, token).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }

                Interlocked.Exchange(ref _wakeSignaled, 0);

                var flushes = new List<TaskCompletionSource<bool>>();
                while (_flushRequests.TryDequeue(out var request))
                {
                    flushes.Add(request);
                }

                // Woken by 100 queued records, the timer or a flush: send everything queued now.
                while (!token.IsCancellationRequested && TryBuildBatch(out var batch))
                {
                    await SendBatchAsync(batch, token).ConfigureAwait(false);
                }

                foreach (var flush in flushes)
                {
                    flush.TrySetResult(true);
                }
            }
        }
        catch
        {
            // The worker must never crash the process.
        }
        finally
        {
            while (_flushRequests.TryDequeue(out var request))
            {
                request.TrySetResult(false);
            }
        }
    }

    private bool TryBuildBatch(out List<string> batch)
    {
        batch = new List<string>();
        var bytes = 0;

        if (_carry is { } carried)
        {
            batch.Add(carried.Json);
            bytes = carried.Bytes;
            _carry = null;
        }

        while (batch.Count < MaxBatchRecords && _channel!.Reader.TryRead(out var record))
        {
            if (batch.Count > 0 && bytes + 1 + record.Bytes > _recordBudget)
            {
                _carry = record;
                break;
            }

            bytes += record.Bytes + (batch.Count > 0 ? 1 : 0);
            batch.Add(record.Json);
        }

        return batch.Count > 0;
    }

    private async Task SendBatchAsync(List<string> records, CancellationToken token)
    {
        var request = new LogRequest(_endpoint, _headers, OtlpEncoder.Batch(_prefix, records));

        for (var attempt = 1; attempt <= MaxAttempts; attempt++)
        {
            if (IsPaused(_clock()))
            {
                Drop(records.Count);
                return;
            }

            LogSendResult result;
            try
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
                timeout.CancelAfter(RequestTimeout);
                result = await _sender!.SendAsync(request, timeout.Token).ConfigureAwait(false);
            }
            catch when (token.IsCancellationRequested)
            {
                Drop(records.Count);
                return;
            }
            catch
            {
                // Network error or per-request timeout: 1 s, then 5 s.
                if (attempt == MaxAttempts || !await WaitAsync(NetworkBackoff[attempt - 1], token).ConfigureAwait(false))
                {
                    Drop(records.Count);
                    return;
                }

                continue;
            }

            var status = result?.StatusCode ?? 0;
            if (status is >= 200 and < 300)
            {
                return;
            }

            if (status is 401 or 403)
            {
                Interlocked.Exchange(ref _pausedUntilTicks, (_clock() + AuthPause).UtcTicks);
                Drop(records.Count);
                return;
            }

            if (status is 429 or 503)
            {
                if (attempt == MaxAttempts ||
                    !await WaitAsync(ParseRetryAfter(result?.RetryAfter), token).ConfigureAwait(false))
                {
                    Drop(records.Count);
                    return;
                }

                continue;
            }

            // 400 / 413 / 415 and anything else: the batch will not get better by retrying.
            Drop(records.Count);
            return;
        }
    }

    private async Task<bool> WaitAsync(TimeSpan delay, CancellationToken token)
    {
        try
        {
            await _delay(delay, token).ConfigureAwait(false);
            return !token.IsCancellationRequested;
        }
        catch
        {
            return false;
        }
    }

    internal static TimeSpan ParseRetryAfter(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) ||
            !double.TryParse(value.Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out var seconds) ||
            double.IsNaN(seconds) || seconds < 0)
        {
            return DefaultRetryAfter;
        }

        var delay = seconds >= MaxRetryAfter.TotalSeconds ? MaxRetryAfter : TimeSpan.FromSeconds(seconds);
        return delay;
    }

    private static string? Env(string name)
    {
        try
        {
            return System.Environment.GetEnvironmentVariable(name);
        }
        catch
        {
            return null;
        }
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

    private readonly record struct QueuedRecord(string Json, int Bytes);
}
