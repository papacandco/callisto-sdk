package com.callisto.sdk.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Character set for a generated OTP code. */
public enum OtpType {
    DIGIT("digit"),
    ALPHA("alpha"),
    ALPHANUMERIC("alphanumeric");

    private final String value;

    OtpType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static OtpType fromValue(String value) {
        for (OtpType v : values()) {
            if (v.value.equals(value)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown OtpType: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
