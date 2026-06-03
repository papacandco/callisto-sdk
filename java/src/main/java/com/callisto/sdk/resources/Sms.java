package com.callisto.sdk.resources;

import com.callisto.sdk.http.Transport;
import com.callisto.sdk.models.Paginated;
import com.callisto.sdk.models.SendSmsResult;
import com.callisto.sdk.models.SmsMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/** The SMS resource. */
public class Sms {

    private final Transport transport;

    public Sms(Transport transport) {
        this.transport = transport;
    }

    /**
     * Sends an SMS to one or more recipients.
     *
     * @param request send options.
     */
    public SendSmsResult send(SmsSendRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", request.getSender());
        body.put("to", request.getTo());
        body.put("message", request.getMessage());
        if (request.getNotifyUrl() != null) {
            body.put("notify_url", request.getNotifyUrl());
        }
        if (request.getScheduledAt() != null) {
            body.put("scheduled_at", request.getScheduledAt());
        }
        return transport.mapper().convertValue(
                transport.request("POST", "/sms/send", body, null), SendSmsResult.class);
    }

    /** Lists sent SMS messages with no filters. */
    public Paginated<SmsMessage> list() {
        return list(null, null, null, null);
    }

    /**
     * Lists sent SMS messages.
     *
     * @param startedAt optional start date/time filter.
     * @param endedAt   optional end date/time filter.
     * @param page      optional page number.
     * @param perPage   optional items per page.
     */
    public Paginated<SmsMessage> list(String startedAt, String endedAt, Integer page, Integer perPage) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("started_at", startedAt);
        query.put("ended_at", endedAt);
        query.put("page", page);
        query.put("per_page", perPage);
        return Paginated.fromJson(
                transport.request("GET", "/sms/messages", null, query),
                transport.mapper(), SmsMessage.class);
    }

    /**
     * Fetches a single SMS by ID.
     *
     * @param messageId the message ID.
     */
    public SmsMessage getStatus(String messageId) {
        return transport.mapper().convertValue(
                transport.request("GET", "/sms/" + messageId, null, null), SmsMessage.class);
    }
}
