package com.acme.secrets.client.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(200);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(1);
    private static final int ATTEMPTS = 5;

    private final RetryPolicy policy = new RetryPolicy(MAX_RETRIES, INITIAL_BACKOFF, MAX_BACKOFF);

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503, 504})
    void isRetryable_serverErrorOrTooManyRequests_returnsTrue(int status) {
        boolean retryable = policy.isRetryable(status);

        assertThat(retryable).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404})
    void isRetryable_clientError_returnsFalse(int status) {
        boolean retryable = policy.isRetryable(status);

        assertThat(retryable).isFalse();
    }

    @Test
    void backoff_increasingAttempts_doublesUntilCapped() {
        List<Duration> backoffs = backoffsOfFirstAttempts(ATTEMPTS);

        assertThat(backoffs).containsExactly(
                INITIAL_BACKOFF,
                INITIAL_BACKOFF.multipliedBy(2),
                INITIAL_BACKOFF.multipliedBy(4),
                MAX_BACKOFF,
                MAX_BACKOFF);
    }

    private List<Duration> backoffsOfFirstAttempts(int attempts) {
        return IntStream.range(0, attempts).mapToObj(policy::backoff).toList();
    }
}
