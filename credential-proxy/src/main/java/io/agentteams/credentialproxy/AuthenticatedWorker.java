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
        String modelCredentialRef,
        UUID attemptId,
        UUID leaseId) {
    public AuthenticatedWorker {
        Objects.requireNonNull(proxyCredentialId, "proxyCredentialId");
        Objects.requireNonNull(agentId, "agentId");
        requireText(modelCredentialRef, "modelCredentialRef");
        boolean allTenantFields = tenantId != null && projectId != null && teamId != null
                && !tenantId.isBlank() && !projectId.isBlank() && !teamId.isBlank();
        boolean noTenantFields = tenantId == null && projectId == null && teamId == null;
        if (!allTenantFields && !noTenantFields) {
            throw new IllegalArgumentException("tenant scope must be complete or absent");
        }
        if ((attemptId == null) != (leaseId == null)) {
            throw new IllegalArgumentException("attempt and lease scope must be provided together");
        }
        if (attemptId == null && !allTenantFields) {
            throw new IllegalArgumentException("tenant-bound credential scope is required");
        }
    }

    /** Existing enrollment credentials stay bound to one tenant/project/team scope. */
    public AuthenticatedWorker(UUID proxyCredentialId, UUID agentId, String tenantId, String projectId,
            String teamId, String modelCredentialRef) {
        this(proxyCredentialId, agentId, tenantId, projectId, teamId, modelCredentialRef, null, null);
    }

    public boolean isAttemptBound() {
        return attemptId != null;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
