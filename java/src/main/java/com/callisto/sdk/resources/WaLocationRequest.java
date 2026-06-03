package com.callisto.sdk.resources;

import java.util.Objects;

/** Options for {@link WhatsApp#sendLocation(WaLocationRequest)}. */
public final class WaLocationRequest {

    private final String code;
    private final String to;
    private final double latitude;
    private final double longitude;
    private final String name;
    private final String address;
    private final String scheduledAt;

    private WaLocationRequest(Builder b) {
        this.code = b.code;
        this.to = b.to;
        this.latitude = b.latitude;
        this.longitude = b.longitude;
        this.name = b.name;
        this.address = b.address;
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

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getScheduledAt() {
        return scheduledAt;
    }

    public static final class Builder {
        private String code;
        private String to;
        private Double latitude;
        private Double longitude;
        private String name;
        private String address;
        private String scheduledAt;

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        public Builder latitude(double latitude) {
            this.latitude = latitude;
            return this;
        }

        public Builder longitude(double longitude) {
            this.longitude = longitude;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder address(String address) {
            this.address = address;
            return this;
        }

        public Builder scheduledAt(String scheduledAt) {
            this.scheduledAt = scheduledAt;
            return this;
        }

        public WaLocationRequest build() {
            Objects.requireNonNull(code, "code is required");
            Objects.requireNonNull(to, "to is required");
            Objects.requireNonNull(latitude, "latitude is required");
            Objects.requireNonNull(longitude, "longitude is required");
            return new WaLocationRequest(this);
        }
    }
}
