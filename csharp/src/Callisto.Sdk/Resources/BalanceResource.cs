using System.Collections.Generic;
using System.Net.Http;
using Callisto.Sdk.Http;
using Callisto.Sdk.Models;

namespace Callisto.Sdk.Resources;

/// <summary>Account balance operations.</summary>
public sealed class BalanceResource
{
    private readonly Transport _t;

    internal BalanceResource(Transport transport) => _t = transport;

    /// <summary>Returns the account balance. <c>GET /sms/balance</c>.</summary>
    /// <param name="format">Response format. Defaults to <c>"full"</c>.</param>
    /// <param name="currency">Optional currency code to filter the balance.</param>
    public Balance Get(string format = "full", string? currency = null)
    {
        var query = new Dictionary<string, object?>
        {
            ["format"] = format,
            ["currency"] = currency,
        };
        return _t.Request<Balance>(HttpMethod.Get, "/sms/balance", query: query);
    }
}
