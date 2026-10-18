package com.callisto.sdk.logs;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * A {@link java.util.logging.Handler} that forwards JUL records to a {@link CallistoLogger}.
 *
 * <pre>{@code
 * java.util.logging.Logger.getLogger("").addHandler(new CallistoLogHandler(logs));
 * }</pre>
 *
 * <p>Mapping: below {@code INFO} → debug, {@code INFO} → info, {@code WARNING} → warn,
 * {@code SEVERE} → error. The formatted message is the body; the logger name becomes
 * {@code logger.name}; a thrown exception becomes {@code exception.type},
 * {@code exception.message} and {@code exception.stacktrace}. Records emitted while the logger is
 * sending (its worker and HTTP threads, the JDK HTTP client's own loggers, or a re-entrant call)
 * are dropped, never shipped again.
 */
public class CallistoLogHandler extends Handler {

    private static final Formatter MESSAGE_FORMATTER = new SimpleFormatter();
    private static final ThreadLocal<Boolean> PUBLISHING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final CallistoLogger logger;

    public CallistoLogHandler(CallistoLogger logger) {
        this.logger = logger;
        setLevel(Level.ALL);
    }

    /** Maps a JUL level to a log level (SEVERE → error). */
    public static LogLevel mapLevel(Level level) {
        int v = level == null ? Level.INFO.intValue() : level.intValue();
        if (v >= Level.SEVERE.intValue()) {
            return LogLevel.ERROR;
        }
        if (v >= Level.WARNING.intValue()) {
            return LogLevel.WARN;
        }
        if (v >= Level.INFO.intValue()) {
            return LogLevel.INFO;
        }
        return LogLevel.DEBUG;
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || logger == null || PUBLISHING.get() || InternalThreads.isInternal()
                || isInternalLogger(record.getLoggerName())) {
            return;
        }
        PUBLISHING.set(Boolean.TRUE);
        try {
            if (!isLoggable(record)) {
                return;
            }
            Formatter formatter = getFormatter() != null ? getFormatter() : MESSAGE_FORMATTER;
            String message = formatter.formatMessage(record);

            Map<String, Object> attributes = new LinkedHashMap<>();
            if (record.getLoggerName() != null) {
                attributes.put("logger.name", record.getLoggerName());
            }
            Throwable thrown = record.getThrown();
            if (thrown != null) {
                attributes.put("exception.type", thrown.getClass().getName());
                if (thrown.getMessage() != null) {
                    attributes.put("exception.message", thrown.getMessage());
                }
                StringWriter sw = new StringWriter();
                thrown.printStackTrace(new PrintWriter(sw));
                attributes.put("exception.stacktrace", sw.toString());
            }
            logger.log(mapLevel(record.getLevel()), message, attributes);
        } catch (Throwable ignored) {
            // A handler never disturbs the application.
        } finally {
            PUBLISHING.set(Boolean.FALSE);
        }
    }

    private static boolean isInternalLogger(String name) {
        return name != null && (name.startsWith("jdk.internal.httpclient")
                || name.startsWith("jdk.httpclient")
                || name.startsWith("java.net.http")
                || name.startsWith("com.callisto.sdk"));
    }

    /** Flushes the underlying logger (bounded). */
    @Override
    public void flush() {
        if (logger != null) {
            logger.flush();
        }
    }

    /**
     * Flushes the underlying logger. It does not close it: the logger belongs to the code that
     * built it, which calls {@link CallistoLogger#close()}.
     */
    @Override
    public void close() {
        flush();
    }
}
