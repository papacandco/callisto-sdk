using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using Callisto.Sdk;
using Callisto.Sdk.Errors;
using Callisto.Sdk.Http;
using Xunit;

namespace Callisto.Sdk.Tests;

public class TransportTests
{
    private static Config Cfg() => Config.Resolve("cid", "key", "https://api.example.com/v1");

    [Fact]
    public void BuildsBasicAuthHeader()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{\"credit\": 0, \"currency\": \"X\"}");
        using var t = new Transport(Cfg(), handler: handler);

        t.RequestRaw(HttpMethod.Get, "/sms/balance");

        // base64("cid:key") == "Y2lkOmtleQ=="
        Assert.Equal("Basic Y2lkOmtleQ==", handler.Authorization);
        Assert.Contains("application/json", handler.Accept);
    }

    [Fact]
    public void DropsNullQueryParams()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{}");
        using var t = new Transport(Cfg(), handler: handler);

        var query = new Dictionary<string, object?> { ["a"] = "1", ["b"] = null };
        t.RequestRaw(HttpMethod.Get, "/x", query: query);

        var q = handler.Query();
        Assert.Equal("1", q["a"]);
        Assert.False(q.ContainsKey("b"));
    }

    [Fact]
    public void PrependsBaseUrlToPath()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{}");
        using var t = new Transport(Cfg(), handler: handler);

        t.RequestRaw(HttpMethod.Get, "/sms/balance");

        Assert.Equal("https://api.example.com/v1/sms/balance", handler.Uri!.GetLeftPart(UriPartial.Path));
    }

    [Fact]
    public void EmptyResponseBodyReturnsNull()
    {
        var handler = new FakeHandler(HttpStatusCode.NoContent);
        using var t = new Transport(Cfg(), handler: handler);

        var result = t.RequestRaw(HttpMethod.Get, "/x");

        Assert.Null(result);
    }

    [Fact]
    public void TransportFailureRaisesNetworkException()
    {
        using var t = new Transport(Cfg(), handler: new ThrowingHandler());

        var ex = Assert.Throws<NetworkException>(() => t.RequestRaw(HttpMethod.Get, "/x"));
        Assert.Equal(0, ex.StatusCode);
    }

    [Fact]
    public void ConfigResolvesFromEnvironment()
    {
        var prevId = Environment.GetEnvironmentVariable("CALLISTO_CLIENT_ID");
        var prevKey = Environment.GetEnvironmentVariable("CALLISTO_API_KEY");
        try
        {
            Environment.SetEnvironmentVariable("CALLISTO_CLIENT_ID", "envid");
            Environment.SetEnvironmentVariable("CALLISTO_API_KEY", "envkey");

            var cfg = Config.Resolve();

            Assert.Equal("envid", cfg.ClientId);
            Assert.Equal("envkey", cfg.ApiKey);
            Assert.Equal(Config.DefaultBaseUrl, cfg.BaseUrl);
        }
        finally
        {
            Environment.SetEnvironmentVariable("CALLISTO_CLIENT_ID", prevId);
            Environment.SetEnvironmentVariable("CALLISTO_API_KEY", prevKey);
        }
    }

    [Fact]
    public void ConfigTrimsTrailingSlash()
    {
        var cfg = Config.Resolve("a", "b", "https://x.com/v1/");
        Assert.Equal("https://x.com/v1", cfg.BaseUrl);
    }

    [Fact]
    public void MissingCredentialsThrows()
    {
        var prevId = Environment.GetEnvironmentVariable("CALLISTO_CLIENT_ID");
        var prevKey = Environment.GetEnvironmentVariable("CALLISTO_API_KEY");
        try
        {
            Environment.SetEnvironmentVariable("CALLISTO_CLIENT_ID", null);
            Environment.SetEnvironmentVariable("CALLISTO_API_KEY", null);
            Assert.Throws<ArgumentException>(() => Config.Resolve());
        }
        finally
        {
            Environment.SetEnvironmentVariable("CALLISTO_CLIENT_ID", prevId);
            Environment.SetEnvironmentVariable("CALLISTO_API_KEY", prevKey);
        }
    }
}
