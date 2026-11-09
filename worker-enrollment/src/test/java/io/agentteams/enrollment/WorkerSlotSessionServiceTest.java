package io.agentteams.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerSlotSessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final UUID SLOT_ID = UUID.fromString("55000000-0000-0000-0000-000000000001");
    private static final String SUBJECT = "pool-operator";
    private static final Set<String> PERMISSIONS = Set.of("worker:slot:session:manage");

    @Test
    void issuesTenantNeutralSessionForAnActiveSlotAndStoresOnlyItsHash() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        repository.slot = Optional.of(new WorkerSlotSessionRepository.WorkerSlotRecord(SLOT_ID, "ACTIVE"));
        WorkerSlotSessionService service = service(repository);

        IssuedWorkerSlotSession issued = service.issue(SLOT_ID, SUBJECT, PERMISSIONS, Duration.ofDays(7));

        assertThat(issued.slotId()).isEqualTo(SLOT_ID);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(repository.saved.slotId()).isEqualTo(SLOT_ID);
        assertThat(repository.saved.subject()).isEqualTo(SUBJECT);
        assertThat(repository.saved.tokenSha256()).isEqualTo(sha256(issued.accessToken()));
        assertThat(repository.saved.tokenSha256()).doesNotContain(issued.accessToken());
    }

    @Test
    void issuesGatewayCompatibleWorkerSlotBearer() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.slot = Optional.of(new WorkerSlotSessionRepository.WorkerSlotRecord(SLOT_ID, "ACTIVE"));
        WorkerSlotSessionService service = new WorkerSlotSessionService(repository,
                new SecureWorkerSlotTokenGenerator(), Clock.fixed(NOW, ZoneOffset.UTC));

        IssuedWorkerSlotSession issued = service.issue(SLOT_ID, SUBJECT, PERMISSIONS, Duration.ofHours(1));

        assertThat(issued.accessToken()).matches("wslot_[A-Za-z0-9_-]{43}");
    }

    @Test
    void refusesInactiveOrUnknownSlotsWithoutWritingASession() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.slot = Optional.of(new WorkerSlotSessionRepository.WorkerSlotRecord(SLOT_ID, "DRAINING"));
        WorkerSlotSessionService service = service(repository);

        assertThatThrownBy(() -> service.issue(SLOT_ID, SUBJECT, PERMISSIONS, Duration.ofHours(1)))
                .isInstanceOf(WorkerEnrollmentException.class);
        assertThat(repository.saved).isNull();

        repository.slot = Optional.empty();
        assertThatThrownBy(() -> service.issue(SLOT_ID, SUBJECT, PERMISSIONS, Duration.ofHours(1)))
                .isInstanceOf(WorkerEnrollmentException.class);
        assertThat(repository.saved).isNull();
    }

    @Test
    void requiresTheDedicatedSlotSessionManagementPermission() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.slot = Optional.of(new WorkerSlotSessionRepository.WorkerSlotRecord(SLOT_ID, "ACTIVE"));
        WorkerSlotSessionService service = service(repository);

        assertThatThrownBy(() -> service.issue(SLOT_ID, SUBJECT, Set.of("worker:session:manage"),
                Duration.ofHours(1))).isInstanceOf(WorkerEnrollmentException.class);
        assertThat(repository.saved).isNull();
    }

    @Test
    void revokesOnlyTheNamedSlotSessionAndRejectsCrossSlotRevocation() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.ownership = Optional.of(new WorkerSlotSessionRepository.WorkerSlotSessionOwnership(
                SLOT_ID, false));
        WorkerSlotSessionService service = service(repository);
        UUID sessionId = UUID.fromString("55000000-0000-0000-0000-000000000099");

        service.revoke(SLOT_ID, sessionId, PERMISSIONS);

        assertThat(repository.revokedSlotId).isEqualTo(SLOT_ID);
        assertThat(repository.revokedSessionId).isEqualTo(sessionId);
        assertThat(repository.revokedAt).isEqualTo(NOW);

        repository.revokedAt = null;
        repository.ownership = Optional.of(new WorkerSlotSessionRepository.WorkerSlotSessionOwnership(
                UUID.fromString("55000000-0000-0000-0000-000000000002"), false));
        assertThatThrownBy(() -> service.revoke(SLOT_ID, sessionId, PERMISSIONS))
                .isInstanceOf(WorkerEnrollmentException.class);
        assertThat(repository.revokedAt).isNull();
    }

    private static WorkerSlotSessionService service(InMemoryRepository repository) {
        return new WorkerSlotSessionService(repository, () -> "wslot_test-token",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class InMemoryRepository implements WorkerSlotSessionRepository {
        private Optional<WorkerSlotRecord> slot = Optional.empty();
        private WorkerSlotSessionRecord saved;
        private Optional<WorkerSlotSessionOwnership> ownership = Optional.empty();
        private UUID revokedSlotId;
        private UUID revokedSessionId;
        private Instant revokedAt;

        @Override
        public Optional<WorkerSlotRecord> findSlot(UUID slotId) {
            return slot.filter(candidate -> candidate.id().equals(slotId));
        }

        @Override
        public void insertSession(WorkerSlotSessionRecord session) {
            saved = session;
        }

        @Override
        public Optional<WorkerSlotSessionOwnership> findSessionForUpdate(UUID slotId, UUID sessionId) {
            return ownership;
        }

        @Override
        public boolean revoke(UUID slotId, UUID sessionId, Instant revokedAt) {
            this.revokedSlotId = slotId;
            this.revokedSessionId = sessionId;
            this.revokedAt = revokedAt;
            return ownership.isPresent();
        }
    }
}
