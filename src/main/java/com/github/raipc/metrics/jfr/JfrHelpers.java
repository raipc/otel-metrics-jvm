package com.github.raipc.metrics.jfr;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import jdk.jfr.consumer.RecordedThread;

/**
 * Utilities for extracting thread identity from JFR {@link RecordedThread} structures.
 */
public class JfrHelpers {
    private static final MethodHandle IS_VIRTUAL;
    static {
        MethodHandle handle;
        try {
            handle = MethodHandles.lookup()
                    .findVirtual(RecordedThread.class, "isVirtual", MethodType.methodType(boolean.class));
        } catch (Throwable e) {
            handle = null;
        }
        IS_VIRTUAL = handle;
    }

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

    /**
     * Mirrors {@code RecordedThread.isVirtual()} (JDK 21+). Resolved via {@link MethodHandle} so the project
     * can compile when {@code --release} is older than 21.
     */
    public static boolean isVirtual(RecordedThread thread) {
        if (thread == null) {
            return false;
        }
        try {
            return IS_VIRTUAL != null && (boolean) IS_VIRTUAL.invokeExact(thread);
        } catch (Throwable e) {
            return false;
        }
    }

    private static String replaceEmpty(String name, RecordedThread thread) {
        if (name.isEmpty()) {
            name = isVirtual(thread) ? "<virtual>" : "<empty>";
        }
        return name;
    }
}
