package com.acme.secrets.sync.target;

/** A secret with the requested name already exists. */
public class ResourceExistsException extends TargetStoreException {

    public ResourceExistsException(String message) {
        super(message);
    }
}
