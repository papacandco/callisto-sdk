using System.Collections.Generic;
using System.Net.Http;
using Callisto.Sdk.Enums;
using Callisto.Sdk.Errors;
using Callisto.Sdk.Http;
using Callisto.Sdk.Models;

namespace Callisto.Sdk.Resources;

/// <summary>One-time-password operations.</summary>
public sealed class OtpResource
{
    private readonly Transport _t;

    internal OtpResource(Transport transport) => _t = transport;

    /// <summary>Sends an OTP using a typed <see cref="OtpType"/> / <see cref="OtpProvider"/>.</summary>
    public SendOtpResult Send(
        string to,
        string message,
        string? sender = null,
        int? expiredIn = null,
        OtpType? type = null,
        int? digitSize = null,
        OtpProvider? provider = null,
        string? instanceCode = null)
        => Send(to, message, sender, expiredIn, type?.Value(), digitSize, provider?.Value(), instanceCode);

    /// <summary>
    /// Sends an OTP. <c>POST /otp/send</c>. The <paramref name="type"/> and
    /// <paramref name="provider"/> arguments accept the raw wire string values. When
    /// <paramref name="provider"/> is <c>"whatsapp"</c>, <paramref name="instanceCode"/> is
    /// required (sent as JSON key <c>instanceCode</c>).
    /// </summary>
    public SendOtpResult Send(
        string to,
        string message,
        string? sender = null,
        int? expiredIn = null,
        string? type = null,
        int? digitSize = null,
        string? provider = null,
        string? instanceCode = null)
    {
        if (provider == OtpProvider.WhatsApp.Value() && string.IsNullOrEmpty(instanceCode))
        {
            var error = new ValidationException("instance_code is required when provider is whatsapp");
            _t.CaptureClientError(error);
            throw error;
        }

        var body = new Dictionary<string, object?>
        {
            ["to"] = to,
            ["message"] = message,
        };
        if (sender is not null) body["sender"] = sender;
        if (expiredIn is not null) body["expired_in"] = expiredIn;
        if (type is not null) body["type"] = type;
        if (digitSize is not null) body["digit_size"] = digitSize;
        if (provider is not null) body["provider"] = provider;
        if (instanceCode is not null) body["instanceCode"] = instanceCode;

        return _t.Request<SendOtpResult>(HttpMethod.Post, "/otp/send", body: body);
    }

    /// <summary>Verifies an OTP code. <c>POST /otp/verify</c>.</summary>
    public VerifyOtpResult Verify(string otpId, string code)
    {
        var body = new Dictionary<string, object?> { ["otp_id"] = otpId, ["code"] = code };
        return _t.Request<VerifyOtpResult>(HttpMethod.Post, "/otp/verify", body: body);
    }

    /// <summary>Fetches the status of an OTP. <c>GET /otps/{id}</c>.</summary>
    public Otp GetStatus(string otpId)
        => _t.Request<Otp>(HttpMethod.Get, $"/otps/{otpId}");

    /// <summary>Lists OTPs. <c>GET /otps</c> (query key is <c>limit</c>).</summary>
    public Paginated<Otp> List(
        string? startedAt = null,
        string? endedAt = null,
        int? page = null,
        int? limit = null)
    {
        var query = new Dictionary<string, object?>
        {
            ["started_at"] = startedAt,
            ["ended_at"] = endedAt,
            ["page"] = page,
            ["limit"] = limit,
        };
        return _t.Request<Paginated<Otp>>(HttpMethod.Get, "/otps", query: query);
    }
}
