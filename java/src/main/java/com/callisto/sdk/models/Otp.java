package com.callisto.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An OTP record.
 *
 * <p>Carries both {@code otpId} (populated by {@code getStatus}) and {@code id}
 * (populated by {@code list} rows) — depending on the endpoint, one or the other may be set.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Otp {

    @JsonProperty("otp_id")
    private String otpId;

    @JsonProperty("id")
    private String id;

    @JsonProperty("status")
    private String status;

    @JsonProperty("recipient")
    private String recipient;

    @JsonProperty("expires_at")
    private String expiresAt;

    @JsonProperty("verified_at")
    private String verifiedAt;

    @JsonProperty("attempts")
    private Integer attempts;

    @JsonProperty("created_at")
    private String createdAt;

    public String getOtpId() {
        return otpId;
    }

    public String getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public String getVerifiedAt() {
        return verifiedAt;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}
