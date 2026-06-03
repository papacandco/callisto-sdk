using System.Net;
using Callisto.Sdk;
using Xunit;

namespace Callisto.Sdk.Tests;

public class BalanceTests
{
    [Fact]
    public void Get_SendsRequestAndDecodes()
    {
        var handler = new FakeHandler(HttpStatusCode.OK,
            "{\"credit\": 12.5, \"currency\": \"XOF\", \"sms_price_local\": 0.9}");
        using var client = TestClient.Create(handler);

        var balance = client.Balance.Get();

        Assert.Equal("GET", handler.Method!.Method);
        Assert.Equal("/v1/sms/balance", handler.Path);
        Assert.Equal("full", handler.Query()["format"]);
        Assert.Equal("Basic Y2lkOmtleQ==", handler.Authorization); // base64("cid:key")
        Assert.Contains("application/json", handler.Accept);

        Assert.Equal(12.5, balance.Credit);
        Assert.Equal("XOF", balance.Currency);
        Assert.Equal(0.9, balance.SmsPriceLocal);
        Assert.Null(balance.SmsPriceInternational);
    }

    [Fact]
    public void Get_PassesFormatAndCurrencyDropsNull()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{\"credit\": 0, \"currency\": \"USD\"}");
        using var client = TestClient.Create(handler);

        client.Balance.Get(format: "compact", currency: "USD");

        var query = handler.Query();
        Assert.Equal("compact", query["format"]);
        Assert.Equal("USD", query["currency"]);
    }

    [Fact]
    public void Get_NullCurrencyIsDropped()
    {
        var handler = new FakeHandler(HttpStatusCode.OK, "{\"credit\": 0, \"currency\": \"USD\"}");
        using var client = TestClient.Create(handler);

        client.Balance.Get();

        Assert.False(handler.Query().ContainsKey("currency"));
    }
}
