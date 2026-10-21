package com.acme.secrets.sync.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.secrets.sync.testing.FakeSleeper;
import com.acme.secrets.sync.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class TokenBucketRateLimiterTest {

    private static final Instant START = Instant.parse("2026-01-05T09:00:00Z");
    private static final int PERMITS_PER_SECOND = 10;
    private static final int CAPACITY = 10;
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLLING_TOLERANCE = Duration.ofMillis(100);

    private final MutableClock clock = new MutableClock(START);
    private final TokenBucketRateLimiter limiter =
            new TokenBucketRateLimiter(PERMITS_PER_SECOND, CAPACITY, clock, new FakeSleeper(clock));

    @Test
    void tryAcquire_withinCapacity_grantsImmediately() {
        int granted = tryAcquireTimes(CAPACITY);

        assertThat(granted).isEqualTo(CAPACITY);
    }

    @Test
    void tryAcquire_afterOneIdleSecond_refillsToCapacity() {
        tryAcquireTimes(CAPACITY);
        clock.advance(ONE_SECOND);

        int granted = tryAcquireTimes(CAPACITY + 1);

        assertThat(granted).isEqualTo(CAPACITY);
    }

    @Test
    void acquire_bucketDrainedAtTenPerSecond_grantsTenMorePermitsWithinOneSecond() throws InterruptedException {
        tryAcquireTimes(CAPACITY);
        Instant drainedAt = clock.instant();

        acquireTimes(PERMITS_PER_SECOND);

        assertThat(Duration.between(drainedAt, clock.instant()))
                .isBetween(ONE_SECOND, ONE_SECOND.plus(POLLING_TOLERANCE));
    }

    private int tryAcquireTimes(int attempts) {
        int granted = 0;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (limiter.tryAcquire()) {
                granted++;
            }
        }
        return granted;
    }

    private void acquireTimes(int permits) throws InterruptedException {
        for (int permit = 0; permit < permits; permit++) {
            limiter.acquire(ACQUIRE_TIMEOUT);
        }
    }
}
