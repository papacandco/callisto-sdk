using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using Callisto.Sdk;
using Callisto.Sdk.Errors;
using Callisto.Sdk.Http;
using Callisto.Sdk.Reporting;
using Xunit;

namespace Callisto.Sdk.Tests;

/// <summary>
/// A fake <see cref="IErrorSender"/> that records every (dsn, payload) it receives. Can be told
/// to fail (return false) or throw, to exercise the swallow-all behavior.
/// </summary>
public sealed class FakeSender : IErrorSender
{
    private readonly bool _returnValue;
    private readonly bool _throws;

    public List<(string Dsn, string Payload)> Sent { get; } = new();
    public List<JsonElement> Payloads { get; } = new();

    public FakeSender(bool returnValue = true, bool throws = false)
    {
        _returnValue = returnValue;
        _throws = throws;
    }

    public bool Send(string dsn, string payload)
    {
        Sent.Add((dsn, payload));
        using var doc = JsonDocument.Parse(payload);
        Payloads.Add(doc.RootElement.Clone());

        if (_throws)
        {
            throw new InvalidOperationException("sender boom");
        }

        return _returnValue;
    }
}

public class ReporterTests
{
    private const string Dsn = "https://app.example.com/ingest/abc?key=deadbeef";

    private static ErrorReporter Make(IErrorSender sender, string? dsn = Dsn, string? env = null)
        => new(dsn, env, sender);

    [Fact]
    public void CaptureExceptionPostsToDsnWithMessageTypeLevel()
    {
        var sender = new FakeSender();
        using var reporter = Make(sender);

        reporter.CaptureException(new NotFoundException("missing", 404));
        reporter.Flush();

        Assert.Single(sender.Sent);
        Assert.Equal(Dsn, sender.Sent[0].Dsn);

        var root = sender.Payloads[0];
        Assert.Equal("missing", root.GetProperty("message").GetString());
        Assert.Equal("NotFoundException", root.GetProperty("type").GetString());
        Assert.Equal("error", root.GetProperty("level").GetString());
    }

    [Fact]
    public void ContextCarriesSdkMetadataAndEnvironment()
    {
        var sender = new FakeSender();
        using var reporter = Make(sender, env: "staging");

        reporter.CaptureException(new ApiException("boom", 500));
        reporter.Flush();

        var ctx = sender.Payloads[0].GetProperty("context");
        var sdk = ctx.GetProperty("sdk");
        Assert.Equal("Callisto.Sdk", sdk.GetProperty("name").GetString());
        Assert.Equal("csharp", sdk.GetProperty("language").GetString());
        Assert.False(string.IsNullOrEmpty(sdk.GetProperty("version").GetString()));
        Assert.Equal("staging", ctx.GetProperty("environment").GetString());
        Assert.Equal(500, ctx.GetProperty("status_code").GetInt32());
    }

    [Fact]
    public void RateLimitCarriesRetryAfter()
    {
        var sender = new FakeSender();
        using var reporter = Make(sender);

        reporter.CaptureException(new RateLimitException("slow", 429, null, 42));
        reporter.Flush();

        var ctx = sender.Payloads[0].GetProperty("context");
        Assert.Equal(42, ctx.GetProperty("retry_after").GetInt32());
    }

    [Fact]
    public void TransportErrorSetsRequestAndCulprit()
    {
        var sender = new FakeSender();
        using var reporter = Make(sender);
        var handler = new FakeHandler(HttpStatusCode.InternalServerError, "{\"message\":\"boom\"}");
        var cfg = Config.Resolve("cid", "key", "https://api.example.com/v1");
        using var t = new Transport(cfg, handler: handler, reporter: reporter);

        Assert.Throws<ApiException>(() => t.RequestRaw(HttpMethod.Post, "/otp/send"));
        reporter.Flush();

        var root = sender.Payloads[0];
        var request = root.GetProperty("request");
        Assert.Equal("POST", request.GetProperty("method").GetString());
        Assert.Equal("/otp/send", request.GetProperty("path").GetString());
        Assert.Equal("POST /otp/send", root.GetProperty("culprit").GetString());
        Assert.Equal(500, root.GetProperty("context").GetProperty("status_code").GetInt32());
    }

