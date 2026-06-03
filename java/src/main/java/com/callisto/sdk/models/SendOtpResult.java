package com.callisto.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** Result of an OTP send. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SendOtpResult {

    @JsonProperty("id")
    private String id;

    @JsonProperty("provider")
    private String provider;

    @JsonProperty("recipient")
    private Map<String, Object> recipient;

    @JsonProperty("expires_at")
    private String expiresAt;

    @JsonProperty("expires_in")
    private int expiresIn;

    public String getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public Map<String, Object> getRecipient() {
        return recipient;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public int getExpiresIn() {
        return expiresIn;
    }
}
