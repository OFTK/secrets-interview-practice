package com.acme.secrets.client.auth;

import com.acme.secrets.client.SecretsClientConfig;
import com.acme.secrets.client.fake.FakeSecretsServer;
import com.acme.secrets.client.testing.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class TokenProviderConcurrencyTest {

    private static final Instant START = Instant.parse("2026-03-02T09:00:00Z");
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final Duration TOKEN_LATENCY = Duration.ofMillis(200);
    private static final int CALLERS = 16;
    private static final int TOKEN_REQUESTS_BEFORE_EXPIRY = 1;

    private final MutableClock clock = new MutableClock(START);
    private FakeSecretsServer server;
    private TokenProvider tokenProvider;

    @BeforeEach
    void startServer() {
        server = FakeSecretsServer.start(clock);
        server.setTokenTtl(TOKEN_TTL);
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        tokenProvider = new TokenProvider(httpClient, SecretsClientConfig.builder()
                .baseUri(server.baseUri())
                .apiKey(FakeSecretsServer.API_KEY)
                .clock(clock)
                .build());
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    @Timeout(10)
    void getToken_concurrentCallersOnExpiredToken_fetchOneToken() throws InterruptedException {
        tokenProvider.getToken();
        clock.advance(TOKEN_TTL);
        server.setTokenLatency(TOKEN_LATENCY);

        List<Throwable> failures = callAllAtOnce(CALLERS, tokenProvider::getToken);

        assertThat(failures).isEmpty();
        assertThat(server.tokenRequestCount()).isEqualTo(TOKEN_REQUESTS_BEFORE_EXPIRY + 1);
    }

    private static List<Throwable> callAllAtOnce(int callers, Runnable call) throws InterruptedException {
        ExecutorService threads = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                calls.add(threads.submit(() -> {
                    ready.countDown();
                    go.await();
                    call.run();
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            return failuresOf(calls);
        } finally {
            threads.shutdownNow();
        }
    }

    private static List<Throwable> failuresOf(List<Future<?>> calls) throws InterruptedException {
        List<Throwable> failures = new ArrayList<>();
        for (Future<?> call : calls) {
            try {
                call.get();
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        return failures;
    }
}
