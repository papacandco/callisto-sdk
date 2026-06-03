package com.callisto.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Account balance. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Balance {

    @JsonProperty("credit")
    private double credit;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("sms_price_local")
    private Double smsPriceLocal;

    @JsonProperty("sms_price_international")
    private Double smsPriceInternational;

    public double getCredit() {
        return credit;
    }

    public String getCurrency() {
        return currency;
    }

    public Double getSmsPriceLocal() {
        return smsPriceLocal;
    }

    public Double getSmsPriceInternational() {
        return smsPriceInternational;
    }
}
