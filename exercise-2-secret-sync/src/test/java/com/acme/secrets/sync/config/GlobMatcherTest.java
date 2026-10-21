package com.acme.secrets.sync.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class GlobMatcherTest {

    private static final String PAYMENTS_PATTERN = "prod/payments/*";
    private static final String ORDERS_TREE_PATTERN = "prod/orders/**";
    private static final String APP_V1_PATTERN = "prod/app.v1/*";
    private static final String PAYMENTS_DB_PASSWORD = "prod/payments/db-password";
    private static final String PAYMENTS_ADMIN_SIGNING_KEY = "prod/payments/admin/root-signing-key";
    private static final String PREPROD_DB_PASSWORD = "preprod/payments/db-password";
    private static final String ORDERS_REGIONAL_DB_PASSWORD = "prod/orders/eu-west-1/primary/db-password";
    private static final String APP_X_V1_KEY = "prod/appXv1/key";

    @Test
    void compile_singleStar_matchesDirectChild() {
        Predicate<String> matcher = GlobMatcher.compile(PAYMENTS_PATTERN);

        assertThat(matcher).as("pattern %s", PAYMENTS_PATTERN).accepts(PAYMENTS_DB_PASSWORD);
    }

    @Test
    void compile_singleStar_doesNotMatchNestedPath() {
        Predicate<String> matcher = GlobMatcher.compile(PAYMENTS_PATTERN);

        assertThat(matcher).as("pattern %s", PAYMENTS_PATTERN).rejects(PAYMENTS_ADMIN_SIGNING_KEY);
    }

    @Test
    void compile_pattern_doesNotMatchIdThatMerelyContainsIt() {
        Predicate<String> matcher = GlobMatcher.compile(PAYMENTS_PATTERN);

        assertThat(matcher).as("pattern %s", PAYMENTS_PATTERN).rejects(PREPROD_DB_PASSWORD);
    }

    @Test
    void compile_doubleStar_matchesNestedPath() {
        Predicate<String> matcher = GlobMatcher.compile(ORDERS_TREE_PATTERN);

        assertThat(matcher).as("pattern %s", ORDERS_TREE_PATTERN).accepts(ORDERS_REGIONAL_DB_PASSWORD);
    }

    @Test
    void compile_dot_isLiteral() {
        Predicate<String> matcher = GlobMatcher.compile(APP_V1_PATTERN);

        assertThat(matcher).as("pattern %s", APP_V1_PATTERN).rejects(APP_X_V1_KEY);
    }
}
