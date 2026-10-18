using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Callisto.Sdk.Logs;
using Xunit;

namespace Callisto.Sdk.Tests;

/// <summary>
/// A scriptable <see cref="ILogSender"/>: records every request and answers from a queue of
/// responses (200 once the queue is empty). A response of <c>null</c> throws a network error.
/// An optional gate holds the first send until released.
/// </summary>
public sealed class FakeLogSender : ILogSender
{
    private readonly ConcurrentQueue<LogSendResult?> _responses = new();
    private readonly object _lock = new();
    private readonly List<LogRequest> _requests = new();

    public ManualResetEventSlim? Gate { get; set; }
    public ManualResetEventSlim Entered { get; } = new(false);

    public FakeLogSender(params LogSendResult?[] responses)
    {
        foreach (var response in responses)
        {
            _responses.Enqueue(response);
        }
    }

    public IReadOnlyList<LogRequest> Requests
    {
        get
        {
            lock (_lock)
            {
                return _requests.ToList();
            }
        }
    }

    public IEnumerable<JsonNode> Bodies => Requests.Select(r => JsonNode.Parse(r.Body)!);

    public IEnumerable<JsonArray> RecordBatches
        => Bodies.Select(b => b["resourceLogs"]![0]!["scopeLogs"]![0]!["logRecords"]!.AsArray());

    public IEnumerable<JsonNode> Records => RecordBatches.SelectMany(b => b.Select(r => r!));

    public Task<LogSendResult> SendAsync(LogRequest request, CancellationToken cancellationToken)
    {
        lock (_lock)
        {
            _requests.Add(request);
        }

        Entered.Set();
        Gate?.Wait(TimeSpan.FromSeconds(10));

        if (_responses.TryDequeue(out var response))
        {
            if (response is null)
            {
                throw new HttpRequestException("network down");
            }

            return Task.FromResult(response);
        }

        return Task.FromResult(new LogSendResult(200));
    }
}

/// <summary>Captures the real HTTP request the default sender builds.</summary>
public sealed class CapturingLogHandler : HttpMessageHandler
{
    public HttpRequestMessage? Request { get; private set; }
    public string? Body { get; private set; }
    public string? ContentType { get; private set; }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        Request = request;
        Body = request.Content is null ? null : await request.Content.ReadAsStringAsync(cancellationToken);
        ContentType = request.Content?.Headers.ContentType?.ToString();
        return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{}") };
    }
}

public class LogsTests
{
    internal const string Pk = "pk_00112233445566778899aabbccddeeff";
    internal const string Sk = "sk_000102030405060708090a0b0c0d0e0f1011121314151617";
    internal static readonly DateTimeOffset FixedTime = DateTimeOffset.FromUnixTimeSeconds(1760745600);

    internal static CallistoLogger Make(
        ILogSender sender,
        List<TimeSpan>? delays = null,
        Func<DateTimeOffset>? clock = null,
        string? service = "checkout",
        string? environment = "production",
        TimeSpan? interval = null)
        => new(new CallistoLoggerOptions
        {
            LogsAccessKey = Pk,
            LogsSecret = Sk,
            ServiceName = service,
            Environment = environment,
            Sender = sender,
            Clock = clock ?? (() => FixedTime),
            Delay = (delay, _) =>
            {
                lock (delays ?? new List<TimeSpan>())
                {
                    delays?.Add(delay);
                }

                return Task.CompletedTask;
            },
            FlushInterval = interval ?? TimeSpan.FromHours(1),
        });

    private static JsonNode OnlyRecord(FakeLogSender sender) => Assert.Single(sender.Records);

    private static JsonNode? Attr(JsonNode record, string key)
        => record["attributes"]?.AsArray().FirstOrDefault(a => (string?)a!["key"] == key)?["value"];

    private static bool WaitFor(Func<bool> condition, int ms = 3000)
    {
        var until = DateTime.UtcNow.AddMilliseconds(ms);
        while (DateTime.UtcNow < until)
        {
            if (condition())
            {
                return true;
            }

            Thread.Sleep(5);
        }

        return condition();
    }

    // 1. Golden payload -------------------------------------------------------------------------

