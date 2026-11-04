package io.agentteams.enrollment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WorkerSessionRepository {
    Optional<WorkerRegistration> findAgent(UUID agentId);

    void insert(WorkerSessionRecord session);

    Optional<WorkerSessionOwnership> findSessionForUpdate(UUID agentId, UUID sessionId);

    boolean revoke(UUID agentId, UUID sessionId, Instant revokedAt);

    record WorkerRegistration(WorkerScope scope, String phase) { }

    record WorkerSessionRecord(UUID id, UUID agentId, String tokenSha256, Instant expiresAt, Instant createdAt) { }

    record WorkerSessionOwnership(WorkerScope scope, boolean revoked) { }
}
