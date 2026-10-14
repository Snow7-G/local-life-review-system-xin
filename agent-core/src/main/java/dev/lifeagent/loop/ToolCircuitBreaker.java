package dev.lifeagent.loop;

import java.util.HashMap;
import java.util.Map;

/**
 * Trips a tool after it fails three times in a row for one session.
 *
 * <p>The key is {@code (sessionId, toolName)}: a broken tool in one session must not disable it for
 * every other session. A single success resets the counter, because "three consecutive failures"
 * means consecutive.</p>
 *
 * <p>Synchronized because read-only tools run on virtual threads that can report concurrently.</p>
 */
final class ToolCircuitBreaker {

    static final int DEFAULT_FAILURE_THRESHOLD = 3;

    private record Key(String sessionId, String toolName) {
    }

    private final int threshold;
    private final Map<Key, Integer> consecutiveFailures = new HashMap<>();

    ToolCircuitBreaker(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        this.threshold = threshold;
    }

    /** @return {@code true} when the next call to this tool must be short-circuited. */
    synchronized boolean isOpen(String sessionId, String toolName) {
        return failures(sessionId, toolName) >= threshold;
    }

    /** @return how many times this tool failed in a row. */
    synchronized int consecutiveFailures(String sessionId, String toolName) {
        return failures(sessionId, toolName);
    }

    synchronized void recordSuccess(String sessionId, String toolName) {
        consecutiveFailures.remove(new Key(sessionId, toolName));
    }

    /**
     * Records one failure.
     *
     * @return the new consecutive-failure count
     */
    synchronized int recordFailure(String sessionId, String toolName) {
        Key key = new Key(sessionId, toolName);
        int updated = consecutiveFailures.getOrDefault(key, 0) + 1;
        consecutiveFailures.put(key, updated);
        return updated;
    }

    private int failures(String sessionId, String toolName) {
        return consecutiveFailures.getOrDefault(new Key(sessionId, toolName), 0);
    }
}
