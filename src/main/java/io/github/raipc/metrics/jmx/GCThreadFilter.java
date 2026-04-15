package io.github.raipc.metrics.jmx;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * @author Anton Rybochkin
 */
enum GCThreadFilter {
    PARALLEL {
        @Override
        boolean isGcThread(String name) {
            return name.startsWith("GC Thread");
        }
    },
    G1 {
        @Override
        boolean isGcThread(String name) {
            return name.startsWith("GC Thread") || name.startsWith("G1 ");
        }
    },
    ZGC {
        @Override
        boolean isGcThread(String name) {
            switch (name) {
                case "XDriver":
                case "XDirector":
                case "XStat":
                case "XUnmapper":
                case "XUncommitter":
                    return true;
                default:
                    return name.startsWith("XWorker") || name.startsWith("Runtime Worker");
            }
        }
    },
    GENERATIONAL_ZGC {
        @Override
        boolean isGcThread(String name) {
            switch (name) {
                case "ZDirector":
                case "ZStat":
                case "ZUnmapper":
                case "ZUncommitter":
                case "ZDriverMinor":
                case "ZDriverMajor":
                    return true;
                default:
                    return name.startsWith("ZWorker") || name.startsWith("Runtime Worker");
            }
        }
    },
    SHENANDOAH {
        @Override
        boolean isGcThread(String name) {
            return name.startsWith("Shenandoah") || name.startsWith("Safepoint Clean");
        }
    },
    OTHER {
        @Override
        boolean isGcThread(String name) {
            return name.startsWith("GC Thread");
        }
    },
    NOOP {
        @Override
        boolean isGcThread(String name) {
            return false;
        }
    };

    abstract boolean isGcThread(String name);

    static GCThreadFilter detect() {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            switch (bean.getName()) {
                case "PS Scavenge":
                case "PS MarkSweep":
                    return PARALLEL;
                case "G1 Young Generation":
                case "G1 Old Generation":
                case "G1 Concurrent GC":
                    return G1;
                case "Shenandoah Cycles":
                case "Shenandoah Pauses":
                    return SHENANDOAH;
                case "ZGC Cycles":
                case "ZGC Pauses":
                    return ZGC;
                case "ZGC Minor Cycles":
                case "ZGC Major Cycles":
                case "ZGC Minor Pauses":
                case "ZGC Major Pauses":
                    return GENERATIONAL_ZGC;
            }
        }
        return OTHER;
    }
}
