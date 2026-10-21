package com.acme.secrets.sync.source;

import java.time.Instant;

/** Metadata of a vault secret as returned by a listing; a listing never carries values. */
public record SecretRef(String id, int version, Instant updatedAt) {
}
