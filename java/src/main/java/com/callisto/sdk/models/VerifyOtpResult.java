package com.callisto.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Result of an OTP verification. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerifyOtpResult {

    @JsonProperty("id")
    private String id;

    @JsonProperty("status")
    private String status;

    @JsonProperty("verified")
    private boolean verified;

    @JsonProperty("verified_at")
    private String verifiedAt;

    public String getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public boolean isVerified() {
        return verified;
    }

    public String getVerifiedAt() {
        return verifiedAt;
    }
}
