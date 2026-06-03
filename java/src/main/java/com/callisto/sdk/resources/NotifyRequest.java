package com.callisto.sdk.resources;

import java.util.List;
import java.util.Objects;

/**
 * Options for {@link Notify#send(NotifyRequest)}.
 *
 * <p>At least one event block must be present, otherwise
 * {@code Notify#send} raises a validation error before any request is made.
 */
public final class NotifyRequest {

    private final String topic;
    private final List<?> email;
    private final List<?> sms;
    private final List<?> mobilePush;
    private final List<?> webPush;
    private final List<?> webhook;
    private final List<?> messaging;
    private final List<?> realTime;

    private NotifyRequest(Builder b) {
        this.topic = b.topic;
        this.email = b.email;
        this.sms = b.sms;
        this.mobilePush = b.mobilePush;
        this.webPush = b.webPush;
        this.webhook = b.webhook;
        this.messaging = b.messaging;
        this.realTime = b.realTime;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTopic() {
        return topic;
    }

    public List<?> getEmail() {
        return email;
    }

    public List<?> getSms() {
        return sms;
    }

    public List<?> getMobilePush() {
        return mobilePush;
    }

    public List<?> getWebPush() {
        return webPush;
    }

    public List<?> getWebhook() {
        return webhook;
    }

    public List<?> getMessaging() {
        return messaging;
    }

    public List<?> getRealTime() {
        return realTime;
    }

    public static final class Builder {
        private String topic;
        private List<?> email;
        private List<?> sms;
        private List<?> mobilePush;
        private List<?> webPush;
        private List<?> webhook;
        private List<?> messaging;
        private List<?> realTime;

        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder email(List<?> email) {
            this.email = email;
            return this;
        }

        public Builder sms(List<?> sms) {
            this.sms = sms;
            return this;
        }

        public Builder mobilePush(List<?> mobilePush) {
            this.mobilePush = mobilePush;
            return this;
        }

        public Builder webPush(List<?> webPush) {
            this.webPush = webPush;
            return this;
        }

        public Builder webhook(List<?> webhook) {
            this.webhook = webhook;
            return this;
        }

        public Builder messaging(List<?> messaging) {
            this.messaging = messaging;
            return this;
        }

        public Builder realTime(List<?> realTime) {
            this.realTime = realTime;
            return this;
        }

        public NotifyRequest build() {
            Objects.requireNonNull(topic, "topic is required");
            return new NotifyRequest(this);
        }
    }
}
