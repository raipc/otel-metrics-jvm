package com.github.raipc.metrics.jmx;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Anton Rybochkin
 */
final class InternalThreadJmxMetricsAccessor {
    private static final Logger logger = Logger.getLogger(InternalThreadJmxMetricsAccessor.class.getName());
    private static final Object HOTSPOT_THREAD_MBEAN;
    private static final MethodHandle GET_CPU_TIMES;

    static {
        Object hotspotThreadMBean;
        MethodHandle getCpuTimes = null;
        try {
            // access must be provided with --add-opens=java.management/sun.management=ALL-UNNAMED
            Class<?> helper = Class.forName("sun.management.ManagementFactoryHelper");
            hotspotThreadMBean = helper.getMethod("getHotspotThreadMBean").invoke(null);
            var method = hotspotThreadMBean.getClass().getMethod("getInternalThreadCpuTimes");
            method.setAccessible(true);
            getCpuTimes = MethodHandles.lookup().unreflect(method);
            logger.info("HotspotThreadMBean loaded successfully. Internal VM thread metrics will be available");
        } catch (Throwable e) {
            String cause = e instanceof IllegalAccessException
                    ? "No access to internal metrics. The application must be started with VM options" +
                    " --add-opens=java.management/sun.management=ALL-UNNAMED"
                    : e.toString();
            logger.log(Level.WARNING,
                    "Could not load HotspotThreadMBean: {0}. Internal thread metrics will not be available", cause);
            hotspotThreadMBean = null;
        }
        HOTSPOT_THREAD_MBEAN = hotspotThreadMBean;
        GET_CPU_TIMES = getCpuTimes;
    }

    static InternalThreadJmxMetricsAccessor getInstance() {
        return GET_CPU_TIMES != null ? new InternalThreadJmxMetricsAccessor() : null;
    }

    private InternalThreadJmxMetricsAccessor() {
    }

    Map<String, Long> getInternalThreadCpuTimes() {
        assert GET_CPU_TIMES != null;
        try {
            return (Map<String, Long>) GET_CPU_TIMES.invoke(HOTSPOT_THREAD_MBEAN);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
