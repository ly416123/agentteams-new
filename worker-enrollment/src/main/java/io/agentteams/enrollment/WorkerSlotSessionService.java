package io.agentteams.enrollment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues long-lived, tenant-neutral credentials for explicitly provisioned fixed Worker slots. */
@Service
public class WorkerSlotSessionService {
    public static final String MANAGE_PERMISSION = "worker:slot:session:manage";
    public static final Duration MIN_TTL = Duration.ofHours(1);
    public static final Duration MAX_TTL = Duration.ofDays(30);

    private final WorkerSlotSessionRepository repository;
    private final WorkerSlotTokenGenerator tokens;
    private final Clock clock;

    public WorkerSlotSessionService(WorkerSlotSessionRepository repository, WorkerSlotTokenGenerator tokens,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public IssuedWorkerSlotSession issue(UUID slotId, String subject, Set<String> permissions, Duration requestedTtl) {
        requirePermission(permissions);
        Objects.requireNonNull(slotId, "slotId");
        if (subject == null || subject.isBlank()) {
            throw WorkerEnrollmentException.forbidden("authenticated subject is required");
        }
        Duration ttl = requestedTtl == null ? MAX_TTL : requestedTtl;
        if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw WorkerEnrollmentException.badRequest("Worker slot session lifetime must be between 1 hour and 30 days");
        }
        WorkerSlotSessionRepository.WorkerSlotRecord slot = repository.findSlot(slotId)
                .filter(candidate -> "ACTIVE".equals(candidate.status()))
                .orElseThrow(() -> new WorkerEnrollmentException("Worker slot is not available"));
        String accessToken = tokens.generate();
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Worker token generator returned no token");
        }
        Instant now = clock.instant();
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = now.plus(ttl);
        repository.insertSession(new WorkerSlotSessionRepository.WorkerSlotSessionRecord(sessionId,
                slot.id(), subject, WorkerSessionService.sha256(accessToken), expiresAt, now));
        return new IssuedWorkerSlotSession(sessionId, slot.id(), accessToken, expiresAt);
    }

    @Transactional
    public void revoke(UUID slotId, UUID sessionId, Set<String> permissions) {
        requirePermission(permissions);
        Objects.requireNonNull(slotId, "slotId");
        Objects.requireNonNull(sessionId, "sessionId");
        WorkerSlotSessionRepository.WorkerSlotSessionOwnership ownership = repository
                .findSessionForUpdate(slotId, sessionId)
                .filter(candidate -> slotId.equals(candidate.slotId()))
                .orElseThrow(() -> new WorkerEnrollmentException("Worker slot session is not available"));
        if (!ownership.revoked() && !repository.revoke(slotId, sessionId, clock.instant())) {
            throw new WorkerEnrollmentException("Worker slot session is not available");
        }
    }

    private static void requirePermission(Set<String> permissions) {
        if (permissions == null || !permissions.contains(MANAGE_PERMISSION)) {
            throw WorkerEnrollmentException.forbidden("Worker slot session management permission is required");
        }
    }
}
