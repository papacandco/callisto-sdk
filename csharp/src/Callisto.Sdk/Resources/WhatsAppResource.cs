using System.Collections.Generic;
using System.Net.Http;
using System.Text.Json;
using Callisto.Sdk.Enums;
using Callisto.Sdk.Http;
using Callisto.Sdk.Models;

namespace Callisto.Sdk.Resources;

/// <summary>WhatsApp instance and messaging operations.</summary>
public sealed class WhatsAppResource
{
    private readonly Transport _t;

    internal WhatsAppResource(Transport transport) => _t = transport;

    private static Dictionary<string, object?> DropNull(Dictionary<string, object?> d)
    {
        var result = new Dictionary<string, object?>();
        foreach (var kvp in d)
        {
            if (kvp.Value is not null) result[kvp.Key] = kvp.Value;
        }

        return result;
    }

    /// <summary>Creates a WhatsApp instance. <c>POST /whatsapp/instances</c>.</summary>
    public WhatsAppInstance CreateInstance(
        string name,
        string? phoneNumber = null,
        string? webhookUrl = null,
        string? idempotencyKey = null)
    {
        var body = DropNull(new Dictionary<string, object?>
        {
            ["name"] = name,
            ["phone_number"] = phoneNumber,
            ["webhook_url"] = webhookUrl,
            ["idempotency_key"] = idempotencyKey,
        });
        return _t.Request<WhatsAppInstance>(HttpMethod.Post, "/whatsapp/instances", body: body);
    }

    /// <summary>Lists WhatsApp instances. <c>GET /whatsapp/instances</c>.</summary>
    public Paginated<WhatsAppInstance> ListInstances(int page = 1)
    {
        var query = new Dictionary<string, object?> { ["page"] = page };
        return _t.Request<Paginated<WhatsAppInstance>>(HttpMethod.Get, "/whatsapp/instances", query: query);
    }

    /// <summary>Fetches a WhatsApp instance by code. <c>GET /whatsapp/{code}</c>.</summary>
    public WhatsAppInstance GetInstance(string code)
        => _t.Request<WhatsAppInstance>(HttpMethod.Get, $"/whatsapp/{code}");

    /// <summary>Returns the raw QR-code payload. <c>GET /whatsapp/{code}/qr</c>.</summary>
    public JsonElement? GetQr(string code)
        => _t.RequestRaw(HttpMethod.Get, $"/whatsapp/{code}/qr");

    /// <summary>Returns the raw status payload. <c>GET /whatsapp/{code}/status</c>.</summary>
    public JsonElement? GetStatus(string code)
        => _t.RequestRaw(HttpMethod.Get, $"/whatsapp/{code}/status");

    /// <summary>Lists messages for an instance. <c>GET /whatsapp/{code}/messages</c>.</summary>
    public Paginated<WhatsAppMessage> ListMessages(
        string code,
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
        return _t.Request<Paginated<WhatsAppMessage>>(HttpMethod.Get, $"/whatsapp/{code}/messages", query: query);
    }

    /// <summary>Fetches a single WhatsApp message. <c>GET /whatsapp/messages/{id}</c>.</summary>
    public WhatsAppMessage GetMessage(string messageId)
        => _t.Request<WhatsAppMessage>(HttpMethod.Get, $"/whatsapp/messages/{messageId}");

    /// <summary>Sends a text message. <c>POST /whatsapp/{code}/send/text</c>.</summary>
    public SendWaResult SendText(string code, string to, string message, string? scheduledAt = null)
    {
        var body = DropNull(new Dictionary<string, object?>
        {
            ["to"] = to,
            ["message"] = message,
            ["scheduled_at"] = scheduledAt,
        });
        return _t.Request<SendWaResult>(HttpMethod.Post, $"/whatsapp/{code}/send/text", body: body);
    }

    /// <summary>Sends a media message using a typed <see cref="WhatsAppMediaType"/>.</summary>
    public SendWaResult SendMedia(
        string code,
        string to,
        WhatsAppMediaType type,
        string mediaUrl,
        string? caption = null,
        string? filename = null,
        string? scheduledAt = null)
        => SendMedia(code, to, type.Value(), mediaUrl, caption, filename, scheduledAt);

    /// <summary>Sends a media message. <c>POST /whatsapp/{code}/send/media</c>.</summary>
    public SendWaResult SendMedia(
        string code,
        string to,
        string type,
        string mediaUrl,
        string? caption = null,
        string? filename = null,
        string? scheduledAt = null)
    {
        var body = DropNull(new Dictionary<string, object?>
        {
            ["to"] = to,
            ["type"] = type,
            ["media_url"] = mediaUrl,
            ["caption"] = caption,
            ["filename"] = filename,
            ["scheduled_at"] = scheduledAt,
        });
        return _t.Request<SendWaResult>(HttpMethod.Post, $"/whatsapp/{code}/send/media", body: body);
    }

    /// <summary>Sends an interactive buttons message. <c>POST /whatsapp/{code}/send/buttons</c>.</summary>
    public SendWaResult SendButtons(
        string code,
        string to,
        string body,
        IEnumerable<object> buttons,
        string? header = null,
        string? footer = null,
        string? scheduledAt = null)
    {
        var payload = DropNull(new Dictionary<string, object?>
        {
            ["to"] = to,
            ["body"] = body,
            ["buttons"] = buttons,
            ["header"] = header,
            ["footer"] = footer,
            ["scheduled_at"] = scheduledAt,
        });
        return _t.Request<SendWaResult>(HttpMethod.Post, $"/whatsapp/{code}/send/buttons", body: payload);
    }

    /// <summary>Sends a location message. <c>POST /whatsapp/{code}/send/location</c>.</summary>
    public SendWaResult SendLocation(
        string code,
        string to,
        double latitude,
        double longitude,
        string? name = null,
        string? address = null,
        string? scheduledAt = null)
    {
        var payload = DropNull(new Dictionary<string, object?>
        {
            ["to"] = to,
            ["latitude"] = latitude,
            ["longitude"] = longitude,
            ["name"] = name,
            ["address"] = address,
            ["scheduled_at"] = scheduledAt,
        });
        return _t.Request<SendWaResult>(HttpMethod.Post, $"/whatsapp/{code}/send/location", body: payload);
    }

    /// <summary>Sends an interactive list message. <c>POST /whatsapp/{code}/send/list</c>.</summary>
    public SendWaResult SendList(
        string code,
        string to,
        string body,
        string buttonText,
        IEnumerable<object> sections,
        string? header = null,
        string? footer = null,
        string? scheduledAt = null)
    {
        var payload = DropNull(new Dictionary<string, object?>
        {
            ["to"] = to,
            ["body"] = body,
            ["button_text"] = buttonText,
            ["sections"] = sections,
            ["header"] = header,
            ["footer"] = footer,
            ["scheduled_at"] = scheduledAt,
        });
        return _t.Request<SendWaResult>(HttpMethod.Post, $"/whatsapp/{code}/send/list", body: payload);
    }
}
