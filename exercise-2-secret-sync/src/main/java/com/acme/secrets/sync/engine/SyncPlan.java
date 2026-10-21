package com.acme.secrets.sync.engine;

import com.acme.secrets.sync.engine.SyncOperation.Kind;
import java.util.List;

/** The operations that bring one target store in line with the vault for one policy. */
public record SyncPlan(List<SyncOperation> operations) {

    public SyncPlan {
        operations = List.copyOf(operations);
    }

    /** The CREATE and UPDATE operations, in plan order. */
    public List<SyncOperation> upserts() {
        return operations.stream().filter(op -> op.kind() != Kind.DELETE).toList();
    }

    public List<SyncOperation> deletes() {
        return operations.stream().filter(op -> op.kind() == Kind.DELETE).toList();
    }

    public long count(Kind kind) {
        return operations.stream().filter(op -> op.kind() == kind).count();
    }
}
