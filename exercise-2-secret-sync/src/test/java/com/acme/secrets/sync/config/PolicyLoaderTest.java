package com.acme.secrets.sync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyLoaderTest {

    private static final String POLICIES_YAML = """
            policies:
              - name: payments-app
                sourcePattern: "prod/payments/*"
                targetId: aws-payments-app
                deleteOrphans: true
              - name: orders-app
                sourcePattern: "prod/orders/**"
                targetId: aws-orders-app
                deleteOrphans: false
            """;
    private static final SyncPolicy PAYMENTS_POLICY =
            new SyncPolicy("payments-app", "prod/payments/*", "aws-payments-app", true);
    private static final SyncPolicy ORDERS_POLICY =
            new SyncPolicy("orders-app", "prod/orders/**", "aws-orders-app", false);

    private final PolicyLoader loader = new PolicyLoader();

    @Test
    void load_validYaml_returnsPolicies() {
        InputStream yaml = utf8Stream(POLICIES_YAML);

        List<SyncPolicy> policies = loader.load(yaml);

        assertThat(policies).containsExactly(PAYMENTS_POLICY, ORDERS_POLICY);
    }

    private static InputStream utf8Stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
