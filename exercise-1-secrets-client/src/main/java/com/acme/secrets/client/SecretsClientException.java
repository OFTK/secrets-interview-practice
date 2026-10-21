package com.acme.secrets.client;

/**
 * Base class of the errors reported by {@link SecretsClient}.
 */
public class SecretsClientException extends RuntimeException {

    public SecretsClientException(String message) {
        super(message);
    }

    public SecretsClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
