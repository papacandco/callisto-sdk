using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using Callisto.Sdk;
using Callisto.Sdk.Errors;
using Callisto.Sdk.Http;
using Xunit;

namespace Callisto.Sdk.Tests;

public class ErrorsTests
{
    private static Config Cfg() => Config.Resolve("cid", "key", "https://api.example.com/v1");

    private static ApiException Capture(HttpStatusCode status, string? body,
        IReadOnlyDictionary<string, string>? headers = null)
    {
        var handler = new FakeHandler(status, body, headers);
        using var t = new Transport(Cfg(), handler: handler);
        return Assert.Throws<ApiException>(() => Run(t));

        static void Run(Transport t) => t.RequestRaw(HttpMethod.Get, "/x");
    }

    [Fact]
    public void Status401MapsToAuthentication()
    {
        var ex = Assert.Throws<AuthenticationException>(() =>
        {
            var handler = new FakeHandler(HttpStatusCode.Unauthorized, "{\"message\": \"bad creds\"}");
            using var t = new Transport(Cfg(), handler: handler);
            t.RequestRaw(HttpMethod.Get, "/x");
        });
        Assert.Equal(401, ex.StatusCode);
        Assert.Equal("bad creds", ex.Message);
    }

    [Theory]
    [InlineData(HttpStatusCode.BadRequest, 400)]
    [InlineData(HttpStatusCode.UnprocessableEntity, 422)]
    public void Status400And422MapToValidation(HttpStatusCode status, int code)
    {
        var ex = Assert.Throws<ValidationException>(() =>
        {
            var handler = new FakeHandler(status, "{\"message\": \"invalid\"}");
            using var t = new Transport(Cfg(), handler: handler);
            t.RequestRaw(HttpMethod.Get, "/x");
        });
        Assert.Equal(code, ex.StatusCode);
        Assert.Equal("invalid", ex.Message);
    }

    [Fact]
    public void Status404MapsToNotFound()
    {
        var ex = Assert.Throws<NotFoundException>(() =>
        {
            var handler = new FakeHandler(HttpStatusCode.NotFound, "{\"message\": \"missing\"}");
            using var t = new Transport(Cfg(), handler: handler);
            t.RequestRaw(HttpMethod.Get, "/x");
        });
        Assert.Equal(404, ex.StatusCode);
    }

    [Fact]
    public void Status429MapsToRateLimitAndParsesRetryAfter()
    {
        var ex = Assert.Throws<RateLimitException>(() =>
        {
            var handler = new FakeHandler(
                HttpStatusCode.TooManyRequests,
                "{\"message\": \"slow down\"}",
                new Dictionary<string, string> { ["Retry-After"] = "42" });
            using var t = new Transport(Cfg(), handler: handler);
            t.RequestRaw(HttpMethod.Get, "/x");
        });
        Assert.Equal(429, ex.StatusCode);
        Assert.Equal(42, ex.RetryAfter);
        Assert.Equal("slow down", ex.Message);
    }

    [Fact]
    public void Status429NonNumericRetryAfterIsNull()
    {
        var ex = Assert.Throws<RateLimitException>(() =>
        {
            var handler = new FakeHandler(
                HttpStatusCode.TooManyRequests,
                "{\"message\": \"slow down\"}",
                new Dictionary<string, string> { ["Retry-After"] = "Wed, 21 Oct 2026 07:28:00 GMT" });
            using var t = new Transport(Cfg(), handler: handler);
            t.RequestRaw(HttpMethod.Get, "/x");
        });
        Assert.Null(ex.RetryAfter);
    }

    [Fact]
    public void OtherStatusMapsToApiError()
    {
        var ex = Assert.Throws<ApiException>(() =>
        {
            var handler = new FakeHandler(HttpStatusCode.InternalServerError, "{\"message\": \"boom\"}");
            using var t = new Transport(Cfg(), handler: handler);
            t.RequestRaw(HttpMethod.Get, "/x");
        });
        Assert.Equal(500, ex.StatusCode);
    }

    [Fact]
    public void MissingMessageFallsBackToHttpStatus()
    {
        var ex = Capture(HttpStatusCode.ServiceUnavailable, "{\"error\": \"x\"}");
        Assert.Equal("HTTP 503", ex.Message);
    }

    [Fact]
    public void NonJsonErrorBodyFallsBackToHttpStatus()
    {
        var ex = Capture(HttpStatusCode.BadGateway, "plain text gateway error");
        Assert.Equal("HTTP 502", ex.Message);
    }
}
