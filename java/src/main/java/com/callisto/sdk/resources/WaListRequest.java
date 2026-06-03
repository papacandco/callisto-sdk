package com.callisto.sdk.resources;

import java.util.List;
import java.util.Objects;

/** Options for {@link WhatsApp#sendList(WaListRequest)}. */
public final class WaListRequest {

    private final String code;
    private final String to;
    private final String body;
    private final String buttonText;
    private final List<?> sections;
    private final String header;
    private final String footer;
    private final String scheduledAt;

    private WaListRequest(Builder b) {
        this.code = b.code;
        this.to = b.to;
        this.body = b.body;
        this.buttonText = b.buttonText;
        this.sections = b.sections;
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

    public String getButtonText() {
        return buttonText;
    }

    public List<?> getSections() {
        return sections;
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
        private String buttonText;
        private List<?> sections;
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

        public Builder buttonText(String buttonText) {
            this.buttonText = buttonText;
            return this;
        }

        /** List of section objects (each with rows). */
        public Builder sections(List<?> sections) {
            this.sections = sections;
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

        public WaListRequest build() {
            Objects.requireNonNull(code, "code is required");
            Objects.requireNonNull(to, "to is required");
            Objects.requireNonNull(body, "body is required");
            Objects.requireNonNull(buttonText, "buttonText is required");
            Objects.requireNonNull(sections, "sections is required");
            return new WaListRequest(this);
        }
    }
}
