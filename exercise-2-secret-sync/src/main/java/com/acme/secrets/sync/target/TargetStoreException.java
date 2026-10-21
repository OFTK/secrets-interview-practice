package com.acme.secrets.sync.target;

/** A request to a {@link TargetStore} failed. */
public class TargetStoreException extends RuntimeException {

    public TargetStoreException(String message) {
        super(message);
    }
}
