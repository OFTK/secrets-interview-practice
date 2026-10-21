package com.acme.secrets.client.fake;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Admits at most {@code limit} requests within any sliding window of real (monotonic) time.
 */
final class BurstLimiter {

    private final int limit;
    private final long windowNanos;
    private final Deque<Long> admitted = new ArrayDeque<>();

    BurstLimiter(int limit, Duration window) {
        this.limit = limit;
        this.windowNanos = window.toNanos();
    }

    synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        while (!admitted.isEmpty() && now - admitted.peekFirst() >= windowNanos) {
            admitted.pollFirst();
        }
        if (admitted.size() >= limit) {
            return false;
        }
        admitted.addLast(now);
        return true;
    }
}
