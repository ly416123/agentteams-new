package io.agentteams.credentialproxy;

import java.time.Instant;
import java.util.Objects;

public record IssuedAttemptCapability(String token, Instant expiresAt) {
    public IssuedAttemptCapability {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token is required");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
