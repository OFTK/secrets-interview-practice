package com.acme.secrets.sync.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.secrets.sync.config.SyncPolicy;
import com.acme.secrets.sync.engine.SyncReport.Status;
import com.acme.secrets.sync.source.FakeVault;
import com.acme.secrets.sync.target.FakeCloudSecretStore;
import com.acme.secrets.sync.testing.FakeSleeper;
import com.acme.secrets.sync.testing.MutableClock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class SyncEngineTest {

    private static final Instant START = Instant.parse("2026-01-05T09:00:00Z");
    private static final String TARGET_ID = "aws-payments-app";
    private static final SyncPolicy PAYMENTS_POLICY =
            new SyncPolicy("payments-app", "prod/payments/*", TARGET_ID, true);
    private static final int WORKERS = 4;
    private static final int PAGE_SIZE = 100;
    private static final int GENEROUS_PERMITS = 1_000;
    private static final String DB_PASSWORD = "prod/payments/db-password";
    private static final String STRIPE_API_KEY = "prod/payments/stripe-api-key";
    private static final String JWT_SIGNING_KEY = "prod/payments/jwt-signing-key";
    private static final Map<String, String> PAYMENTS_SECRETS = Map.of(
            DB_PASSWORD, "db-password-value",
            STRIPE_API_KEY, "stripe-api-key-value",
            JWT_SIGNING_KEY, "jwt-signing-key-value");
    private static final String WEBHOOK_SECRET_ID = "prod/payments/stripe webhook secret";
    private static final String WEBHOOK_SECRET_VALUE = "whsec-value";
    private static final String CREATE_WEBHOOK_SECRET = "CREATE prod/payments/stripe_webhook_secret";
    private static final String TLS_BUNDLE = "prod/payments/tls-bundle";
    private static final String OVERSIZED_VALUE = "A".repeat(70_000);

    private final MutableClock clock = new MutableClock(START);
    private final FakeSleeper sleeper = new FakeSleeper(clock);
    private final FakeVault vault = new FakeVault(clock);
    private final FakeCloudSecretStore target = new FakeCloudSecretStore(TARGET_ID);
    private final SyncExecutor executor = new SyncExecutor(vault,
            new TokenBucketRateLimiter(GENEROUS_PERMITS, GENEROUS_PERMITS, clock, sleeper), WORKERS, sleeper);
    private final SyncEngine engine = new SyncEngine(
            new SourceScanner(vault, PAGE_SIZE), new DiffCalculator(), executor, Map.of(TARGET_ID, target));

    @AfterEach
    void closeExecutor() {
        executor.close();
    }

    @Test
    void runCycle_newSecrets_createsThemInTarget() {
        storeInVault(PAYMENTS_SECRETS);

        engine.runCycle(PAYMENTS_POLICY);

        assertThat(targetContents()).isEqualTo(PAYMENTS_SECRETS);
    }

    @Test
    void runCycle_secondRunWithoutChanges_changesNothing() {
        storeInVault(PAYMENTS_SECRETS);
        engine.runCycle(PAYMENTS_POLICY);
        List<String> operationsAfterFirstCycle = target.operations();

        engine.runCycle(PAYMENTS_POLICY);

        assertThat(target.operations()).isEqualTo(operationsAfterFirstCycle);
    }

    @Test
    void runCycle_secretDeletedInVault_deletesTargetCopy() {
        storeInVault(PAYMENTS_SECRETS);
        engine.runCycle(PAYMENTS_POLICY);
        vault.delete(STRIPE_API_KEY);

        engine.runCycle(PAYMENTS_POLICY);

        assertThat(target.secretNames()).containsExactlyInAnyOrder(DB_PASSWORD, JWT_SIGNING_KEY);
    }

    @Test
    void runAll_vaultUnavailable_deletesNothing() {
        storeInVault(PAYMENTS_SECRETS);
        engine.runCycle(PAYMENTS_POLICY);
        vault.setAvailable(false);

        engine.runAll(List.of(PAYMENTS_POLICY));

        assertThat(target.secretNames()).containsExactlyInAnyOrderElementsOf(PAYMENTS_SECRETS.keySet());
    }

    @Test
    void runCycle_secretNameNeedsMapping_isStableAcrossCycles() {
        vault.put(WEBHOOK_SECRET_ID, WEBHOOK_SECRET_VALUE);
        engine.runCycle(PAYMENTS_POLICY);

        engine.runCycle(PAYMENTS_POLICY);

        assertThat(target.operations()).containsExactly(CREATE_WEBHOOK_SECRET);
    }

    @Test
    void runCycle_targetRejectsSecret_reportsFailure() {
        storeInVault(PAYMENTS_SECRETS);
        vault.put(TLS_BUNDLE, OVERSIZED_VALUE);

        SyncReport report = engine.runCycle(PAYMENTS_POLICY);

        assertThat(report.status()).isEqualTo(Status.PARTIAL_FAILURE);
    }

    private void storeInVault(Map<String, String> secrets) {
        secrets.forEach(vault::put);
    }

    private Map<String, String> targetContents() {
        return target.secretNames().stream().collect(Collectors.toMap(Function.identity(), target::valueOf));
    }
}
