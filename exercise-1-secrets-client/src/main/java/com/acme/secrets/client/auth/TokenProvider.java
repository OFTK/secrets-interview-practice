package com.acme.secrets.client.auth;

import com.acme.secrets.client.AuthenticationException;
import com.acme.secrets.client.SecretsClientConfig;
import com.acme.secrets.client.SecretsClientException;
import com.acme.secrets.client.http.SecretPaths;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

/**
 * Exchanges the API key for short-lived access tokens and keeps the current token in memory.
 * A token is replaced once it is within the configured refresh skew of its expiry.
 */
public final class TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(TokenProvider.class);
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final HttpClient httpClient;
    private final URI tokenUri;
    private final String apiKey;
    private final Duration requestTimeout;
    private final Duration refreshSkew;
    private final Clock clock;
    private volatile AccessToken token;

    public TokenProvider(HttpClient httpClient, SecretsClientConfig config) {
        this.httpClient = httpClient;
        this.tokenUri = SecretPaths.tokenUri(config.baseUri());
        this.apiKey = config.apiKey();
        this.requestTimeout = config.requestTimeout();
        this.refreshSkew = config.tokenRefreshSkew();
        this.clock = config.clock();
    }

    public AccessToken getToken() {
        AccessToken current = token;
        if (!isFresh(current)) {
            current = fetchToken();
            token = current;
        }
        return current;
    }

    /** Forgets the current token, so the next {@link #getToken()} requests a new one. */
    public void invalidate() {
        token = null;
    }

    private boolean isFresh(AccessToken candidate) {
        return candidate != null && !candidate.expiresWithin(refreshSkew, clock);
    }

    private AccessToken fetchToken() {
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .timeout(requestTimeout)
                .header("X-Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 401) {
            throw new AuthenticationException("API key rejected");
        }
        if (response.statusCode() != 200) {
            throw new SecretsClientException("Token request failed: HTTP " + response.statusCode());
        }
        AccessToken fetched = parseToken(response.body());
        log.info("Obtained access token, expires at {}", fetched.expiresAt());
        return fetched;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SecretsClientException("Token request failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretsClientException("Interrupted while requesting an access token", e);
        }
    }

    private AccessToken parseToken(String body) {
        try {
            TokenResponse response = JSON.readValue(body, TokenResponse.class);
            return new AccessToken(response.accessToken(), clock.instant().plusSeconds(response.expiresIn()));
        } catch (JsonProcessingException e) {
            throw new SecretsClientException("Malformed token response", e);
        }
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("expires_in") long expiresIn) {
    }
}
