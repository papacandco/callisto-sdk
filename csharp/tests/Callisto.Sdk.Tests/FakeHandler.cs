using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace Callisto.Sdk.Tests;

/// <summary>
/// Captures the outgoing request (method, URL, query, Authorization header, JSON body) and
/// returns a canned response.
/// </summary>
public sealed class FakeHandler : HttpMessageHandler
{
    private readonly HttpStatusCode _status;
    private readonly string? _responseBody;
    private readonly IReadOnlyDictionary<string, string>? _responseHeaders;

    public HttpMethod? Method { get; private set; }
    public Uri? Uri { get; private set; }
    public string? Path { get; private set; }
    public string? Authorization { get; private set; }
    public string? Accept { get; private set; }
    public string? RequestBody { get; private set; }

    public FakeHandler(
        HttpStatusCode status = HttpStatusCode.OK,
        string? responseBody = null,
        IReadOnlyDictionary<string, string>? responseHeaders = null)
    {
        _status = status;
        _responseBody = responseBody;
        _responseHeaders = responseHeaders;
    }

    /// <summary>Parsed query string of the last captured request.</summary>
    public Dictionary<string, string?> Query()
    {
        var result = new Dictionary<string, string?>();
        if (Uri is null) return result;
        var raw = Uri.Query.TrimStart('?');
        if (raw.Length == 0) return result;
        foreach (var pair in raw.Split('&'))
        {
            var idx = pair.IndexOf('=');
            if (idx < 0)
            {
                result[Uri.UnescapeDataString(pair)] = "";
            }
            else
            {
                var key = Uri.UnescapeDataString(pair.Substring(0, idx));
                var value = Uri.UnescapeDataString(pair.Substring(idx + 1));
                result[key] = value;
            }
        }

        return result;
    }

    protected override HttpResponseMessage Send(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        Method = request.Method;
        Uri = request.RequestUri;
        Path = request.RequestUri?.AbsolutePath;
        Authorization = request.Headers.Authorization is null
            ? null
            : $"{request.Headers.Authorization.Scheme} {request.Headers.Authorization.Parameter}";
        Accept = request.Headers.Accept.ToString();
        RequestBody = request.Content?.ReadAsStringAsync(cancellationToken).GetAwaiter().GetResult();

        var response = new HttpResponseMessage(_status);
        if (_responseBody is not null)
        {
            response.Content = new StringContent(_responseBody, System.Text.Encoding.UTF8, "application/json");
        }

        if (_responseHeaders is not null)
        {
            foreach (var kvp in _responseHeaders)
            {
                response.Headers.TryAddWithoutValidation(kvp.Key, kvp.Value);
            }
        }

        return response;
    }

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        => Task.FromResult(Send(request, cancellationToken));
}

/// <summary>An <see cref="HttpMessageHandler"/> that throws on send, to exercise NetworkException.</summary>
public sealed class ThrowingHandler : HttpMessageHandler
{
    protected override HttpResponseMessage Send(HttpRequestMessage request, CancellationToken cancellationToken)
        => throw new HttpRequestException("boom");

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        => throw new HttpRequestException("boom");
}

/// <summary>Test helpers.</summary>
public static class TestClient
{
    public static CallistoClient Create(FakeHandler handler)
        => new("cid", "key", baseUrl: "https://api.example.com/v1", handler: handler);
}
