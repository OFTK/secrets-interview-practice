package com.acme.secrets.client;

import com.acme.secrets.client.fake.FakeSecretsServer;
import com.acme.secrets.client.testing.MutableClock;
import com.acme.secrets.client.testing.RecordingSleeper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Timeout(10)
class SecretsClientTest {

    private static final Instant START = Instant.parse("2026-03-02T09:00:00Z");
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final Duration TOKEN_REFRESH_SKEW = Duration.ofSeconds(30);
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(200);
    private static final int SERVICE_UNAVAILABLE = 503;
    private static final int TRANSIENT_FAILURES = 2;

    private static final String SECRET_ID = "prod/orders/db-password";
    private static final String SECRET_VALUE = "orders-pass-1";
    private static final String ROTATED_VALUE = "orders-pass-2";
    private static final int FIRST_VERSION = 1;
    private static final String OTHER_SECRET_ID = "prod/orders/api-key";
    private static final String OTHER_SECRET_VALUE = "orders-api-key-1";
    private static final String MISSING_SECRET_ID = "prod/orders/does-not-exist";
    private static final List<String> BATCH_IDS = List.of(
            "prod/catalog/key-1", "prod/catalog/key-2", "prod/catalog/key-3",
            "prod/catalog/key-4", "prod/catalog/key-5");

    private final MutableClock clock = new MutableClock(START);
    private final RecordingSleeper sleeper = new RecordingSleeper();
    private FakeSecretsServer server;
    private SecretsClient client;

    @BeforeEach
    void startServerAndClient() {
        server = FakeSecretsServer.start(clock);
        server.setTokenTtl(TOKEN_TTL);
        client = new SecretsClient(SecretsClientConfig.builder()
                .baseUri(server.baseUri())
                .apiKey(FakeSecretsServer.API_KEY)
                .cacheTtl(CACHE_TTL)
                .initialBackoff(INITIAL_BACKOFF)
                .tokenRefreshSkew(TOKEN_REFRESH_SKEW)
                .clock(clock)
                .sleeper(sleeper)
                .build());
    }

    @AfterEach
    void stopClientAndServer() {
        client.close();
        server.close();
    }

    @Test
    void getSecret_existingSecret_returnsValueAndVersion() {
        server.putSecret(SECRET_ID, SECRET_VALUE);

        Secret secret = client.getSecret(SECRET_ID);

        assertThat(secret).extracting(Secret::value, Secret::version).containsExactly(SECRET_VALUE, FIRST_VERSION);
    }

    @Test
    void getSecret_calledTwiceWithinTtl_hitsServerOnce() {
        server.putSecret(SECRET_ID, SECRET_VALUE);
        client.getSecret(SECRET_ID);
        clock.advance(CACHE_TTL.minusSeconds(1));

        client.getSecret(SECRET_ID);

        assertThat(server.secretRequestCount(SECRET_ID)).isEqualTo(1);
    }

    @Test
    void getSecret_unknownSecret_throwsSecretNotFound() {
        Throwable thrown = catchThrowable(() -> client.getSecret(MISSING_SECRET_ID));

        assertThat(thrown)
                .isInstanceOf(SecretNotFoundException.class)
                .hasMessage("Secret not found: " + MISSING_SECRET_ID);
    }

    @Test
    void getSecret_unknownSecret_sendsSingleRequest() {
        catchThrowable(() -> client.getSecret(MISSING_SECRET_ID));

        assertThat(server.secretRequestCount(MISSING_SECRET_ID)).isEqualTo(1);
    }

    @Test
    void getSecret_twoTransient503s_retriesAndSucceeds() {
        server.putSecret(SECRET_ID, SECRET_VALUE);
        server.failNextSecretRequests(TRANSIENT_FAILURES, SERVICE_UNAVAILABLE);

        Secret secret = client.getSecret(SECRET_ID);

        assertThat(secret.value()).isEqualTo(SECRET_VALUE);
        assertThat(sleeper.sleeps()).containsExactly(INITIAL_BACKOFF, INITIAL_BACKOFF.multipliedBy(2));
    }

    @Test
    void getSecret_afterCacheTtl_returnsRotatedValue() {
        server.putSecret(SECRET_ID, SECRET_VALUE);
        client.getSecret(SECRET_ID);
        server.putSecret(SECRET_ID, ROTATED_VALUE);
        clock.advance(CACHE_TTL);

        Secret secret = client.getSecret(SECRET_ID);

        assertThat(secret.value()).isEqualTo(ROTATED_VALUE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void getSecret_blankId_throwsIllegalArgument(String secretId) {
        Throwable thrown = catchThrowable(() -> client.getSecret(secretId));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getSecret_tokenNearExpiry_requestsNewToken() {
        server.putSecret(SECRET_ID, SECRET_VALUE);
        server.putSecret(OTHER_SECRET_ID, OTHER_SECRET_VALUE);
        client.getSecret(SECRET_ID);
        clock.advance(TOKEN_TTL.minus(TOKEN_REFRESH_SKEW).plusSeconds(1));

        client.getSecret(OTHER_SECRET_ID);

        assertThat(server.tokenRequestCount()).isEqualTo(2);
        assertThat(server.totalSecretRequestCount()).as("secret requests, rejected ones included").isEqualTo(2);
    }

    @Test
    void getSecrets_severalIds_returnsAllInRequestOrder() {
        seed(BATCH_IDS);
        client.getSecret(BATCH_IDS.get(0));

        Map<String, Secret> secrets = client.getSecrets(BATCH_IDS);

        assertThat(secrets.keySet()).containsExactlyElementsOf(BATCH_IDS);
    }

    private void seed(List<String> secretIds) {
        secretIds.forEach(secretId -> server.putSecret(secretId, SECRET_VALUE));
    }
}
