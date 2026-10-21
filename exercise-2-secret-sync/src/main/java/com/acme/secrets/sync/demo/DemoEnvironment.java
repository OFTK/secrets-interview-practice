package com.acme.secrets.sync.demo;

import com.acme.secrets.sync.config.PolicyLoader;
import com.acme.secrets.sync.config.SyncPolicy;
import com.acme.secrets.sync.engine.DiffCalculator;
import com.acme.secrets.sync.engine.Sleeper;
import com.acme.secrets.sync.engine.SourceScanner;
import com.acme.secrets.sync.engine.SyncEngine;
import com.acme.secrets.sync.engine.SyncExecutor;
import com.acme.secrets.sync.engine.TokenBucketRateLimiter;
import com.acme.secrets.sync.source.FakeVault;
import com.acme.secrets.sync.target.FakeCloudSecretStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/** A fresh vault, cloud store and sync engine for one demo scenario, running on real time. */
final class DemoEnvironment implements AutoCloseable {

    private static final String POLICIES_RESOURCE = "/policies.yaml";
    private static final String POLICY_NAME = "payments-app";
    private static final String SECRET_VALUE = "correct-horse-battery-staple";
    private static final int WORKERS = 8;
    private static final int PAGE_SIZE = 100;

    private final SyncPolicy policy = loadPolicy();
    private final FakeVault vault = new FakeVault(Clock.systemUTC());
    private final FakeCloudSecretStore target = new FakeCloudSecretStore(policy.targetId());
    private final SyncExecutor executor;
    private final SyncEngine engine;

    DemoEnvironment(int permitsPerSecond, int burstCapacity) {
        TokenBucketRateLimiter limiter =
                new TokenBucketRateLimiter(permitsPerSecond, burstCapacity, Clock.systemUTC(), Sleeper.real());
        executor = new SyncExecutor(vault, limiter, WORKERS, Sleeper.real());
        engine = new SyncEngine(new SourceScanner(vault, PAGE_SIZE), new DiffCalculator(), executor,
                Map.of(policy.targetId(), target));
    }

    SyncPolicy policy() {
        return policy;
    }

    FakeVault vault() {
        return vault;
    }

    FakeCloudSecretStore target() {
        return target;
    }

    SyncEngine engine() {
        return engine;
    }

    void storeInVault(List<String> ids) {
        ids.forEach(id -> vault.put(id, SECRET_VALUE));
    }

    @Override
    public void close() {
        executor.close();
    }

    private static SyncPolicy loadPolicy() {
        try (InputStream yaml = DemoEnvironment.class.getResourceAsStream(POLICIES_RESOURCE)) {
            return new PolicyLoader().load(yaml).stream()
                    .filter(policy -> policy.name().equals(POLICY_NAME))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No policy '" + POLICY_NAME + "' in " + POLICIES_RESOURCE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