    [Fact]
    public void GoldenPayloadMatchesTheSpec()
    {
        var sender = new FakeLogSender();
        using (var logger = Make(sender))
        {
            logger.Info("order paid", new Dictionary<string, object?>
            {
                ["order_id"] = 42,
                ["paid"] = true,
                ["trace_id"] = "5B8EFFF798038103D269B633813FC60C",
                ["span_id"] = "EEE19B7EC3C1B174",
            });
            logger.Flush();
        }

        var expected = JsonNode.Parse($$"""
        {
          "resourceLogs": [{
            "resource": { "attributes": [
              { "key": "service.name", "value": { "stringValue": "checkout" } },
              { "key": "deployment.environment.name", "value": { "stringValue": "production" } },
              { "key": "telemetry.sdk.name", "value": { "stringValue": "callisto-sdk" } },
              { "key": "telemetry.sdk.language", "value": { "stringValue": "dotnet" } },
              { "key": "telemetry.sdk.version", "value": { "stringValue": "{{SdkInfo.Version}}" } }
            ]},
            "scopeLogs": [{
              "scope": { "name": "callisto-sdk", "version": "{{SdkInfo.Version}}" },
              "logRecords": [{
                "timeUnixNano": "1760745600000000000",
                "observedTimeUnixNano": "1760745600000000000",
                "severityNumber": 9,
                "severityText": "INFO",
                "body": { "stringValue": "order paid" },
                "attributes": [
                  { "key": "order_id", "value": { "intValue": "42" } },
                  { "key": "paid", "value": { "boolValue": true } }
                ],
                "traceId": "5b8efff798038103d269b633813fc60c",
                "spanId": "eee19b7ec3c1b174"
              }]
            }]
          }]
        }
        """);

        var request = Assert.Single(sender.Requests);
        var actual = JsonNode.Parse(request.Body);
        Assert.True(JsonNode.DeepEquals(expected, actual), $"Body differs:\n{request.Body}");
    }

    [Fact]
    public void EnvironmentAttributeIsOmittedWhenUnset()
    {
        var sender = new FakeLogSender();
        using (var logger = Make(sender, environment: null))
        {
            logger.Info("x");
            logger.Flush();
        }

        var body = Assert.Single(sender.Requests).Body;
        Assert.DoesNotContain("deployment.environment.name", body);
        var record = OnlyRecord(sender);
        Assert.Null(record["attributes"]);
        Assert.Null(record["traceId"]);
        Assert.Null(record["spanId"]);
    }

    [Fact]
    public void SdkVersionConstantMatchesThePackageVersion()
    {
        var info = typeof(CallistoLogger).Assembly
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()!.InformationalVersion;
        Assert.Equal(SdkInfo.Version, info.Split('+')[0]);
        Assert.Equal("callisto-sdk-dotnet/" + SdkInfo.Version, SdkInfo.UserAgent);
    }

    // 2. Auth header and endpoint ---------------------------------------------------------------

    [Fact]
    public void RequestCarriesBasicAuthWithTheSharedVectorAndHeaders()
    {
        var sender = new FakeLogSender();
        using (var logger = new CallistoLogger(new CallistoLoggerOptions
        {
            LogsAccessKey = Pk,
            LogsSecret = Sk,
            LogsEndpoint = "https://logs.example.test/v1/logs",
            Sender = sender,
        }))
        {
            logger.Info("hello");
            logger.Flush();
        }

        var request = Assert.Single(sender.Requests);
        var expected = "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(Pk + ":" + Sk));
        Assert.Equal(
            "Basic cGtfMDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmY6c2tfMDAwMTAyMDMwNDA1MDYwNzA4MDkwYTBiMGMwZDBlMGYxMDExMTIxMzE0MTUxNjE3",
            expected);
        Assert.Equal(expected, request.Headers["Authorization"]);
        Assert.Equal("application/json", request.Headers["Content-Type"]);
        Assert.Equal("callisto-sdk-dotnet/" + SdkInfo.Version, request.Headers["User-Agent"]);
        Assert.Equal("https://logs.example.test/v1/logs", request.Endpoint);
    }

    [Fact]
    public void DefaultHttpSenderPostsToTheEndpointWithItsOwnHeaders()
    {
        var handler = new CapturingLogHandler();
        using (var logger = new CallistoLogger(new CallistoLoggerOptions
        {
            LogsAccessKey = Pk,
            LogsSecret = Sk,
            LogsEndpoint = "https://logs.example.test/v1/logs",
            HttpMessageHandler = handler,
        }))
        {
            Assert.Equal("https://logs.example.test/v1/logs", logger.Endpoint);
            logger.Info("hello");
            logger.Flush();
        }

        Assert.NotNull(handler.Request);
        Assert.Equal(HttpMethod.Post, handler.Request!.Method);
        Assert.Equal("https://logs.example.test/v1/logs", handler.Request.RequestUri!.ToString());
        Assert.Equal("Basic", handler.Request.Headers.Authorization!.Scheme);
        Assert.Equal(
            Convert.ToBase64String(Encoding.UTF8.GetBytes(Pk + ":" + Sk)),
            handler.Request.Headers.Authorization.Parameter);
        Assert.Equal("application/json", handler.ContentType);
        Assert.Equal("callisto-sdk-dotnet/" + SdkInfo.Version, handler.Request.Headers.UserAgent.ToString());
        Assert.Contains("\"hello\"", handler.Body);
    }

