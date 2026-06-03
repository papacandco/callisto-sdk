using System.Net;
using System.Text.Json;
using Callisto.Sdk;
using Callisto.Sdk.Enums;
using Xunit;

namespace Callisto.Sdk.Tests;

public class WhatsAppTests
{
    [Fact]
    public void CreateInstance_DropsNullFields()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{\"id\": \"i1\", \"name\": \"Bot\"}");
        using var client = TestClient.Create(handler);

        var instance = client.WhatsApp.CreateInstance(name: "Bot", phoneNumber: "+1");

        Assert.Equal("/v1/whatsapp/instances", handler.Path);
        using var doc = JsonDocument.Parse(handler.RequestBody!);
        var root = doc.RootElement;
        Assert.Equal("Bot", root.GetProperty("name").GetString());
        Assert.Equal("+1", root.GetProperty("phone_number").GetString());
        Assert.False(root.TryGetProperty("webhook_url", out _));
        Assert.Equal("i1", instance.Id);
    }

    [Fact]
    public void ListInstances_DefaultsPageToOne()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"items\": [{\"id\": \"i1\"}], \"total\": 1, \"per_page\": 20, " +
            "\"current_page\": 1, \"next\": null, \"previous\": null, \"total_pages\": 1}");
        using var client = TestClient.Create(handler);

        var page = client.WhatsApp.ListInstances();

        Assert.Equal("/v1/whatsapp/instances", handler.Path);
        Assert.Equal("1", handler.Query()["page"]);
        Assert.Single(page.Items);
        Assert.Equal("i1", page.Items[0].Id);
    }

    [Fact]
    public void GetInstance_UsesCodePath()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{\"id\": \"i1\", \"code\": \"ABC\"}");
        using var client = TestClient.Create(handler);

        var instance = client.WhatsApp.GetInstance("ABC");

        Assert.Equal("/v1/whatsapp/ABC", handler.Path);
        Assert.Equal("ABC", instance.Code);
    }

    [Fact]
    public void GetQr_ReturnsRawPayload()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{\"qr\": \"data:image/png;base64,xxx\"}");
        using var client = TestClient.Create(handler);

        var raw = client.WhatsApp.GetQr("ABC");

        Assert.Equal("/v1/whatsapp/ABC/qr", handler.Path);
        Assert.NotNull(raw);
        Assert.Equal("data:image/png;base64,xxx", raw!.Value.GetProperty("qr").GetString());
    }

    [Fact]
    public void GetStatus_ReturnsRawPayload()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{\"connected\": true}");
        using var client = TestClient.Create(handler);

        var raw = client.WhatsApp.GetStatus("ABC");

        Assert.Equal("/v1/whatsapp/ABC/status", handler.Path);
        Assert.True(raw!.Value.GetProperty("connected").GetBoolean());
    }

    [Fact]
    public void ListMessages_SendsQuery()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"items\": [], \"total\": 0, \"per_page\": 0, \"current_page\": 1, " +
            "\"next\": null, \"previous\": null, \"total_pages\": 0}");
        using var client = TestClient.Create(handler);

        client.WhatsApp.ListMessages("ABC", page: 3, perPage: 5);

        Assert.Equal("/v1/whatsapp/ABC/messages", handler.Path);
        Assert.Equal("3", handler.Query()["page"]);
        Assert.Equal("5", handler.Query()["per_page"]);
    }

    [Fact]
    public void GetMessage_UsesMessagesPath()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{\"id\": \"m1\", \"status\": \"sent\"}");
        using var client = TestClient.Create(handler);

        var msg = client.WhatsApp.GetMessage("m1");

        Assert.Equal("/v1/whatsapp/messages/m1", handler.Path);
        Assert.Equal("m1", msg.Id);
    }

    [Fact]
    public void SendText_PostsBody()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"id\": \"m1\", \"instance_id\": \"i1\", \"recipient\": \"+1\", " +
            "\"message_type\": \"text\", \"status\": \"queued\", \"scheduled\": false}");
        using var client = TestClient.Create(handler);

        var result = client.WhatsApp.SendText("ABC", "+1", "Hello");

        Assert.Equal("/v1/whatsapp/ABC/send/text", handler.Path);
        using var doc = JsonDocument.Parse(handler.RequestBody!);
        Assert.Equal("+1", doc.RootElement.GetProperty("to").GetString());
        Assert.Equal("Hello", doc.RootElement.GetProperty("message").GetString());
        Assert.Equal("text", result.MessageType);
    }

    [Fact]
    public void SendMedia_SerializesEnumValue()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"id\": \"m1\", \"instance_id\": \"i1\", \"recipient\": \"+1\", " +
            "\"message_type\": \"image\", \"status\": \"queued\", \"scheduled\": false, " +
            "\"media_url\": \"https://x/y.png\"}");
        using var client = TestClient.Create(handler);

        var result = client.WhatsApp.SendMedia("ABC", "+1", WhatsAppMediaType.Image, "https://x/y.png", caption: "hi");

        Assert.Equal("/v1/whatsapp/ABC/send/media", handler.Path);
        using var doc = JsonDocument.Parse(handler.RequestBody!);
        var root = doc.RootElement;
        Assert.Equal("image", root.GetProperty("type").GetString());
        Assert.Equal("https://x/y.png", root.GetProperty("media_url").GetString());
        Assert.Equal("hi", root.GetProperty("caption").GetString());
        Assert.False(root.TryGetProperty("filename", out _));
        Assert.Equal("https://x/y.png", result.MediaUrl);
    }

    [Fact]
    public void SendButtons_PostsButtonsArray()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"id\": \"m1\", \"instance_id\": \"i1\", \"recipient\": \"+1\", " +
            "\"message_type\": \"buttons\", \"status\": \"queued\", \"scheduled\": false}");
        using var client = TestClient.Create(handler);

        var buttons = new object[] { new { id = "b1", title = "Yes" } };
        client.WhatsApp.SendButtons("ABC", "+1", "Pick one", buttons, header: "H");

        Assert.Equal("/v1/whatsapp/ABC/send/buttons", handler.Path);
        using var doc = JsonDocument.Parse(handler.RequestBody!);
        var root = doc.RootElement;
        Assert.Equal("Pick one", root.GetProperty("body").GetString());
        Assert.Equal(1, root.GetProperty("buttons").GetArrayLength());
        Assert.Equal("H", root.GetProperty("header").GetString());
    }

    [Fact]
    public void SendLocation_PostsCoordinates()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"id\": \"m1\", \"instance_id\": \"i1\", \"recipient\": \"+1\", " +
            "\"message_type\": \"location\", \"status\": \"queued\", \"scheduled\": false}");
        using var client = TestClient.Create(handler);

        client.WhatsApp.SendLocation("ABC", "+1", 5.34, -4.02, name: "Office");

        Assert.Equal("/v1/whatsapp/ABC/send/location", handler.Path);
        using var doc = JsonDocument.Parse(handler.RequestBody!);
        var root = doc.RootElement;
        Assert.Equal(5.34, root.GetProperty("latitude").GetDouble());
        Assert.Equal(-4.02, root.GetProperty("longitude").GetDouble());
        Assert.Equal("Office", root.GetProperty("name").GetString());
    }

    [Fact]
    public void SendList_PostsSections()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"id\": \"m1\", \"instance_id\": \"i1\", \"recipient\": \"+1\", " +
            "\"message_type\": \"list\", \"status\": \"queued\", \"scheduled\": false}");
        using var client = TestClient.Create(handler);

        var sections = new object[] { new { title = "S1", rows = new[] { new { id = "r1", title = "R1" } } } };
        client.WhatsApp.SendList("ABC", "+1", "Menu", "Open", sections, footer: "F");

        Assert.Equal("/v1/whatsapp/ABC/send/list", handler.Path);
        using var doc = JsonDocument.Parse(handler.RequestBody!);
        var root = doc.RootElement;
        Assert.Equal("Open", root.GetProperty("button_text").GetString());
        Assert.Equal(1, root.GetProperty("sections").GetArrayLength());
        Assert.Equal("F", root.GetProperty("footer").GetString());
    }
}
