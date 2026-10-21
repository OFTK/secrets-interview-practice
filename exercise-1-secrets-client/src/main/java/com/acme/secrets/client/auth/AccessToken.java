package com.acme.secrets.client.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Short-lived bearer token for the secrets API.
 */
public record AccessToken(String value, Instant expiresAt) {

    /** True when the token has expired or expires within {@code skew} from now. */
    public boolean expiresWithin(Duration skew, Clock clock) {
        return !clock.instant().plus(skew).isBefore(expiresAt);
    }

    @Override
    public String toString() {
        return "AccessToken[value=****, expiresAt=" + expiresAt + "]";
    }
}
