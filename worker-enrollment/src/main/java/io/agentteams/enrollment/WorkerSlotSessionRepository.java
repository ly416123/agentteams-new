package io.agentteams.enrollment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for tenant-neutral Worker slot identities. */
public interface WorkerSlotSessionRepository {
    Optional<WorkerSlotRecord> findSlot(UUID slotId);

    void insertSession(WorkerSlotSessionRecord session);

    Optional<WorkerSlotSessionOwnership> findSessionForUpdate(UUID slotId, UUID sessionId);

    boolean revoke(UUID slotId, UUID sessionId, Instant revokedAt);

    record WorkerSlotRecord(UUID id, String status) { }

    record WorkerSlotSessionRecord(UUID id, UUID slotId, String subject, String tokenSha256,
            Instant expiresAt, Instant createdAt) { }

    record WorkerSlotSessionOwnership(UUID slotId, boolean revoked) { }
}
