package com.github.raipc.metrics.jfr;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

/**
 * Collects GC metrics from {@code jdk.GarbageCollection} JFR events.
 * <p>
 * Recorded instruments:
 * <ul>
 *   <li>{@code jvm.gc.pause} — histogram of total pause duration per GC event (µs)</li>
 *   <li>{@code jvm.gc.longest_pause} — histogram of the longest pause per GC event (µs)</li>
 * </ul>
 * Each instrument is tagged with {@code jvm.gc.name}, {@code jvm.gc.cause},
 * and {@code instrumentation.source} attributes.
 *
 * <h3>Recommended SDK configuration</h3>
 * GC pause durations span several orders of magnitude (sub-millisecond minor GCs to
 * multi-second full GCs). Base-2 exponential bucket histograms adapt to the actual
 * data range automatically, avoiding the need to hand-pick bucket boundaries:
 * <pre>{@code
 * SdkMeterProvider.builder()
 *     .registerView(
 *         InstrumentSelector.builder()
 *             .setName("jvm.gc.*")
 *             .setType(InstrumentType.HISTOGRAM)
 *             .build(),
 *         View.builder()
 *             .setAggregation(Aggregation.base2ExponentialBucketHistogram())
 *             .build())
 *     .registerMetricReader(reader)
 *     .build();
 * }</pre>
 */
public final class JfrGarbageCollection implements JfrEventHandler {
    static final String EVENT_NAME = "jdk.GarbageCollection";
    private static final AttributeKey<String> COLLECTOR_KEY = AttributeKey.stringKey("jvm.gc.name");
    private static final AttributeKey<String> CAUSE_KEY = AttributeKey.stringKey("jvm.gc.cause");
    private static final AttributeKey<String> SOURCE_KEY = AttributeKey.stringKey("instrumentation.source");
    private static final String SOURCE_VALUE = "jfr";

    private final LongHistogram sumOfPauses;
    private final LongHistogram longestPause;
    private final Map<Key, Attributes> attrsByKey = new HashMap<>();
    private final Key reusableKey = new Key("", "");

    public JfrGarbageCollection(Meter meter) {
        this.sumOfPauses = meter.histogramBuilder("jvm.gc.pause")
                .ofLongs()
                .setUnit("us")
                .setDescription("Sum of GC pause durations")
                .build();
        this.longestPause = meter.histogramBuilder("jvm.gc.longest_pause")
                .ofLongs()
                .setUnit("us")
                .setDescription("Longest GC pause duration")
                .build();
    }

    @Override
    public void subscribe(RecordingStream stream) {
        stream.enable(EVENT_NAME);
        stream.onEvent(EVENT_NAME, this);
    }

    @Override
    public void accept(RecordedEvent event) {
        var name = event.getString("name");
        var cause = event.getString("cause");
        var attrs = attrsByKey.get(reusableKey.update(name, cause));
        if (attrs == null) {
            attrs = Attributes.of(COLLECTOR_KEY, name, CAUSE_KEY, cause, SOURCE_KEY, SOURCE_VALUE);
            attrsByKey.put(reusableKey.copy(), attrs);
        }

        var sumOfPausesMicros = toMicros(event.getDuration("sumOfPauses"));
        var longestPauseMicros = toMicros(event.getDuration("longestPause"));
        this.sumOfPauses.record(sumOfPausesMicros, attrs);
        this.longestPause.record(longestPauseMicros, attrs);
    }

    private static long toMicros(Duration duration) {
        return TimeUnit.SECONDS.toMicros(duration.getSeconds()) + TimeUnit.NANOSECONDS.toMicros(duration.getNano());
    }

    private static final class Key {
        private String collector;
        private String cause;

        Key(String collector, String cause) {
            this.collector = collector;
            this.cause = cause;
        }

        Key update(String collector, String cause) {
            this.collector = collector;
            this.cause = cause;
            return this;
        }

        Key copy() {
            return new Key(collector, cause);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Key key = (Key) o;
            return Objects.equals(collector, key.collector) && Objects.equals(cause, key.cause);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(collector) + Objects.hashCode(cause);
        }
    }
}
