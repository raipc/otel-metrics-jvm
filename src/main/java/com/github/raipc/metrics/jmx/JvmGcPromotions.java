package com.github.raipc.metrics.jmx;

import java.io.Closeable;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

import com.sun.management.GarbageCollectionNotificationInfo;

import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

/**
 * Collects JVM memory promotion rate from GC notifications
 * using OpenTelemetry instruments.
 * <p>
 * Listens to {@link GarbageCollectionNotificationInfo} events via JMX
 * {@link NotificationEmitter} and reports:
 * <ul>
 *   <li>{@code jvm.gc.promotion} — cumulative bytes promoted to the old generation</li>
 * </ul>
 * Supports Parallel, G1, Shenandoah, and ZGC (classic and generational) collectors.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var promotions = new JvmGcPromotions(meter);
 * // ... on shutdown:
 * promotions.close();
 * }</pre>
 *
 * @author Anton Rybochkin
 */
public final class JvmGcPromotions implements Closeable {
    private static final MemoryUsage EMPTY = new MemoryUsage(0, 0, 0, 0);

    private final List<ListenerRegistration> registrations = new ArrayList<>();

    public JvmGcPromotions(Meter meter) {
        LongCounter promotion = meter.counterBuilder("jvm.gc.promotion")
                .setUnit("By")
                .setDescription("Bytes promoted to old generation")
                .build();

        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof NotificationEmitter emitter) {
                GcListener listener = switch (bean.getName()) {
                    case "PS Scavenge", "PS MarkSweep" ->
                            new GcListener("PS Old Gen", promotion);
                    case "G1 Young Generation", "G1 Old Generation", "G1 Concurrent GC" ->
                            new GcListener("G1 Old Gen", promotion);
                    case "Shenandoah Cycles" ->
                            new GcListener("Shenandoah Old Gen", promotion);
                    case "ZGC Cycles" ->
                            new GcListener("", promotion);
                    case "ZGC Minor Cycles", "ZGC Major Cycles" ->
                            new GcListener("ZGC Old Generation", promotion);
                    default -> null;
                };
                if (listener != null) {
                    emitter.addNotificationListener(listener, null, null);
                    registrations.add(new ListenerRegistration(emitter, listener));
                }
            }
        }
    }

    @Override
    public void close() {
        for (ListenerRegistration reg : registrations) {
            try {
                reg.emitter.removeNotificationListener(reg.listener);
            } catch (Exception ignored) {
            }
        }
        registrations.clear();
    }

    private record ListenerRegistration(NotificationEmitter emitter, NotificationListener listener) {}

    static final class GcListener implements NotificationListener {
        private final String oldGen;
        private final LongCounter promotion;

        GcListener(String oldGen, LongCounter promotion) {
            this.oldGen = oldGen;
            this.promotion = promotion;
        }

        @Override
        public void handleNotification(Notification notification, Object handback) {
            if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                return;
            }

            CompositeData cd = (CompositeData) notification.getUserData();
            GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(cd);

            Map<String, MemoryUsage> before = info.getGcInfo().getMemoryUsageBeforeGc();
            Map<String, MemoryUsage> after = info.getGcInfo().getMemoryUsageAfterGc();

            long promotedToOldGen = after.getOrDefault(oldGen, EMPTY).getUsed()
                    - before.getOrDefault(oldGen, EMPTY).getUsed();
            if (promotedToOldGen > 0L) {
                promotion.add(promotedToOldGen);
            }
        }
    }
}
