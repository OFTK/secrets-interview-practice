package com.acme.secrets.sync.target;

/** The store rejected the request because the name or value breaks one of its limits. */
public class ValidationException extends TargetStoreException {

    public ValidationException(String message) {
        super(message);
    }
}
