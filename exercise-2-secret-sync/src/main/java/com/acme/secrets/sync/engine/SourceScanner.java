package com.acme.secrets.sync.engine;

import com.acme.secrets.sync.config.GlobMatcher;
import com.acme.secrets.sync.config.SyncPolicy;
import com.acme.secrets.sync.source.Page;
import com.acme.secrets.sync.source.SecretRef;
import com.acme.secrets.sync.source.VaultClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Lists the vault secrets that a sync policy selects. */
public final class SourceScanner {

    private static final Logger log = LoggerFactory.getLogger(SourceScanner.class);

    private final VaultClient vault;
    private final int pageSize;

    public SourceScanner(VaultClient vault, int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive, was " + pageSize);
        }
        this.vault = vault;
        this.pageSize = pageSize;
    }

    public List<SecretRef> scan(SyncPolicy policy) {
        Predicate<String> matches = GlobMatcher.compile(policy.sourcePattern());
        List<SecretRef> all = listAll();
        List<SecretRef> matching = all.stream().filter(ref -> matches.test(ref.id())).toList();
        log.info("Scanned {} vault secrets, {} match policy '{}'", all.size(), matching.size(), policy.name());
        return matching;
    }

    private List<SecretRef> listAll() {
        Page<SecretRef> first = vault.listSecrets(0, pageSize);
        List<SecretRef> all = new ArrayList<>(first.items());
        int pageCount = (first.total() + pageSize - 1) / pageSize;
        for (int page = 1; page < pageCount; page++) {
            all.addAll(vault.listSecrets(page * pageSize, pageSize).items());
        }
        return all;
    }
}
