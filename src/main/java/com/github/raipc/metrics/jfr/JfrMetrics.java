package com.github.raipc.metrics.jfr;

import java.time.Duration;
import java.util.List;

import jdk.jfr.consumer.RecordingStream;

/**
 * Subscribes a fixed set of {@link JfrEventHandler}s to a single {@link RecordingStream}
 * and runs them asynchronously on a shared JFR streaming thread.
 *
 * <p>Usage example:
 * <pre>{@code
 * var metrics = new JfrMetrics(List.of(
 *     new JfrGarbageCollection(meter),
 *     new JfrAllocationStall(meter, extractor)
 * ));
 * metrics.start();
 * // ...
 * metrics.close();
 * }</pre>
 */
public final class JfrMetrics implements AutoCloseable {
    private final List<JfrEventHandler> handlers;
    private final Duration maxAge;
    private RecordingStream stream;

    public JfrMetrics(List<JfrEventHandler> handlers) {
        this(handlers, Duration.ofSeconds(5));
    }

    public JfrMetrics(List<JfrEventHandler> handlers, Duration maxAge) {
        this.handlers = List.copyOf(handlers);
        this.maxAge = maxAge;
    }

    public synchronized void start() {
        if (stream != null) {
            return;
        }
        var rs = new RecordingStream();
        try {
            rs.setReuse(true);
            rs.setOrdered(false);
            rs.setMaxAge(maxAge);
            for (var handler : handlers) {
                handler.subscribe(rs);
            }
            rs.startAsync();
        } catch (Throwable e) {
            rs.close();
            throw new RuntimeException(e);
        }
        stream = rs;
    }

    @Override
    public synchronized void close() {
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }
}
