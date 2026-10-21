package com.acme.secrets.sync.source;

/** Acme Vault cannot serve requests right now, for example during a maintenance window. */
public class VaultUnavailableException extends RuntimeException {

    public VaultUnavailableException(String message) {
        super(message);
    }
}