    [Fact]
    public void NetworkErrorIsCaptured()
    {
        var sender = new FakeSender();
        using var reporter = Make(sender);
        var cfg = Config.Resolve("cid", "key", "https://api.example.com/v1");
        using var t = new Transport(cfg, handler: new ThrowingHandler(), reporter: reporter);

        Assert.Throws<NetworkException>(() => t.RequestRaw(HttpMethod.Get, "/x"));
        reporter.Flush();

        Assert.Single(sender.Sent);
        Assert.Equal("NetworkException", sender.Payloads[0].GetProperty("type").GetString());
    }

    [Fact]
    public void NoCredentialOrRequestBodyLeakOnTransportError()
    {
        // Drive a real transport error so the capture goes through the production path. The
        // OUTGOING request body (phone number + message content) must never appear in the
        // captured payload — only the server's error body, status, method, and path may leave.
        var sender = new FakeSender();
        using var reporter = Make(sender);
        var cfg = Config.Resolve("cid", "key", "https://api.example.com/v1");
        var handler = new FakeHandler(HttpStatusCode.UnprocessableEntity, "{\"message\":\"invalid\"}");
        using var t = new Transport(cfg, handler: handler, reporter: reporter);

        var outgoingBody = new Dictionary<string, object?>
        {
            ["to"] = "+2250700000000",
            ["message"] = "secret content",
        };
        Assert.Throws<ValidationException>(
            () => t.RequestRaw(HttpMethod.Post, "/otp/send", body: outgoingBody));
        reporter.Flush();

        var raw = sender.Sent[0].Payload;
        // No credentials.
        Assert.DoesNotContain("Basic", raw);
        Assert.DoesNotContain("Authorization", raw);
        Assert.DoesNotContain("api_key", raw);
        Assert.DoesNotContain("client_id", raw);
        Assert.DoesNotContain("Y2lkOmtleQ==", raw); // base64("cid:key")
        // No outgoing request body (phone / message content).
        Assert.DoesNotContain("+2250700000000", raw);
        Assert.DoesNotContain("secret content", raw);
    }

    [Fact]
    public void ServerErrorBodyIsRetainedInContext()
    {
        // The spec explicitly allows the server's error response body under context.body.
        var sender = new FakeSender();
        using var reporter = Make(sender);
        var serverBody = JsonSerializer.Deserialize<JsonElement>("{\"message\":\"nope\",\"code\":\"E1\"}");
        reporter.CaptureException(new ValidationException("nope", 422, serverBody));
        reporter.Flush();

        var ctx = sender.Payloads[0].GetProperty("context");
        Assert.Equal("E1", ctx.GetProperty("body").GetProperty("code").GetString());
    }

    [Fact]
    public void SenderReturningFailureIsSwallowed()
    {
        var sender = new FakeSender(returnValue: false);
        using var reporter = Make(sender);

        var ex = Record.Exception(() =>
        {
            reporter.CaptureException(new ApiException("boom", 500));
            reporter.Flush();
        });
        Assert.Null(ex);
        Assert.Single(sender.Sent);
    }

    [Fact]
    public void SenderThrowingIsSwallowed()
    {
        var sender = new FakeSender(throws: true);
        using var reporter = Make(sender);

        var ex = Record.Exception(() =>
        {
            reporter.CaptureException(new ApiException("boom", 500));
            reporter.Flush();
        });
        Assert.Null(ex);
    }

    [Fact]
    public void NoDsnIsNoOp()
    {
        var sender = new FakeSender();
        using var reporter = new ErrorReporter(null, sender: sender);

        Assert.False(reporter.Enabled);
        reporter.CaptureException(new ApiException("boom", 500));
        reporter.CaptureMessage("hello");
        reporter.Flush();

        Assert.Empty(sender.Sent);
    }

    [Fact]
    public void InvalidDsnIsNoOp()
    {
        var sender = new FakeSender();
        using var reporter = new ErrorReporter("not a url", sender: sender);

        Assert.False(reporter.Enabled);
        reporter.CaptureException(new ApiException("boom", 500));
        reporter.Flush();

        Assert.Empty(sender.Sent);
    }

