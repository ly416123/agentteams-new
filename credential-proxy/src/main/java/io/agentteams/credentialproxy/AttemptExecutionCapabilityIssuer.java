package io.agentteams.credentialproxy;

import java.util.UUID;

/** Issues a short-lived, slot-session-bound capability for one active execution Attempt. */
@FunctionalInterface
public interface AttemptExecutionCapabilityIssuer {
    IssuedAttemptCapability issue(String workerSlotSessionToken, UUID taskId, UUID assignmentId,
            UUID attemptId, UUID leaseId);
}
