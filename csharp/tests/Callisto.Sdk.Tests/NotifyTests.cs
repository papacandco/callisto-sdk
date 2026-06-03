using System.Net;
using System.Text.Json;
using Callisto.Sdk;
using Callisto.Sdk.Errors;
using Xunit;

namespace Callisto.Sdk.Tests;

public class NotifyTests
{
    [Fact]
    public void Send_PostsTopicAndPresentBlocksWithSnakeCaseKeys()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"status\": \"queued\", \"topic\": \"news\", \"queued_events\": 2, \"topic_messages\": []}");
        using var client = TestClient.Create(handler);

        var email = new object[] { new { to = "a@b.com" } };
        var mobile = new object[] { new { token = "t1" } };
        var result = client.Notify.Send(topic: "news", email: email, mobilePush: mobile);

        Assert.Equal("/v1/notify/send", handler.Path);
        using var doc = JsonDocument.Parse(handler.RequestBody!);
        var root = doc.RootElement;
        Assert.Equal("news", root.GetProperty("topic").GetString());
        Assert.True(root.TryGetProperty("email", out _));
        Assert.True(root.TryGetProperty("mobile_push", out _));
        Assert.False(root.TryGetProperty("sms", out _));
        Assert.Equal("queued", result.Status);
    }

    [Fact]
    public void Send_NoBlocksThrowsValidation()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{}");
        using var client = TestClient.Create(handler);

        Assert.Throws<ValidationException>(() => client.Notify.Send(topic: "news"));
    }

    [Fact]
    public void Send_EmptyBlocksThrowValidation()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{}");
        using var client = TestClient.Create(handler);

        Assert.Throws<ValidationException>(() =>
            client.Notify.Send(topic: "news", sms: new object[0]));
    }
}
