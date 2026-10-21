package com.acme.secrets.client;

import java.time.Duration;

/**
 * Waits between retries. Abstracted from {@link Thread#sleep} so that tests can run retries without waiting.
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    static Sleeper real() {
        return duration -> Thread.sleep(duration.toMillis());
    }
}
