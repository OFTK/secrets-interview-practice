package com.acme.secrets.sync.target;

/** No secret with the requested name exists. */
public class ResourceNotFoundException extends TargetStoreException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
