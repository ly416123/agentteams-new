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

class WorkerSessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final UUID AGENT_ID = UUID.fromString("44000000-0000-0000-0000-000000000001");
    private static final String SUBJECT = "operator";
    private static final WorkerScope SCOPE = new WorkerScope("tenant-a", "project-a", "team-a");
    private static final Set<String> PERMISSIONS = Set.of(WorkerSessionService.MANAGE_PERMISSION);

    @Test
    void issuesOneTimeTokenAndPersistsOnlyItsSha256ForTheRequestedScope() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        WorkerSessionService service = service(repository);

        IssuedWorkerSession issued = service.issue(AGENT_ID, SUBJECT, SCOPE, PERMISSIONS, Duration.ofHours(12));

        assertThat(issued.accessToken()).isEqualTo("opaque-one-time-token");
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(repository.saved.agentId()).isEqualTo(AGENT_ID);
        assertThat(repository.saved.tokenSha256()).isEqualTo(sha256(issued.accessToken()));
        assertThat(repository.saved.tokenSha256()).isNotEqualTo(issued.accessToken());
    }

    @Test
    void issuesIndependentModelProxyTokenBoundToSessionAndScope() throws Exception {
        InMemoryRepository repository = new InMemoryRepository();
        WorkerSessionService service = service(repository);

        IssuedWorkerSession issued = service.issue(AGENT_ID, SUBJECT, SCOPE, PERMISSIONS,
                Duration.ofHours(12), "vault://tenant-a/project-a/chatgpt");

        assertThat(issued.accessToken()).isEqualTo("opaque-one-time-token");
        assertThat(issued.modelProxyToken()).isEqualTo("atp_" + "A".repeat(43));
        assertThat(repository.savedModelCredential.agentId()).isEqualTo(AGENT_ID);
        assertThat(repository.savedModelCredential.sessionId()).isEqualTo(issued.sessionId());
        assertThat(repository.savedModelCredential.scope()).isEqualTo(SCOPE);
        assertThat(repository.savedModelCredential.modelCredentialRef())
                .isEqualTo("vault://tenant-a/project-a/chatgpt");
        assertThat(repository.savedModelCredential.expiresAt()).isEqualTo(issued.expiresAt());
        assertThat(repository.savedModelCredential.tokenSha256()).isEqualTo(sha256(issued.modelProxyToken()));
        assertThat(repository.savedModelCredential.tokenSha256()).isNotEqualTo(issued.modelProxyToken());
    }

    @Test
    void canonicalizesAuthorizedProjectNameBeforeComparingWorkerAndBindingProxyCredential() {
        InMemoryRepository repository = new InMemoryRepository();
        WorkerSessionService service = service(repository);

        service.issue(AGENT_ID, SUBJECT, new WorkerScope("tenant-a", "project-alias", "team-a"),
                PERMISSIONS, Duration.ofHours(1), "vault://tenant-a/project-a/chatgpt");

        assertThat(repository.executionScope).isEqualTo(SCOPE);
        assertThat(repository.savedModelCredential.scope()).isEqualTo(SCOPE);
    }

    @Test
    void rejectsUnsafeOrEmptyModelCredentialReferencesBeforeWritingSession() {
        InMemoryRepository repository = new InMemoryRepository();
        WorkerSessionService service = service(repository);

        assertThatThrownBy(() -> service.issue(AGENT_ID, SUBJECT, SCOPE, PERMISSIONS,
                Duration.ofHours(1), "  ")).isInstanceOf(WorkerEnrollmentException.class);
        assertThatThrownBy(() -> service.issue(AGENT_ID, SUBJECT, SCOPE, PERMISSIONS,
                Duration.ofHours(1), "vault://tenant-a/" + (char) 10 + "forged"))
                .isInstanceOf(WorkerEnrollmentException.class);
        assertThat(repository.saved).isNull();
    }

    @Test
    void refusesToIssueForAnotherTenantBeforePersistingAnything() {
        InMemoryRepository repository = new InMemoryRepository();
        WorkerSessionService service = service(repository);

        assertThatThrownBy(() -> service.issue(AGENT_ID, SUBJECT,
                new WorkerScope("tenant-b", "project-b", "team-b"), PERMISSIONS, Duration.ofHours(1)))
                .isInstanceOf(WorkerEnrollmentException.class);

        assertThat(repository.saved).isNull();
    }

    @Test
    void requiresTheDedicatedWorkerSessionPermission() {
        InMemoryRepository repository = new InMemoryRepository();
        WorkerSessionService service = service(repository);

        assertThatThrownBy(() -> service.issue(AGENT_ID, SUBJECT, SCOPE,
                Set.of("AGENT_WRITE"), Duration.ofHours(1)))
                .isInstanceOf(WorkerEnrollmentException.class);

        assertThat(repository.saved).isNull();
    }

    @Test
    void rejectsSessionLifetimesOutsideOneHourThroughThirtyDays() {
        WorkerSessionService service = service(new InMemoryRepository());

        assertThatThrownBy(() -> service.issue(AGENT_ID, SUBJECT, SCOPE, PERMISSIONS, Duration.ofMinutes(59)))
                .isInstanceOf(WorkerEnrollmentException.class);
        assertThatThrownBy(() -> service.issue(AGENT_ID, SUBJECT, SCOPE, PERMISSIONS, Duration.ofDays(31)))
                .isInstanceOf(WorkerEnrollmentException.class);
    }

    @Test
    void revokesOnlyASessionOwnedByTheAgentAndCallerScope() {
        InMemoryRepository repository = new InMemoryRepository();
        UUID sessionId = UUID.fromString("44000000-0000-0000-0000-000000000099");
        repository.ownership = new WorkerSessionRepository.WorkerSessionOwnership(SCOPE, false);
        WorkerSessionService service = service(repository);

        service.revoke(AGENT_ID, sessionId, SUBJECT, SCOPE, PERMISSIONS);

        assertThat(repository.revokedAgentId).isEqualTo(AGENT_ID);
        assertThat(repository.revokedSessionId).isEqualTo(sessionId);
        assertThat(repository.revokedAt).isEqualTo(NOW);
        assertThat(repository.modelCredentialRevokedAt).isEqualTo(NOW);
    }

    @Test
    void doesNotRevokeASessionFromAnotherScope() {
        InMemoryRepository repository = new InMemoryRepository();
        UUID sessionId = UUID.fromString("44000000-0000-0000-0000-000000000099");
        repository.ownership = new WorkerSessionRepository.WorkerSessionOwnership(
                new WorkerScope("tenant-b", "project-b", "team-b"), false);
        WorkerSessionService service = service(repository);

        assertThatThrownBy(() -> service.revoke(AGENT_ID, sessionId, SUBJECT, SCOPE, PERMISSIONS))
                .isInstanceOf(WorkerEnrollmentException.class);

        assertThat(repository.revokedAt).isNull();
    }

    @Test
    void requiresAnActiveCurrentProjectManagementMembership() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.membershipActive = false;
        WorkerSessionService service = service(repository);

        assertThatThrownBy(() -> service.issue(AGENT_ID, SUBJECT, SCOPE, PERMISSIONS, Duration.ofHours(1)))
                .isInstanceOf(WorkerEnrollmentException.class);
        assertThat(repository.saved).isNull();
        assertThat(repository.lastMembershipSubject).isEqualTo(SUBJECT);
        assertThat(repository.lastMembershipScope).isEqualTo(SCOPE);
    }

    private static WorkerSessionService service(InMemoryRepository repository) {
        return new WorkerSessionService(repository, () -> "opaque-one-time-token", () -> "atp_" + "A".repeat(43),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class InMemoryRepository implements WorkerSessionRepository {
        private WorkerSessionRecord saved;
        private ModelProxyCredentialRecord savedModelCredential;
        private WorkerSessionOwnership ownership;
        private UUID revokedAgentId;
        private UUID revokedSessionId;
        private Instant revokedAt;
        private Instant modelCredentialRevokedAt;
        private WorkerScope executionScope;
        private boolean membershipActive = true;
        private String lastMembershipSubject;
        private WorkerScope lastMembershipScope;

        @Override
        public void setExecutionScope(WorkerScope scope) {
            executionScope = scope;
        }

        @Override
        public boolean hasActiveProjectManagementMembership(String subject, WorkerScope scope) {
            lastMembershipSubject = subject;
            lastMembershipScope = scope;
            return membershipActive;
        }

        @Override
        public Optional<WorkerScope> resolveAuthorizedScope(String subject, WorkerScope requestedScope) {
            lastMembershipSubject = subject;
            lastMembershipScope = requestedScope;
            if (!membershipActive) return Optional.empty();
            String project = requestedScope.project().equals("project-alias") ? SCOPE.project() : requestedScope.project();
            return Optional.of(new WorkerScope(requestedScope.tenant(), project, requestedScope.team()));
        }

        @Override
        public Optional<WorkerRegistration> findAgent(UUID agentId) {
            return agentId.equals(AGENT_ID)
                    ? Optional.of(new WorkerRegistration(SCOPE, "READY")) : Optional.empty();
        }

        @Override
        public void insert(WorkerSessionRecord session) {
            saved = session;
        }

        @Override
        public void insertModelProxyCredential(ModelProxyCredentialRecord credential) {
            savedModelCredential = credential;
        }

        @Override
        public Optional<WorkerSessionOwnership> findSessionForUpdate(UUID agentId, UUID sessionId) {
            return Optional.ofNullable(ownership);
        }

        @Override
        public boolean revoke(UUID agentId, UUID sessionId, Instant revokedAt) {
            this.revokedAgentId = agentId;
            this.revokedSessionId = sessionId;
            this.revokedAt = revokedAt;
            this.modelCredentialRevokedAt = revokedAt;
            return ownership != null;
        }
    }
}
