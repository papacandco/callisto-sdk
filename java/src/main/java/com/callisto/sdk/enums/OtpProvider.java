package com.callisto.sdk.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Delivery channel for an OTP. */
public enum OtpProvider {
    SMS("sms"),
    WHATSAPP("whatsapp");

    private final String value;

    OtpProvider(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static OtpProvider fromValue(String value) {
        for (OtpProvider v : values()) {
            if (v.value.equals(value)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown OtpProvider: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
