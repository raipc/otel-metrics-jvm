package io.github.raipc.metrics.jmx;

import java.io.Closeable;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongBinaryOperator;
import java.util.logging.Logger;

import io.github.raipc.metrics.util.ThreadId;
import io.github.raipc.metrics.util.ThreadPoolNameExtractor;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;

/**
 * Collects per-thread-pool CPU time, thread count, and allocation metrics
 * using OpenTelemetry asynchronous instruments.
 * <p>
 * Registers a {@link BatchCallback} that, on each metric collection cycle:
 * <ol>
 *   <li>Enumerates all Java threads and (optionally) internal VM threads</li>
 *   <li>Computes CPU time and allocation deltas since the previous collection</li>
 *   <li>Aggregates deltas by thread pool and reports cumulative values</li>
 * </ol>
 * <p>
 * Reported instruments:
 * <ul>
 *   <li>{@code jvm.thread.cpu_time} — async counter, cumulative CPU time per pool (ns), {@code thread.pool.name}</li>
 *   <li>{@code jvm.thread.cpu_time.total} — same metric aggregated across all pools (no pool attribute)</li>
 *   <li>{@code jvm.thread.count} — async gauge, current thread count per pool</li>
 *   <li>{@code jvm.thread.count.total} — total thread count across all pools</li>
 *   <li>{@code jvm.thread.allocated_bytes} — async counter, cumulative allocation per pool (bytes)</li>
 *   <li>{@code jvm.thread.allocated_bytes.total} — total cumulative allocation across pools</li>
 *   <li>{@code jvm.gc.thread.cpu_time} — async counter, cumulative GC-thread CPU time (ns)</li>
 *   <li>{@code jvm.gc.thread.count} — async gauge, current GC-thread count</li>
 * </ul>
 *
 * @author Anton Rybochkin
 */
public class JvmThreadsCpu implements Closeable {
    private static final Logger logger = Logger.getLogger(JvmThreadsCpu.class.getName());
    private static final Duration DEFAULT_STALE_POOL_PERIOD = Duration.ofMinutes(5);
    private static final Duration DEFAULT_STALE_THREAD_PERIOD = Duration.ofMinutes(1);
    private static final AttributeKey<String> THREAD_POOL_KEY = AttributeKey.stringKey("thread.pool.name");

    private final ThreadPoolNameExtractor threadPoolNameExtractor;
    private final GCThreadFilter gcThreadFilter;
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private final boolean monitorGcThreads;
    private final boolean collectInternalThreadMetrics;
    private final InternalThreadJmxMetricsAccessor internalJmxAccessor;
    private final long stalePoolPeriodNs;
    private final long staleThreadPeriodNs;

    private final Map<ThreadId, ThreadStatistics> metricsByJavaThread = new HashMap<>();
    private final Map<String, ThreadStatistics> metricsByInternalThread = new HashMap<>();

    private final Map<String, PoolAccumulator> poolAccumulators = new HashMap<>();
    private final PoolAccumulator totalAccumulator = new PoolAccumulator(true);
    private final PoolAccumulator totalGcAccumulator = new PoolAccumulator(false);

    private final ObservableLongMeasurement cpuTimeObserver;
    private final ObservableLongMeasurement cpuTimeTotalObserver;
    private final ObservableLongMeasurement threadCountObserver;
    private final ObservableLongMeasurement threadCountTotalObserver;
    private final ObservableLongMeasurement allocatedBytesObserver;
    private final ObservableLongMeasurement allocatedBytesTotalObserver;
    private final ObservableLongMeasurement gcCpuTimeObserver;
    private final ObservableLongMeasurement gcCountObserver;
    private final BatchCallback batchCallback;

