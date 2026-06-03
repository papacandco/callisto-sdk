package com.callisto.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A WhatsApp instance. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WhatsAppInstance {

    @JsonProperty("id")
    private String id;

    @JsonProperty("code")
    private String code;

    @JsonProperty("client_id")
    private String clientId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("phone_number")
    private String phoneNumber;

    @JsonProperty("phone_name")
    private String phoneName;

    @JsonProperty("status")
    private String status;

    @JsonProperty("billing_status")
    private String billingStatus;

    @JsonProperty("trial_days_remaining")
    private Integer trialDaysRemaining;

    @JsonProperty("monthly_fee")
    private Double monthlyFee;

    @JsonProperty("messages_sent_today")
    private Integer messagesSentToday;

    @JsonProperty("messages_sent_month")
    private Integer messagesSentMonth;

    @JsonProperty("daily_limit")
    private Integer dailyLimit;

    @JsonProperty("last_message_at")
    private String lastMessageAt;

    @JsonProperty("webhook_url")
    private String webhookUrl;

    @JsonProperty("is_active")
    private Boolean isActive;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    public String getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getClientId() {
        return clientId;
    }

    public String getName() {
        return name;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getPhoneName() {
        return phoneName;
    }

    public String getStatus() {
        return status;
    }

    public String getBillingStatus() {
        return billingStatus;
    }

    public Integer getTrialDaysRemaining() {
        return trialDaysRemaining;
    }

    public Double getMonthlyFee() {
        return monthlyFee;
    }

    public Integer getMessagesSentToday() {
        return messagesSentToday;
    }

    public Integer getMessagesSentMonth() {
        return messagesSentMonth;
    }

    public Integer getDailyLimit() {
        return dailyLimit;
    }

    public String getLastMessageAt() {
        return lastMessageAt;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }
}
