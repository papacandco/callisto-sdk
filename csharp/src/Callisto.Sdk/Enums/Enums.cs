namespace Callisto.Sdk.Enums;

/// <summary>Lifecycle status of an SMS/WhatsApp message.</summary>
public enum MessageStatus
{
    Pending,
    Sent,
    Delivered,
    Failed,
}

/// <summary>Lifecycle status of an OTP.</summary>
public enum OtpStatus
{
    Pending,
    Verified,
    Expired,
    Failed,
}

/// <summary>The kind of OTP code to generate.</summary>
public enum OtpType
{
    Digit,
    Alpha,
    Alphanumeric,
}

/// <summary>The channel used to deliver an OTP.</summary>
public enum OtpProvider
{
    Sms,
    WhatsApp,
}

/// <summary>The type of media in a WhatsApp media message.</summary>
public enum WhatsAppMediaType
{
    Image,
    Video,
    Document,
    Audio,
}

/// <summary>Maps SDK enums to/from their wire (lower-case) string values.</summary>
public static class EnumValues
{
    public static string Value(this OtpType type) => type switch
    {
        OtpType.Digit => "digit",
        OtpType.Alpha => "alpha",
        OtpType.Alphanumeric => "alphanumeric",
        _ => type.ToString().ToLowerInvariant(),
    };

    public static string Value(this OtpProvider provider) => provider switch
    {
        OtpProvider.Sms => "sms",
        OtpProvider.WhatsApp => "whatsapp",
        _ => provider.ToString().ToLowerInvariant(),
    };

    public static string Value(this WhatsAppMediaType type) => type switch
    {
        WhatsAppMediaType.Image => "image",
        WhatsAppMediaType.Video => "video",
        WhatsAppMediaType.Document => "document",
        WhatsAppMediaType.Audio => "audio",
        _ => type.ToString().ToLowerInvariant(),
    };
}
