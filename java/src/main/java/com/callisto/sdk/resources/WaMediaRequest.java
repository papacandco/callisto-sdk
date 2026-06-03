package com.callisto.sdk.resources;

import com.callisto.sdk.enums.WhatsAppMediaType;

import java.util.Objects;

/** Options for {@link WhatsApp#sendMedia(WaMediaRequest)}. */
public final class WaMediaRequest {

    private final String code;
    private final String to;
    private final String type;
    private final String mediaUrl;
    private final String caption;
    private final String filename;
    private final String scheduledAt;

    private WaMediaRequest(Builder b) {
        this.code = b.code;
        this.to = b.to;
        this.type = b.type;
        this.mediaUrl = b.mediaUrl;
        this.caption = b.caption;
        this.filename = b.filename;
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

    public String getType() {
        return type;
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public String getCaption() {
        return caption;
    }

    public String getFilename() {
        return filename;
    }

    public String getScheduledAt() {
        return scheduledAt;
    }

    public static final class Builder {
        private String code;
        private String to;
        private String type;
        private String mediaUrl;
        private String caption;
        private String filename;
        private String scheduledAt;

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        public Builder type(WhatsAppMediaType type) {
            this.type = type == null ? null : type.getValue();
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder mediaUrl(String mediaUrl) {
            this.mediaUrl = mediaUrl;
            return this;
        }

        public Builder caption(String caption) {
            this.caption = caption;
            return this;
        }

        public Builder filename(String filename) {
            this.filename = filename;
            return this;
        }

        public Builder scheduledAt(String scheduledAt) {
            this.scheduledAt = scheduledAt;
            return this;
        }

        public WaMediaRequest build() {
            Objects.requireNonNull(code, "code is required");
            Objects.requireNonNull(to, "to is required");
            Objects.requireNonNull(type, "type is required");
            Objects.requireNonNull(mediaUrl, "mediaUrl is required");
            return new WaMediaRequest(this);
        }
    }
}