    public JvmThreadsCpu(Builder builder) {
        this.threadPoolNameExtractor = builder.threadPoolNameExtractor;
        this.internalJmxAccessor = builder.monitorInternalThreads || builder.aggregateGcThreads
                ? InternalThreadJmxMetricsAccessor.getInstance() : null;
        this.collectInternalThreadMetrics = builder.monitorInternalThreads && this.internalJmxAccessor != null;
        var gcThreadFilter = builder.aggregateGcThreads ? GCThreadFilter.detect() : GCThreadFilter.NOOP;
        if (gcThreadFilter != GCThreadFilter.NOOP && internalJmxAccessor == null) {
            logger.warning("GC threads aggregation is enabled, but internal JMX API is not available");
            gcThreadFilter = GCThreadFilter.NOOP;
        }
        this.gcThreadFilter = gcThreadFilter;
        this.monitorGcThreads = gcThreadFilter != GCThreadFilter.NOOP;
        this.stalePoolPeriodNs = builder.stalePoolPeriod.toNanos();
        this.staleThreadPeriodNs = builder.staleThreadPeriod.toNanos();

        Meter meter = builder.meter;
        this.cpuTimeObserver = meter.counterBuilder("jvm.thread.cpu_time")
                .setUnit("ns")
                .setDescription("Thread pool cumulative CPU time")
                .buildObserver();
        this.threadCountObserver = meter.gaugeBuilder("jvm.thread.count")
                .ofLongs()
                .setDescription("Number of threads per thread pool")
                .buildObserver();
        this.allocatedBytesObserver = meter.counterBuilder("jvm.thread.allocated_bytes")
                .setUnit("By")
                .setDescription("Thread pool cumulative memory allocation")
                .buildObserver();
        this.cpuTimeTotalObserver = meter.counterBuilder("jvm.thread.cpu_time.total")
                .setUnit("ns")
                .setDescription("Cumulative CPU time across all thread pools")
                .buildObserver();
        this.threadCountTotalObserver = meter.gaugeBuilder("jvm.thread.count.total")
                .ofLongs()
                .setDescription("Total number of threads across all pools")
                .buildObserver();
        this.allocatedBytesTotalObserver = meter.counterBuilder("jvm.thread.allocated_bytes.total")
                .setUnit("By")
                .setDescription("Cumulative memory allocation across all thread pools")
                .buildObserver();
        this.gcCpuTimeObserver = meter.counterBuilder("jvm.gc.thread.cpu_time")
                .setUnit("ns")
                .setDescription("GC threads cumulative CPU time")
                .buildObserver();
        this.gcCountObserver = meter.gaugeBuilder("jvm.gc.thread.count")
                .ofLongs()
                .setDescription("Number of GC threads")
                .buildObserver();

        this.batchCallback = meter.batchCallback(
                this::collectAndReport,
                cpuTimeObserver,
                cpuTimeTotalObserver,
                threadCountObserver,
                threadCountTotalObserver,
                allocatedBytesObserver,
                allocatedBytesTotalObserver,
                gcCpuTimeObserver,
                gcCountObserver
        );
    }

    public JvmThreadsCpu(Meter meter, ThreadPoolNameExtractor threadPoolNameExtractor) {
        this(new Builder(meter, threadPoolNameExtractor));
    }

    boolean useRestrictedJmxApi() {
        return internalJmxAccessor != null;
    }

    @Override
    public void close() {
        batchCallback.close();
    }

    private synchronized void collectAndReport() {
        var statePerPool = new HashMap<String, MetricsAggrState>();
        var total = MetricsAggrState.withMemory();
        var totalGc = MetricsAggrState.withoutMemory();
        int collectedJava = collectMetricsFromJavaThreads(statePerPool, total);
        int collectedInternal = addInternalVmThreads(statePerPool, total, totalGc);
        updateAccumulatorsAndReport(statePerPool, total, totalGc);
        removeTerminatedThreads(collectedJava, collectedInternal);
    }

    private int collectMetricsFromJavaThreads(
            Map<String, MetricsAggrState> metricStatesPerThreadPool,
            MetricsAggrState total)
    {
        int currentTimeSeconds = (int) TimeUnit.NANOSECONDS.toSeconds(System.nanoTime());
        ThreadId reusableKey = new ThreadId();
        long[] threadIds = threadMXBean.getAllThreadIds();
        ThreadInfo[] threads = threadMXBean.getThreadInfo(threadIds);
        int skipped = 0;
        for (int idx = 0; idx < threadIds.length; idx++) {
            var thread = threads[idx];
            if (thread == null) {
                ++skipped;
                continue;
            }

            ThreadStatistics threadStatistics = metricsByJavaThread.get(reusableKey.setId(thread.getThreadId()));
            if (threadStatistics == null) {
                String threadName = thread.getThreadName();
                String poolName = threadPoolNameExtractor.threadPoolName(threadName);
                threadStatistics = new ThreadStatistics(threadName, poolName);
                ThreadStatistics prev = metricsByJavaThread.putIfAbsent(reusableKey.copy(), threadStatistics);
                if (prev != null) {
                    threadStatistics = prev;
                }
            }
            threadStatistics.lastUpdateTimeSec = currentTimeSeconds;
            var name = threadStatistics.threadPoolName;
            var perThreadDeltas = metricStatesPerThreadPool.computeIfAbsent(
                    name, unused -> MetricsAggrState.withMemory());
            var cpuTime = threadMXBean.getThreadCpuTime(thread.getThreadId());
            if (cpuTime > 0) {
                long cpuDelta = updateAndReturnCpuDelta(threadStatistics, cpuTime);
                perThreadDeltas.cpuTime += cpuDelta;
                total.cpuTime += cpuDelta;
            }
            if (threadMXBean instanceof com.sun.management.ThreadMXBean sunThreadMxBean) {
                var threadBytes = sunThreadMxBean.getThreadAllocatedBytes(thread.getThreadId());
                long allocDelta = updateAndReturnAllocationDelta(threadStatistics, threadBytes);
                total.allocatedBytes += allocDelta;
                perThreadDeltas.allocatedBytes += allocDelta;
            }

            perThreadDeltas.count++;
            total.count++;
        }
        return threadIds.length - skipped;
    }

