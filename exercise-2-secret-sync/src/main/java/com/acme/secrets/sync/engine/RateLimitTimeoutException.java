package com.acme.secrets.sync.engine;

/** No rate-limit permit became available within the caller's timeout. */
public class RateLimitTimeoutException extends RuntimeException {

    public RateLimitTimeoutException(String message) {
        super(message);
    }
}
