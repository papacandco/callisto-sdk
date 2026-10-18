package com.callisto.sdk.logs;

import java.util.Locale;

/** Log severity, mapped to the OTLP {@code severityNumber} / {@code severityText} pair. */
public enum LogLevel {
    DEBUG(5, "DEBUG"),
    INFO(9, "INFO"),
    WARN(13, "WARN"),
    ERROR(17, "ERROR"),
    FATAL(21, "FATAL");

    private final int severityNumber;
    private final String severityText;

    LogLevel(int severityNumber, String severityText) {
        this.severityNumber = severityNumber;
        this.severityText = severityText;
    }

    /** The OTLP {@code severityNumber}. */
    public int severityNumber() {
        return severityNumber;
    }

    /** The OTLP {@code severityText}. */
    public String severityText() {
        return severityText;
    }

    /**
     * Parses {@code debug|info|warn|error|fatal} (any case; {@code warning} is accepted for
     * {@code warn}). Returns {@code null} for anything else.
     */
    public static LogLevel fromString(String level) {
        if (level == null) {
            return null;
        }
        switch (level.trim().toLowerCase(Locale.ROOT)) {
            case "debug":
                return DEBUG;
            case "info":
                return INFO;
            case "warn":
            case "warning":
                return WARN;
            case "error":
                return ERROR;
            case "fatal":
                return FATAL;
            default:
                return null;
        }
    }
}