    [Fact]
    public void DefaultEndpointIsCallistoLogs()
    {
        using var logger = new CallistoLogger(new CallistoLoggerOptions
        {
            LogsAccessKey = Pk,
            LogsSecret = Sk,
            Sender = new FakeLogSender(),
        });
        Assert.Equal("https://logs.callistosignal.com/v1/logs", logger.Endpoint);
        Assert.Equal(CallistoLogger.DefaultEndpoint, logger.Endpoint);
    }

    // 3. Severity and AnyValue mapping ----------------------------------------------------------

    [Theory]
    [InlineData("debug", 5, "DEBUG")]
    [InlineData("info", 9, "INFO")]
    [InlineData("warn", 13, "WARN")]
    [InlineData("error", 17, "ERROR")]
    [InlineData("fatal", 21, "FATAL")]
    public void SeverityMappingForEveryLevel(string level, int number, string text)
    {
        var sender = new FakeLogSender();
        using (var logger = Make(sender))
        {
            switch (level)
            {
                case "debug": logger.Debug("m"); break;
                case "info": logger.Info("m"); break;
                case "warn": logger.Warn("m"); break;
                case "error": logger.Error("m"); break;
                case "fatal": logger.Fatal("m"); break;
            }

            logger.Log(level.ToUpperInvariant(), "m");
            Assert.True(Enum.TryParse<CallistoLogLevel>(level, ignoreCase: true, out var parsed));
            logger.Log(parsed, "m");
            logger.Flush();
        }

        var records = sender.Records.ToList();
        Assert.Equal(3, records.Count);
        Assert.All(records, r =>
        {
            Assert.Equal(number, (int)r["severityNumber"]!);
            Assert.Equal(text, (string?)r["severityText"]);
        });
    }

    [Fact]
    public void UnknownStringLevelLogsAsInfo()
    {
        var sender = new FakeLogSender();
        using (var logger = Make(sender))
        {
            logger.Log("verbose", "m");
            logger.Flush();
        }

        Assert.Equal("INFO", (string?)OnlyRecord(sender)["severityText"]);
    }

    [Fact]
    public void AnyValueMapping()
    {
        var sender = new FakeLogSender();
        using (var logger = Make(sender))
        {
            logger.Info("types", new Dictionary<string, object?>
            {
                ["s"] = "text",
                ["b"] = false,
                ["i"] = 42,
                ["l"] = 9_007_199_254_740_993L,
                ["neg"] = (short)-7,
                ["d"] = 1.5,
                ["f"] = 0.25f,
                ["nan"] = double.NaN,
                ["inf"] = double.PositiveInfinity,
                ["ninf"] = double.NegativeInfinity,
                ["null"] = null,
                ["list"] = new object?[] { 1, "two", null, new[] { true } },
                ["map"] = new Dictionary<string, object?> { ["a"] = 1, ["gone"] = null, ["b"] = new Dictionary<string, int> { ["c"] = 3 } },
                ["guid"] = Guid.Parse("0f8fad5b-d9cb-469f-a165-70867728950e"),
            });
            logger.Flush();
        }

        var record = OnlyRecord(sender);
        Assert.Equal("text", (string?)Attr(record, "s")!["stringValue"]);
        Assert.False((bool)Attr(record, "b")!["boolValue"]!);
        Assert.Equal("42", (string?)Attr(record, "i")!["intValue"]);
        Assert.Equal(JsonValueKind.String, Attr(record, "i")!["intValue"]!.GetValueKind());
        Assert.Equal("9007199254740993", (string?)Attr(record, "l")!["intValue"]);
        Assert.Equal("-7", (string?)Attr(record, "neg")!["intValue"]);
        Assert.Equal(1.5, (double)Attr(record, "d")!["doubleValue"]!);
        Assert.Equal(JsonValueKind.Number, Attr(record, "d")!["doubleValue"]!.GetValueKind());
        Assert.Equal(0.25, (double)Attr(record, "f")!["doubleValue"]!);
        Assert.Equal("NaN", (string?)Attr(record, "nan")!["stringValue"]);
        Assert.Equal("Infinity", (string?)Attr(record, "inf")!["stringValue"]);
        Assert.Equal("-Infinity", (string?)Attr(record, "ninf")!["stringValue"]);
        Assert.Null(Attr(record, "null"));
        Assert.Equal(13, record["attributes"]!.AsArray().Count);

        var list = Attr(record, "list")!["arrayValue"]!["values"]!.AsArray();
        Assert.Equal(4, list.Count);
        Assert.Equal("1", (string?)list[0]!["intValue"]);
        Assert.Equal("two", (string?)list[1]!["stringValue"]);
        Assert.Empty(list[2]!.AsObject());
        Assert.True((bool)list[3]!["arrayValue"]!["values"]![0]!["boolValue"]!);

        var map = Attr(record, "map")!["kvlistValue"]!["values"]!.AsArray();
        Assert.Equal(new[] { "a", "b" }, map.Select(e => (string?)e!["key"]).ToArray());
        Assert.Equal("1", (string?)map[0]!["value"]!["intValue"]);
        var nested = map[1]!["value"]!["kvlistValue"]!["values"]!.AsArray();
        Assert.Equal("c", (string?)nested[0]!["key"]);
        Assert.Equal("3", (string?)nested[0]!["value"]!["intValue"]);

        Assert.Equal("0f8fad5b-d9cb-469f-a165-70867728950e", (string?)Attr(record, "guid")!["stringValue"]);
    }

