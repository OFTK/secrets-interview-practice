package com.acme.secrets.client.http;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class SecretPathsTest {

    private static final URI BASE_URI = URI.create("https://secrets.example.com/");
    private static final String ID_WITH_RESERVED_CHARACTERS = "payments/stripe#live key";
    private static final String EXPECTED_URI = "https://secrets.example.com/v1/secrets/payments%2Fstripe%23live%20key";

    @Test
    void secretUri_idWithReservedCharacters_encodesItAsOnePathSegment() {
        URI uri = SecretPaths.secretUri(BASE_URI, ID_WITH_RESERVED_CHARACTERS);

        assertThat(uri).hasToString(EXPECTED_URI);
    }
}
