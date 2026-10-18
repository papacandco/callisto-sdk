package com.callisto.sdk.logs;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallistoLogHandlerTest {

    final FakeLogSender sender = new FakeLogSender();

    CallistoLogger logger() {
        return CallistoLogger.builder()
                .accessKey(FakeLogSender.PK).secret(FakeLogSender.SK)
                .endpoint("https://logs.example.test/v1/logs")
                .sender(sender).sleeper(ms -> { }).env(n -> null)
                .build();
    }

    static Logger julLogger(String name, CallistoLogHandler handler) {
        Logger jul = Logger.getLogger(name);
        jul.setUseParentHandlers(false);
        jul.setLevel(Level.ALL);
        for (java.util.logging.Handler h : jul.getHandlers()) {
            jul.removeHandler(h);
        }
        jul.addHandler(handler);
        return jul;
    }

    @Test
    void levelMapping() {
        assertEquals(LogLevel.DEBUG, CallistoLogHandler.mapLevel(Level.FINEST));
        assertEquals(LogLevel.DEBUG, CallistoLogHandler.mapLevel(Level.FINE));
        assertEquals(LogLevel.DEBUG, CallistoLogHandler.mapLevel(Level.CONFIG));
        assertEquals(LogLevel.INFO, CallistoLogHandler.mapLevel(Level.INFO));
        assertEquals(LogLevel.WARN, CallistoLogHandler.mapLevel(Level.WARNING));
        assertEquals(LogLevel.ERROR, CallistoLogHandler.mapLevel(Level.SEVERE));
    }

    @Test
    void forwardsMessageLoggerNameAndException() {
        try (CallistoLogger logs = logger()) {
            CallistoLogHandler handler = new CallistoLogHandler(logs);
            Logger jul = julLogger("shop.orders", handler);
            jul.log(Level.INFO, "order {0} paid", 42);
            jul.log(Level.SEVERE, "payment failed", new IllegalStateException("card declined"));
            jul.fine("details");
            logs.flush();
            jul.removeHandler(handler);
        }
        JsonNode records = sender.requests.get(0).records();
        assertEquals(3, records.size());

        JsonNode info = records.get(0);
        assertEquals("INFO", info.get("severityText").asText());
        assertEquals("order 42 paid", info.get("body").get("stringValue").asText());
        Map<String, JsonNode> a = CallistoLoggerTest.attrMap(info);
        assertEquals("shop.orders", a.get("logger.name").get("stringValue").asText());
        assertFalse(a.containsKey("exception.type"));

        JsonNode severe = records.get(1);
        assertEquals("ERROR", severe.get("severityText").asText());
        assertEquals(17, severe.get("severityNumber").asInt());
        Map<String, JsonNode> e = CallistoLoggerTest.attrMap(severe);
        assertEquals("java.lang.IllegalStateException", e.get("exception.type").get("stringValue").asText());
        assertEquals("card declined", e.get("exception.message").get("stringValue").asText());
        assertTrue(e.get("exception.stacktrace").get("stringValue").asText()
                .contains("CallistoLogHandlerTest"));

        assertEquals("DEBUG", records.get(2).get("severityText").asText());
    }

    @Test
    void respectsTheHandlerLevel() {
        try (CallistoLogger logs = logger()) {
            CallistoLogHandler handler = new CallistoLogHandler(logs);
            handler.setLevel(Level.WARNING);
            Logger jul = julLogger("shop.level", handler);
            jul.info("skipped");
            jul.warning("kept");
            logs.flush();
            jul.removeHandler(handler);
        }
        assertEquals(1, sender.totalRecords());
    }

    @Test
    void recordsEmittedWhileSendingAreDropped() {
        try (CallistoLogger logs = logger()) {
            CallistoLogHandler handler = new CallistoLogHandler(logs);
            Logger jul = julLogger("shop.recursion", handler);
            Logger http = julLogger("jdk.internal.httpclient.debug", handler);
            // The "HTTP library" logs from inside the send, on the logger's own thread.
            sender.onSend = () -> jul.warning("emitted while sending");
            jul.info("first");
            logs.flush();
            sender.onSend = null;
            http.info("http stack noise from an app thread");
            logs.flush();
            jul.removeHandler(handler);
            http.removeHandler(handler);
        }
        assertEquals(1, sender.requests.size());
        assertEquals(1, sender.totalRecords());
        assertEquals("first", sender.requests.get(0).records().get(0)
                .get("body").get("stringValue").asText());
    }

    @Test
    void handlerWithDisabledLoggerIsANoOp() {
        CallistoLogger logs = CallistoLogger.noop();
        CallistoLogHandler handler = new CallistoLogHandler(logs);
        Logger jul = julLogger("shop.disabled", handler);
        jul.severe("nothing");
        handler.flush();
        handler.close();
        jul.removeHandler(handler);
        assertEquals(0, sender.requests.size());
    }
}
