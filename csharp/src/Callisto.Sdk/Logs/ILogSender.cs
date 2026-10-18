using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Callisto.Sdk.Logs;

/// <summary>One OTLP/HTTP logs request, fully built by the logger.</summary>
public sealed class LogRequest
{
    public LogRequest(string endpoint, IReadOnlyDictionary<string, string> headers, string body)
    {
        Endpoint = endpoint;
        Headers = headers;
        Body = body;
    }

    /// <summary>The POST URL.</summary>
    public string Endpoint { get; }

    /// <summary><c>Authorization</c>, <c>Content-Type</c> and <c>User-Agent</c>.</summary>
    public IReadOnlyDictionary<string, string> Headers { get; }

    /// <summary>The OTLP JSON body.</summary>
    public string Body { get; }
}

/// <summary>The outcome of one send: the HTTP status and the raw <c>Retry-After</c> header.</summary>
public sealed record LogSendResult(int StatusCode, string? RetryAfter = null);

/// <summary>
/// Delivers a <see cref="LogRequest"/>. Injectable so tests can replace the network. Throwing
/// (or being cancelled by the per-request timeout) counts as a network error and is retried.
/// </summary>
public interface ILogSender
{
    Task<LogSendResult> SendAsync(LogRequest request, CancellationToken cancellationToken);
}

/// <summary>Default sender: the logger's own <see cref="HttpClient"/>, 10 s per request.</summary>
internal sealed class HttpLogSender : ILogSender, IDisposable
{
    private readonly HttpClient _client;

    public HttpLogSender(HttpMessageHandler? handler, TimeSpan timeout)
    {
        _client = handler is null ? new HttpClient() : new HttpClient(handler, disposeHandler: false);
        _client.Timeout = timeout;
    }

    public async Task<LogSendResult> SendAsync(LogRequest request, CancellationToken cancellationToken)
    {
        using var message = new HttpRequestMessage(HttpMethod.Post, request.Endpoint);
        var content = new ByteArrayContent(Encoding.UTF8.GetBytes(request.Body));
        content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        message.Content = content;

        foreach (var header in request.Headers)
        {
            if (string.Equals(header.Key, "Content-Type", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            message.Headers.TryAddWithoutValidation(header.Key, header.Value);
        }

        using var response = await _client
            .SendAsync(message, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);

        string? retryAfter = response.Headers.TryGetValues("Retry-After", out var values)
            ? values.FirstOrDefault()
            : null;

        return new LogSendResult((int)response.StatusCode, retryAfter);
    }

    public void Dispose() => _client.Dispose();
}
