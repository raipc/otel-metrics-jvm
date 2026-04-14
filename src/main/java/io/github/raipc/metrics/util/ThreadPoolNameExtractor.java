package io.github.raipc.metrics.util;

/**
 * Resolve thread pool name by thread name
 *
 * @author Anton Rybochkin
 */
@FunctionalInterface
public interface ThreadPoolNameExtractor {
    String threadPoolName(String threadName);
}
