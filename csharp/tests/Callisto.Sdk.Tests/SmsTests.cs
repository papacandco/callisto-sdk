using System.Net;
using System.Text.Json;
using Callisto.Sdk;
using Xunit;

namespace Callisto.Sdk.Tests;

public class SmsTests
{
    [Fact]
    public void Send_SingleRecipient_PostsBodyAndDecodes()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"total_amount\": 1.0, \"available_credit\": 9.0, \"status\": \"queued\", " +
            "\"recipient_count\": 1, \"scheduled\": false, \"messages\": []}");
        using var client = TestClient.Create(handler);

        var result = client.Sms.Send(sender: "Acme", to: "+225070", message: "Hi");

        Assert.Equal("POST", handler.Method!.Method);
        Assert.Equal("/v1/sms/send", handler.Path);

        using var doc = JsonDocument.Parse(handler.RequestBody!);
        var root = doc.RootElement;
        Assert.Equal("Acme", root.GetProperty("sender").GetString());
        Assert.Equal("+225070", root.GetProperty("to").GetString());
        Assert.Equal("Hi", root.GetProperty("message").GetString());
        Assert.False(root.TryGetProperty("notify_url", out _));

        Assert.Equal("queued", result.Status);
        Assert.Equal(1, result.RecipientCount);
        Assert.Equal(9.0, result.AvailableCredit);
    }

    [Fact]
    public void Send_ListRecipientAndOptionalFields()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"total_amount\": 2.0, \"available_credit\": 8.0, \"status\": \"queued\", " +
            "\"recipient_count\": 2, \"scheduled\": true, \"messages\": []}");
        using var client = TestClient.Create(handler);

        client.Sms.Send(
            sender: "Acme",
            to: new[] { "+1", "+2" },
            message: "Hi",
            notifyUrl: "https://hook",
            scheduledAt: "2026-06-04T00:00:00Z");

        using var doc = JsonDocument.Parse(handler.RequestBody!);
        var root = doc.RootElement;
        Assert.Equal(JsonValueKind.Array, root.GetProperty("to").ValueKind);
        Assert.Equal(2, root.GetProperty("to").GetArrayLength());
        Assert.Equal("https://hook", root.GetProperty("notify_url").GetString());
        Assert.Equal("2026-06-04T00:00:00Z", root.GetProperty("scheduled_at").GetString());
    }

    [Fact]
    public void List_SendsQueryParams()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"items\": [{\"id\": \"m1\", \"status\": \"sent\"}], \"total\": 1, " +
            "\"per_page\": 10, \"current_page\": 1, \"next\": null, \"previous\": null, \"total_pages\": 1}");
        using var client = TestClient.Create(handler);

        var page = client.Sms.List(startedAt: "2026-01-01", page: 1, perPage: 10);

        Assert.Equal("GET", handler.Method!.Method);
        Assert.Equal("/v1/sms/messages", handler.Path);
        var query = handler.Query();
        Assert.Equal("2026-01-01", query["started_at"]);
        Assert.Equal("1", query["page"]);
        Assert.Equal("10", query["per_page"]);
        Assert.False(query.ContainsKey("ended_at"));

        Assert.Single(page.Items);
        Assert.Equal("m1", page.Items[0].Id);
        Assert.Equal("sent", page.Items[0].Status);
        Assert.Equal(1, page.Total);
        Assert.Null(page.Next);
    }

    [Fact]
    public void GetStatus_UsesPathId()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"id\": \"abc\", \"status\": \"delivered\", \"recipient\": \"+1\"}");
        using var client = TestClient.Create(handler);

        var msg = client.Sms.GetStatus("abc");

        Assert.Equal("/v1/sms/abc", handler.Path);
        Assert.Equal("abc", msg.Id);
        Assert.Equal("delivered", msg.Status);
    }
}
