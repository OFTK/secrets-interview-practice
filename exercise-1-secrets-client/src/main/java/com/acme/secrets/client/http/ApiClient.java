package com.acme.secrets.client.http;

import com.acme.secrets.client.AuthenticationException;
import com.acme.secrets.client.Secret;
import com.acme.secrets.client.SecretNotFoundException;
import com.acme.secrets.client.SecretsClientConfig;
import com.acme.secrets.client.SecretsClientException;
import com.acme.secrets.client.Sleeper;
import com.acme.secrets.client.auth.TokenProvider;
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
import java.time.Duration;

/**
 * Calls the secrets endpoint of the REST API.
 */
public final class ApiClient {

    private static final Logger log = LoggerFactory.getLogger(ApiClient.class);
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final int NO_RESPONSE = -1;

    private final HttpClient httpClient;
    private final TokenProvider tokenProvider;
    private final RetryPolicy retryPolicy;
    private final URI baseUri;
    private final Duration requestTimeout;
    private final Sleeper sleeper;

    public ApiClient(HttpClient httpClient, TokenProvider tokenProvider, SecretsClientConfig config) {
        this.httpClient = httpClient;
        this.tokenProvider = tokenProvider;
        this.retryPolicy = new RetryPolicy(config.maxRetries(), config.initialBackoff(), config.maxBackoff());
        this.baseUri = config.baseUri();
        this.requestTimeout = config.requestTimeout();
        this.sleeper = config.sleeper();
    }

    /**
     * Reads the current version of a secret. Failed requests are retried as the {@link RetryPolicy} allows,
     * and an access token that the server rejects is replaced once.
     */
    public Secret fetchSecret(String secretId) {
        URI uri = SecretPaths.secretUri(baseUri, secretId);
        HttpRequest request = authorizedGet(uri);
        boolean tokenRefreshed = false;
        int retries = 0;
        while (true) {
            HttpResponse<String> response = send(request);
            int status = response == null ? NO_RESPONSE : response.statusCode();
            if (status == 200) {
                return parseSecret(response.body());
            }
            if (status == 401 && !tokenRefreshed) {
                tokenProvider.invalidate();
                tokenRefreshed = true;
                continue;
            }
            if ((status == NO_RESPONSE || retryPolicy.isRetryable(status)) && retries < retryPolicy.maxRetries()) {
                Duration backoff = retryPolicy.backoff(retries++);
                log.warn("Request for secret {} failed with HTTP {}, retry {}/{} in {} ms",
                        secretId, status, retries, retryPolicy.maxRetries(), backoff.toMillis());
                pause(backoff);
                continue;
            }
            throw errorFor(status, secretId);
        }
    }

    private HttpRequest authorizedGet(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + tokenProvider.getToken().value())
                .GET()
                .build();
    }

    /** Returns {@code null} when the request failed with an I/O error. */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("I/O error calling {}: {}", request.uri(), e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretsClientException("Interrupted while fetching a secret", e);
        }
    }

    private void pause(Duration backoff) {
        try {
            sleeper.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretsClientException("Interrupted while waiting to retry", e);
        }
    }

    private static Secret parseSecret(String body) {
        try {
            return JSON.readValue(body, Secret.class);
        } catch (JsonProcessingException e) {
            throw new SecretsClientException("Malformed secret response", e);
        }
    }

    private static SecretsClientException errorFor(int status, String secretId) {
        return switch (status) {
            case 404 -> new SecretNotFoundException(secretId);
            case 401, 403 -> new AuthenticationException(
                    "Not authorized to read secret " + secretId + ": HTTP " + status);
            case NO_RESPONSE -> new SecretsClientException("I/O error while fetching secret " + secretId);
            default -> new SecretsClientException("GET secret " + secretId + " failed: HTTP " + status);
        };
    }
}
