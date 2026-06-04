using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;
using Callisto.Sdk.Errors;

namespace Callisto.Sdk.Reporting;

/// <summary>
/// Abstraction over the HTTP send that delivers a serialized event to the DSN. Injectable so
/// tests can supply a fake sender. Implementations must signal success only on HTTP 202.
/// </summary>
public interface IErrorSender
{
    /// <summary>
    /// Posts the serialized JSON <paramref name="payload"/> to <paramref name="dsn"/>. Returns
    /// <c>true</c> when the ingest endpoint responded <c>202</c>, otherwise <c>false</c>. Must
    /// not throw; the reporter swallows all failures regardless.
    /// </summary>
    bool Send(string dsn, string payload);
}

/// <summary>
/// Built-in, Sentry-style error reporter. Captures exceptions/messages and delivers them to the
/// Callisto ingest DSN on a background worker. Best-effort: never throws, never blocks the
/// caller's error path, and never transmits credentials or the outgoing request body.
/// </summary>
public sealed class ErrorReporter : IDisposable
{
    private static readonly HashSet<string> AllowedLevels =
        new(StringComparer.Ordinal) { "fatal", "error", "warning", "info" };

    private readonly string? _dsn;
    private readonly string _sdkName;
    private readonly string _sdkVersion;
    private readonly string _sdkLanguage;
    private readonly string? _environment;
    private readonly IErrorSender _sender;
    private readonly bool _enabled;

    private readonly Channel<string>? _channel;
    private readonly Task? _worker;
    private int _pending;

