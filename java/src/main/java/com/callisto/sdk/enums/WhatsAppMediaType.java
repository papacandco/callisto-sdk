package com.callisto.sdk.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Media type for a WhatsApp media message. */
public enum WhatsAppMediaType {
    IMAGE("image"),
    VIDEO("video"),
    DOCUMENT("document"),
    AUDIO("audio");

    private final String value;

    WhatsAppMediaType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static WhatsAppMediaType fromValue(String value) {
        for (WhatsAppMediaType v : values()) {
            if (v.value.equals(value)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown WhatsAppMediaType: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
