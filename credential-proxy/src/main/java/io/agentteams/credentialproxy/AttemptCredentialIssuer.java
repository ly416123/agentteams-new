package io.agentteams.credentialproxy;

import java.util.UUID;

/** Issues one short-lived model capability for the authenticated Worker's active Attempt. */
@FunctionalInterface
public interface AttemptCredentialIssuer {
    IssuedAttemptCredential issue(String workerSessionToken, UUID taskId, UUID attemptId, UUID leaseId);
}
