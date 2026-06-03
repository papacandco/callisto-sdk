package com.callisto.sdk.resources;

import com.callisto.sdk.errors.ValidationException;
import com.callisto.sdk.http.Transport;
import com.callisto.sdk.models.NotifyResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The notify resource. */
public class Notify {

    private final Transport transport;

    public Notify(Transport transport) {
        this.transport = transport;
    }

    /**
     * Sends a multi-channel notification to a topic.
     *
     * <p>At least one event block must be present, otherwise a {@link ValidationException} is
     * thrown before any request is made. JSON keys are snake_case ({@code email}, {@code sms},
     * {@code mobile_push}, {@code web_push}, {@code webhook}, {@code messaging},
     * {@code real_time}).
     *
     * @param request send options.
     */
    public NotifyResult send(NotifyRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", request.getTopic());

        boolean hasBlock = false;
        hasBlock |= put(body, "email", request.getEmail());
        hasBlock |= put(body, "sms", request.getSms());
        hasBlock |= put(body, "mobile_push", request.getMobilePush());
        hasBlock |= put(body, "web_push", request.getWebPush());
        hasBlock |= put(body, "webhook", request.getWebhook());
        hasBlock |= put(body, "messaging", request.getMessaging());
        hasBlock |= put(body, "real_time", request.getRealTime());

        if (!hasBlock) {
            ValidationException ex = new ValidationException(
                    "At least one event block (email, sms, mobile_push, web_push, "
                            + "webhook, messaging, real_time) must be provided.");
            if (transport.reporter() != null) {
                transport.reporter().captureException(ex);
            }
            throw ex;
        }
        return transport.mapper().convertValue(
                transport.request("POST", "/notify/send", body, null), NotifyResult.class);
    }

    private static boolean put(Map<String, Object> body, String key, List<?> value) {
        if (value != null && !value.isEmpty()) {
            body.put(key, value);
            return true;
        }
        return false;
    }
}
