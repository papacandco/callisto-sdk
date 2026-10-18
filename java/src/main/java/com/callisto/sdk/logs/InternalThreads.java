package com.callisto.sdk.logs;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Marks the threads the logger owns (its worker and its HTTP threads). A log call made on one of
 * them is emitted while the logger is sending, so it is dropped: never re-shipped, no recursion.
 */
final class InternalThreads {

    private static final ThreadLocal<Boolean> INTERNAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private InternalThreads() {
    }

    static boolean isInternal() {
        return INTERNAL.get();
    }

    static ThreadFactory factory(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(() -> {
                INTERNAL.set(Boolean.TRUE);
                r.run();
            }, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
