package com.acme.secrets.sync.target;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory {@link TargetStore} with the limits of AWS Secrets Manager: names must match
 * {@code [A-Za-z0-9/_+=.@-]{1,512}} and values may not exceed 65,536 bytes of UTF-8. Thread-safe.
 */
public final class FakeCloudSecretStore implements TargetStore {

    private static final Logger log = LoggerFactory.getLogger(FakeCloudSecretStore.class);
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9/_+=.@-]{1,512}");
    private static final int MAX_VALUE_BYTES = 65_536;
    private static final long THROTTLING_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final String targetId;
    private final NavigableMap<String, StoredSecret> secrets = new TreeMap<>();
    private final List<String> operations = new ArrayList<>();
    private final Deque<Long> recentWriteNanos = new ArrayDeque<>();
    private int maxOperationsPerSecond;

    public FakeCloudSecretStore(String targetId) {
        this.targetId = targetId;
    }

    /** Throttles writes beyond this many per second of wall-clock time; {@code 0} (the default) disables it. */
    public synchronized void setMaxOperationsPerSecond(int maxOperationsPerSecond) {
        this.maxOperationsPerSecond = maxOperationsPerSecond;
        recentWriteNanos.clear();
    }

    @Override
    public synchronized List<TargetSecret> listManagedSecrets() {
        return secrets.entrySet().stream()
                .map(entry -> new TargetSecret(entry.getKey(), entry.getValue().sourceVersion()))
                .toList();
    }

    @Override
    public synchronized void createSecret(String name, String value, int sourceVersion) {
        admitWrite();
        validate(name, value);
        if (secrets.containsKey(name)) {
            throw new ResourceExistsException("Secret " + name + " already exists");
        }
        secrets.put(name, new StoredSecret(value, sourceVersion));
        recordWrite("CREATE", name, sourceVersion);
    }

    @Override
    public synchronized void updateSecret(String name, String value, int sourceVersion) {
        admitWrite();
        validate(name, value);
        requireExisting(name);
        secrets.put(name, new StoredSecret(value, sourceVersion));
        recordWrite("UPDATE", name, sourceVersion);
    }

    @Override
    public synchronized void deleteSecret(String name) {
        admitWrite();
        requireExisting(name);
        secrets.remove(name);
        operations.add("DELETE " + name);
        log.info("[{}] DELETE {}", targetId, name);
    }

    /** Successful writes in the order they were applied, such as {@code CREATE prod/payments/db-password}. */
    public synchronized List<String> operations() {
        return List.copyOf(operations);
    }

    /** Names of the stored secrets in ascending order. */
    public synchronized List<String> secretNames() {
        return List.copyOf(secrets.keySet());
    }

    public synchronized String valueOf(String name) {
        return requireExisting(name).value();
    }

    public synchronized int sourceVersionOf(String name) {
        return requireExisting(name).sourceVersion();
    }

    private void admitWrite() {
        if (maxOperationsPerSecond == 0) {
            return;
        }
        long now = System.nanoTime();
        while (!recentWriteNanos.isEmpty() && now - recentWriteNanos.peekFirst() >= THROTTLING_WINDOW_NANOS) {
            recentWriteNanos.removeFirst();
        }
        if (recentWriteNanos.size() >= maxOperationsPerSecond) {
            throw new ThrottlingException("Rate exceeded");
        }
        recentWriteNanos.addLast(now);
    }

    private static void validate(String name, String value) {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new ValidationException("Invalid secret name: " + name);
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new ValidationException("Secret value exceeds " + MAX_VALUE_BYTES + " bytes");
        }
    }

    private StoredSecret requireExisting(String name) {
        StoredSecret secret = secrets.get(name);
        if (secret == null) {
            throw new ResourceNotFoundException("Secret " + name + " not found");
        }
        return secret;
    }

    private void recordWrite(String kind, String name, int sourceVersion) {
        operations.add(kind + " " + name);
        log.info("[{}] {} {} (v{})", targetId, kind, name, sourceVersion);
    }

    private record StoredSecret(String value, int sourceVersion) {

        @Override
        public String toString() {
            return "StoredSecret[value=****, sourceVersion=" + sourceVersion + "]";
        }
    }
}