    [Fact]
    public void NestingDeeperThanFiveLevelsIsStringified()
    {
        object deep = new Dictionary<string, object?> { ["leaf"] = 1 };
        for (var i = 0; i < 6; i++)
        {
            deep = new Dictionary<string, object?> { ["n"] = deep };
        }

        var sender = new FakeLogSender();
        using (var logger = Make(sender))
        {
            logger.Info("deep", new Dictionary<string, object?> { ["deep"] = deep });
            logger.Flush();
        }

        var value = Attr(OnlyRecord(sender), "deep")!;
        var levels = 0;
        while (value["kvlistValue"] is { } kv)
        {
            levels++;
            value = kv["values"]![0]!["value"]!;
        }

        Assert.Equal(5, levels);
        var text = (string?)value["stringValue"];
        Assert.NotNull(text);
        Assert.Contains("leaf", text);
    }

    // 4. trace_id / span_id ---------------------------------------------------------------------

    [Theory]
    [InlineData("not-a-trace-id")]
    [InlineData("00000000000000000000000000000000")]
    [InlineData("5b8efff798038103d269b633813fc60")]
    [InlineData("5b8efff798038103d269b633813fc60g")]
    public void InvalidTraceIdStaysAnAttribute(string traceId)
    {
        var sender = new FakeLogSender();
        using (var logger = Make(sender))
        {
            logger.Info("m", new Dictionary<string, object?> { ["trace_id"] = traceId, ["span_id"] = "0000000000000000" });
            logger.Flush();
        }

        var record = OnlyRecord(sender);
        Assert.Null(record["traceId"]);
        Assert.Null(record["spanId"]);
        Assert.Equal(traceId, (string?)Attr(record, "trace_id")!["stringValue"]);
        Assert.Equal("0000000000000000", (string?)Attr(record, "span_id")!["stringValue"]);
    }

    [Fact]
    public void NonStringTraceIdStaysAnAttribute()
    {
        var sender = new FakeLogSender();
        using (var logger = Make(sender))
        {
            logger.Info("m", new Dictionary<string, object?> { ["trace_id"] = 12345 });
            logger.Flush();
        }

        var record = OnlyRecord(sender);
        Assert.Null(record["traceId"]);
        Assert.Equal("12345", (string?)Attr(record, "trace_id")!["intValue"]);
    }

    // 5. Batching -------------------------------------------------------------------------------

    [Fact]
    public void HundredQueuedRecordsTriggerASendWithoutFlush()
    {
        var sender = new FakeLogSender();
        using var logger = Make(sender);

        for (var i = 0; i < 99; i++)
        {
            logger.Info("m" + i);
        }

        Thread.Sleep(50);
        Assert.Empty(sender.Requests);

        logger.Info("m99");
        Assert.True(WaitFor(() => sender.Requests.Count == 1));
        Assert.Equal(100, sender.Records.Count());
    }

    [Fact]
    public void FlushSendsTheRest()
    {
        var sender = new FakeLogSender();
        using var logger = Make(sender);

        for (var i = 0; i < 7; i++)
        {
            logger.Info("m" + i);
        }

        Assert.Empty(sender.Requests);
        logger.Flush();
        Assert.Single(sender.Requests);
        Assert.Equal(7, sender.Records.Count());
    }

    [Fact]
    public async Task FlushAsyncSendsTheRest()
    {
        var sender = new FakeLogSender();
        await using var logger = Make(sender);
        logger.Warn("async");
        await logger.FlushAsync(CancellationToken.None);
        Assert.Single(sender.Requests);
    }

    [Fact]
    public void TimerSendsWithoutFlush()
    {
        var sender = new FakeLogSender();
        using var logger = Make(sender, interval: TimeSpan.FromMilliseconds(30));
        logger.Info("tick");
        Assert.True(WaitFor(() => sender.Requests.Count == 1));
    }

    [Fact]
    public void BatchIsCutAt500Records()
    {
        var gate = new ManualResetEventSlim(false);
        var sender = new FakeLogSender { Gate = gate };
        using var logger = Make(sender);

        for (var i = 0; i < 100; i++)
        {
            logger.Info("first" + i);
        }

        Assert.True(sender.Entered.Wait(TimeSpan.FromSeconds(3)));
        for (var i = 0; i < 1100; i++)
        {
            logger.Info("next" + i);
        }

        gate.Set();
        logger.Flush();

        var sizes = sender.RecordBatches.Select(b => b.Count).ToArray();
        Assert.Equal(new[] { 100, 500, 500, 100 }, sizes);
        Assert.Equal(0, logger.DroppedCount);
    }

