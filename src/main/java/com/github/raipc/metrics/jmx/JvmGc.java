package com.github.raipc.metrics.jmx;

import java.io.Closeable;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;

/**
 * Exports {@link GarbageCollectorMXBean} statistics as OpenTelemetry metrics,
 * matching the instruments produced by {@code ru.yandex.monlib.metrics.jvm.JvmGc}:
 * <ul>
 *   <li>{@code jvm.gc.count} — cumulative collection count per collector</li>
 *   <li>{@code jvm.gc.time} — cumulative collection time per collector (ms)</li>
 * </ul>
 * Each series is tagged with {@code jvm.gc.name} and {@code instrumentation.source=jmx}.
 */
public final class JvmGc implements Closeable {
    private static final AttributeKey<String> GC_NAME_KEY = AttributeKey.stringKey("jvm.gc.name");
    private static final AttributeKey<String> SOURCE_KEY = AttributeKey.stringKey("instrumentation.source");
    private static final String SOURCE_VALUE = "jmx";

    private final GarbageCollectorMXBean[] gcBeans;
    private final Attributes[] attributesByGcBean;
    private final BatchCallback batchCallback;

    public JvmGc(Meter meter) {
        var mxBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.gcBeans = mxBeans.toArray(GarbageCollectorMXBean[]::new);
        this.attributesByGcBean = new Attributes[this.gcBeans.length];
        for (int i = 0; i < this.gcBeans.length; i++) {
            this.attributesByGcBean[i] = Attributes.of(
                    GC_NAME_KEY, this.gcBeans[i].getName(),
                    SOURCE_KEY, SOURCE_VALUE);
        }

        ObservableLongMeasurement countObserver = meter.counterBuilder("jvm.gc.count")
                .setDescription("GC collection count from GarbageCollectorMXBean")
                .buildObserver();
        ObservableLongMeasurement timeObserver = meter.counterBuilder("jvm.gc.time")
                .setUnit("ms")
                .setDescription("GC collection time from GarbageCollectorMXBean")
                .buildObserver();

        this.batchCallback = meter.batchCallback(
                () -> {
                    for (int i = 0; i < gcBeans.length; i++) {
                        countObserver.record(gcBeans[i].getCollectionCount(), attributesByGcBean[i]);
                        timeObserver.record(gcBeans[i].getCollectionTime(), attributesByGcBean[i]);
                    }
                },
                countObserver,
                timeObserver);
    }

    @Override
    public void close() {
        batchCallback.close();
    }
}
