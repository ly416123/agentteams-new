package io.agentteams.enrollment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ModelProxyCredentialRecord(
        UUID id,
        UUID sessionId,
        UUID agentId,
        WorkerScope scope,
        String tokenSha256,
        String modelCredentialRef,
        Instant expiresAt,
        Instant createdAt) {
    public ModelProxyCredentialRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(tokenSha256, "tokenSha256");
        Objects.requireNonNull(modelCredentialRef, "modelCredentialRef");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
