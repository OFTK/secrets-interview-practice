package com.acme.secrets.client;

/**
 * One version of a secret, as returned by Secrets Manager.
 */
public record Secret(String id, String value, int version) {

    @Override
    public String toString() {
        return "Secret[id=" + id + ", version=" + version + ", value=****]";
    }
}
