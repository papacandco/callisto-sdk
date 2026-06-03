package com.callisto.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A sent SMS message. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SmsMessage {

    @JsonProperty("id")
    private String id;

    @JsonProperty("sender_name")
    private String senderName;

    @JsonProperty("recipient")
    private String recipient;

    @JsonProperty("content")
    private String content;

    @JsonProperty("status")
    private String status;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    public String getId() {
        return id;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getContent() {
        return content;
    }

    public String getStatus() {
        return status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }
}
