package com.callisto.sdk.resources;

import com.callisto.sdk.enums.OtpProvider;
import com.callisto.sdk.errors.ValidationException;
import com.callisto.sdk.http.Transport;
import com.callisto.sdk.models.Paginated;
import com.callisto.sdk.models.SendOtpResult;
import com.callisto.sdk.models.VerifyOtpResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** The OTP resource. */
public class Otp {

    private final Transport transport;

    public Otp(Transport transport) {
        this.transport = transport;
    }

    /**
     * Generates and sends a one-time password.
     *
     * <p>When {@code provider == whatsapp}, {@code instanceCode} is required; otherwise a
     * {@link ValidationException} is thrown before any request is made. {@code instanceCode}
     * is sent to the API as {@code instanceCode}.
     *
     * @param request send options.
     */
    public SendOtpResult send(OtpSendRequest request) {
        String provider = request.getProvider();
        if (OtpProvider.WHATSAPP.getValue().equals(provider)
                && (request.getInstanceCode() == null || request.getInstanceCode().isEmpty())) {
            ValidationException ex =
                    new ValidationException("instanceCode is required when provider is whatsapp");
            if (transport.reporter() != null) {
                transport.reporter().captureException(ex);
            }
            throw ex;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", request.getTo());
        body.put("message", request.getMessage());
        if (request.getSender() != null) {
            body.put("sender", request.getSender());
        }
        if (request.getExpiredIn() != null) {
            body.put("expired_in", request.getExpiredIn());
        }
        if (request.getType() != null) {
            body.put("type", request.getType());
        }
        if (request.getDigitSize() != null) {
            body.put("digit_size", request.getDigitSize());
        }
        if (provider != null) {
            body.put("provider", provider);
        }
        if (request.getInstanceCode() != null) {
            body.put("instanceCode", request.getInstanceCode());
        }
        return transport.mapper().convertValue(
                transport.request("POST", "/otp/send", body, null), SendOtpResult.class);
    }

    /**
     * Verifies a code against an OTP.
     *
     * @param otpId the OTP ID returned by {@code send}.
     * @param code  the code submitted by the user.
     */
    public VerifyOtpResult verify(String otpId, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("otp_id", otpId);
        body.put("code", code);
        return transport.mapper().convertValue(
                transport.request("POST", "/otp/verify", body, null), VerifyOtpResult.class);
    }

    /**
     * Fetches a single OTP by ID.
     *
     * @param otpId the OTP ID.
     */
    public com.callisto.sdk.models.Otp getStatus(String otpId) {
        return transport.mapper().convertValue(
                transport.request("GET", "/otps/" + otpId, null, null),
                com.callisto.sdk.models.Otp.class);
    }

    /** Lists OTPs with no filters. */
    public Paginated<com.callisto.sdk.models.Otp> list() {
        return list(null, null, null, null);
    }

    /**
     * Lists OTPs.
     *
     * @param startedAt optional start date/time filter.
     * @param endedAt   optional end date/time filter.
     * @param page      optional page number.
     * @param limit     optional items per page (sent as query key {@code limit}).
     */
    public Paginated<com.callisto.sdk.models.Otp> list(String startedAt, String endedAt,
                                                       Integer page, Integer limit) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("started_at", startedAt);
        query.put("ended_at", endedAt);
        query.put("page", page);
        query.put("limit", limit);
        return Paginated.fromJson(
                transport.request("GET", "/otps", null, query),
                transport.mapper(), com.callisto.sdk.models.Otp.class);
    }
}
