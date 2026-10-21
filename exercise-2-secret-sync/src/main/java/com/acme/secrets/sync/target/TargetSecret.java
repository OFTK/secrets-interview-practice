package com.acme.secrets.sync.target;

/** A secret held by a target store, tagged with the vault version it was copied from. */
public record TargetSecret(String name, int sourceVersion) {
}
