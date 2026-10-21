package com.acme.secrets.client.cache;

import com.acme.secrets.client.Secret;
import com.acme.secrets.client.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCacheTest {

    private static final Instant START = Instant.parse("2026-03-02T09:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Secret SECRET = new Secret("prod/orders/db-password", "orders-pass-1", 1);

    private final MutableClock clock = new MutableClock(START);
    private final SecretCache cache = new SecretCache(TTL, clock);

    @Test
    void get_beforeTtlElapsed_returnsCachedSecret() {
        cache.put(SECRET);
        clock.advance(TTL.minusSeconds(1));

        Optional<Secret> cached = cache.get(SECRET.id());

        assertThat(cached).contains(SECRET);
    }

    @Test
    void get_afterTtlElapsed_returnsEmpty() {
        cache.put(SECRET);
        clock.advance(TTL);

        Optional<Secret> cached = cache.get(SECRET.id());

        assertThat(cached).isEmpty();
    }

    @Test
    void invalidate_cachedSecret_removesIt() {
        cache.put(SECRET);

        cache.invalidate(SECRET.id());

        assertThat(cache.size()).isZero();
    }
}
