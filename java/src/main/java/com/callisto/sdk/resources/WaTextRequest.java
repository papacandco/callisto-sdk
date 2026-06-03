package com.callisto.sdk.resources;

import java.util.Objects;

/** Options for {@link WhatsApp#sendText(WaTextRequest)}. */
public final class WaTextRequest {

    private final String code;
    private final String to;
    private final String message;
    private final String scheduledAt;

    private WaTextRequest(Builder b) {
        this.code = b.code;
        this.to = b.to;
        this.message = b.message;
        this.scheduledAt = b.scheduledAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getCode() {
        return code;
    }

    public String getTo() {
        return to;
    }

    public String getMessage() {
        return message;
    }

    public String getScheduledAt() {
        return scheduledAt;
    }

    public static final class Builder {
        private String code;
        private String to;
        private String message;
        private String scheduledAt;

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder scheduledAt(String scheduledAt) {
            this.scheduledAt = scheduledAt;
            return this;
        }

        public WaTextRequest build() {
            Objects.requireNonNull(code, "code is required");
            Objects.requireNonNull(to, "to is required");
            Objects.requireNonNull(message, "message is required");
            return new WaTextRequest(this);
        }
    }
}
