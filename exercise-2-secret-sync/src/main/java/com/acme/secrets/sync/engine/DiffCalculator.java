package com.acme.secrets.sync.engine;

import com.acme.secrets.sync.config.SyncPolicy;
import com.acme.secrets.sync.source.SecretRef;
import com.acme.secrets.sync.target.TargetNameMapper;
import com.acme.secrets.sync.target.TargetSecret;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Works out which operations make a target store mirror the vault secrets a policy selects. */
public final class DiffCalculator {

    public SyncPlan plan(SyncPolicy policy, List<SecretRef> sources, List<TargetSecret> targets) {
        Map<String, TargetSecret> remaining = new HashMap<>();
        targets.forEach(target -> remaining.put(target.name(), target));
        List<SyncOperation> operations = new ArrayList<>();
        for (SecretRef source : sources) {
            String targetName = TargetNameMapper.toTargetName(source.id());
            TargetSecret existing = remaining.remove(source.id());
            if (existing == null) {
                operations.add(SyncOperation.create(source.id(), targetName));
            } else if (existing.sourceVersion() != source.version()) {
                operations.add(SyncOperation.update(source.id(), targetName));
            }
        }
        if (policy.deleteOrphans()) {
            remaining.keySet().forEach(name -> operations.add(SyncOperation.delete(name)));
        }
        return new SyncPlan(operations);
    }
}