    private IDictionary<string, object?>? _user;
    private readonly object _userLock = new();

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
    };

    /// <summary>
    /// Creates a reporter. When <paramref name="dsn"/> is null/blank/not a well-formed URL the
    /// reporter is disabled and every method is a no-op.
    /// </summary>
    /// <param name="dsn">The full ingest POST URL.</param>
    /// <param name="environment">Optional environment tag.</param>
    /// <param name="sender">Injectable sender; defaults to a dedicated <see cref="HttpClient"/>.</param>
    /// <param name="sdkName">SDK name for <c>context.sdk.name</c>.</param>
    /// <param name="sdkVersion">SDK version; resolved from the assembly when null.</param>
    /// <param name="sdkLanguage">SDK language for <c>context.sdk.language</c>.</param>
    public ErrorReporter(
        string? dsn,
        string? environment = null,
        IErrorSender? sender = null,
        string sdkName = "Callisto.Sdk",
        string? sdkVersion = null,
        string sdkLanguage = "csharp")
    {
        _dsn = string.IsNullOrWhiteSpace(dsn) ? null : dsn;
        _environment = string.IsNullOrWhiteSpace(environment) ? null : environment;
        _sdkName = sdkName;
        _sdkVersion = sdkVersion ?? ResolveVersion();
        _sdkLanguage = sdkLanguage;
        _sender = sender ?? new HttpErrorSender();

        _enabled = _dsn is not null && Uri.IsWellFormedUriString(_dsn, UriKind.Absolute);

        if (_enabled)
        {
            _channel = Channel.CreateUnbounded<string>(new UnboundedChannelOptions
            {
                SingleReader = true,
            });
            _worker = Task.Run(WorkerLoopAsync);
        }
    }

    /// <summary>Whether the reporter is active (a valid DSN was supplied).</summary>
    public bool Enabled => _enabled;

    /// <summary>Sets or clears the user context attached to subsequent events.</summary>
    public void SetUser(IDictionary<string, object?>? user)
    {
        lock (_userLock)
        {
            _user = user;
        }
    }

    /// <summary>Captures an exception and enqueues a background send. Never throws.</summary>
    public void CaptureException(
        Exception ex,
        string level = "error",
        IDictionary<string, object?>? extra = null)
    {
        if (!_enabled)
        {
            return;
        }

        try
        {
            var payload = BuildExceptionPayload(ex, level, extra);
            Enqueue(payload);
        }
        catch
        {
            // Never let capturing surface an error.
        }
    }

    /// <summary>Captures a plain message and enqueues a background send. Never throws.</summary>
    public void CaptureMessage(
        string message,
        string level = "info",
        IDictionary<string, object?>? extra = null)
    {
        if (!_enabled)
        {
            return;
        }

        try
        {
            var payload = new Dictionary<string, object?>
            {
                ["message"] = message,
                ["level"] = NormalizeLevel(level),
                ["context"] = BuildContext(extra, null),
            };
            AttachUser(payload);
            Enqueue(JsonSerializer.Serialize(payload, JsonOptions));
        }
        catch
        {
            // swallow
        }
    }

    /// <summary>Drains pending sends with a short bound. Best-effort.</summary>
    public void Flush()
    {
        if (!_enabled || _channel is null)
        {
            return;
        }

        // Wait briefly for in-flight events to drain.
        var deadline = DateTime.UtcNow.AddSeconds(2);
        while (DateTime.UtcNow < deadline)
        {
            if (Volatile.Read(ref _pending) == 0)
            {
                break;
            }

            Thread.Sleep(10);
        }
    }

    /// <summary>Flushes and stops the background worker.</summary>
    public void Dispose()
    {
        if (!_enabled || _channel is null || _worker is null)
        {
            return;
        }

        try
        {
            Flush();
            _channel.Writer.TryComplete();
            _worker.Wait(TimeSpan.FromSeconds(2));
        }
        catch
        {
            // best-effort
        }
    }

    private void Enqueue(string payload)
    {
        if (_channel is null)
        {
            return;
        }

        Interlocked.Increment(ref _pending);
        if (!_channel.Writer.TryWrite(payload))
        {
            Interlocked.Decrement(ref _pending);
        }
    }

    private async Task WorkerLoopAsync()
    {
        if (_channel is null || _dsn is null)
        {
            return;
        }

        try
        {
            while (await _channel.Reader.WaitToReadAsync().ConfigureAwait(false))
            {
                while (_channel.Reader.TryRead(out var payload))
                {
                    try
                    {
                        _sender.Send(_dsn, payload);
                    }
                    catch
                    {
                        // Swallow all sender failures; never re-capture.
                    }
                    finally
                    {
                        Interlocked.Decrement(ref _pending);
                    }
                }
            }
        }
        catch
        {
            // Worker must never crash the process.
        }
    }

    private string BuildExceptionPayload(
        Exception ex,
        string level,
        IDictionary<string, object?>? extra)
    {
        var payload = new Dictionary<string, object?>
        {
            ["message"] = ex.Message,
            ["type"] = ex.GetType().Name,
            ["level"] = NormalizeLevel(level),
        };

        // Transport-originated errors carry method/path in extra so we can set culprit/request.
        string? method = null;
        string? path = null;
        if (extra is not null)
        {
            if (extra.TryGetValue("__method", out var m) && m is string ms)
            {
                method = ms;
            }

            if (extra.TryGetValue("__path", out var p) && p is string ps)
            {
                path = ps;
            }
        }

        // Source context only for genuine application exceptions: a transport call site can embed
        // the outgoing request body as literal arguments, which would violate the hard
        // no-request-body guarantee. Transport errors already carry method/path as their culprit.
        var frames = BuildStacktrace(ex, withSource: method is null || path is null);

        if (method is not null && path is not null)
        {
            payload["culprit"] = $"{method} {path}";
            payload["request"] = new Dictionary<string, object?>
            {
                ["method"] = method,
                ["path"] = path,
            };
        }
        else
        {
            var culprit = TopFrameCulprit(frames);
            if (culprit is not null)
            {
                payload["culprit"] = culprit;
            }
        }

        if (frames.Count > 0)
        {
            payload["stacktrace"] = frames;
        }

        payload["context"] = BuildContext(extra, ex);
        AttachUser(payload);

        return JsonSerializer.Serialize(payload, JsonOptions);
    }

    private Dictionary<string, object?> BuildContext(
        IDictionary<string, object?>? extra,
        Exception? ex)
    {
        var sdk = new Dictionary<string, object?>
        {
            ["name"] = _sdkName,
            ["version"] = _sdkVersion,
            ["language"] = _sdkLanguage,
        };

        var context = new Dictionary<string, object?>
        {
            ["sdk"] = sdk,
        };

        if (_environment is not null)
        {
            context["environment"] = _environment;
        }

        if (ex is CallistoException ce)
        {
            context["status_code"] = ce.StatusCode;
            if (ce.Body is not null)
            {
                context["body"] = ce.Body;
            }

            if (ce is RateLimitException rle && rle.RetryAfter is not null)
            {
                context["retry_after"] = rle.RetryAfter;
            }
        }

        if (extra is not null)
        {
            foreach (var kvp in extra)
            {
                // Internal routing keys are not part of the public context.
                if (kvp.Key is "__method" or "__path")
                {
                    continue;
                }

                context[kvp.Key] = kvp.Value;
            }
        }

        return context;
    }

    private void AttachUser(Dictionary<string, object?> payload)
    {
        IDictionary<string, object?>? user;
        lock (_userLock)
        {
            user = _user;
        }

        if (user is not null && user.Count > 0)
        {
            payload["user"] = user;
        }
    }

    private static readonly Regex FrameRegex =
        new(@"^\s*at\s+(?<function>.+?)(?:\s+in\s+(?<file>.+):line\s+(?<line>\d+))?\s*$",
            RegexOptions.Compiled);

    /// <summary>Source lines captured on each side of a frame's error line.</summary>
    private const int SourceContextLines = 5;

    /// <summary>Skip source capture for files larger than this (bytes).</summary>
    private const long MaxSourceBytes = 2_000_000;

    private static List<Dictionary<string, object?>> BuildStacktrace(Exception ex, bool withSource)
    {
        var frames = new List<Dictionary<string, object?>>();
        var trace = ex.StackTrace;
        if (string.IsNullOrEmpty(trace))
        {
            try
            {
                trace = System.Environment.StackTrace;
            }
            catch
            {
                trace = null;
            }
        }

        if (string.IsNullOrEmpty(trace))
        {
            return frames;
        }

        foreach (var rawLine in trace.Split('\n'))
        {
            var line = rawLine.TrimEnd('\r').Trim();
            if (line.Length == 0)
            {
                continue;
            }

            var match = FrameRegex.Match(line);
            if (!match.Success)
            {
                continue;
            }

            var frame = new Dictionary<string, object?>
            {
                ["function"] = match.Groups["function"].Value.Trim(),
            };

            if (match.Groups["file"].Success)
            {
                frame["file"] = match.Groups["file"].Value.Trim();
            }

            if (match.Groups["line"].Success &&
                int.TryParse(match.Groups["line"].Value, out var lineNo))
            {
                frame["line"] = lineNo;
            }

            if (withSource &&
                frame.TryGetValue("file", out var fileObj) && fileObj is string srcFile &&
                frame.TryGetValue("line", out var lineObj) && lineObj is int srcLine)
            {
                AttachSource(frame, srcFile, srcLine);
            }

            frames.Add(frame);
        }

        return frames;
    }

    /// <summary>
    /// Best-effort: attach a <c>pre_context</c>/<c>context_line</c>/<c>post_context</c> window (up
    /// to <see cref="SourceContextLines"/> lines each side) around the frame's error line, so the
    /// dashboard can render the failing code with the error line in focus.
    /// </summary>
    /// <remarks>
    /// C# stack frames only expose a file path and line number when debug symbols (PDBs) are
    /// present and the source is on disk — typical in dev / debug builds. When the source is absent
    /// or unreadable the frame simply renders without a window.
    /// </remarks>
    private static void AttachSource(Dictionary<string, object?> frame, string file, int line)
    {
        if (string.IsNullOrEmpty(file) || line < 1)
        {
            return;
        }

        try
        {
            if (!File.Exists(file) || new FileInfo(file).Length > MaxSourceBytes)
            {
                return;
            }

            var lines = File.ReadAllLines(file);
            if (line > lines.Length)
            {
                return;
            }

            var index = line - 1;
            var start = Math.Max(0, index - SourceContextLines);
            var end = Math.Min(lines.Length, index + 1 + SourceContextLines);

            var pre = new List<string>();
            for (var i = start; i < index; i++)
            {
                pre.Add(lines[i]);
            }

            var post = new List<string>();
            for (var i = index + 1; i < end; i++)
            {
                post.Add(lines[i]);
            }

            frame["pre_context"] = pre;
            frame["context_line"] = lines[index];
            frame["post_context"] = post;
        }
        catch
        {
            // Best-effort: unreadable / decoding error → leave the frame without a window.
        }
    }

    private static string? TopFrameCulprit(List<Dictionary<string, object?>> frames)
    {
        if (frames.Count == 0)
        {
            return null;
        }

        var top = frames[0];
        var function = top.TryGetValue("function", out var f) ? f as string : null;
        if (string.IsNullOrEmpty(function))
        {
            return null;
        }

        if (top.TryGetValue("file", out var fileObj) && fileObj is string file &&
            top.TryGetValue("line", out var lineObj) && lineObj is int line)
        {
            return $"{function} ({file}:{line})";
        }

        return function;
    }

    private static string NormalizeLevel(string? level)
    {
        if (level is not null)
        {
            var normalized = level.Trim().ToLowerInvariant();
            if (AllowedLevels.Contains(normalized))
            {
                return normalized;
            }
        }

        return "error";
    }

    private static string ResolveVersion()
    {
        try
        {
            var asm = typeof(ErrorReporter).Assembly;
            var info = asm.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
            if (!string.IsNullOrEmpty(info))
            {
                // Strip build metadata (e.g. "0.1.0+abcdef").
                var plus = info.IndexOf('+');
                return plus >= 0 ? info.Substring(0, plus) : info;
            }

            return asm.GetName().Version?.ToString() ?? "0.0.0";
        }
        catch
        {
            return "0.0.0";
        }
    }

    /// <summary>Default sender: posts JSON to the DSN via a dedicated <see cref="HttpClient"/>.</summary>
    private sealed class HttpErrorSender : IErrorSender
    {
        private static readonly HttpClient Client = new() { Timeout = TimeSpan.FromSeconds(5) };

        public bool Send(string dsn, string payload)
        {
            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Post, dsn)
                {
                    Content = new StringContent(payload, Encoding.UTF8, "application/json"),
                };
                using var response = Client.Send(request);
                return (int)response.StatusCode == 202;
            }
            catch
            {
                return false;
            }
        }
    }
}