    [Fact]
    public void CaptureMessageWorks()
    {
        var sender = new FakeSender();
        using var reporter = Make(sender);

        reporter.CaptureMessage("just so you know", "warning");
        reporter.Flush();

        var root = sender.Payloads[0];
        Assert.Equal("just so you know", root.GetProperty("message").GetString());
        Assert.Equal("warning", root.GetProperty("level").GetString());
        Assert.Equal("Callisto.Sdk", root.GetProperty("context").GetProperty("sdk").GetProperty("name").GetString());
    }

    [Fact]
    public void InvalidLevelFallsBackToError()
    {
        var sender = new FakeSender();
        using var reporter = Make(sender);

        reporter.CaptureException(new ApiException("boom", 500), level: "bogus");
        reporter.Flush();

        Assert.Equal("error", sender.Payloads[0].GetProperty("level").GetString());
    }

    [Fact]
    public void SetUserIsAttached()
    {
        var sender = new FakeSender();
        using var reporter = Make(sender);

        reporter.SetUser(new Dictionary<string, object?> { ["id"] = "u1", ["email"] = "a@b.com" });
        reporter.CaptureException(new ApiException("boom", 500));
        reporter.Flush();

        var user = sender.Payloads[0].GetProperty("user");
        Assert.Equal("u1", user.GetProperty("id").GetString());
        Assert.Equal("a@b.com", user.GetProperty("email").GetString());
    }

    [Fact]
    public void ExtraIsMergedIntoContext()
    {
        var sender = new FakeSender();
        using var reporter = Make(sender);

        reporter.CaptureException(
            new ApiException("boom", 500),
            extra: new Dictionary<string, object?> { ["order_id"] = "o-99" });
        reporter.Flush();

        Assert.Equal("o-99", sender.Payloads[0].GetProperty("context").GetProperty("order_id").GetString());
    }

    [Fact]
    public void OriginalExceptionStillPropagatesWithReporter()
    {
        var sender = new FakeSender();
        using var reporter = Make(sender);
        var cfg = Config.Resolve("cid", "key", "https://api.example.com/v1");
        var handler = new FakeHandler(HttpStatusCode.Unauthorized, "{\"message\":\"bad creds\"}");
        using var t = new Transport(cfg, handler: handler, reporter: reporter);

        var ex = Assert.Throws<AuthenticationException>(() => t.RequestRaw(HttpMethod.Get, "/x"));
        Assert.Equal(401, ex.StatusCode);
    }

    [Fact]
    public void ClientPublicMethodsWorkEndToEnd()
    {
        var sender = new FakeSender();
        using var client = new CallistoClient(
            "cid", "key", baseUrl: "https://api.example.com/v1",
            errorDsn: Dsn, errorSender: sender);

        client.SetUser(new Dictionary<string, object?> { ["id"] = "u2" });
        client.CaptureMessage("ping");
        client.CaptureException(new InvalidOperationException("custom"));
        client.ErrorReporter.Flush();

        Assert.Equal(2, sender.Sent.Count);
        Assert.Equal("ping", sender.Payloads[0].GetProperty("message").GetString());
        Assert.Equal("InvalidOperationException", sender.Payloads[1].GetProperty("type").GetString());
        Assert.Equal("u2", sender.Payloads[1].GetProperty("user").GetProperty("id").GetString());
    }

    [Fact]
    public void ClientWithoutDsnDoesNotReport()
    {
        var sender = new FakeSender();
        using var client = new CallistoClient(
            "cid", "key", baseUrl: "https://api.example.com/v1", errorSender: sender);

        Assert.False(client.ErrorReporter.Enabled);
        client.CaptureMessage("ping");
        client.ErrorReporter.Flush();

        Assert.Empty(sender.Sent);
    }

    [Fact]
    public void ResourceValidationErrorIsCaptured()
    {
        var sender = new FakeSender();
        using var client = new CallistoClient(
            "cid", "key", baseUrl: "https://api.example.com/v1",
            handler: new FakeHandler(HttpStatusCode.OK, "{}"),
            errorDsn: Dsn, errorSender: sender);

        Assert.Throws<ValidationException>(() => client.Notify.Send("welcome"));
        client.ErrorReporter.Flush();

        Assert.Single(sender.Sent);
        Assert.Equal("ValidationException", sender.Payloads[0].GetProperty("type").GetString());
    }
}
