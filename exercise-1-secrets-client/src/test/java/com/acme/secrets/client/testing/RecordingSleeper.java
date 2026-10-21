package com.acme.secrets.client.testing;

import com.acme.secrets.client.Sleeper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the requested waits and returns immediately.
 */
public final class RecordingSleeper implements Sleeper {

    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();

    @Override
    public void sleep(Duration duration) {
        sleeps.add(duration);
    }

    public List<Duration> sleeps() {
        return List.copyOf(sleeps);
    }
}
