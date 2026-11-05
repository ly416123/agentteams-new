package io.agentteams.credentialproxy;

import java.util.Objects;
import java.util.UUID;

/** Identity recovered from the model-proxy credential hash, never from request metadata. */
public record AuthenticatedWorker(
        UUID proxyCredentialId,
        UUID agentId,
        String tenantId,
        String projectId,
        String teamId,
        String modelCredentialRef) {
    public AuthenticatedWorker {
        Objects.requireNonNull(proxyCredentialId, "proxyCredentialId");
        Objects.requireNonNull(agentId, "agentId");
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        requireText(teamId, "teamId");
        requireText(modelCredentialRef, "modelCredentialRef");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
