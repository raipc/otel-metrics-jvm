package com.github.raipc.metrics.util;

/**
 * Advanced implementation of {@link ThreadPoolNameExtractor} that extracts thread pool name
 * by replacing numeric sequences that follow a dash or underscore with a single '#' character.
 * <p>
 * This extractor performs the following transformations:
 * <ul>
 *   <li>Determines the processing boundary by scanning backwards from the end and excluding
 *       trailing digits, dashes, hashes, and underscores from transformation</li>
 *   <li>Replaces patterns like {@code "-123"} or {@code "_123"} that appear before this boundary
 *       with a single {@code "#"}</li>
 *   <li>Skips consecutive digits after a dash or underscore (only the first digit triggers replacement)</li>
 * </ul>
 * <p>
 * Important behavior:
 * <ul>
 *   <li>Patterns at the end of the string (after the boundary) are not transformed and remain unchanged</li>
 *   <li>Patterns in the middle (before the boundary) are replaced with {@code "#"}</li>
 *   <li>If no transformations occur within the boundary, the original string is returned unchanged</li>
 *   <li>Trailing digits, dashes, and underscores are excluded from processing but may remain in the result
 *       if they are part of the original string structure</li>
 * </ul>
 * <p>
 * Examples:
 * <ul>
 *   <li>{@code "pool-317-thread"} → {@code "pool-#-thread"}</li>
 *   <li>{@code "GC Thread#4"} → {@code "GC Thread"}</li>
 *   <li>{@code "http-nio-8080-exec-1"} → {@code "http-nio-#-exec"}</li>
 *   <li>{@code "HikariPool-1-connection-thread"} → {@code "HikariPool-#-connection-thread"}</li>
 *   <li>{@code "scheduler-1-thread"} → {@code "scheduler-#-thread"}</li>
 *   <li>{@code "ClickHouseHttpClient@12345678"} → {@code "ClickHouseHttpClient"}</li>
 *   <li>{@code "OkHttp http://localhost:14511/..."} → {@code "OkHttp http://localhost:14511/..."}</li>
 * </ul>
 * <p>
 * The extractor processes the thread name from left to right within the determined boundary,
 * identifying numeric sequences that immediately follow a dash or underscore character
 * and replacing the entire sequence with a single '#'.
 *
 * @author Anton Rybochkin
 */
public class NumRemovingThreadPoolNameExtractor implements ThreadPoolNameExtractor {
    /**
     * Extracts the thread pool name from a thread name by replacing numeric sequences
     * that follow a dash or underscore with '#' characters.
     * <p>
     * The method first determines a processing boundary by excluding trailing digits,
     * dashes, underscores, and '#' characters.
     * Only patterns like "-123" or "_123" that appear before this boundary are transformed.
     * Patterns at the end of the string remain unchanged. If no transformations occur
     * within the boundary, the original thread name is returned unchanged.
     *
     * @param threadName the full thread name, must not be null
     * @return the extracted thread pool name with numeric sequences replaced by '#',
     *         or the original thread name if no transformations are needed
     */
    @Override
    public String threadPoolName(String threadName) {
        int length = findEndIndex(threadName);
        if (length == 0) {
            return threadName;
        }
        StringBuilder sb = null;
        boolean skipDigit = false;
        for (int i = 1; i < length; i++) {
            var c = threadName.charAt(i);
            if (!Character.isDigit(c)) {
                if (sb != null) {
                    sb.append(c);
                }
                skipDigit = false;
            } else if (!skipDigit) {
                char prev = threadName.charAt(i - 1);
                if (prev == '-' || prev == '_') {
                    if (sb == null) {
                        sb = new StringBuilder(threadName.length()).append(threadName, 0, i);
                    }
                    sb.append('#');
                    skipDigit = true;
                }
            }
        }

        return sb != null ? sb.toString() : threadName.substring(0, length);
    }

    /**
     * Finds the end index for processing the thread name by determining the boundary
     * where trailing digits, dashes, and underscores should be excluded from transformation.
     * <p>
     * The method scans from the end of the string backwards:
     * <ul>
     *   <li>Removes trailing digits from consideration</li>
     *   <li>Removes trailing dashes, underscores, '@' and '#' symbols, updating the safe length</li>
     * </ul>
     * <p>
     * This boundary determines where the main processing loop should stop. Characters
     * beyond this boundary are not processed for pattern replacement.
     *
     * @param threadName the thread name to process
     * @return the index where processing should end (exclusive), which represents
     *         the length of the part of the thread name that should be processed
     */
    private int findEndIndex(String threadName) {
        int newLength = threadName.length();
        int safeLength = newLength;
        while (true) {
            if (newLength == 0) {
                return 0;
            }
            char ch = threadName.charAt(newLength - 1);
            if (Character.isDigit(ch)) {
                newLength--;
            } else if (ch == '-' || ch == '_' || ch == '#' || ch == '@' || ch == ' ') {
                newLength--;
                safeLength = newLength;
            } else {
                if (Character.isLetter(ch)) {
                    safeLength = newLength;
                }
                break;
            }
        }
        return safeLength;
    }
}
