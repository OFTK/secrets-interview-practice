package com.acme.secrets.client.demo;

import com.acme.secrets.client.Secret;
import com.acme.secrets.client.SecretsClient;
import com.acme.secrets.client.SecretsClientConfig;
import com.acme.secrets.client.fake.FakeSecretsServer;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Reproduces the open SDK tickets against an in-process {@link FakeSecretsServer}.
 * Run it with a ticket id, for example {@code SDK-101}.
 */
public final class DemoApp {

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("SDK-101", "Secret values appear in application logs", DemoApp::fetchAndLog),
            new Scenario("SDK-102", "Reading a missing secret takes seconds to fail", DemoApp::readMissingSecret),
            new Scenario("SDK-103", "Rotated secret not picked up after the cache TTL", DemoApp::rotateSecret),
            new Scenario("SDK-104", "Secret ids with '#' or spaces return the wrong secret or fail",
                    DemoApp::readSpecialCharacterIds),
            new Scenario("SDK-105", "First read after access tokens are revoked fails", DemoApp::revokeTokens),
            new Scenario("SDK-106", "Intermittent HTTP 429 errors when reading secrets in parallel",
                    DemoApp::readInParallel));

    private DemoApp() {
    }

    public static void main(String[] args) throws InterruptedException {
        Optional<Scenario> scenario = args.length == 1 ? find(args[0]) : Optional.empty();
        if (scenario.isEmpty()) {
            printUsage();
            return;
        }
        System.out.println("=== " + scenario.get().ticket() + ": " + scenario.get().title() + " ===");
        scenario.get().steps().run();
    }

    private static void fetchAndLog() {
        String secretId = "prod/payments/db-password";
        try (FakeSecretsServer server = FakeSecretsServer.start();
             SecretsClient client = new SecretsClient(configFor(server).build())) {
            server.putSecret(secretId, "Pa55w0rd-demo");

            client.getSecret(secretId);

            System.out.println("Fetched " + secretId + ". Check the log lines above.");
        }
    }

    private static void readMissingSecret() {
        String secretId = "prod/payments/does-not-exist";
        try (FakeSecretsServer server = FakeSecretsServer.start();
             SecretsClient client = new SecretsClient(configFor(server).build())) {
            long startedAt = System.nanoTime();
            String outcome = outcomeOf(() -> client.getSecret(secretId));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            System.out.println("getSecret(\"" + secretId + "\") took " + elapsedMillis + " ms -> " + outcome);
            System.out.println("Requests the server received for this id: " + server.secretRequestCount(secretId));
        }
    }

    private static void rotateSecret() throws InterruptedException {
        String secretId = "prod/orders/db-password";
        Duration cacheTtl = Duration.ofSeconds(2);
        try (FakeSecretsServer server = FakeSecretsServer.start();
             SecretsClient client = new SecretsClient(configFor(server).cacheTtl(cacheTtl).build())) {
            server.putSecret(secretId, "orders-pass-v1");
            System.out.println("First read:  " + outcomeOf(() -> client.getSecret(secretId)));

            server.putSecret(secretId, "orders-pass-v2");
            System.out.println("Rotated " + secretId + " on the server to version 2.");
            System.out.println("Waiting 3 s (the client's cache TTL is " + cacheTtl.toSeconds() + " s)...");
            Thread.sleep(3_000);

            System.out.println("Second read: " + outcomeOf(() -> client.getSecret(secretId)));
        }
    }

    private static void readSpecialCharacterIds() {
        try (FakeSecretsServer server = FakeSecretsServer.start();
             SecretsClient client = new SecretsClient(configFor(server).build())) {
            server.putSecret("payments/stripe#live", "sk_live_...demo");
            server.putSecret("payments/stripe", "sk_test_...demo");
            server.putSecret("hr/db password", "hr-pass-demo");

            for (String secretId : List.of("payments/stripe#live", "hr/db password")) {
                System.out.println("'" + secretId + "' -> " + outcomeOf(() -> client.getSecret(secretId)));
            }
        }
    }

    private static void revokeTokens() {
        String apiKeyId = "prod/inventory/api-key";
        String passwordId = "prod/inventory/db-password";
        try (FakeSecretsServer server = FakeSecretsServer.start();
             SecretsClient client = new SecretsClient(configFor(server).build())) {
            server.putSecret(apiKeyId, "inventory-api-key-demo");
            server.putSecret(passwordId, "inventory-pass-demo");

            System.out.println("1. " + apiKeyId + " -> " + outcomeOf(() -> client.getSecret(apiKeyId)));
            server.revokeAllTokens();
            System.out.println("   The platform team revoked all access tokens.");
            System.out.println("2. " + passwordId + " -> " + outcomeOf(() -> client.getSecret(passwordId)));
            System.out.println("3. " + passwordId + " -> " + outcomeOf(() -> client.getSecret(passwordId)));
            System.out.println("Token requests: " + server.tokenRequestCount());
        }
    }

    private static void readInParallel() throws InterruptedException {
        List<String> catalogIds = secretIds("prod/catalog/key-", 20);
        List<String> billingIds = secretIds("prod/billing/key-", 20);
        try (FakeSecretsServer server = FakeSecretsServer.start()) {
            server.setTokenTtl(Duration.ofSeconds(3));
            server.setTokenLatency(Duration.ofMillis(300));
            catalogIds.forEach(id -> server.putSecret(id, "catalog-demo-value"));
            billingIds.forEach(id -> server.putSecret(id, "billing-demo-value"));
            SecretsClientConfig config = configFor(server)
                    .tokenRefreshSkew(Duration.ofMillis(500))
                    .parallelism(8)
                    .build();
            try (SecretsClient client = new SecretsClient(config)) {
                client.getSecret(catalogIds.get(0));
                System.out.println("Read " + catalogIds.get(0) + "; the client now holds a token valid for 3 s.");
                System.out.println("First batch, " + catalogIds.size() + " ids: "
                        + batchOutcome(() -> client.getSecrets(catalogIds)));

                System.out.println("Waiting 3 s for the access token to expire...");
                Thread.sleep(3_000);

                int tokenRequestsBefore = server.tokenRequestCount();
                System.out.println("Second batch, " + billingIds.size() + " ids: "
                        + batchOutcome(() -> client.getSecrets(billingIds)));
                System.out.println("Token requests during the second batch: "
                        + (server.tokenRequestCount() - tokenRequestsBefore));
            }
        }
    }

    private static SecretsClientConfig.Builder configFor(FakeSecretsServer server) {
        return SecretsClientConfig.builder()
                .baseUri(server.baseUri())
                .apiKey(FakeSecretsServer.API_KEY);
    }

    private static List<String> secretIds(String prefix, int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(n -> String.format(Locale.ROOT, "%s%02d", prefix, n))
                .toList();
    }

    private static String outcomeOf(Supplier<Secret> read) {
        try {
            Secret secret = read.get();
            return "version " + secret.version() + ": '" + secret.value() + "'";
        } catch (RuntimeException e) {
            return describe(e);
        }
    }

    private static String batchOutcome(Supplier<Map<String, Secret>> read) {
        try {
            return "got all " + read.get().size() + " secrets";
        } catch (RuntimeException e) {
            String outcome = describe(e);
            return e.getCause() == null ? outcome : outcome + " (first failure: " + describe(e.getCause()) + ")";
        }
    }

    private static String describe(Throwable failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    private static Optional<Scenario> find(String ticket) {
        String normalized = ticket.trim().toUpperCase(Locale.ROOT);
        return SCENARIOS.stream().filter(scenario -> scenario.ticket().equals(normalized)).findFirst();
    }

    private static void printUsage() {
        System.out.println("Usage: DemoApp <ticket>, where <ticket> is one of:");
        SCENARIOS.forEach(scenario -> System.out.println("  " + scenario.ticket() + "  " + scenario.title()));
    }

    private record Scenario(String ticket, String title, Steps steps) {
    }

    @FunctionalInterface
    private interface Steps {

        void run() throws InterruptedException;
    }
}
