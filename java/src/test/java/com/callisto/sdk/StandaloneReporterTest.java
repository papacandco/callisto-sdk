package com.callisto.sdk;

import com.callisto.sdk.reporting.ErrorReporter;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers the DSN-only standalone reporter surface: the convenience constructors,
 * {@link ErrorReporter#fromEnv()}, and {@link ErrorReporter#installUncaughtHandler()}.
 * Error reporting here uses no client id / api key.
 */
class StandaloneReporterTest {

    private static final String DSN = "https://app.callistosignal.com/ingest/uuid?key=abc123";

    @Test
    void convenienceConstructorsResolveEnabledState() {
        assertTrue(new ErrorReporter(DSN).isEnabled());
        assertTrue(new ErrorReporter(DSN, "production").isEnabled());
        assertFalse(new ErrorReporter("").isEnabled());
        assertFalse(new ErrorReporter((String) null).isEnabled());
        assertFalse(new ErrorReporter("not a url").isEnabled());
    }

    @Test
    void fromEnvReturnsReporterDisabledWithoutDsnEnv() {
        // Skip if the host environment happens to define the DSN; otherwise it must be disabled.
        assumeTrue(System.getenv("CALLISTO_APP_ERROR_DSN") == null);

        ErrorReporter reporter = ErrorReporter.fromEnv();
        assertNotNull(reporter);
        assertFalse(reporter.isEnabled(),
                "fromEnv() should be disabled when CALLISTO_APP_ERROR_DSN is unset");
    }

    @Test
    void installUncaughtHandlerReportsFatalAndChainsPrevious() {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        try {
            AtomicInteger previousCalls = new AtomicInteger();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> previousCalls.incrementAndGet());

            ReporterTest.FakeSender sender = new ReporterTest.FakeSender();
            ErrorReporter reporter = new ErrorReporter(DSN, "production", sender, TestSupport.MAPPER);
            reporter.installUncaughtHandler();

            Thread.getDefaultUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), new IllegalStateException("kaboom"));
            reporter.flush();

            assertEquals(1, sender.calls.get(), "should report the uncaught throwable once");
            JsonNode p = TestSupport.json(sender.lastJson);
            assertEquals("fatal", p.get("level").asText());
            assertEquals(1, previousCalls.get(), "should chain the previously-installed handler");
            reporter.close();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }

    @Test
    void installUncaughtHandlerNoopWhenDisabled() {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        try {
            new ErrorReporter("").installUncaughtHandler(); // disabled: no DSN
            assertSame(original, Thread.getDefaultUncaughtExceptionHandler(),
                    "a disabled reporter must not install a handler");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }
}
