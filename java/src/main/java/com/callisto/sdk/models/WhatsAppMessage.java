package com.callisto.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** A WhatsApp message. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WhatsAppMessage {

    @JsonProperty("id")
    private String id;

    @JsonProperty("instance_id")
    private String instanceId;

    @JsonProperty("client_id")
    private String clientId;

    @JsonProperty("client_api_id")
    private String clientApiId;

    @JsonProperty("recipient")
    private String recipient;

    @JsonProperty("recipient_name")
    private String recipientName;

    @JsonProperty("message_type")
    private String messageType;

    @JsonProperty("content")
    private String content;

    @JsonProperty("media_url")
    private String mediaUrl;

    @JsonProperty("media_mimetype")
    private String mediaMimetype;

    @JsonProperty("media_filename")
    private String mediaFilename;

    @JsonProperty("extra_data")
    private Map<String, Object> extraData;

    @JsonProperty("direction")
    private String direction;

    @JsonProperty("status")
    private String status;

    @JsonProperty("whatsapp_message_id")
    private String whatsappMessageId;

    @JsonProperty("error_code")
    private Integer errorCode;

    @JsonProperty("error_message")
    private String errorMessage;

    @JsonProperty("retry_count")
    private Integer retryCount;

    @JsonProperty("is_billable")
    private Boolean isBillable;

    @JsonProperty("cost")
    private Double cost;

    @JsonProperty("sent_at")
    private String sentAt;

    @JsonProperty("delivered_at")
    private String deliveredAt;

    @JsonProperty("read_at")
    private String readAt;

    @JsonProperty("scheduled_at")
    private String scheduledAt;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("processor_identifier")
    private String processorIdentifier;

    public String getId() {
        return id;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientApiId() {
        return clientApiId;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getContent() {
        return content;
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public String getMediaMimetype() {
        return mediaMimetype;
    }

    public String getMediaFilename() {
        return mediaFilename;
    }

    public Map<String, Object> getExtraData() {
        return extraData;
    }

    public String getDirection() {
        return direction;
    }

    public String getStatus() {
        return status;
    }

    public String getWhatsappMessageId() {
        return whatsappMessageId;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public Boolean getIsBillable() {
        return isBillable;
    }

    public Double getCost() {
        return cost;
    }

    public String getSentAt() {
        return sentAt;
    }

    public String getDeliveredAt() {
        return deliveredAt;
    }

    public String getReadAt() {
        return readAt;
    }

    public String getScheduledAt() {
        return scheduledAt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public String getProcessorIdentifier() {
        return processorIdentifier;
    }
}