    [Fact]
    public void BatchIsCutAtAboutOneMegabyte()
    {
        var sender = new FakeLogSender();
        using (var logger = Make(sender))
        {
            var big = new string('x', 20_000);
            for (var i = 0; i < 60; i++)
            {
                logger.Info(big);
            }

            logger.Flush();
        }

        Assert.True(sender.Requests.Count >= 2);
        Assert.All(sender.Requests, r => Assert.True(Encoding.UTF8.GetByteCount(r.Body) <= 1_000_000));
        Assert.Equal(60, sender.Records.Count());
    }

    // 6. Size guards ----------------------------------------------------------------------------

    [Fact]
    public void BodyAndStringAttributesAreTruncated()
    {
        var sender = new FakeLogSender();
        using (var logger = Make(sender))
        {
            logger.Info(new string('b', 40_000), new Dictionary<string, object?>
            {
                ["long"] = new string('a', 10_000),
                ["short"] = "ok",
                ["nested"] = new[] { new string('n', 9_000) },
            });
            logger.Flush();
        }

        var record = OnlyRecord(sender);
        var body = (string)record["body"]!["stringValue"]!;
        Assert.Equal(32_768, body.Length);
        Assert.EndsWith("…", body);
        Assert.StartsWith("bbbb", body);

        var attr = (string)Attr(record, "long")!["stringValue"]!;
        Assert.Equal(8_192, attr.Length);
        Assert.EndsWith("…", attr);
        Assert.Equal("ok", (string?)Attr(record, "short")!["stringValue"]);

        var nested = (string)Attr(record, "nested")!["arrayValue"]!["values"]![0]!["stringValue"]!;
        Assert.Equal(8_192, nested.Length);
    }

    [Fact]
    public void AtMost128AttributesPerRecord()
    {
        var attributes = new Dictionary<string, object?>();
        for (var i = 0; i < 200; i++)
        {
            attributes["k" + i] = i;
        }

        var sender = new FakeLogSender();
        using (var logger = Make(sender))
        {
            logger.Info("many", attributes);
            logger.Flush();
        }

        var list = OnlyRecord(sender)["attributes"]!.AsArray();
        Assert.Equal(128, list.Count);
        Assert.Equal("k0", (string?)list[0]!["key"]);
        Assert.Equal("k127", (string?)list[127]!["key"]);
    }

    // 7. Responses and retry --------------------------------------------------------------------

    [Theory]
    [InlineData(400)]
    [InlineData(413)]
    [InlineData(415)]
    [InlineData(500)]
    public void RejectedBatchIsDroppedWithoutRetry(int status)
    {
        var delays = new List<TimeSpan>();
        var sender = new FakeLogSender(new LogSendResult(status));
        using var logger = Make(sender, delays);

        logger.Info("a");
        logger.Info("b");
        logger.Flush();

        Assert.Single(sender.Requests);
        Assert.Empty(delays);
        Assert.Equal(2, logger.DroppedCount);
    }

    [Fact]
    public void TooManyRequestsWithRetryAfterRetriesThenSucceeds()
    {
        var delays = new List<TimeSpan>();
        var sender = new FakeLogSender(new LogSendResult(429, "1"));
        using var logger = Make(sender, delays);

        logger.Info("a");
        logger.Flush();

        Assert.Equal(2, sender.Requests.Count);
        Assert.Equal(sender.Requests[0].Body, sender.Requests[1].Body);
        Assert.Equal(new[] { TimeSpan.FromSeconds(1) }, delays);
        Assert.Equal(0, logger.DroppedCount);
    }

    [Fact]
    public void ThreeFailedAttemptsDropTheBatch()
    {
        var delays = new List<TimeSpan>();
        var sender = new FakeLogSender(new LogSendResult(503), new LogSendResult(503, "120"), new LogSendResult(503, "2"));
        using var logger = Make(sender, delays);

        logger.Info("a");
        logger.Flush();

        Assert.Equal(3, sender.Requests.Count);
        // absent → 5 s default; 120 → capped at 60 s; no wait after the last attempt.
        Assert.Equal(new[] { TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(60) }, delays);
        Assert.Equal(1, logger.DroppedCount);
    }

    [Fact]
    public void NetworkErrorsRetryAfterOneThenFiveSecondsThenDrop()
    {
        var delays = new List<TimeSpan>();
        var sender = new FakeLogSender(null, null, null);
        using var logger = Make(sender, delays);

        logger.Info("a");
        logger.Flush();

        Assert.Equal(3, sender.Requests.Count);
        Assert.Equal(new[] { TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(5) }, delays);
        Assert.Equal(1, logger.DroppedCount);
    }

