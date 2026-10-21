package com.acme.secrets.sync.config;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Reads the sync policies that tenant admins upload as YAML from the web console. */
public final class PolicyLoader {

    public List<SyncPolicy> load(InputStream yaml) {
        Map<String, Object> root = new Yaml().load(yaml);
        if (root == null || !(root.get("policies") instanceof List<?> entries)) {
            throw new IllegalArgumentException("Policy document must contain a 'policies' list");
        }
        return entries.stream().map(PolicyLoader::toPolicy).toList();
    }

    private static SyncPolicy toPolicy(Object entry) {
        if (!(entry instanceof Map<?, ?> fields)) {
            throw new IllegalArgumentException("Each entry under 'policies' must be a mapping");
        }
        return new SyncPolicy(
                text(fields, "name"),
                text(fields, "sourcePattern"),
                text(fields, "targetId"),
                Boolean.TRUE.equals(fields.get("deleteOrphans")));
    }

    private static String text(Map<?, ?> fields, String key) {
        Object value = fields.get(key);
        return value == null ? null : value.toString();
    }
}
