package com.callisto.sdk.resources;

import com.callisto.sdk.enums.OtpProvider;
import com.callisto.sdk.enums.OtpType;

import java.util.Objects;

/** Options for {@link Otp#send(OtpSendRequest)}. */
public final class OtpSendRequest {

    private final String to;
    private final String message;
    private final String sender;
    private final Integer expiredIn;
    private final String type;
    private final Integer digitSize;
    private final String provider;
    private final String instanceCode;

    private OtpSendRequest(Builder b) {
        this.to = b.to;
        this.message = b.message;
        this.sender = b.sender;
        this.expiredIn = b.expiredIn;
        this.type = b.type;
        this.digitSize = b.digitSize;
        this.provider = b.provider;
        this.instanceCode = b.instanceCode;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTo() {
        return to;
    }

    public String getMessage() {
        return message;
    }

    public String getSender() {
        return sender;
    }

    public Integer getExpiredIn() {
        return expiredIn;
    }

    public String getType() {
        return type;
    }

    public Integer getDigitSize() {
        return digitSize;
    }

    public String getProvider() {
        return provider;
    }

    public String getInstanceCode() {
        return instanceCode;
    }

    public static final class Builder {
        private String to;
        private String message;
        private String sender;
        private Integer expiredIn;
        private String type;
        private Integer digitSize;
        private String provider;
        private String instanceCode;

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder sender(String sender) {
            this.sender = sender;
            return this;
        }

        public Builder expiredIn(Integer expiredIn) {
            this.expiredIn = expiredIn;
            return this;
        }

        public Builder type(OtpType type) {
            this.type = type == null ? null : type.getValue();
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder digitSize(Integer digitSize) {
            this.digitSize = digitSize;
            return this;
        }

        public Builder provider(OtpProvider provider) {
            this.provider = provider == null ? null : provider.getValue();
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder instanceCode(String instanceCode) {
            this.instanceCode = instanceCode;
            return this;
        }

        public OtpSendRequest build() {
            Objects.requireNonNull(to, "to is required");
            Objects.requireNonNull(message, "message is required");
            return new OtpSendRequest(this);
        }
    }
}
