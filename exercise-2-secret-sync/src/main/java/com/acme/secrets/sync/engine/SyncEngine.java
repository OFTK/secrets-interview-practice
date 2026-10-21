package com.acme.secrets.sync.engine;

import com.acme.secrets.sync.config.SyncPolicy;
import com.acme.secrets.sync.engine.SyncOperation.Kind;
import com.acme.secrets.sync.source.SecretRef;
import com.acme.secrets.sync.target.TargetStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs sync cycles: scan the vault, diff against the target store, apply the plan. */
public final class SyncEngine {

    private static final Logger log = LoggerFactory.getLogger(SyncEngine.class);

    private final SourceScanner scanner;
    private final DiffCalculator diff;
    private final SyncExecutor executor;
    private final Map<String, TargetStore> targets;

    public SyncEngine(SourceScanner scanner, DiffCalculator diff, SyncExecutor executor,
                      Map<String, TargetStore> targets) {
        this.scanner = scanner;
        this.diff = diff;
        this.executor = executor;
        this.targets = Map.copyOf(targets);
    }

    public SyncReport runCycle(SyncPolicy policy) {
        TargetStore target = targetFor(policy);
        List<SecretRef> sources = scanner.scan(policy);
        SyncPlan plan = diff.plan(policy, sources, target.listManagedSecrets());
        log.info("Plan for policy '{}': {} creates, {} updates, {} deletes",
                policy.name(), plan.count(Kind.CREATE), plan.count(Kind.UPDATE), plan.count(Kind.DELETE));
        SyncReport report = new SyncReport(policy.name());
        executor.execute(plan, target, report);
        log.info("Sync cycle finished: {}", report);
        return report;
    }

    /** Runs one cycle per policy. A failed cycle is reported as aborted and the remaining policies still run. */
    public List<SyncReport> runAll(List<SyncPolicy> policies) {
        List<SyncReport> reports = new ArrayList<>();
        for (SyncPolicy policy : policies) {
            try {
                reports.add(runCycle(policy));
            } catch (RuntimeException e) {
                log.error("Sync cycle for policy '{}' aborted: {}", policy.name(), e.getMessage());
                reports.add(SyncReport.aborted(policy.name(), e.getMessage()));
            }
        }
        return reports;
    }

    private TargetStore targetFor(SyncPolicy policy) {
        TargetStore target = targets.get(policy.targetId());
        if (target == null) {
            throw new IllegalArgumentException(
                    "Policy '" + policy.name() + "' refers to unknown target '" + policy.targetId() + "'");
        }
        return target;
    }
}
