package com.callisto.sdk.resources;

import java.util.List;
import java.util.Objects;

/**
 * Options for {@link Sms#send(SmsSendRequest)}.
 *
 * <p>{@code to} accepts either a single recipient ({@link #to(String)}) or a list
 * ({@link Builder#to(List)}).
 */
public final class SmsSendRequest {

    private final String sender;
    private final Object to;
    private final String message;
    private final String notifyUrl;
    private final String scheduledAt;

    private SmsSendRequest(Builder b) {
        this.sender = b.sender;
        this.to = b.to;
        this.message = b.message;
        this.notifyUrl = b.notifyUrl;
        this.scheduledAt = b.scheduledAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getSender() {
        return sender;
    }

    public Object getTo() {
        return to;
    }

    public String getMessage() {
        return message;
    }

    public String getNotifyUrl() {
        return notifyUrl;
    }

    public String getScheduledAt() {
        return scheduledAt;
    }

    public static final class Builder {
        private String sender;
        private Object to;
        private String message;
        private String notifyUrl;
        private String scheduledAt;

        public Builder sender(String sender) {
            this.sender = sender;
            return this;
        }

        /** Sets a single recipient. */
        public Builder to(String to) {
            this.to = to;
            return this;
        }

        /** Sets a list of recipients. */
        public Builder to(List<String> to) {
            this.to = to;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder notifyUrl(String notifyUrl) {
            this.notifyUrl = notifyUrl;
            return this;
        }

        public Builder scheduledAt(String scheduledAt) {
            this.scheduledAt = scheduledAt;
            return this;
        }

        public SmsSendRequest build() {
            Objects.requireNonNull(sender, "sender is required");
            Objects.requireNonNull(to, "to is required");
            Objects.requireNonNull(message, "message is required");
            return new SmsSendRequest(this);
        }
    }
}
