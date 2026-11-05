package io.agentteams.credentialproxy;

import java.util.List;
import java.util.UUID;

/** Reads active Assignment/Attempt/Lease rows for an authenticated Worker identity. */
public interface ActiveAttemptRepository {
    List<ActiveAttemptCandidate> findActiveAttempts(UUID authenticatedAgentId);
}