    private int addInternalVmThreads(
            Map<String, MetricsAggrState> metricsPerThreadPool,
            MetricsAggrState totalMetrics,
            MetricsAggrState totalGc)
    {
        if (internalJmxAccessor == null) {
            return 0;
        }
        int currentTimeSec = (int) TimeUnit.NANOSECONDS.toSeconds(System.nanoTime());
        Map<String, Long> internalThreadCpuTimes = internalJmxAccessor.getInternalThreadCpuTimes();
        for (Map.Entry<String, Long> entry : internalThreadCpuTimes.entrySet()) {
            var threadName = entry.getKey();
            ThreadStatistics threadStatistics = metricsByInternalThread.get(threadName);
            if (threadStatistics == null) {
                var threadPoolName = collectInternalThreadMetrics
                        ? threadPoolNameExtractor.threadPoolName(threadName)
                        : threadName;
                threadStatistics = new ThreadStatistics(threadName, threadPoolName);
                var prev = metricsByInternalThread.putIfAbsent(threadName, threadStatistics);
                if (prev != null) {
                    threadStatistics = prev;
                }
            }
            threadStatistics.lastUpdateTimeSec = currentTimeSec;
            var cpuTime = entry.getValue();
            long cpuDelta = updateAndReturnCpuDelta(threadStatistics, cpuTime);
            totalMetrics.cpuTime += cpuDelta;
            totalMetrics.count++;
            if (gcThreadFilter.isGcThread(threadName)) {
                totalGc.cpuTime += cpuDelta;
                totalGc.count++;
            }
            if (collectInternalThreadMetrics) {
                var perThreadMetrics = metricsPerThreadPool.computeIfAbsent(
                        threadStatistics.threadPoolName,
                        unused -> MetricsAggrState.withoutMemory());
                perThreadMetrics.cpuTime += cpuDelta;
                perThreadMetrics.count++;
            }
        }
        return internalThreadCpuTimes.size();
    }

    private void removeTerminatedThreads(int collectedJavaThreads, int collectedInternalThreads) {
        long currentTimeNs = System.nanoTime();
        int deleteThreadBeforeSec = (int) TimeUnit.NANOSECONDS.toSeconds(currentTimeNs - staleThreadPeriodNs);
        if (metricsByJavaThread.size() > collectedJavaThreads) {
            metricsByJavaThread.values().removeIf(s -> s.lastUpdateTimeSec < deleteThreadBeforeSec);
        }
        if (metricsByInternalThread.size() > collectedInternalThreads) {
            metricsByInternalThread.values().removeIf(s -> s.lastUpdateTimeSec < deleteThreadBeforeSec);
        }
        int deletePoolBeforeSec = (int) TimeUnit.NANOSECONDS.toSeconds(currentTimeNs - stalePoolPeriodNs);
        poolAccumulators.values().removeIf(e -> e.lastUpdateTimeSec < deletePoolBeforeSec);
    }

