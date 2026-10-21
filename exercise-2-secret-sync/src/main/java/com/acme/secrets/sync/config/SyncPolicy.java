package com.acme.secrets.sync.config;

/**
 * A tenant-authored rule: copy the vault secrets matching {@code sourcePattern} into target {@code targetId}.
 *
 * @param deleteOrphans whether target secrets without a matching vault secret are deleted from the target
 */
public record SyncPolicy(String name, String sourcePattern, String targetId, boolean deleteOrphans) {

    public SyncPolicy {
        requireNonBlank(name, "name");
        requireNonBlank(sourcePattern, "sourcePattern");
        requireNonBlank(targetId, "targetId");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Sync policy field '" + field + "' must not be blank");
        }
    }
}
