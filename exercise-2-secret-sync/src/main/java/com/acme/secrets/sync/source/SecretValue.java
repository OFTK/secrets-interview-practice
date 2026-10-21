package com.acme.secrets.sync.source;

/** The value of one version of a vault secret. {@link #toString()} masks the value. */
public record SecretValue(String id, int version, String value) {

    @Override
    public String toString() {
        return "SecretValue[id=" + id + ", version=" + version + ", value=****]";
    }
}
