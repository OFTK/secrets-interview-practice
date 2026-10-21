package com.acme.secrets.client.fake;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * In-process stand-in for the Secrets Manager REST API, for tests and demos. Token expiry follows the given clock;
 * the token burst limit (more than 5 token requests per second are answered with 429) uses real time.
 */
public final class FakeSecretsServer implements AutoCloseable {

    public static final String API_KEY = "local-dev-api-key";

    private static final Logger log = LoggerFactory.getLogger(FakeSecretsServer.class);
    private static final String TOKEN_PATH = "/v1/auth/token";
    private static final String SECRETS_PATH = "/v1/secrets/";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int WORKER_THREADS = 16;

    private final HttpServer httpServer;
    private final ExecutorService workers;
    private final URI baseUri;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final BurstLimiter tokenBurstLimiter = new BurstLimiter(5, Duration.ofSeconds(1));
    private final Map<String, StoredSecret> secrets = new ConcurrentHashMap<>();
    private final Map<String, Instant> tokenExpiries = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> secretRequestCounts = new ConcurrentHashMap<>();
    private final AtomicInteger totalSecretRequests = new AtomicInteger();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private volatile Duration tokenTtl = Duration.ofMinutes(15);
    private volatile Duration tokenLatency = Duration.ZERO;
    private int pendingFailures;
    private int pendingFailureStatus;

    private FakeSecretsServer(HttpServer httpServer, URI baseUri, Clock clock) {
        this.httpServer = httpServer;
        this.baseUri = baseUri;
        this.clock = clock;
        this.workers = Executors.newFixedThreadPool(WORKER_THREADS, daemonThreads());
        httpServer.setExecutor(workers);
        httpServer.createContext(TOKEN_PATH, exchange -> serve(exchange, this::issueToken));
        httpServer.createContext(SECRETS_PATH, exchange -> serve(exchange, this::readSecret));
        httpServer.start();
    }

    public static FakeSecretsServer start() {
        return start(Clock.systemUTC());
    }

    public static FakeSecretsServer start(Clock clock) {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
            int port = httpServer.getAddress().getPort();
            URI baseUri = new URI("http", null, loopback.getHostAddress(), port, "/", null, null);
            return new FakeSecretsServer(httpServer, baseUri, clock);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the fake secrets server", e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public URI baseUri() {
        return baseUri;
    }

    /** Creates the secret with version 1, or rotates it to the next version. */
    public void putSecret(String secretId, String value) {
        secrets.compute(secretId, (id, current) ->
                new StoredSecret(value, current == null ? 1 : current.version() + 1));
    }

    public void revokeAllTokens() {
        tokenExpiries.clear();
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    /** Delays every token response by {@code latency} of real time. */
    public void setTokenLatency(Duration latency) {
        this.tokenLatency = latency;
    }

    /** Answers the next {@code count} secret requests that carry a valid token with {@code status}. */
    public synchronized void failNextSecretRequests(int count, int status) {
        pendingFailures = count;
        pendingFailureStatus = status;
    }

    public int tokenRequestCount() {
        return tokenRequests.get();
    }

    public int secretRequestCount(String secretId) {
        AtomicInteger count = secretRequestCounts.get(secretId);
        return count == null ? 0 : count.get();
    }

    public int totalSecretRequestCount() {
        return totalSecretRequests.get();
    }

    @Override
    public void close() {
        httpServer.stop(0);
        workers.shutdownNow();
    }

    private Response issueToken(HttpExchange exchange) {
        tokenRequests.incrementAndGet();
        if (!API_KEY.equals(exchange.getRequestHeaders().getFirst("X-Api-Key"))) {
            return Response.error(401, "unauthorized");
        }
        simulateLatency();
        if (!tokenBurstLimiter.tryAcquire()) {
            return Response.error(429, "rate_limited");
        }
        Duration ttl = tokenTtl;
        String token = newTokenValue();
        tokenExpiries.put(token, clock.instant().plus(ttl));
        return new Response(200, new TokenBody(token, ttl.toSeconds()));
    }

    private Response readSecret(HttpExchange exchange) {
        String rawId = exchange.getRequestURI().getRawPath().substring(SECRETS_PATH.length());
        String secretId = URLDecoder.decode(rawId, StandardCharsets.UTF_8);
        totalSecretRequests.incrementAndGet();
        secretRequestCounts.computeIfAbsent(secretId, id -> new AtomicInteger()).incrementAndGet();
        if (!hasValidToken(exchange)) {
            return Response.error(401, "unauthorized");
        }
        OptionalInt injectedStatus = takeInjectedFailure();
        if (injectedStatus.isPresent()) {
            return Response.error(injectedStatus.getAsInt(), "injected_failure");
        }
        StoredSecret secret = secrets.get(secretId);
        if (secret == null) {
            return Response.error(404, "not_found");
        }
        return new Response(200, new SecretBody(secretId, secret.value(), secret.version()));
    }

    private boolean hasValidToken(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        Instant expiresAt = tokenExpiries.get(authorization.substring(BEARER_PREFIX.length()));
        return expiresAt != null && clock.instant().isBefore(expiresAt);
    }

    private synchronized OptionalInt takeInjectedFailure() {
        if (pendingFailures == 0) {
            return OptionalInt.empty();
        }
        pendingFailures--;
        return OptionalInt.of(pendingFailureStatus);
    }

    private void simulateLatency() {
        try {
            Thread.sleep(tokenLatency.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String newTokenValue() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void serve(HttpExchange exchange, Function<HttpExchange, Response> handler) throws IOException {
        Response response;
        try {
            response = handler.apply(exchange);
        } catch (RuntimeException e) {
            log.error("Failed to handle {} {}", exchange.getRequestMethod(), exchange.getRequestURI(), e);
            response = Response.error(500, "internal_error");
        }
        byte[] body = json.writeValueAsBytes(response.body());
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        log.info("{} {} -> {}", exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), response.status());
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "fake-server-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record StoredSecret(String value, int version) {
    }

    private record Response(int status, Object body) {

        static Response error(int status, String error) {
            return new Response(status, new ErrorBody(error));
        }
    }

    private record TokenBody(@JsonProperty("access_token") String accessToken,
                             @JsonProperty("expires_in") long expiresIn) {
    }

    private record SecretBody(String id, String value, int version) {
    }

    private record ErrorBody(String error) {
    }
}