    private void updateAccumulatorsAndReport(
            Map<String, MetricsAggrState> perThread,
            MetricsAggrState total,
            MetricsAggrState totalGc)
    {
        int currentTimeSec = (int) TimeUnit.NANOSECONDS.toSeconds(System.nanoTime());
        perThread.forEach((pool, state) -> {
            PoolAccumulator accum = poolAccumulators.computeIfAbsent(
                    pool, unused -> new PoolAccumulator(state.hasAllocatedBytes));
            accum.cumulativeCpuTimeNs += state.cpuTime;
            accum.currentCount = state.count;
            if (state.hasAllocatedBytes) {
                accum.cumulativeAllocatedBytes += state.allocatedBytes;
            }
            accum.lastUpdateTimeSec = currentTimeSec;

            Attributes attrs = Attributes.of(THREAD_POOL_KEY, pool);
            cpuTimeObserver.record(accum.cumulativeCpuTimeNs, attrs);
            threadCountObserver.record(accum.currentCount, attrs);
            if (accum.hasAllocatedBytes) {
                allocatedBytesObserver.record(accum.cumulativeAllocatedBytes, attrs);
            }
        });

        totalAccumulator.cumulativeCpuTimeNs += total.cpuTime;
        totalAccumulator.currentCount = total.count;
        totalAccumulator.cumulativeAllocatedBytes += total.allocatedBytes;
        totalAccumulator.lastUpdateTimeSec = currentTimeSec;
        cpuTimeTotalObserver.record(totalAccumulator.cumulativeCpuTimeNs, Attributes.empty());
        threadCountTotalObserver.record(totalAccumulator.currentCount, Attributes.empty());
        allocatedBytesTotalObserver.record(totalAccumulator.cumulativeAllocatedBytes, Attributes.empty());

        if (monitorGcThreads) {
            totalGcAccumulator.cumulativeCpuTimeNs += totalGc.cpuTime;
            totalGcAccumulator.currentCount = totalGc.count;
            totalGcAccumulator.lastUpdateTimeSec = currentTimeSec;
            gcCpuTimeObserver.record(totalGcAccumulator.cumulativeCpuTimeNs);
            gcCountObserver.record(totalGcAccumulator.currentCount);
        }
    }

    static class MetricsAggrState {
        final boolean hasAllocatedBytes;
        long cpuTime;
        long count;
        long allocatedBytes;

        private MetricsAggrState(boolean hasAllocatedBytes) {
            this.hasAllocatedBytes = hasAllocatedBytes;
        }

        static MetricsAggrState withMemory() {
            return new MetricsAggrState(true);
        }

        static MetricsAggrState withoutMemory() {
            return new MetricsAggrState(false);
        }
    }

    private static final class PoolAccumulator {
        final boolean hasAllocatedBytes;
        long cumulativeCpuTimeNs;
        long currentCount;
        long cumulativeAllocatedBytes;
        int lastUpdateTimeSec;

        PoolAccumulator(boolean hasAllocatedBytes) {
            this.hasAllocatedBytes = hasAllocatedBytes;
        }
    }

    public static class Builder {
        private final Meter meter;
        private final ThreadPoolNameExtractor threadPoolNameExtractor;
        private boolean monitorInternalThreads = true;
        private boolean aggregateGcThreads = true;
        private Duration stalePoolPeriod = DEFAULT_STALE_POOL_PERIOD;
        private Duration staleThreadPeriod = DEFAULT_STALE_THREAD_PERIOD;

        public Builder(Meter meter, ThreadPoolNameExtractor threadPoolNameExtractor) {
            this.meter = meter;
            this.threadPoolNameExtractor = threadPoolNameExtractor;
        }

        public Builder monitorInternalThreads(boolean monitorInternalThreads) {
            this.monitorInternalThreads = monitorInternalThreads;
            return this;
        }

        public Builder aggregateGcThreads(boolean aggregateGcThreads) {
            this.aggregateGcThreads = aggregateGcThreads;
            return this;
        }

        public Builder stalePoolPeriod(Duration stalePoolPeriod) {
            this.stalePoolPeriod = stalePoolPeriod;
            return this;
        }

        public Builder staleThreadPeriod(Duration staleThreadPeriod) {
            this.staleThreadPeriod = staleThreadPeriod;
            return this;
        }

        public JvmThreadsCpu build() {
            return new JvmThreadsCpu(this);
        }
    }

    private static long updateAndReturnCpuDelta(ThreadStatistics threadStatistics, long newCumulativeValue) {
        long delta = Math.max(newCumulativeValue - threadStatistics.cpuTimeNs, 0);
        if (delta > 0) {
            threadStatistics.cpuTimeNs = newCumulativeValue;
        }
        return delta;
    }

    private static long updateAndReturnAllocationDelta(ThreadStatistics threadStatistics, long newCumulativeValue) {
        long delta = Math.max(newCumulativeValue - threadStatistics.allocatedBytes, 0);
        if (delta > 0) {
            threadStatistics.allocatedBytes = newCumulativeValue;
        }
        return delta;
    }

    private static class ThreadStatistics {
        private final String threadName;
        private final String threadPoolName;
        private long cpuTimeNs;
        private long allocatedBytes;
        private int lastUpdateTimeSec;

        private ThreadStatistics(String threadName, String threadPoolName) {
            this.threadName = threadName;
            this.threadPoolName = threadPoolName;
        }
    }
}
