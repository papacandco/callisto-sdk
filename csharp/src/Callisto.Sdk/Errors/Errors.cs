using System;

namespace Callisto.Sdk.Errors;

/// <summary>
/// Base type for all errors raised by the SDK. Carries the human-readable
/// <see cref="Exception.Message"/>, the HTTP <see cref="StatusCode"/> (or <c>0</c> for
/// transport failures), and the decoded response <see cref="Body"/>.
/// </summary>
public class CallistoException : Exception
{
    /// <summary>HTTP status code, or <c>0</c> for transport/connection failures.</summary>
    public int StatusCode { get; }

    /// <summary>The decoded response body, when available.</summary>
    public object? Body { get; }

    public CallistoException(string message, int statusCode = 0, object? body = null)
        : base(message)
    {
        StatusCode = statusCode;
        Body = body;
    }
}

/// <summary>Raised on HTTP <c>401</c> responses.</summary>
public sealed class AuthenticationException : CallistoException
{
    public AuthenticationException(string message, int statusCode = 0, object? body = null)
        : base(message, statusCode, body) { }
}

/// <summary>Raised on HTTP <c>400</c> / <c>422</c> responses and on client-side validation.</summary>
public sealed class ValidationException : CallistoException
{
    public ValidationException(string message, int statusCode = 0, object? body = null)
        : base(message, statusCode, body) { }
}

/// <summary>Raised on HTTP <c>404</c> responses.</summary>
public sealed class NotFoundException : CallistoException
{
    public NotFoundException(string message, int statusCode = 0, object? body = null)
        : base(message, statusCode, body) { }
}

/// <summary>Raised on HTTP <c>429</c> responses. Carries the parsed <c>Retry-After</c> header.</summary>
public sealed class RateLimitException : CallistoException
{
    /// <summary>The value of the <c>Retry-After</c> header in seconds, when present and numeric.</summary>
    public int? RetryAfter { get; }

    public RateLimitException(string message, int statusCode = 0, object? body = null, int? retryAfter = null)
        : base(message, statusCode, body)
    {
        RetryAfter = retryAfter;
    }
}

/// <summary>Raised on any other non-2xx HTTP response.</summary>
public sealed class ApiException : CallistoException
{
    public ApiException(string message, int statusCode = 0, object? body = null)
        : base(message, statusCode, body) { }
}

/// <summary>Raised on a transport/connection failure (status code <c>0</c>).</summary>
public sealed class NetworkException : CallistoException
{
    public NetworkException(string message, object? body = null)
        : base(message, 0, body) { }
}

internal static class ErrorFactory
{
    public static CallistoException FromStatus(int status, string message, object? body, int? retryAfter)
    {
        return status switch
        {
            401 => new AuthenticationException(message, status, body),
            400 or 422 => new ValidationException(message, status, body),
            404 => new NotFoundException(message, status, body),
            429 => new RateLimitException(message, status, body, retryAfter),
            _ => new ApiException(message, status, body),
        };
    }
}
