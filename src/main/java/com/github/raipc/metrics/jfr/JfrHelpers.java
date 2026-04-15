package com.github.raipc.metrics.jfr;

import jdk.jfr.consumer.RecordedThread;

/**
 * Utilities for extracting thread identity from JFR {@link RecordedThread} structures.
 */
public class JfrHelpers {
    private JfrHelpers() {}

    private static final long UNKNOWN_THREAD_ID = Long.MIN_VALUE;
    private static final String UNKNOWN_THREAD_NAME = "<unknown>";

    /**
     * Derive a unique id from a JFR thread structure.
     * For Java platform/virtual threads the id is {@code javaThreadId};
     * for native threads it is the negated OS tid.
     */
    public static long getThreadId(RecordedThread thread) {
        if (thread != null) {
            long id = thread.getJavaThreadId();
            if (id != -1) {
                return id;
            }
            id = thread.getOSThreadId();
            if (id > 0) {
                return -id;
            }
        }
        return UNKNOWN_THREAD_ID;
    }

    public static String getThreadName(RecordedThread thread) {
        if (thread != null) {
            String name = thread.getJavaName();
            if (name != null) {
                return replaceEmpty(name, thread);
            }
            name = thread.getOSName();
            if (name != null) {
                return replaceEmpty(name, thread);
            }
        }
        return UNKNOWN_THREAD_NAME;
    }

    private static String replaceEmpty(String name, RecordedThread thread) {
        if (name.isEmpty()) {
            name = thread.isVirtual() ? "<virtual>" : "<empty>";
        }
        return name;
    }
}
