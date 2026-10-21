package com.acme.secrets.client.cache;

import com.acme.secrets.client.Secret;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of secrets by id. An entry expires {@code ttl} after it was put.
 */
public final class SecretCache {

    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public SecretCache(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    public Optional<Secret> get(String secretId) {
        Entry entry = entries.get(secretId);
        if (entry == null) {
            return Optional.empty();
        }
        if (clock.instant().isBefore(entry.expiresAt())) {
            return Optional.of(entry.secret());
        }
        entries.remove(secretId, entry);
        return Optional.empty();
    }

    public void put(Secret secret) {
        entries.put(secret.id(), new Entry(secret, clock.instant().plus(ttl)));
    }

    public void invalidate(String secretId) {
        entries.remove(secretId);
    }

    public int size() {
        return entries.size();
    }

    private record Entry(Secret secret, Instant expiresAt) {
    }
}
