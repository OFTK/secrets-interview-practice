package com.acme.secrets.sync.source;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.TreeMap;

/** In-memory {@link VaultClient} for tests and demos. Thread-safe. */
public final class FakeVault implements VaultClient {

    private final Clock clock;
    private final NavigableMap<String, StoredSecret> secrets = new TreeMap<>();
    private boolean available = true;
    private int listCallCount;
    private int getCallCount;

    public FakeVault(Clock clock) {
        this.clock = clock;
    }

    /** Creates the secret at version 1, or stores the next version of an existing secret. */
    public synchronized void put(String id, String value) {
        StoredSecret current = secrets.get(id);
        int version = current == null ? 1 : current.version() + 1;
        secrets.put(id, new StoredSecret(version, value, clock.instant()));
    }

    public synchronized void delete(String id) {
        secrets.remove(id);
    }

    /** While unavailable, every {@link VaultClient} call throws {@link VaultUnavailableException}. */
    public synchronized void setAvailable(boolean available) {
        this.available = available;
    }

    public synchronized int listCallCount() {
        return listCallCount;
    }

    public synchronized int getCallCount() {
        return getCallCount;
    }

    @Override
    public synchronized Page<SecretRef> listSecrets(int offset, int limit) {
        listCallCount++;
        ensureAvailable();
        List<SecretRef> items = secrets.entrySet().stream()
                .skip(offset)
                .limit(limit)
                .map(entry -> entry.getValue().toRef(entry.getKey()))
                .toList();
        return new Page<>(items, offset, secrets.size());
    }

    @Override
    public synchronized SecretValue getSecret(String id) {
        getCallCount++;
        ensureAvailable();
        StoredSecret secret = secrets.get(id);
        if (secret == null) {
            throw new NoSuchElementException("Secret " + id + " not found in vault");
        }
        return new SecretValue(id, secret.version(), secret.value());
    }

    private void ensureAvailable() {
        if (!available) {
            throw new VaultUnavailableException("Vault returned 503 Service Unavailable");
        }
    }

    private record StoredSecret(int version, String value, Instant updatedAt) {

        SecretRef toRef(String id) {
            return new SecretRef(id, version, updatedAt);
        }

        @Override
        public String toString() {
            return "StoredSecret[version=" + version + ", value=****, updatedAt=" + updatedAt + "]";
        }
    }
}
