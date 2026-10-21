package com.acme.secrets.sync.target;

import java.util.List;

/**
 * A cloud secret store that SecretSync writes to. Writes may fail with {@link ValidationException} or
 * {@link ThrottlingException}; the latter can be retried.
 */
public interface TargetStore {

    /** Lists the secrets SecretSync manages in this store. */
    List<TargetSecret> listManagedSecrets();

    /** @throws ResourceExistsException if a secret with this name already exists */
    void createSecret(String name, String value, int sourceVersion);

    /** @throws ResourceNotFoundException if no secret has this name */
    void updateSecret(String name, String value, int sourceVersion);

    /** @throws ResourceNotFoundException if no secret has this name */
    void deleteSecret(String name);
}
