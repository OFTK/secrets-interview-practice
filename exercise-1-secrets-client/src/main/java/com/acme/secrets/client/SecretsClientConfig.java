package com.acme.secrets.client;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable settings of a {@link SecretsClient}. Only {@code baseUri} and {@code apiKey} are required.
 */
public final class SecretsClientConfig {

    private final URI baseUri;
    private final String apiKey;
    private final Duration cacheTtl;
    private final int maxRetries;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration requestTimeout;
    private final Duration tokenRefreshSkew;
    private final int parallelism;
    private final Clock clock;
    private final Sleeper sleeper;

    private SecretsClientConfig(Builder builder) {
        this.baseUri = builder.baseUri;
        this.apiKey = builder.apiKey;
        this.cacheTtl = builder.cacheTtl;
        this.maxRetries = builder.maxRetries;
        this.initialBackoff = builder.initialBackoff;
        this.maxBackoff = builder.maxBackoff;
        this.requestTimeout = builder.requestTimeout;
        this.tokenRefreshSkew = builder.tokenRefreshSkew;
        this.parallelism = builder.parallelism;
        this.clock = builder.clock;
        this.sleeper = builder.sleeper;
    }

    public static Builder builder() {
        return new Builder();
    }

    public URI baseUri() {
        return baseUri;
    }

    public String apiKey() {
        return apiKey;
    }

    public Duration cacheTtl() {
        return cacheTtl;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public Duration initialBackoff() {
        return initialBackoff;
    }

    public Duration maxBackoff() {
        return maxBackoff;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    /** How long before its expiry an access token is replaced. */
    public Duration tokenRefreshSkew() {
        return tokenRefreshSkew;
    }

    /** Number of threads that {@link SecretsClient#getSecrets} uses. */
    public int parallelism() {
        return parallelism;
    }

    public Clock clock() {
        return clock;
    }

    public Sleeper sleeper() {
        return sleeper;
    }

    public static final class Builder {

        private URI baseUri;
        private String apiKey;
        private Duration cacheTtl = Duration.ofMinutes(5);
        private int maxRetries = 3;
        private Duration initialBackoff = Duration.ofMillis(200);
        private Duration maxBackoff = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration tokenRefreshSkew = Duration.ofSeconds(30);
        private int parallelism = 8;
        private Clock clock = Clock.systemUTC();
        private Sleeper sleeper = Sleeper.real();

        private Builder() {
        }

        public Builder baseUri(URI baseUri) {
            this.baseUri = baseUri;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder cacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder tokenRefreshSkew(Duration tokenRefreshSkew) {
            this.tokenRefreshSkew = tokenRefreshSkew;
            return this;
        }

        public Builder parallelism(int parallelism) {
            this.parallelism = parallelism;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder sleeper(Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        public SecretsClientConfig build() {
            Objects.requireNonNull(baseUri, "baseUri is required");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey is required");
            }
            requirePositive(cacheTtl, "cacheTtl");
            requirePositive(initialBackoff, "initialBackoff");
            requirePositive(maxBackoff, "maxBackoff");
            requirePositive(requestTimeout, "requestTimeout");
            Objects.requireNonNull(tokenRefreshSkew, "tokenRefreshSkew is required");
            if (tokenRefreshSkew.isNegative()) {
                throw new IllegalArgumentException("tokenRefreshSkew must not be negative");
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must not be negative");
            }
            if (parallelism < 1) {
                throw new IllegalArgumentException("parallelism must be at least 1");
            }
            Objects.requireNonNull(clock, "clock is required");
            Objects.requireNonNull(sleeper, "sleeper is required");
            return new SecretsClientConfig(this);
        }

        private static void requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name + " is required");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
