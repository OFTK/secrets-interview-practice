package com.acme.secrets.sync.testing;

import com.acme.secrets.sync.engine.Sleeper;
import java.time.Duration;

/** A {@link Sleeper} that advances a {@link MutableClock} by the requested duration instead of waiting. */
public final class FakeSleeper implements Sleeper {

    private final MutableClock clock;

    public FakeSleeper(MutableClock clock) {
        this.clock = clock;
    }

    @Override
    public void sleep(Duration duration) {
        clock.advance(duration);
    }
}
