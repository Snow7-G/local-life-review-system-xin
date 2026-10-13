package dev.lifeagent.llm;

import java.time.Duration;
import java.util.Objects;

/** Immutable exponential-backoff settings; maxRetries excludes the initial attempt. */
public record RetryPolicy(
        int maxRetries,
        Duration baseDelay,
        Duration maxDelay,
        double jitterFactor) {

    public RetryPolicy {
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        if (baseDelay.isZero() || baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be less than baseDelay");
        }
        if (!Double.isFinite(jitterFactor) || jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be between 0 and 1");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(30), 0.25);
    }

    Duration delayForRetry(int retryIndex, double randomSample) {
        if (retryIndex < 0) {
            throw new IllegalArgumentException("retryIndex must be non-negative");
        }
        if (!Double.isFinite(randomSample) || randomSample < 0.0 || randomSample > 1.0) {
            throw new IllegalArgumentException("random sample must be between 0 and 1");
        }
        long baseNanos = saturatedNanos(baseDelay);
        long maximumNanos = saturatedNanos(maxDelay);
        long exponential = baseNanos;
        for (int index = 0; index < retryIndex && exponential < maximumNanos; index++) {
            exponential = exponential > maximumNanos / 2 ? maximumNanos : exponential * 2;
        }
        double jitter = exponential * jitterFactor * randomSample;
        long withJitter = jitter >= Long.MAX_VALUE - exponential
                ? Long.MAX_VALUE
                : exponential + (long) jitter;
        return Duration.ofNanos(Math.min(maximumNanos, withJitter));
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
