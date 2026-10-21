package com.acme.secrets.sync.engine;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Outcome of one sync cycle for one policy. Sync workers update the counters concurrently. */
public final class SyncReport {

    public enum Status { SUCCESS, PARTIAL_FAILURE, FAILED }

    private final String policyName;
    private final boolean aborted;
    private final String abortReason;
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger updated = new AtomicInteger();
    private final AtomicInteger deleted = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    public SyncReport(String policyName) {
        this(policyName, false, null);
    }

    private SyncReport(String policyName, boolean aborted, String abortReason) {
        this.policyName = policyName;
        this.aborted = aborted;
        this.abortReason = abortReason;
    }

    /** Report for a cycle that stopped before it changed anything in the target. */
    public static SyncReport aborted(String policyName, String reason) {
        return new SyncReport(policyName, true, reason);
    }

    public void recordCreated() {
        created.incrementAndGet();
    }

    public void recordUpdated() {
        updated.incrementAndGet();
    }

    public void recordDeleted() {
        deleted.incrementAndGet();
    }

    public void recordFailed() {
        failed.incrementAndGet();
    }

    public String policyName() {
        return policyName;
    }

    public int created() {
        return created.get();
    }

    public int updated() {
        return updated.get();
    }

    public int deleted() {
        return deleted.get();
    }

    public int failed() {
        return failed.get();
    }

    public Status status() {
        if (aborted) {
            return Status.FAILED;
        }
        return failed.get() == 0 ? Status.SUCCESS : Status.PARTIAL_FAILURE;
    }

    @Override
    public String toString() {
        String summary = String.format(Locale.ROOT, "policy=%s status=%s created=%d updated=%d deleted=%d failed=%d",
                policyName, status(), created(), updated(), deleted(), failed());
        return aborted ? summary + " reason=" + abortReason : summary;
    }
}
