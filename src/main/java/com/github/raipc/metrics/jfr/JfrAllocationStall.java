package com.github.raipc.metrics.jfr;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

import com.github.raipc.metrics.util.ThreadId;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import com.github.raipc.metrics.util.ThreadPoolNameExtractor;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

/**
 * Collects ZGC allocation stall metrics from {@code jdk.ZAllocationStall} JFR events.
 * <p>
 * Recorded instruments:
 * <ul>
 *   <li>{@code jvm.gc.allocation_stall} — histogram of stall durations (us),
 *       tagged with {@code thread.pool.name} and {@code instrumentation.source} attributes</li>
 * </ul>
 *
 * <h3>Recommended SDK configuration</h3>
 * <pre>{@code
 * SdkMeterProvider.builder()
 *     .registerView(
 *         InstrumentSelector.builder()
 *             .setName("jvm.gc.allocation_stall")
 *             .setType(InstrumentType.HISTOGRAM)
 *             .build(),
 *         View.builder()
 *             .setAggregation(Aggregation.base2ExponentialBucketHistogram())
 *             .build())
 *     .registerMetricReader(reader)
 *     .build();
 * }</pre>
 */
public final class JfrAllocationStall implements JfrEventHandler {
    private static final String STALL_EVENT_NAME = "jdk.ZAllocationStall";
    private static final String THREAD_END_EVENT_NAME = "jdk.ThreadEnd";
    private static final AttributeKey<String> THREAD_POOL_KEY = AttributeKey.stringKey("thread.pool.name");
    private static final AttributeKey<String> SOURCE_KEY = AttributeKey.stringKey("instrumentation.source");
    private static final String SOURCE_VALUE = "jfr";
    private static final Attributes VIRTUAL_THREAD_ATTRS = Attributes.of(
            THREAD_POOL_KEY, "<virtual>", SOURCE_KEY, SOURCE_VALUE);

    private final Meter meter;
    private final ThreadPoolNameExtractor threadPoolNameExtractor;
    private final Map<ThreadId, Attributes> attributesByThreadId = new HashMap<>();
    private final ThreadId reusableKey = new ThreadId();
    private LongHistogram stallDuration;

    public JfrAllocationStall(Meter meter, ThreadPoolNameExtractor threadPoolNameExtractor) {
        this.meter = meter;
        this.threadPoolNameExtractor = threadPoolNameExtractor;
    }

    @Override
    public void subscribe(RecordingStream stream) {
        stream.enable(STALL_EVENT_NAME).withoutStackTrace();
        stream.onEvent(STALL_EVENT_NAME, this);
        stream.enable(THREAD_END_EVENT_NAME).withoutStackTrace();
        stream.onEvent(THREAD_END_EVENT_NAME, this::onThreadEnd);
    }

    @Override
    public void accept(RecordedEvent recordedEvent) {
        long durationMicros = recordedEvent.getDuration().toNanos() / 1000;
        getStallDuration().record(durationMicros, resolveAttributes(recordedEvent.getThread()));
    }

    private void onThreadEnd(RecordedEvent event) {
        long threadId = JfrHelpers.getThreadId(event.getThread());
        attributesByThreadId.remove(reusableKey.setId(threadId));
    }

    private LongHistogram getStallDuration() {
        if (stallDuration == null) {
            stallDuration = meter.histogramBuilder("jvm.gc.allocation_stall")
                    .ofLongs()
                    .setUnit("us")
                    .setDescription("ZGC allocation stall duration")
                    .build();
        }
        return stallDuration;
    }

    private Attributes resolveAttributes(RecordedThread thread) {
        if (thread != null && thread.isVirtual()) {
            return VIRTUAL_THREAD_ATTRS;
        }
        long threadId = JfrHelpers.getThreadId(thread);
        var cached = attributesByThreadId.get(reusableKey.setId(threadId));
        if (cached != null) {
            return cached;
        }
        String threadName = JfrHelpers.getThreadName(thread);
        String poolName = threadPoolNameExtractor.threadPoolName(threadName);
        var attrs = Attributes.of(THREAD_POOL_KEY, poolName, SOURCE_KEY, SOURCE_VALUE);
        attributesByThreadId.put(reusableKey.copy(), attrs);
        return attrs;
    }

    public static boolean isApplicable() {
        return ManagementFactory.getGarbageCollectorMXBeans()
                .stream()
                .anyMatch(bean -> bean.getName().contains("ZGC"));
    }
}
