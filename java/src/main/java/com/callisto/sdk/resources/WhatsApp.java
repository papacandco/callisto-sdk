package com.callisto.sdk.resources;

import com.callisto.sdk.http.Transport;
import com.callisto.sdk.models.Paginated;
import com.callisto.sdk.models.SendWaResult;
import com.callisto.sdk.models.WhatsAppInstance;
import com.callisto.sdk.models.WhatsAppMessage;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** The WhatsApp resource. */
public class WhatsApp {

    private final Transport transport;

    public WhatsApp(Transport transport) {
        this.transport = transport;
    }

    /** Creates a WhatsApp instance with only a display name. */
    public WhatsAppInstance createInstance(String name) {
        return createInstance(name, null, null, null);
    }

    /**
     * Creates a WhatsApp instance.
     *
     * @param name           instance display name.
     * @param phoneNumber    optional phone number to attach.
     * @param webhookUrl     optional webhook URL for incoming events.
     * @param idempotencyKey optional key to safely retry creation.
     */
    public WhatsAppInstance createInstance(String name, String phoneNumber,
                                           String webhookUrl, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        putIfPresent(body, "phone_number", phoneNumber);
        putIfPresent(body, "webhook_url", webhookUrl);
        putIfPresent(body, "idempotency_key", idempotencyKey);
        return transport.mapper().convertValue(
                transport.request("POST", "/whatsapp/instances", body, null), WhatsAppInstance.class);
    }

    /** Lists WhatsApp instances starting at page 1. */
    public Paginated<WhatsAppInstance> listInstances() {
        return listInstances(1);
    }

    /**
     * Lists WhatsApp instances.
     *
     * @param page page number (defaults to {@code 1}).
     */
    public Paginated<WhatsAppInstance> listInstances(int page) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page", page);
        return Paginated.fromJson(
                transport.request("GET", "/whatsapp/instances", null, query),
                transport.mapper(), WhatsAppInstance.class);
    }

    /**
     * Fetches a single instance.
     *
     * @param code instance code.
     */
    public WhatsAppInstance getInstance(String code) {
        return transport.mapper().convertValue(
                transport.request("GET", "/whatsapp/" + code, null, null), WhatsAppInstance.class);
    }

    /**
     * Fetches the QR code used to link the instance. Returns the raw {@link JsonNode}.
     *
     * @param code instance code.
     */
    public JsonNode getQr(String code) {
        return transport.request("GET", "/whatsapp/" + code + "/qr", null, null);
    }

    /**
     * Fetches the connection status of an instance. Returns the raw {@link JsonNode}.
     *
     * @param code instance code.
     */
    public JsonNode getStatus(String code) {
        return transport.request("GET", "/whatsapp/" + code + "/status", null, null);
    }

    /** Lists messages for an instance with no filters. */
    public Paginated<WhatsAppMessage> listMessages(String code) {
        return listMessages(code, null, null, null, null);
    }

    /**
     * Lists messages for an instance.
     *
     * @param code      instance code.
     * @param startedAt optional start date/time filter.
     * @param endedAt   optional end date/time filter.
     * @param page      optional page number.
     * @param perPage   optional items per page.
     */
    public Paginated<WhatsAppMessage> listMessages(String code, String startedAt, String endedAt,
                                                   Integer page, Integer perPage) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("started_at", startedAt);
        query.put("ended_at", endedAt);
        query.put("page", page);
        query.put("per_page", perPage);
        return Paginated.fromJson(
                transport.request("GET", "/whatsapp/" + code + "/messages", null, query),
                transport.mapper(), WhatsAppMessage.class);
    }

    /**
     * Fetches a single WhatsApp message.
     *
     * @param messageId the message ID.
     */
    public WhatsAppMessage getMessage(String messageId) {
        return transport.mapper().convertValue(
                transport.request("GET", "/whatsapp/messages/" + messageId, null, null),
                WhatsAppMessage.class);
    }

    /** Sends a text message. */
    public SendWaResult sendText(WaTextRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", request.getTo());
        body.put("message", request.getMessage());
        putIfPresent(body, "scheduled_at", request.getScheduledAt());
        return post(request.getCode(), "text", body);
    }

    /** Sends a media message. */
    public SendWaResult sendMedia(WaMediaRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", request.getTo());
        body.put("type", request.getType());
        body.put("media_url", request.getMediaUrl());
        putIfPresent(body, "caption", request.getCaption());
        putIfPresent(body, "filename", request.getFilename());
        putIfPresent(body, "scheduled_at", request.getScheduledAt());
        return post(request.getCode(), "media", body);
    }

    /** Sends an interactive buttons message. */
    public SendWaResult sendButtons(WaButtonsRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", request.getTo());
        body.put("body", request.getBody());
        body.put("buttons", request.getButtons());
        putIfPresent(body, "header", request.getHeader());
        putIfPresent(body, "footer", request.getFooter());
        putIfPresent(body, "scheduled_at", request.getScheduledAt());
        return post(request.getCode(), "buttons", body);
    }

    /** Sends a location message. */
    public SendWaResult sendLocation(WaLocationRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", request.getTo());
        body.put("latitude", request.getLatitude());
        body.put("longitude", request.getLongitude());
        putIfPresent(body, "name", request.getName());
        putIfPresent(body, "address", request.getAddress());
        putIfPresent(body, "scheduled_at", request.getScheduledAt());
        return post(request.getCode(), "location", body);
    }

    /** Sends an interactive list message. */
    public SendWaResult sendList(WaListRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", request.getTo());
        body.put("body", request.getBody());
        body.put("button_text", request.getButtonText());
        body.put("sections", request.getSections());
        putIfPresent(body, "header", request.getHeader());
        putIfPresent(body, "footer", request.getFooter());
        putIfPresent(body, "scheduled_at", request.getScheduledAt());
        return post(request.getCode(), "list", body);
    }

    private SendWaResult post(String code, String kind, Map<String, Object> body) {
        return transport.mapper().convertValue(
                transport.request("POST", "/whatsapp/" + code + "/send/" + kind, body, null),
                SendWaResult.class);
    }

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }
}
