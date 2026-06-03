package com.callisto.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Result of a WhatsApp send. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SendWaResult {

    @JsonProperty("id")
    private String id;

    @JsonProperty("instance_id")
    private String instanceId;

    @JsonProperty("recipient")
    private Object recipient;

    @JsonProperty("message_type")
    private String messageType;

    @JsonProperty("status")
    private String status;

    @JsonProperty("scheduled")
    private boolean scheduled;

    @JsonProperty("media_url")
    private String mediaUrl;

    public String getId() {
        return id;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public Object getRecipient() {
        return recipient;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getStatus() {
        return status;
    }

    public boolean isScheduled() {
        return scheduled;
    }

    public String getMediaUrl() {
        return mediaUrl;
    }
}
