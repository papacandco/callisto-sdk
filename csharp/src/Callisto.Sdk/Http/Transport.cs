using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Callisto.Sdk.Errors;
using Callisto.Sdk.Reporting;

namespace Callisto.Sdk.Http;

/// <summary>
/// Internal HTTP transport. Applies Basic auth, serializes JSON bodies, drops null query
/// params, and maps non-2xx responses to typed <see cref="CallistoException"/> instances.
/// </summary>
public sealed class Transport : IDisposable
{
    /// <summary>Shared JSON options: tolerant deserialization, snake_case via attributes.</summary>
    public static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly Config _config;
    private readonly HttpClient _client;
    private readonly bool _ownsClient;

    /// <summary>
    /// Optional error reporter. When set, transport-originated errors are captured (with
    /// method/path) immediately before being thrown.
    /// </summary>
    public ErrorReporter? Reporter { get; set; }

    /// <summary>
    /// Creates a transport. When <paramref name="httpClient"/> is provided it is used as-is
    /// (Basic auth and timeout are still applied to it). When <paramref name="handler"/> is
    /// provided, an internally-owned <see cref="HttpClient"/> is built on top of it — useful for
    /// injecting a fake handler in tests.
    /// </summary>
    public Transport(
        Config config,
        HttpClient? httpClient = null,
        HttpMessageHandler? handler = null,
        ErrorReporter? reporter = null)
    {
        _config = config;
        Reporter = reporter;

        if (httpClient is not null)
        {
            _client = httpClient;
            _ownsClient = false;
        }
        else if (handler is not null)
        {
            _client = new HttpClient(handler, disposeHandler: false);
            _ownsClient = true;
        }
        else
        {
            _client = new HttpClient();
            _ownsClient = true;
        }

        _client.Timeout = config.Timeout;
        var token = Convert.ToBase64String(
            Encoding.UTF8.GetBytes($"{config.ClientId}:{config.ApiKey}"));
        _client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic", token);
        if (!_client.DefaultRequestHeaders.Accept.Contains(new MediaTypeWithQualityHeaderValue("application/json")))
        {
            _client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        }
    }

    /// <summary>Sends a request and deserializes the JSON response to <typeparamref name="T"/>.</summary>
    public T Request<T>(
        HttpMethod method,
        string path,
        object? body = null,
        IReadOnlyDictionary<string, object?>? query = null)
    {
        var element = RequestRaw(method, path, body, query);
        if (element is null || element.Value.ValueKind == JsonValueKind.Null)
        {
            return default!;
        }

        return element.Value.Deserialize<T>(JsonOptions)!;
    }

    /// <summary>Sends a request and returns the raw decoded JSON payload (or null when empty).</summary>
    public JsonElement? RequestRaw(
        HttpMethod method,
        string path,
        object? body = null,
        IReadOnlyDictionary<string, object?>? query = null)
    {
        var url = _config.BaseUrl + path + BuildQueryString(query);
        using var request = new HttpRequestMessage(method, url);

        if (body is not null)
        {
            var json = JsonSerializer.Serialize(body, JsonOptions);
            request.Content = new StringContent(json, Encoding.UTF8, "application/json");
        }

        HttpResponseMessage response;
        try
        {
            response = _client.Send(request);
        }
        catch (Exception exc) when (exc is HttpRequestException or TaskCanceledException or OperationCanceledException)
        {
            var networkError = new NetworkException($"Request to {url} failed: {exc.Message}");
            Report(networkError, method, path);
            throw networkError;
        }

        using (response)
        {
            var content = ReadContent(response);
            JsonElement? data = null;
            if (!string.IsNullOrEmpty(content))
            {
                try
                {
                    using var doc = JsonDocument.Parse(content);
                    data = doc.RootElement.Clone();
                }
                catch (JsonException)
                {
                    data = null;
                }
            }

            var status = (int)response.StatusCode;
            if (status < 200 || status >= 300)
            {
                var message = ExtractMessage(data, content, status);
                int? retryAfter = null;
                if (status == 429 &&
                    response.Headers.TryGetValues("Retry-After", out var values))
                {
                    foreach (var raw in values)
                    {
                        if (int.TryParse(raw, out var parsed))
                        {
                            retryAfter = parsed;
                        }

                        break;
                    }
                }

                object? bodyObj = data.HasValue ? (object)data.Value : content;
                var statusError = ErrorFactory.FromStatus(status, message, bodyObj, retryAfter);
                Report(statusError, method, path);
                throw statusError;
            }

            return data;
        }
    }

    /// <summary>
    /// Captures a client-side error (e.g. resource validation) through the reporter (if any),
    /// before the caller throws it. Never throws.
    /// </summary>
    public void CaptureClientError(Exception error)
    {
        var reporter = Reporter;
        if (reporter is null || !reporter.Enabled)
        {
            return;
        }

        reporter.CaptureException(error);
    }

    /// <summary>
    /// Captures a transport-originated error through the reporter (if any), tagging it with the
    /// HTTP method and path so the reporter sets <c>culprit</c>/<c>request</c>. Never throws and
    /// never sends the outgoing request body.
    /// </summary>
    private void Report(Exception error, HttpMethod method, string path)
    {
        var reporter = Reporter;
        if (reporter is null || !reporter.Enabled)
        {
            return;
        }

        var extra = new Dictionary<string, object?>
        {
            ["__method"] = method.Method,
            ["__path"] = path,
        };
        reporter.CaptureException(error, "error", extra);
    }

    private static string ReadContent(HttpResponseMessage response)
    {
        using var stream = response.Content.ReadAsStream();
        using var reader = new System.IO.StreamReader(stream);
        return reader.ReadToEnd();
    }

    private static string ExtractMessage(JsonElement? data, string? raw, int status)
    {
        if (data.HasValue &&
            data.Value.ValueKind == JsonValueKind.Object &&
            data.Value.TryGetProperty("message", out var msg) &&
            msg.ValueKind == JsonValueKind.String)
        {
            return msg.GetString() ?? $"HTTP {status}";
        }

        return $"HTTP {status}";
    }

    private static string BuildQueryString(IReadOnlyDictionary<string, object?>? query)
    {
        if (query is null)
        {
            return string.Empty;
        }

        var parts = new List<string>();
        foreach (var kvp in query)
        {
            if (kvp.Value is null)
            {
                continue;
            }

            var value = Convert.ToString(kvp.Value, System.Globalization.CultureInfo.InvariantCulture) ?? "";
            parts.Add($"{Uri.EscapeDataString(kvp.Key)}={Uri.EscapeDataString(value)}");
        }

        return parts.Count == 0 ? string.Empty : "?" + string.Join("&", parts);
    }

    public void Dispose()
    {
        if (_ownsClient)
        {
            _client.Dispose();
        }
    }
}
