package com.callisto.sdk.resources;

import java.util.List;
import java.util.Objects;

/** Options for {@link WhatsApp#sendButtons(WaButtonsRequest)}. */
public final class WaButtonsRequest {

    private final String code;
    private final String to;
    private final String body;
    private final List<?> buttons;
    private final String header;
    private final String footer;
    private final String scheduledAt;

    private WaButtonsRequest(Builder b) {
        this.code = b.code;
        this.to = b.to;
        this.body = b.body;
        this.buttons = b.buttons;
        this.header = b.header;
        this.footer = b.footer;
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

    public String getBody() {
        return body;
    }

    public List<?> getButtons() {
        return buttons;
    }

    public String getHeader() {
        return header;
    }

    public String getFooter() {
        return footer;
    }

    public String getScheduledAt() {
        return scheduledAt;
    }

    public static final class Builder {
        private String code;
        private String to;
        private String body;
        private List<?> buttons;
        private String header;
        private String footer;
        private String scheduledAt;

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        /** List of button objects (e.g. {@code Map.of("id", "1", "title", "Yes")}). */
        public Builder buttons(List<?> buttons) {
            this.buttons = buttons;
            return this;
        }

        public Builder header(String header) {
            this.header = header;
            return this;
        }

        public Builder footer(String footer) {
            this.footer = footer;
            return this;
        }

        public Builder scheduledAt(String scheduledAt) {
            this.scheduledAt = scheduledAt;
            return this;
        }

        public WaButtonsRequest build() {
            Objects.requireNonNull(code, "code is required");
            Objects.requireNonNull(to, "to is required");
            Objects.requireNonNull(body, "body is required");
            Objects.requireNonNull(buttons, "buttons is required");
            return new WaButtonsRequest(this);
        }
    }
}
