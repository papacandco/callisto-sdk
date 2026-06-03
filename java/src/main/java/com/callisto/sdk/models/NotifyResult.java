package com.callisto.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Result of a notify send. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NotifyResult {

    @JsonProperty("status")
    private String status;

    @JsonProperty("topic")
    private Object topic;

    @JsonProperty("queued_events")
    private Object queuedEvents;

    @JsonProperty("topic_messages")
    private Object topicMessages;

    public String getStatus() {
        return status;
    }

    public Object getTopic() {
        return topic;
    }

    public Object getQueuedEvents() {
        return queuedEvents;
    }

    public Object getTopicMessages() {
        return topicMessages;
    }
}
