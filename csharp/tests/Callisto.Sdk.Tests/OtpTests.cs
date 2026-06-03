using System.Net;
using System.Text.Json;
using Callisto.Sdk;
using Callisto.Sdk.Enums;
using Callisto.Sdk.Errors;
using Xunit;

namespace Callisto.Sdk.Tests;

public class OtpTests
{
    [Fact]
    public void Send_PostsBodyWithEnumValues()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"id\": \"o1\", \"provider\": \"sms\", \"recipient\": {}, " +
            "\"expires_at\": \"2026-06-03T00:05:00Z\", \"expires_in\": 300}");
        using var client = TestClient.Create(handler);

        var result = client.Otp.Send(
            to: "+1",
            message: "Code: {code}",
            sender: "Acme",
            expiredIn: 300,
            type: OtpType.Digit,
            digitSize: 6,
            provider: OtpProvider.Sms);

        Assert.Equal("POST", handler.Method!.Method);
        Assert.Equal("/v1/otp/send", handler.Path);

        using var doc = JsonDocument.Parse(handler.RequestBody!);
        var root = doc.RootElement;
        Assert.Equal("+1", root.GetProperty("to").GetString());
        Assert.Equal("digit", root.GetProperty("type").GetString());
        Assert.Equal("sms", root.GetProperty("provider").GetString());
        Assert.Equal(6, root.GetProperty("digit_size").GetInt32());
        Assert.Equal(300, root.GetProperty("expired_in").GetInt32());

        Assert.Equal("o1", result.Id);
        Assert.Equal(300, result.ExpiresIn);
    }

    [Fact]
    public void Send_AcceptsRawStringValues()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"id\": \"o1\", \"provider\": \"sms\", \"recipient\": {}, " +
            "\"expires_at\": \"x\", \"expires_in\": 60}");
        using var client = TestClient.Create(handler);

        client.Otp.Send(to: "+1", message: "m", type: "alphanumeric", provider: "sms");

        using var doc = JsonDocument.Parse(handler.RequestBody!);
        Assert.Equal("alphanumeric", doc.RootElement.GetProperty("type").GetString());
    }

    [Fact]
    public void Send_WhatsAppRequiresInstanceCode()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{}");
        using var client = TestClient.Create(handler);

        Assert.Throws<ValidationException>(() =>
            client.Otp.Send(to: "+1", message: "m", provider: OtpProvider.WhatsApp));
    }

    [Fact]
    public void Send_WhatsAppWithInstanceCodeUsesInstanceCodeKey()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"id\": \"o1\", \"provider\": \"whatsapp\", \"recipient\": {}, " +
            "\"expires_at\": \"x\", \"expires_in\": 60}");
        using var client = TestClient.Create(handler);

        client.Otp.Send(to: "+1", message: "m", provider: OtpProvider.WhatsApp, instanceCode: "INST");

        using var doc = JsonDocument.Parse(handler.RequestBody!);
        Assert.Equal("INST", doc.RootElement.GetProperty("instanceCode").GetString());
    }

    [Fact]
    public void Verify_PostsOtpIdAndCode()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"id\": \"o1\", \"status\": \"verified\", \"verified\": true, \"verified_at\": \"now\"}");
        using var client = TestClient.Create(handler);

        var result = client.Otp.Verify("o1", "123456");

        Assert.Equal("/v1/otp/verify", handler.Path);
        using var doc = JsonDocument.Parse(handler.RequestBody!);
        Assert.Equal("o1", doc.RootElement.GetProperty("otp_id").GetString());
        Assert.Equal("123456", doc.RootElement.GetProperty("code").GetString());
        Assert.True(result.Verified);
    }

    [Fact]
    public void GetStatus_UsesOtpsPath()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{\"id\": \"o1\", \"status\": \"pending\"}");
        using var client = TestClient.Create(handler);

        var otp = client.Otp.GetStatus("o1");

        Assert.Equal("/v1/otps/o1", handler.Path);
        Assert.Equal("pending", otp.Status);
    }

    [Fact]
    public void List_UsesLimitQueryKey()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"items\": [], \"total\": 0, \"per_page\": 0, \"current_page\": 1, " +
            "\"next\": null, \"previous\": null, \"total_pages\": 0}");
        using var client = TestClient.Create(handler);

        client.Otp.List(page: 2, limit: 25);

        Assert.Equal("/v1/otps", handler.Path);
        var query = handler.Query();
        Assert.Equal("2", query["page"]);
        Assert.Equal("25", query["limit"]);
        Assert.False(query.ContainsKey("per_page"));
    }
}
