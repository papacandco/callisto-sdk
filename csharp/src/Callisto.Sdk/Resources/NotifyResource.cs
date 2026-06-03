using System.Collections.Generic;
using System.Net.Http;
using Callisto.Sdk.Errors;
using Callisto.Sdk.Http;
using Callisto.Sdk.Models;

namespace Callisto.Sdk.Resources;

/// <summary>Multi-channel notification operations.</summary>
public sealed class NotifyResource
{
    private readonly Transport _t;

    internal NotifyResource(Transport transport) => _t = transport;

    /// <summary>
    /// Dispatches a notification to a topic across one or more channels.
    /// <c>POST /notify/send</c>. At least one event block must be provided, otherwise a
    /// <see cref="ValidationException"/> is thrown. JSON keys are snake_case.
    /// </summary>
    public NotifyResult Send(
        string topic,
        IEnumerable<object>? email = null,
        IEnumerable<object>? sms = null,
        IEnumerable<object>? mobilePush = null,
        IEnumerable<object>? webPush = null,
        IEnumerable<object>? webhook = null,
        IEnumerable<object>? messaging = null,
        IEnumerable<object>? realTime = null)
    {
        var blocks = new (string Key, IEnumerable<object>? Value)[]
        {
            ("email", email),
            ("sms", sms),
            ("mobile_push", mobilePush),
            ("web_push", webPush),
            ("webhook", webhook),
            ("messaging", messaging),
            ("real_time", realTime),
        };

        var body = new Dictionary<string, object?> { ["topic"] = topic };
        var any = false;
        foreach (var (key, value) in blocks)
        {
            if (value is null) continue;
            var list = value as IReadOnlyCollection<object> ?? new List<object>(value);
            if (list.Count == 0) continue;
            body[key] = list;
            any = true;
        }

        if (!any)
        {
            var error = new ValidationException(
                "At least one event block (email, sms, mobile_push, web_push, " +
                "webhook, messaging, real_time) must be provided.");
            _t.CaptureClientError(error);
            throw error;
        }

        return _t.Request<NotifyResult>(HttpMethod.Post, "/notify/send", body: body);
    }
}
