package com.acme.secrets.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretTest {

    private static final String SECRET_ID = "prod/orders/db-password";
    private static final String SECRET_VALUE = "Ord3rs-Pa55word";
    private static final int VERSION = 3;

    @Test
    void toString_secretRecord_doesNotContainValue() {
        Secret secret = new Secret(SECRET_ID, SECRET_VALUE, VERSION);

        String text = secret.toString();

        assertThat(text).doesNotContain(SECRET_VALUE);
    }
}
