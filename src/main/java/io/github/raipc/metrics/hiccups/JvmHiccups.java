package io.github.raipc.metrics.hiccups;

import java.io.Closeable;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;

/**
 * Written by Gil Tene of Azul Systems, and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/
 * <p>
 * HiccupMeter is a platform pause measurement tool, it is meant to observe
 * the underlying platform (JVM, OS, HW, etc.) responsiveness while under
 * an unrelated application load, and establish a lower bound for the stalls
 * the application would experience. It can be run as a wrapper around
 * other applications so that measurements can be done without any changes
 * to application code.
 * <p>
 * The purpose of HiccupMeter is to aid application operators and testers
 * in characterizing the inherent "platform hiccups" (execution stalls)
 * that a Java platform will display when running under load. The hiccups
 * measured are NOT stalls caused by the application's code. They are stalls
 * caused by the platform (JVM, OS, HW, etc.) that would be visible to and
 * affect any application thread running on the platform at the time of the
 * stall. It is generally safe to assume that if HiccupMeter experiences and
 * records a certain level of measured platform hiccups, the application
 * running on the same JVM platform during that time had experienced
 * hiccup/stall effects that are at least as large as the measured level.
 * <p>
 * Adapted for OpenTelemetry histograms
 *
 * @author Gil Tene, Anton Rybochkin
 */
public final class JvmHiccups implements Closeable {
    /**
     * Enable hiccup metrics with default parameters and attach them to provided meter.
     * <p>
     * Default params: <p>
     * - resolution = 1ms with object allocations. <p>
     * - metric name: jvm.hiccup.duration <p>
     * - unit: microseconds (us)
     */
    public JvmHiccups(Meter meter, double resolutionMs, boolean allocateObjects) {
        histogram = meter.histogramBuilder("jvm.hiccup.duration")
                .ofLongs()
                .setUnit("us")
                .setDescription("JVM platform hiccup (stall) duration")
                .build();
        startMeasurements(resolutionMs, allocateObjects);
    }

    public JvmHiccups(Meter meter) {
        this(meter, 1.0, true);
    }

    private final LongHistogram histogram;
    private volatile HiccupRecorder recorder;

    public synchronized void startMeasurements(
            double resolutionMs,
            boolean allocateObjects)
    {
        if (recorder != null) {
            throw new IllegalStateException("Hiccup meter is already running.");
        }

        recorder = new HiccupRecorder(histogram, resolutionMs, allocateObjects);
        recorder.start();
    }

    public synchronized void stopMeasurements() {
        if (recorder == null) {
            return;
        }

        recorder.terminate();
        recorder = null;
    }

    @Override
    public void close() {
        stopMeasurements();
    }

    private static class HiccupRecorder extends Thread {
        public volatile boolean doRun;
        @SuppressWarnings("unused")
        public volatile Object lastSleepTimeObj;

        private final LongHistogram histogram;
        private final double resolutionMs;
        private final boolean allocateObjects;

        HiccupRecorder(
                final LongHistogram histogram,
                double resolutionMs,
                boolean allocateObjects)
        {
            this.histogram = histogram;
            this.resolutionMs = resolutionMs;
            this.allocateObjects = allocateObjects;
            this.setDaemon(true);
            this.setName("HiccupRecorder");
            doRun = true;
        }

        void terminate() {
            doRun = false;
        }

        @Override
        public void run() {
            final long resolutionNsec = (long)(resolutionMs * 1000L * 1000L);
            final long resolutionMicros = nanosToMicros(resolutionNsec);
            try {
                long shortestObservedDeltaTimeNsec = Long.MAX_VALUE;
                long timeBeforeMeasurement = Long.MAX_VALUE;
                while (doRun) {
                    if (resolutionMs != 0) {
                        TimeUnit.NANOSECONDS.sleep(resolutionNsec);
                        if (allocateObjects) {
                            // Allocate an object to make sure potential allocation stalls are measured.
                            lastSleepTimeObj = new Object();
                        }
                    }
                    final long timeAfterMeasurement = System.nanoTime();
                    final long deltaTimeNsec = timeAfterMeasurement - timeBeforeMeasurement;
                    timeBeforeMeasurement = timeAfterMeasurement;

                    if (deltaTimeNsec < 0) {
                        // On the very first iteration the delta will be negative; skip recording.
                        continue;
                    }

                    if (deltaTimeNsec < shortestObservedDeltaTimeNsec) {
                        shortestObservedDeltaTimeNsec = deltaTimeNsec;
                    }

                    long hiccupTimeMicros = nanosToMicros(deltaTimeNsec - shortestObservedDeltaTimeNsec);
                    recordWithExpectedInterval(hiccupTimeMicros, resolutionMicros);
                }
            } catch (InterruptedException e) {
                /* no op */
            }
        }

        /**
         * Coordinated omission correction: records the observed value and fills in
         * "missing" intermediate values at each expected interval.
         */
        private void recordWithExpectedInterval(long value, long expectedInterval) {
            histogram.record(value);
            if (expectedInterval <= 0) {
                return;
            }
            for (long missingValue = value - expectedInterval;
                missingValue >= expectedInterval;
                missingValue -= expectedInterval) {
                histogram.record(missingValue);
            }
        }

        private static long nanosToMicros(long nanos) {
            return nanos / 1000L;
        }
    }
}
