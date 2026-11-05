package io.agentteams.credentialproxy;

import java.util.Objects;
import java.util.UUID;

/** Identity recovered from the model-proxy credential hash, never from request metadata. */
public record AuthenticatedWorker(UUID agentId, String tenantId, String projectId, String teamId) {
    public AuthenticatedWorker {
        Objects.requireNonNull(agentId, "agentId");
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        requireText(teamId, "teamId");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
