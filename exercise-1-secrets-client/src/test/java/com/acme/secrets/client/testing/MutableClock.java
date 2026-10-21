package com.acme.secrets.client.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Clock that only moves when a test calls {@link #advance}. Copies made by {@link #withZone} share the time.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }
}
