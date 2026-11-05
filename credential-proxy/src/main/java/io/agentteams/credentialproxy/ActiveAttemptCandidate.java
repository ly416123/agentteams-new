package io.agentteams.credentialproxy;

import java.time.Instant;
import java.util.UUID;

/** A database-resolved candidate; callers must not populate this from request fields. */
public record ActiveAttemptCandidate(
        UUID agentId,
        String tenantId,
        String projectId,
        String teamId,
        UUID taskId,
        UUID attemptId,
        UUID leaseId,
        Instant leaseExpiresAt,
        boolean leaseActive,
        boolean attemptActive,
        boolean assignmentActive) { }
