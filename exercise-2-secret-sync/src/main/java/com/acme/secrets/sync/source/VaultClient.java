package com.acme.secrets.sync.source;

/** Read-only client for Acme Vault. Every call may throw {@link VaultUnavailableException}. */
public interface VaultClient {

    /** Lists secret metadata in ascending id order, starting at position {@code offset}. */
    Page<SecretRef> listSecrets(int offset, int limit);

    /**
     * Reads the current version of a secret.
     *
     * @throws java.util.NoSuchElementException if no secret has this id
     */
    SecretValue getSecret(String id);
}
