package com.acme.secrets.client;

import com.acme.secrets.client.auth.TokenProvider;
import com.acme.secrets.client.cache.SecretCache;
import com.acme.secrets.client.http.ApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads secrets from Acme Secrets Manager and caches them in memory for the configured TTL.
 * Thread-safe; create one instance per application and close it on shutdown.
 */
public final class SecretsClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SecretsClient.class);

    private final ApiClient apiClient;
    private final SecretCache cache;
    private final ExecutorService pool;

    public SecretsClient(SecretsClientConfig config) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.requestTimeout())
                .build();
        TokenProvider tokenProvider = new TokenProvider(httpClient, config);
        this.apiClient = new ApiClient(httpClient, tokenProvider, config);
        this.cache = new SecretCache(config.cacheTtl(), config.clock());
        this.pool = Executors.newFixedThreadPool(config.parallelism(), daemonThreads("secrets-client-"));
    }

    /**
     * Returns the cached secret while it is fresh, otherwise reads its current version from the server.
     *
     * @throws SecretNotFoundException if the secret does not exist
     */
    public Secret getSecret(String secretId) {
        if (secretId == null || secretId.isBlank()) {
            throw new IllegalArgumentException("secretId must not be blank");
        }
        return cache.get(secretId).orElseGet(() -> fetchAndCache(secretId));
    }

    /**
     * Reads several secrets in parallel and returns them in the order of {@code secretIds}.
     *
     * @throws SecretsClientException after all reads completed, if any of them failed; the cause is the first failure
     */
    public Map<String, Secret> getSecrets(Collection<String> secretIds) {
        Map<String, CompletableFuture<Secret>> reads = new LinkedHashMap<>();
        for (String secretId : secretIds) {
            reads.put(secretId, CompletableFuture.supplyAsync(() -> getSecret(secretId), pool));
        }

        Map<String, Secret> secrets = new LinkedHashMap<>();
        List<Throwable> failures = new ArrayList<>();
        reads.forEach((secretId, read) -> {
            try {
                secrets.put(secretId, read.join());
            } catch (CompletionException e) {
                failures.add(e.getCause());
            }
        });
        if (!failures.isEmpty()) {
            throw new SecretsClientException(
                    "Failed to fetch " + failures.size() + " of " + reads.size() + " secrets", failures.get(0));
        }
        return secrets;
    }

    /** Drops the cached copy, so the next read goes to the server. */
    public void invalidate(String secretId) {
        cache.invalidate(secretId);
    }

    @Override
    public void close() {
        pool.shutdown();
    }

    private Secret fetchAndCache(String secretId) {
        Secret secret = apiClient.fetchSecret(secretId);
        cache.put(secret);
        log.info("Fetched secret {}", secret);
        return secret;
    }

    private static ThreadFactory daemonThreads(String namePrefix) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, namePrefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
