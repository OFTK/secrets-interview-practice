package com.acme.secrets.sync.target;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TargetNameMapperTest {

    private static final String SOURCE_ID_WITH_INVALID_CHARACTERS = "prod/payments/stripe webhook#secret:v2";
    private static final String MAPPED_TARGET_NAME = "prod/payments/stripe_webhook_secret_v2";

    @Test
    void toTargetName_invalidCharacters_replacedWithUnderscore() {
        String targetName = TargetNameMapper.toTargetName(SOURCE_ID_WITH_INVALID_CHARACTERS);

        assertThat(targetName).isEqualTo(MAPPED_TARGET_NAME);
    }
}
