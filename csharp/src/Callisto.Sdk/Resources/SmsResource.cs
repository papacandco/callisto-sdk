using System.Collections.Generic;
using System.Net.Http;
using Callisto.Sdk.Http;
using Callisto.Sdk.Models;

namespace Callisto.Sdk.Resources;

/// <summary>SMS operations.</summary>
public sealed class SmsResource
{
    private readonly Transport _t;

    internal SmsResource(Transport transport) => _t = transport;

    /// <summary>Sends an SMS to one recipient. <c>POST /sms/send</c>.</summary>
    public SendSmsResult Send(
        string sender,
        string to,
        string message,
        string? notifyUrl = null,
        string? scheduledAt = null)
        => Send(sender, (object)to, message, notifyUrl, scheduledAt);

    /// <summary>Sends an SMS to multiple recipients. <c>POST /sms/send</c>.</summary>
    public SendSmsResult Send(
        string sender,
        IEnumerable<string> to,
        string message,
        string? notifyUrl = null,
        string? scheduledAt = null)
        => Send(sender, (object)to, message, notifyUrl, scheduledAt);

    private SendSmsResult Send(string sender, object to, string message, string? notifyUrl, string? scheduledAt)
    {
        var body = new Dictionary<string, object?>
        {
            ["sender"] = sender,
            ["to"] = to,
            ["message"] = message,
        };
        if (notifyUrl is not null) body["notify_url"] = notifyUrl;
        if (scheduledAt is not null) body["scheduled_at"] = scheduledAt;
        return _t.Request<SendSmsResult>(HttpMethod.Post, "/sms/send", body: body);
    }

    /// <summary>Lists SMS messages. <c>GET /sms/messages</c>.</summary>
    public Paginated<SmsMessage> List(
        string? startedAt = null,
        string? endedAt = null,
        int? page = null,
        int? perPage = null)
    {
        var query = new Dictionary<string, object?>
        {
            ["started_at"] = startedAt,
            ["ended_at"] = endedAt,
            ["page"] = page,
            ["per_page"] = perPage,
        };
        return _t.Request<Paginated<SmsMessage>>(HttpMethod.Get, "/sms/messages", query: query);
    }

    /// <summary>Fetches the status of a single SMS message. <c>GET /sms/{id}</c>.</summary>
    public SmsMessage GetStatus(string messageId)
        => _t.Request<SmsMessage>(HttpMethod.Get, $"/sms/{messageId}");
}
