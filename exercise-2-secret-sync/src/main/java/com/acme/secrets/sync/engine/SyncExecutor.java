package com.acme.secrets.sync.engine;

import com.acme.secrets.sync.source.SecretValue;
import com.acme.secrets.sync.source.VaultClient;
import com.acme.secrets.sync.target.TargetStore;
import com.acme.secrets.sync.target.ThrottlingException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies sync plans on a fixed pool of worker threads. Every CREATE and UPDATE finishes before the first DELETE
 * starts, so a create and a delete of the same name never race. Values are read from the vault when an operation
 * is applied and never kept in the plan.
 */
public final class SyncExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SyncExecutor.class);
    private static final Duration PERMIT_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_THROTTLING_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(100);

    private final VaultClient vault;
    private final TokenBucketRateLimiter limiter;
    private final Sleeper sleeper;
    private final ExecutorService pool;

    public SyncExecutor(VaultClient vault, TokenBucketRateLimiter limiter, int workers, Sleeper sleeper) {
        this.vault = vault;
        this.limiter = limiter;
        this.sleeper = sleeper;
        this.pool = Executors.newFixedThreadPool(workers, daemonThreads("sync-worker-"));
    }

    public void execute(SyncPlan plan, TargetStore target, SyncReport report) {
        applyAll(plan.upserts(), target, report);
        applyAll(plan.deletes(), target, report);
    }

    @Override
    public void close() {
        pool.shutdown();
    }

    private void applyAll(List<SyncOperation> ops, TargetStore target, SyncReport report) {
        List<Callable<Void>> tasks = ops.stream().map(op -> task(op, target, report)).toList();
        try {
            List<Future<Void>> results = pool.invokeAll(tasks);
            for (int i = 0; i < results.size(); i++) {
                try {
                    results.get(i).get();
                } catch (ExecutionException e) {
                    SyncOperation op = ops.get(i);
                    report.recordFailed();
                    log.error("Failed to {} {}: {}", op.kind(), op.targetName(), e.getCause().getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while applying sync operations");
        }
    }

    private Callable<Void> task(SyncOperation op, TargetStore target, SyncReport report) {
        return () -> {
            limiter.acquire(PERMIT_TIMEOUT);
            apply(op, target);
            recordSuccess(op, report);
            return null;
        };
    }

    private void apply(SyncOperation op, TargetStore target) throws InterruptedException {
        switch (op.kind()) {
            case CREATE -> {
                SecretValue secret = vault.getSecret(op.sourceId());
                withThrottlingRetries(op, () -> target.createSecret(op.targetName(), secret.value(), secret.version()));
            }
            case UPDATE -> {
                SecretValue secret = vault.getSecret(op.sourceId());
                withThrottlingRetries(op, () -> target.updateSecret(op.targetName(), secret.value(), secret.version()));
            }
            case DELETE -> withThrottlingRetries(op, () -> target.deleteSecret(op.targetName()));
        }
    }

    private void withThrottlingRetries(SyncOperation op, Runnable call) throws InterruptedException {
        for (int retry = 0; ; retry++) {
            try {
                call.run();
                return;
            } catch (ThrottlingException e) {
                if (retry == MAX_THROTTLING_RETRIES) {
                    throw e;
                }
                Duration backoff = INITIAL_BACKOFF.multipliedBy(1L << retry);
                log.warn("Throttled on {} {}, retrying in {} ms", op.kind(), op.targetName(), backoff.toMillis());
                sleeper.sleep(backoff);
            }
        }
    }

    private static void recordSuccess(SyncOperation op, SyncReport report) {
        switch (op.kind()) {
            case CREATE -> report.recordCreated();
            case UPDATE -> report.recordUpdated();
            case DELETE -> report.recordDeleted();
        }
    }

    private static ThreadFactory daemonThreads(String namePrefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
