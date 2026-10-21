package com.acme.secrets.sync.target;

/** The caller exceeded the store's request rate; the request may be retried later. */
public class ThrottlingException extends TargetStoreException {

    public ThrottlingException(String message) {
        super(message);
    }
}
