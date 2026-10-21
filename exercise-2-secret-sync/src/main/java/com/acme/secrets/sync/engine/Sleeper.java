package com.acme.secrets.sync.engine;

import java.time.Duration;

/** Pauses the calling thread; injected so that tests can wait on a fake clock instead. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    static Sleeper real() {
        return duration -> Thread.sleep(duration.toMillis());
    }
}
