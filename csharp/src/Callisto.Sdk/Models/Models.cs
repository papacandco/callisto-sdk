using System.Collections.Generic;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Callisto.Sdk.Models;

/// <summary>Account balance. Returned by <c>client.Balance.Get(...)</c>.</summary>
public sealed class Balance
{
    [JsonPropertyName("credit")] public double Credit { get; set; }
    [JsonPropertyName("currency")] public string Currency { get; set; } = "";
    [JsonPropertyName("sms_price_local")] public double? SmsPriceLocal { get; set; }
    [JsonPropertyName("sms_price_international")] public double? SmsPriceInternational { get; set; }
}

/// <summary>Result of <c>client.Sms.Send(...)</c>.</summary>
public sealed class SendSmsResult
{
    [JsonPropertyName("total_amount")] public double TotalAmount { get; set; }
    [JsonPropertyName("available_credit")] public double AvailableCredit { get; set; }
    [JsonPropertyName("status")] public string Status { get; set; } = "";
    [JsonPropertyName("recipient_count")] public int RecipientCount { get; set; }
    [JsonPropertyName("scheduled")] public bool Scheduled { get; set; }
    [JsonPropertyName("messages")] public List<JsonElement> Messages { get; set; } = new();
}

/// <summary>Result of <c>client.Otp.Send(...)</c>.</summary>
public sealed class SendOtpResult
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("provider")] public string Provider { get; set; } = "";
    [JsonPropertyName("recipient")] public JsonElement Recipient { get; set; }
    [JsonPropertyName("expires_at")] public string ExpiresAt { get; set; } = "";
    [JsonPropertyName("expires_in")] public int ExpiresIn { get; set; }
}

/// <summary>Result of <c>client.Otp.Verify(...)</c>.</summary>
public sealed class VerifyOtpResult
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("status")] public string Status { get; set; } = "";
    [JsonPropertyName("verified")] public bool Verified { get; set; }
    [JsonPropertyName("verified_at")] public string? VerifiedAt { get; set; }
}

/// <summary>Result of the <c>client.WhatsApp.Send*(...)</c> family.</summary>
public sealed class SendWaResult
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("instance_id")] public string InstanceId { get; set; } = "";
    [JsonPropertyName("recipient")] public JsonElement Recipient { get; set; }
    [JsonPropertyName("message_type")] public string MessageType { get; set; } = "";
    [JsonPropertyName("status")] public string Status { get; set; } = "";
    [JsonPropertyName("scheduled")] public bool Scheduled { get; set; }
    [JsonPropertyName("media_url")] public string? MediaUrl { get; set; }
}

/// <summary>Result of <c>client.Notify.Send(...)</c>.</summary>
public sealed class NotifyResult
{
    [JsonPropertyName("status")] public string Status { get; set; } = "";
    [JsonPropertyName("topic")] public JsonElement Topic { get; set; }
    [JsonPropertyName("queued_events")] public JsonElement QueuedEvents { get; set; }
    [JsonPropertyName("topic_messages")] public JsonElement TopicMessages { get; set; }
}

/// <summary>A single SMS message record.</summary>
public sealed class SmsMessage
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("sender_name")] public string? SenderName { get; set; }
    [JsonPropertyName("recipient")] public string? Recipient { get; set; }
    [JsonPropertyName("content")] public string? Content { get; set; }
    [JsonPropertyName("status")] public string? Status { get; set; }
    [JsonPropertyName("created_at")] public string? CreatedAt { get; set; }
    [JsonPropertyName("updated_at")] public string? UpdatedAt { get; set; }
}

/// <summary>A single OTP record.</summary>
public sealed class Otp
{
    [JsonPropertyName("otp_id")] public string? OtpId { get; set; }
    [JsonPropertyName("id")] public string? Id { get; set; }
    [JsonPropertyName("status")] public string? Status { get; set; }
    [JsonPropertyName("recipient")] public string? Recipient { get; set; }
    [JsonPropertyName("expires_at")] public string? ExpiresAt { get; set; }
    [JsonPropertyName("verified_at")] public string? VerifiedAt { get; set; }
    [JsonPropertyName("attempts")] public int? Attempts { get; set; }
    [JsonPropertyName("created_at")] public string? CreatedAt { get; set; }
}

