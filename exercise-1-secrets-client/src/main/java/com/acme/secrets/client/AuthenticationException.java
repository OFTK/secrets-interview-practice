package com.acme.secrets.client;

/**
 * The API key or the access token was rejected, or the caller is not allowed to read the secret.
 */
public class AuthenticationException extends SecretsClientException {

    public AuthenticationException(String message) {
        super(message);
    }
}
