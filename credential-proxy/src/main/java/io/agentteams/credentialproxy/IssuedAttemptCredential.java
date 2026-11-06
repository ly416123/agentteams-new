package io.agentteams.credentialproxy;

import java.time.Instant;
import java.util.Objects;

/** The token is returned once and is never persisted in plaintext. */
public record IssuedAttemptCredential(String token, Instant expiresAt) {
    public IssuedAttemptCredential {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
