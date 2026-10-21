package com.acme.secrets.client;

public class SecretNotFoundException extends SecretsClientException {

    private final String secretId;

    public SecretNotFoundException(String secretId) {
        super("Secret not found: " + secretId);
        this.secretId = secretId;
    }

    public String secretId() {
        return secretId;
    }
}
