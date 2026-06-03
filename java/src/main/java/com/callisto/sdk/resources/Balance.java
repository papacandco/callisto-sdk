package com.callisto.sdk.resources;

import com.callisto.sdk.http.Transport;

import java.util.LinkedHashMap;
import java.util.Map;

/** The balance resource. */
public class Balance {

    private final Transport transport;

    public Balance(Transport transport) {
        this.transport = transport;
    }

    /** Returns the account balance using the default format {@code "full"} and no currency filter. */
    public com.callisto.sdk.models.Balance get() {
        return get("full", null);
    }

    /**
     * Returns the account balance.
     *
     * @param format   response format (defaults to {@code "full"} when {@code null}).
     * @param currency optional currency code filter.
     */
    public com.callisto.sdk.models.Balance get(String format, String currency) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("format", format != null ? format : "full");
        query.put("currency", currency);
        return transport.mapper().convertValue(
                transport.request("GET", "/sms/balance", null, query),
                com.callisto.sdk.models.Balance.class);
    }
}
