package com.acme.secrets.sync.engine;

import java.time.Clock;
import java.time.Duration;

/**
 * Token bucket shared by the workers writing to one target store. The bucket starts full, holds at most
 * {@code capacity} permits and refills at {@code permitsPerSecond}. Thread-safe.
 */
public final class TokenBucketRateLimiter {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    private final int permitsPerSecond;
    private final int capacity;
    private final Clock clock;
    private final Sleeper sleeper;
    private long tokens;
    private long lastRefillMillis;

    public TokenBucketRateLimiter(int permitsPerSecond, int capacity, Clock clock, Sleeper sleeper) {
        if (permitsPerSecond < 1 || capacity < 1) {
            throw new IllegalArgumentException("permitsPerSecond and capacity must be positive");
        }
        this.permitsPerSecond = permitsPerSecond;
        this.capacity = capacity;
        this.clock = clock;
        this.sleeper = sleeper;
        this.tokens = capacity;
        this.lastRefillMillis = clock.millis();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    /** @throws RateLimitTimeoutException if no permit became available within {@code timeout} */
    public void acquire(Duration timeout) throws InterruptedException {
        long deadlineMillis = clock.millis() + timeout.toMillis();
        while (!tryAcquire()) {
            if (clock.millis() >= deadlineMillis) {
                throw new RateLimitTimeoutException("No rate-limit permit within " + timeout.toMillis() + " ms");
            }
            sleeper.sleep(POLL_INTERVAL);
        }
    }

    private void refill() {
        long now = clock.millis();
        long elapsedMillis = now - lastRefillMillis;
        long newTokens = elapsedMillis * permitsPerSecond / 1000;
        tokens = Math.min(capacity, tokens + newTokens);
        lastRefillMillis = now;
    }
}
