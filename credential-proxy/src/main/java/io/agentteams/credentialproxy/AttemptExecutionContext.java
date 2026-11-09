package io.agentteams.credentialproxy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable authorization context resolved from the authenticated Agent and database lease. */
public record AttemptExecutionContext(
        int version,
        String tenantId,
        String projectId,
        String teamId,
        UUID agentId,
        UUID taskId,
        UUID attemptId,
        UUID leaseId,
        String modelCredentialRef,
        Instant issuedAt,
        Instant expiresAt) {

    public AttemptExecutionContext {
        if (version != 1) throw new IllegalArgumentException("unsupported execution context version");
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        requireText(teamId, "teamId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(leaseId, "leaseId");
        requireText(modelCredentialRef, "modelCredentialRef");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("context expiry must follow issuance");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    public boolean sameExecutionAs(AttemptExecutionContext other) {
        return other != null
                && version == other.version
                && tenantId.equals(other.tenantId)
                && projectId.equals(other.projectId)
                && teamId.equals(other.teamId)
                && agentId.equals(other.agentId)
                && taskId.equals(other.taskId)
                && attemptId.equals(other.attemptId)
                && leaseId.equals(other.leaseId)
                && modelCredentialRef.equals(other.modelCredentialRef);
    }
}