    [Fact]
    public void NetworkErrorThenSuccessDeliversTheBatch()
    {
        var sender = new FakeLogSender(new LogSendResult?[] { null });
        using var logger = Make(sender);
        logger.Info("a");
        logger.Flush();
        Assert.Equal(2, sender.Requests.Count);
        Assert.Equal(0, logger.DroppedCount);
    }

    [Theory]
    [InlineData(null, 5)]
    [InlineData("", 5)]
    [InlineData("soon", 5)]
    [InlineData("-3", 5)]
    [InlineData("0", 0)]
    [InlineData("1", 1)]
    [InlineData("60", 60)]
    [InlineData("3600", 60)]
    public void RetryAfterParsing(string? header, int seconds)
        => Assert.Equal(TimeSpan.FromSeconds(seconds), CallistoLogger.ParseRetryAfter(header));

    [Theory]
    [InlineData(401)]
    [InlineData(403)]
    public void AuthFailurePausesSendingForSixtySeconds(int status)
    {
        var now = FixedTime;
        var sender = new FakeLogSender(new LogSendResult(status));
        using var logger = Make(sender, clock: () => now);

        logger.Info("rejected");
        logger.Flush();
        Assert.Single(sender.Requests);
        Assert.Equal(1, logger.DroppedCount);

        now = FixedTime.AddSeconds(30);
        logger.Info("during pause");
        logger.Flush();
        Assert.Single(sender.Requests);
        Assert.Equal(2, logger.DroppedCount);

        now = FixedTime.AddSeconds(61);
        logger.Info("after pause");
        logger.Flush();
        Assert.Equal(2, sender.Requests.Count);
        Assert.Equal("after pause", (string?)sender.RecordBatches.Last()[0]!["body"]!["stringValue"]);
        Assert.Equal(2, logger.DroppedCount);
    }

    // 8. Full queue -----------------------------------------------------------------------------

    [Fact]
    public void FullQueueDropsAndCounts()
    {
        var gate = new ManualResetEventSlim(false);
        var sender = new FakeLogSender { Gate = gate };
        using var logger = Make(sender);

        for (var i = 0; i < 100; i++)
        {
            logger.Info("first");
        }

        Assert.True(sender.Entered.Wait(TimeSpan.FromSeconds(3)));

        // The worker is busy with the first batch: the queue fills to 2 000, then drops.
        for (var i = 0; i < 2_050; i++)
        {
            logger.Info("more");
        }

        Assert.Equal(50, logger.DroppedCount);
        gate.Set();
        logger.Flush();
        Assert.Equal(2_100, sender.Records.Count());
    }

    // 9. Disabled logger ------------------------------------------------------------------------

    [Fact]
    public void DisabledLoggerSendsNothingAndStartsNoTask()
    {
        var sender = new FakeLogSender();
        var logger = new CallistoLogger(new CallistoLoggerOptions { Sender = sender });

        Assert.False(logger.Enabled);
        Assert.False(logger.WorkerStarted);
        logger.Info("nothing", new Dictionary<string, object?> { ["a"] = 1 });
        logger.Log("error", "nothing");
        logger.Flush();
        logger.Dispose();
        logger.Dispose();

        Assert.Empty(sender.Requests);
        Assert.Equal(0, logger.DroppedCount);
    }

    [Fact]
    public void DefaultConstructorWithoutEnvIsDisabled()
    {
        // Assumes CALLISTO_LOGS_ACCESS_KEY / _SECRET are not set where the suite runs.
        if (System.Environment.GetEnvironmentVariable("CALLISTO_LOGS_ACCESS_KEY") is not null)
        {
            return;
        }

        using var logger = new CallistoLogger();
        Assert.False(logger.Enabled);
        Assert.False(logger.WorkerStarted);
    }

    [Fact]
    public void LogCallsAfterDisposeAreNoOps()
    {
        var sender = new FakeLogSender();
        var logger = Make(sender);
        logger.Info("before");
        logger.Dispose();
        Assert.Single(sender.Requests);

        logger.Info("after");
        logger.Flush();
        logger.Dispose();
        Assert.Single(sender.Requests);
        Assert.Equal(0, logger.DroppedCount);
    }

    [Fact]
    public void DisposeFlushesQueuedRecords()
    {
        var sender = new FakeLogSender();
        var logger = Make(sender);
        logger.Info("one");
        logger.Info("two");
        logger.Dispose();
        Assert.Equal(2, sender.Records.Count());
    }

