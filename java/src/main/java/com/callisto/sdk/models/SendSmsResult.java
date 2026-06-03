package com.callisto.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Result of an SMS send. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SendSmsResult {

    @JsonProperty("total_amount")
    private double totalAmount;

    @JsonProperty("available_credit")
    private double availableCredit;

    @JsonProperty("status")
    private String status;

    @JsonProperty("recipient_count")
    private int recipientCount;

    @JsonProperty("scheduled")
    private boolean scheduled;

    @JsonProperty("messages")
    private List<Object> messages;

    public double getTotalAmount() {
        return totalAmount;
    }

    public double getAvailableCredit() {
        return availableCredit;
    }

    public String getStatus() {
        return status;
    }

    public int getRecipientCount() {
        return recipientCount;
    }

    public boolean isScheduled() {
        return scheduled;
    }

    public List<Object> getMessages() {
        return messages;
    }
}
