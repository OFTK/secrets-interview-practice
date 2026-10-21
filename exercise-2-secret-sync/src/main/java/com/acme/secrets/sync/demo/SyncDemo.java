package com.acme.secrets.sync.demo;

import com.acme.secrets.sync.engine.SyncReport;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;

/** Reproduces the SYNC-201..SYNC-206 tickets against the in-memory vault and cloud store. */
public final class SyncDemo {

    private static final int PERMITS_PER_SECOND = 50;
    private static final int BURST_CAPACITY = 500;
    private static final int LARGE_POLICY_SECRETS = 250;
    private static final int SLOW_TARGET_PERMITS_PER_SECOND = 10;
    private static final int SLOW_TARGET_BURST_CAPACITY = 10;
    private static final int SLOW_TARGET_SECRETS = 20;
    private static final int REPEATED_CYCLES = 4;
    private static final int TLS_BUNDLE_LENGTH = 70_000;

    private static final Map<String, Scenario> SCENARIOS = new TreeMap<>(Map.of(
            "SYNC-201", new Scenario("Not all secrets of a large policy reach the target", SyncDemo::syncLargePolicy),
            "SYNC-202", new Scenario("Payments target holds secrets it should not have", SyncDemo::syncPaymentsSecrets),
            "SYNC-203", new Scenario("Target emptied after a vault maintenance window", SyncDemo::syncThroughMaintenance),
            "SYNC-204", new Scenario("Secret disappears from the target every other cycle", SyncDemo::syncRepeatedly),
            "SYNC-205", new Scenario("Sync behind a rate limit is slow and incomplete", SyncDemo::syncBehindRateLimit),
            "SYNC-206", new Scenario("Secret missing from the target, report says SUCCESS", SyncDemo::syncTlsBundle)));

    private SyncDemo() {
    }

    public static void main(String[] args) {
        Scenario scenario = args.length == 1 ? SCENARIOS.get(args[0]) : null;
        if (scenario == null) {
            printUsage();
            return;
        }
        print("=== %s: %s ===", args[0], scenario.title());
        scenario.steps().run();
    }

    private static void syncLargePolicy() {
        List<String> ids = numberedIds(LARGE_POLICY_SECRETS);
        try (DemoEnvironment env = new DemoEnvironment(PERMITS_PER_SECOND, BURST_CAPACITY)) {
            env.storeInVault(ids);

            env.engine().runCycle(env.policy());

            Set<String> synced = Set.copyOf(env.target().secretNames());
            List<String> missing = ids.stream().filter(id -> !synced.contains(id)).toList();
            print("Vault secrets matching %s: %d", env.policy().sourcePattern(), ids.size());
            print("Secrets in target %s: %d", env.policy().targetId(), synced.size());
            if (missing.isEmpty()) {
                print("Missing from the target: none");
            } else {
                print("Missing from the target: %d (first %s, last %s)",
                        missing.size(), missing.get(0), missing.get(missing.size() - 1));
            }
        }
    }

    private static void syncPaymentsSecrets() {
        List<String> ids = List.of("prod/payments/db-password", "prod/payments/stripe-api-key",
                "prod/payments/admin/root-signing-key", "preprod/payments/db-password");
        try (DemoEnvironment env = new DemoEnvironment(PERMITS_PER_SECOND, BURST_CAPACITY)) {
            env.storeInVault(ids);
            printNames("Vault secrets", ids);
            print("Policy '%s' syncs %s into %s", env.policy().name(), env.policy().sourcePattern(),
                    env.policy().targetId());

            env.engine().runCycle(env.policy());

            printNames("Secrets in target " + env.policy().targetId(), env.target().secretNames());
        }
    }

    private static void syncThroughMaintenance() {
        try (DemoEnvironment env = new DemoEnvironment(PERMITS_PER_SECOND, BURST_CAPACITY)) {
            env.storeInVault(List.of("prod/payments/db-password", "prod/payments/stripe-api-key",
                    "prod/payments/jwt-signing-key", "prod/payments/smtp-password", "prod/payments/webhook-hmac-key"));
            print("--- Cycle 1 ---");
            env.engine().runCycle(env.policy());
            printNames("Secrets in target", env.target().secretNames());

            print("--- Vault maintenance window starts: the vault is unavailable ---");
            env.vault().setAvailable(false);
            print("--- Cycle 2 ---");
            SyncReport report = env.engine().runAll(List.of(env.policy())).get(0);
            print("Report: %s", report);
            printNames("Secrets in target", env.target().secretNames());
        }
    }

    private static void syncRepeatedly() {
        try (DemoEnvironment env = new DemoEnvironment(PERMITS_PER_SECOND, BURST_CAPACITY)) {
            env.storeInVault(List.of("prod/payments/db-password", "prod/payments/stripe webhook secret"));
            for (int cycle = 1; cycle <= REPEATED_CYCLES; cycle++) {
                print("--- Cycle %d ---", cycle);
                SyncReport report = env.engine().runAll(List.of(env.policy())).get(0);
                print("Report: %s", report);
                printNames("Secrets in target", env.target().secretNames());
            }
        }
    }

    private static void syncBehindRateLimit() {
        List<String> ids = numberedIds(SLOW_TARGET_SECRETS);
        try (DemoEnvironment env = new DemoEnvironment(SLOW_TARGET_PERMITS_PER_SECOND, SLOW_TARGET_BURST_CAPACITY)) {
            env.storeInVault(ids);
            print("Rate limit: %d permits/s, burst capacity %d; secrets to sync: %d",
                    SLOW_TARGET_PERMITS_PER_SECOND, SLOW_TARGET_BURST_CAPACITY, ids.size());

            long start = System.nanoTime();
            SyncReport report = env.engine().runCycle(env.policy());
            double elapsedSeconds = (System.nanoTime() - start) / 1e9;

            print("Elapsed: %.1f s", elapsedSeconds);
            print("Report: %s", report);
            print("Secrets in target: %d of %d", env.target().secretNames().size(), ids.size());
        }
    }

    private static void syncTlsBundle() {
        List<String> ids = List.of("prod/payments/db-password", "prod/payments/stripe-api-key",
                "prod/payments/jwt-signing-key");
        String tlsBundleId = "prod/payments/tls-bundle";
        try (DemoEnvironment env = new DemoEnvironment(PERMITS_PER_SECOND, BURST_CAPACITY)) {
            env.storeInVault(ids);
            env.vault().put(tlsBundleId, "A".repeat(TLS_BUNDLE_LENGTH));
            print("Vault holds %d secrets, including %s (%d characters)", ids.size() + 1, tlsBundleId,
                    TLS_BUNDLE_LENGTH);

            SyncReport report = env.engine().runCycle(env.policy());

            print("Report: %s", report);
            printNames("Secrets in target", env.target().secretNames());
        }
    }

    private static List<String> numberedIds(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(n -> String.format(Locale.ROOT, "prod/payments/svc-%03d", n))
                .toList();
    }

    private static void printNames(String label, List<String> names) {
        print("%s (%d):", label, names.size());
        names.forEach(name -> print("  %s", name));
    }

    private static void printUsage() {
        print("Usage: SyncDemo <ticket>, where <ticket> is one of:");
        SCENARIOS.forEach((ticket, scenario) -> print("  %s  %s", ticket, scenario.title()));
    }

    private static void print(String format, Object... args) {
        System.out.println(String.format(Locale.ROOT, format, args));
    }

    private record Scenario(String title, Runnable steps) {
    }
}
