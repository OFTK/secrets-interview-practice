package com.acme.secrets.sync.engine;

import java.util.Objects;

/**
 * One change to apply to a target store.
 *
 * @param sourceId the vault secret to copy; {@code null} for {@link Kind#DELETE}
 */
public record SyncOperation(Kind kind, String sourceId, String targetName) {

    public enum Kind { CREATE, UPDATE, DELETE }

    public SyncOperation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetName, "targetName");
    }

    public static SyncOperation create(String sourceId, String targetName) {
        return new SyncOperation(Kind.CREATE, sourceId, targetName);
    }

    public static SyncOperation update(String sourceId, String targetName) {
        return new SyncOperation(Kind.UPDATE, sourceId, targetName);
    }

    public static SyncOperation delete(String targetName) {
        return new SyncOperation(Kind.DELETE, null, targetName);
    }
}
