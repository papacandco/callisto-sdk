package com.callisto.sdk.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Lifecycle status of an OTP. */
public enum OtpStatus {
    PENDING("pending"),
    VERIFIED("verified"),
    EXPIRED("expired"),
    FAILED("failed");

    private final String value;

    OtpStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static OtpStatus fromValue(String value) {
        for (OtpStatus v : values()) {
            if (v.value.equals(value)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown OtpStatus: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
