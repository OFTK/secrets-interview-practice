package com.acme.secrets.client.http;

import java.time.Duration;

/**
 * Decides which failed requests are sent again and how long to wait before each retry.
 */
public record RetryPolicy(int maxRetries, Duration initialBackoff, Duration maxBackoff) {

    private static final int MAX_DOUBLINGS = 30;

    public boolean isRetryable(int status) {
        return status >= 400;
    }

    /** Delay before retry number {@code attempt + 1}: doubles with each attempt, capped at {@code maxBackoff}. */
    public Duration backoff(int attempt) {
        Duration delay = initialBackoff.multipliedBy(1L << Math.min(attempt, MAX_DOUBLINGS));
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
