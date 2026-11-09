package io.agentteams.credentialproxy;

import java.util.List;

/** Reads active Assignment/Attempt/Lease rows for an authenticated Worker identity. */
public interface ActiveAttemptRepository {
    List<ActiveAttemptCandidate> findActiveAttempts(AuthenticatedWorker worker);
}