    [Fact]
    public async Task DisposeIsBoundedByFlushTimeout()
    {
        var gate = new ManualResetEventSlim(false);
        var sender = new FakeLogSender { Gate = gate };
        var logger = new CallistoLogger(new CallistoLoggerOptions
        {
            LogsAccessKey = Pk,
            LogsSecret = Sk,
            Sender = sender,
            FlushTimeout = TimeSpan.FromMilliseconds(150),
            FlushInterval = TimeSpan.FromHours(1),
        });

        logger.Info("stuck");
        var watch = System.Diagnostics.Stopwatch.StartNew();
        await logger.DisposeAsync();
        watch.Stop();
        gate.Set();

        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(2), $"took {watch.Elapsed}");
    }

    [Fact]
    public void LogCallNeverThrows()
    {
        var sender = new FakeLogSender();
        using var logger = Make(sender);

        logger.Info(null!);
        logger.Info("bad value", new Dictionary<string, object?> { ["x"] = new ThrowsOnToString() });
        logger.Info("lone surrogate \ud800 here", new Dictionary<string, object?> { ["s"] = "\udc00" });
        logger.Flush();

        Assert.Equal(3, sender.Records.Count());
    }

    private sealed class ThrowsOnToString
    {
        public override string ToString() => throw new InvalidOperationException("boom");
    }

    // 10. Configuration errors ------------------------------------------------------------------

    [Theory]
    [InlineData(Pk, null)]
    [InlineData(null, Sk)]
    [InlineData("pk_00112233445566778899AABBCCDDEEFF", Sk)]
    [InlineData("pk_00112233", Sk)]
    [InlineData(Sk, Pk)]
    [InlineData(Pk, "sk_000102030405060708090a0b0c0d0e0f101112131415161")]
    [InlineData(Pk, "sk_000102030405060708090a0b0c0d0e0f1011121314151617\n")]
    public void HalfOrMalformedCredentialsFailFast(string? key, string? secret)
    {
        var ex = Assert.Throws<ArgumentException>(() => new CallistoLogger(new CallistoLoggerOptions
        {
            LogsAccessKey = key,
            LogsSecret = secret,
            Sender = new FakeLogSender(),
        }));

        Assert.DoesNotContain(Sk, ex.Message);
    }

    [Theory]
    [InlineData("logs.callistosignal.com/v1/logs")]
    [InlineData("ftp://logs.example.test/v1/logs")]
    [InlineData("not a url")]
    public void NonHttpEndpointFailsFast(string endpoint)
    {
        Assert.Throws<ArgumentException>(() => new CallistoLogger(new CallistoLoggerOptions
        {
            LogsAccessKey = Pk,
            LogsSecret = Sk,
            LogsEndpoint = endpoint,
            Sender = new FakeLogSender(),
        }));
    }

    [Fact]
    public void ClientWithMalformedLogsKeyFailsFast()
    {
        Assert.Throws<ArgumentException>(() => new CallistoClient(
            "cid", "key", logsAccessKey: Pk, logsSecret: "sk_nope", logSender: new FakeLogSender()));
    }

    // 11. Isolation -----------------------------------------------------------------------------

    [Fact]
    public void ClientLogsNeverCarryMessagingCredentials()
    {
        const string clientId = "client-id-7f3a91";
        const string apiKey = "api-key-c0ffee42";
        var logSender = new FakeLogSender();
        var messaging = new FakeHandler(HttpStatusCode.OK, "{\"id\":\"m1\",\"status\":\"pending\"}");

        using (var client = new CallistoClient(
            clientId,
            apiKey,
            baseUrl: "https://api.example.com/v1",
            handler: messaging,
            environment: "staging",
            logsAccessKey: Pk,
            logsSecret: Sk,
            serviceName: "billing",
            logSender: logSender))
        {
            Assert.True(client.Logs.Enabled);
            Assert.Equal("billing", client.Logs.ServiceName);
            Assert.Equal("staging", client.Logs.Environment);

            client.Logs.Info("paid", new Dictionary<string, object?> { ["order_id"] = 7 });
            client.Sms.Send("Acme", "+2250700000000", "hello");
        }

        // The client's Dispose closed (and so flushed) the logger.
        var request = Assert.Single(logSender.Requests);
        var everything = request.Endpoint + "\n" + string.Join("\n", request.Headers.Select(h => h.Key + ": " + h.Value)) + "\n" + request.Body;
        var messagingAuth = Convert.ToBase64String(Encoding.UTF8.GetBytes(clientId + ":" + apiKey));
        Assert.DoesNotContain(clientId, everything);
        Assert.DoesNotContain(apiKey, everything);
        Assert.DoesNotContain(messagingAuth, everything);
        Assert.DoesNotContain("client_id", everything);
        Assert.DoesNotContain("api_key", everything);

        // ...and the Logs credentials never reach the messaging API.
        var logsAuth = Convert.ToBase64String(Encoding.UTF8.GetBytes(Pk + ":" + Sk));
        Assert.Equal("Basic " + messagingAuth, messaging.Authorization);
        Assert.DoesNotContain(Sk, messaging.Authorization);
        Assert.DoesNotContain(logsAuth, messaging.Authorization);
        Assert.DoesNotContain(Sk, messaging.RequestBody ?? "");
        Assert.DoesNotContain(Pk, messaging.RequestBody ?? "");
        Assert.DoesNotContain(Sk, messaging.Uri!.ToString());
    }

    [Fact]
    public void ClientWithoutLogsKeyHasANoOpLogger()
    {
        if (System.Environment.GetEnvironmentVariable("CALLISTO_LOGS_ACCESS_KEY") is not null)
        {
            return;
        }

        var logSender = new FakeLogSender();
        using (var client = new CallistoClient("cid", "key", handler: new FakeHandler(), logSender: logSender))
        {
            Assert.NotNull(client.Logs);
            Assert.False(client.Logs.Enabled);
            client.Logs.Error("nothing");
            client.Logs.Flush();
        }

        Assert.Empty(logSender.Requests);
    }
}

