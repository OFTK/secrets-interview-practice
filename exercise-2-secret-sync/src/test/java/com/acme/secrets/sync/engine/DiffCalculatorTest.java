package com.acme.secrets.sync.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.secrets.sync.config.SyncPolicy;
import com.acme.secrets.sync.source.SecretRef;
import com.acme.secrets.sync.target.TargetSecret;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiffCalculatorTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-01-05T09:00:00Z");
    private static final int FIRST_VERSION = 1;
    private static final int SECOND_VERSION = 2;
    private static final String DB_PASSWORD = "prod/payments/db-password";
    private static final String RETIRED_API_KEY = "prod/payments/retired-api-key";
    private static final SyncPolicy DELETE_ORPHANS_POLICY =
            new SyncPolicy("payments-app", "prod/payments/*", "aws-payments-app", true);
    private static final SyncPolicy KEEP_ORPHANS_POLICY =
            new SyncPolicy("payments-app", "prod/payments/*", "aws-payments-app", false);

    private final DiffCalculator diff = new DiffCalculator();

    @Test
    void plan_newSourceSecret_createsIt() {
        List<SecretRef> sources = List.of(source(DB_PASSWORD, FIRST_VERSION));

        SyncPlan plan = diff.plan(DELETE_ORPHANS_POLICY, sources, List.of());

        assertThat(plan.operations()).containsExactly(SyncOperation.create(DB_PASSWORD, DB_PASSWORD));
    }

    @Test
    void plan_changedVersion_updatesIt() {
        List<SecretRef> sources = List.of(source(DB_PASSWORD, SECOND_VERSION));
        List<TargetSecret> targets = List.of(new TargetSecret(DB_PASSWORD, FIRST_VERSION));

        SyncPlan plan = diff.plan(DELETE_ORPHANS_POLICY, sources, targets);

        assertThat(plan.operations()).containsExactly(SyncOperation.update(DB_PASSWORD, DB_PASSWORD));
    }

    @Test
    void plan_unchangedVersion_producesNoOperation() {
        List<SecretRef> sources = List.of(source(DB_PASSWORD, FIRST_VERSION));
        List<TargetSecret> targets = List.of(new TargetSecret(DB_PASSWORD, FIRST_VERSION));

        SyncPlan plan = diff.plan(DELETE_ORPHANS_POLICY, sources, targets);

        assertThat(plan.operations()).isEmpty();
    }

    @Test
    void plan_orphanWithDeleteOrphans_deletesIt() {
        List<SecretRef> sources = List.of(source(DB_PASSWORD, FIRST_VERSION));
        List<TargetSecret> targets = List.of(
                new TargetSecret(DB_PASSWORD, FIRST_VERSION), new TargetSecret(RETIRED_API_KEY, FIRST_VERSION));

        SyncPlan plan = diff.plan(DELETE_ORPHANS_POLICY, sources, targets);

        assertThat(plan.operations()).containsExactly(SyncOperation.delete(RETIRED_API_KEY));
    }

    @Test
    void plan_orphanWithoutDeleteOrphans_keepsIt() {
        List<SecretRef> sources = List.of(source(DB_PASSWORD, FIRST_VERSION));
        List<TargetSecret> targets = List.of(
                new TargetSecret(DB_PASSWORD, FIRST_VERSION), new TargetSecret(RETIRED_API_KEY, FIRST_VERSION));

        SyncPlan plan = diff.plan(KEEP_ORPHANS_POLICY, sources, targets);

        assertThat(plan.operations()).isEmpty();
    }

    private static SecretRef source(String id, int version) {
        return new SecretRef(id, version, UPDATED_AT);
    }
}