/// <summary>A WhatsApp instance record.</summary>
public sealed class WhatsAppInstance
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("code")] public string? Code { get; set; }
    [JsonPropertyName("client_id")] public string? ClientId { get; set; }
    [JsonPropertyName("name")] public string? Name { get; set; }
    [JsonPropertyName("phone_number")] public string? PhoneNumber { get; set; }
    [JsonPropertyName("phone_name")] public string? PhoneName { get; set; }
    [JsonPropertyName("status")] public string? Status { get; set; }
    [JsonPropertyName("billing_status")] public string? BillingStatus { get; set; }
    [JsonPropertyName("trial_days_remaining")] public int? TrialDaysRemaining { get; set; }
    [JsonPropertyName("monthly_fee")] public double? MonthlyFee { get; set; }
    [JsonPropertyName("messages_sent_today")] public int? MessagesSentToday { get; set; }
    [JsonPropertyName("messages_sent_month")] public int? MessagesSentMonth { get; set; }
    [JsonPropertyName("daily_limit")] public int? DailyLimit { get; set; }
    [JsonPropertyName("last_message_at")] public string? LastMessageAt { get; set; }
    [JsonPropertyName("webhook_url")] public string? WebhookUrl { get; set; }
    [JsonPropertyName("is_active")] public bool? IsActive { get; set; }
    [JsonPropertyName("created_at")] public string? CreatedAt { get; set; }
    [JsonPropertyName("updated_at")] public string? UpdatedAt { get; set; }
}

/// <summary>A WhatsApp message record.</summary>
public sealed class WhatsAppMessage
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("instance_id")] public string? InstanceId { get; set; }
    [JsonPropertyName("client_id")] public string? ClientId { get; set; }
    [JsonPropertyName("api_client_id")] public string? ApiClientId { get; set; }
    [JsonPropertyName("recipient")] public string? Recipient { get; set; }
    [JsonPropertyName("recipient_name")] public string? RecipientName { get; set; }
    [JsonPropertyName("message_type")] public string? MessageType { get; set; }
    [JsonPropertyName("content")] public string? Content { get; set; }
    [JsonPropertyName("media_url")] public string? MediaUrl { get; set; }
    [JsonPropertyName("media_mimetype")] public string? MediaMimetype { get; set; }
    [JsonPropertyName("media_filename")] public string? MediaFilename { get; set; }
    [JsonPropertyName("extra_data")] public JsonElement? ExtraData { get; set; }
    [JsonPropertyName("direction")] public string? Direction { get; set; }
    [JsonPropertyName("status")] public string? Status { get; set; }
    [JsonPropertyName("whatsapp_message_id")] public string? WhatsAppMessageId { get; set; }
    [JsonPropertyName("error_code")] public int? ErrorCode { get; set; }
    [JsonPropertyName("error_message")] public string? ErrorMessage { get; set; }
    [JsonPropertyName("retry_count")] public int? RetryCount { get; set; }
    [JsonPropertyName("is_billable")] public bool? IsBillable { get; set; }
    [JsonPropertyName("cost")] public double? Cost { get; set; }
    [JsonPropertyName("sent_at")] public string? SentAt { get; set; }
    [JsonPropertyName("delivered_at")] public string? DeliveredAt { get; set; }
    [JsonPropertyName("read_at")] public string? ReadAt { get; set; }
    [JsonPropertyName("scheduled_at")] public string? ScheduledAt { get; set; }
    [JsonPropertyName("created_at")] public string? CreatedAt { get; set; }
    [JsonPropertyName("updated_at")] public string? UpdatedAt { get; set; }
    [JsonPropertyName("processor_identifier")] public string? ProcessorIdentifier { get; set; }
}

/// <summary>A paginated collection of items.</summary>
/// <typeparam name="T">The item type.</typeparam>
public sealed class Paginated<T>
{
    [JsonPropertyName("items")] public List<T> Items { get; set; } = new();
    [JsonPropertyName("total")] public int Total { get; set; }
    [JsonPropertyName("per_page")] public int PerPage { get; set; }
    [JsonPropertyName("current_page")] public int CurrentPage { get; set; }
    [JsonPropertyName("next")] public int? Next { get; set; }
    [JsonPropertyName("previous")] public int? Previous { get; set; }
    [JsonPropertyName("total_pages")] public int TotalPages { get; set; }
}