/// <summary>Environment-variable resolution; isolated because it mutates process state.</summary>
[CollectionDefinition("LogsEnv", DisableParallelization = true)]
public class LogsEnvCollection
{
}

[Collection("LogsEnv")]
public class LogsEnvTests
{
    private static readonly string[] Vars =
    {
        "CALLISTO_LOGS_ACCESS_KEY", "CALLISTO_LOGS_SECRET", "CALLISTO_LOGS_ENDPOINT",
        "CALLISTO_SERVICE_NAME", "OTEL_SERVICE_NAME", "CALLISTO_ENVIRONMENT",
    };

    private static void WithEnv(IReadOnlyDictionary<string, string?> values, Action body)
    {
        var saved = Vars.ToDictionary(v => v, System.Environment.GetEnvironmentVariable);
        try
        {
            foreach (var v in Vars)
            {
                System.Environment.SetEnvironmentVariable(v, values.TryGetValue(v, out var x) ? x : null);
            }

            body();
        }
        finally
        {
            foreach (var kvp in saved)
            {
                System.Environment.SetEnvironmentVariable(kvp.Key, kvp.Value);
            }
        }
    }

    [Fact]
    public void SettingsResolveFromEnvironment()
    {
        WithEnv(new Dictionary<string, string?>
        {
            ["CALLISTO_LOGS_ACCESS_KEY"] = LogsTests.Pk,
            ["CALLISTO_LOGS_SECRET"] = LogsTests.Sk,
            ["CALLISTO_LOGS_ENDPOINT"] = "http://localhost:4318/v1/logs",
            ["OTEL_SERVICE_NAME"] = "from-otel",
            ["CALLISTO_ENVIRONMENT"] = "qa",
        }, () =>
        {
            var sender = new FakeLogSender();
            using (var logger = new CallistoLogger(new CallistoLoggerOptions { Sender = sender }))
            {
                Assert.True(logger.Enabled);
                Assert.Equal("from-otel", logger.ServiceName);
                Assert.Equal("qa", logger.Environment);
                logger.Info("env");
                logger.Flush();
            }

            var request = Assert.Single(sender.Requests);
            Assert.Equal("http://localhost:4318/v1/logs", request.Endpoint);
        });
    }

    [Fact]
    public void ExplicitArgumentsWinAndCallistoServiceNameBeatsOtel()
    {
        WithEnv(new Dictionary<string, string?>
        {
            ["CALLISTO_LOGS_ACCESS_KEY"] = "pk_ffffffffffffffffffffffffffffffff",
            ["CALLISTO_LOGS_SECRET"] = "sk_ffffffffffffffffffffffffffffffffffffffffffffffff",
            ["CALLISTO_SERVICE_NAME"] = "from-callisto",
            ["OTEL_SERVICE_NAME"] = "from-otel",
        }, () =>
        {
            var sender = new FakeLogSender();
            using (var logger = new CallistoLogger(new CallistoLoggerOptions
            {
                LogsAccessKey = LogsTests.Pk,
                LogsSecret = LogsTests.Sk,
                Sender = sender,
            }))
            {
                Assert.Equal("from-callisto", logger.ServiceName);
                Assert.Null(logger.Environment);
                logger.Info("x");
                logger.Flush();
            }

            var auth = Assert.Single(sender.Requests).Headers["Authorization"];
            Assert.Equal("Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(LogsTests.Pk + ":" + LogsTests.Sk)), auth);
        });
    }

    [Fact]
    public void ServiceNameDefaultsToUnknownService()
    {
        WithEnv(new Dictionary<string, string?>(), () =>
        {
            using var logger = new CallistoLogger(new CallistoLoggerOptions
            {
                LogsAccessKey = LogsTests.Pk,
                LogsSecret = LogsTests.Sk,
                Sender = new FakeLogSender(),
            });
            Assert.Equal("unknown_service", logger.ServiceName);
        });
    }

    [Fact]
    public void HalfCredentialsFromEnvironmentFailFast()
    {
        WithEnv(new Dictionary<string, string?> { ["CALLISTO_LOGS_ACCESS_KEY"] = LogsTests.Pk }, () =>
        {
            Assert.Throws<ArgumentException>(() => new CallistoLogger());
            Assert.Throws<ArgumentException>(() => new CallistoClient("cid", "key", handler: new FakeHandler()));
        });
    }
}
