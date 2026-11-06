package io.agentteams.credentialproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttemptExecutionContextResolverTest {
    private static final UUID PROXY_CREDENTIAL_ID = UUID.fromString("77000000-0000-0000-0000-000000000005");
    private static final UUID AGENT_ID = UUID.fromString("77000000-0000-0000-0000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("77000000-0000-0000-0000-000000000002");
    private static final UUID ATTEMPT_ID = UUID.fromString("77000000-0000-0000-0000-000000000003");
    private static final UUID LEASE_ID = UUID.fromString("77000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-11-05T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void derivesContextOnlyFromOneActiveDatabaseAssignment() {
        var resolver = resolver(List.of(activeCandidate()));

        AttemptExecutionContext context = resolver.resolve(worker());

        assertThat(context).isEqualTo(new AttemptExecutionContext(
                1, "tenant-a", "project-a", "team-a", AGENT_ID, TASK_ID, ATTEMPT_ID, LEASE_ID,
                "model-credential-a", NOW, NOW.plusSeconds(300)));
    }

    @Test
    void deniesWhenWorkerHasNoActiveAssignment() {
        var resolver = resolver(List.of());

        assertThatThrownBy(() -> resolver.resolve(worker()))
                .isInstanceOf(AttemptExecutionDeniedException.class);
    }

    @Test
    void deniesWhenWorkerHasMultipleActiveAssignments() {
        var second = new ActiveAttemptCandidate(UUID.randomUUID(), "tenant-b", "project-b", "team-b",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                NOW.plusSeconds(600), true, true, true);
        var resolver = resolver(List.of(activeCandidate(), second));

        assertThatThrownBy(() -> resolver.resolve(worker()))
                .isInstanceOf(AttemptExecutionDeniedException.class);
    }

    @Test
    void deniesExpiredLeaseEvenIfRepositoryReturnsIt() {
        var expired = new ActiveAttemptCandidate(AGENT_ID, "tenant-a", "project-a", "team-a", TASK_ID,
                ATTEMPT_ID, LEASE_ID, NOW, true, true, true);

        assertThatThrownBy(() -> resolver(List.of(expired)).resolve(worker()))
                .isInstanceOf(AttemptExecutionDeniedException.class);
    }

    @Test
    void contextNeverOutlivesShorterLease() {
        var shortLease = new ActiveAttemptCandidate(AGENT_ID, "tenant-a", "project-a", "team-a", TASK_ID,
                ATTEMPT_ID, LEASE_ID, NOW.plusSeconds(90), true, true, true);

        assertThat(resolver(List.of(shortLease)).resolve(worker()).expiresAt())
                .isEqualTo(NOW.plusSeconds(90));
    }

    @Test
    void deniesCandidateFromAnotherAgent() {
        var forged = new ActiveAttemptCandidate(UUID.randomUUID(), "tenant-a", "project-a", "team-a", TASK_ID,
                ATTEMPT_ID, LEASE_ID, NOW.plusSeconds(600), true, true, true);

        assertThatThrownBy(() -> resolver(List.of(forged)).resolve(worker()))
                .isInstanceOf(AttemptExecutionDeniedException.class);
    }

    @Test
    void deniesCandidateWhenAssignmentAttemptOrLeaseIsNotActive() {
        var inactiveLease = new ActiveAttemptCandidate(AGENT_ID, "tenant-a", "project-a", "team-a", TASK_ID,
                ATTEMPT_ID, LEASE_ID, NOW.plusSeconds(600), false, true, true);
        var inactiveAttempt = new ActiveAttemptCandidate(AGENT_ID, "tenant-a", "project-a", "team-a", TASK_ID,
                ATTEMPT_ID, LEASE_ID, NOW.plusSeconds(600), true, false, true);
        var inactiveAssignment = new ActiveAttemptCandidate(AGENT_ID, "tenant-a", "project-a", "team-a", TASK_ID,
                ATTEMPT_ID, LEASE_ID, NOW.plusSeconds(600), true, true, false);

        for (var candidate : List.of(inactiveLease, inactiveAttempt, inactiveAssignment)) {
            assertThatThrownBy(() -> resolver(List.of(candidate)).resolve(worker()))
                    .isInstanceOf(AttemptExecutionDeniedException.class);
        }
    }

    @Test
    void deniesCandidateWithoutCompleteTenantScope() {
        var missingTenant = new ActiveAttemptCandidate(AGENT_ID, " ", "project-a", "team-a", TASK_ID,
                ATTEMPT_ID, LEASE_ID, NOW.plusSeconds(600), true, true, true);
        assertThatThrownBy(() -> resolver(List.of(missingTenant)).resolve(worker()))
                .isInstanceOf(AttemptExecutionDeniedException.class);
    }

    @Test
    void deniesCandidateWhoseScopeDiffersFromAuthenticatedWorkerScope() {
        var otherTenant = new ActiveAttemptCandidate(AGENT_ID, "tenant-b", "project-a", "team-a", TASK_ID,
                ATTEMPT_ID, LEASE_ID, NOW.plusSeconds(600), true, true, true);

        assertThatThrownBy(() -> resolver(List.of(otherTenant)).resolve(worker()))
                .isInstanceOf(AttemptExecutionDeniedException.class);
    }

    @Test
    void tenantNeutralCredentialMayResolveOnlyItsAttemptScope() {
        UUID tenantBTask = UUID.randomUUID();
        ActiveAttemptCandidate candidate = new ActiveAttemptCandidate(AGENT_ID, "tenant-b", "project-b",
                "team-b", tenantBTask, ATTEMPT_ID, LEASE_ID, NOW.plusSeconds(600), true, true, true);
        AuthenticatedWorker attemptCredential = new AuthenticatedWorker(PROXY_CREDENTIAL_ID, AGENT_ID,
                null, null, null, "vault://tenant-b/project-b/model", ATTEMPT_ID, LEASE_ID);

        AttemptExecutionContext context = resolver(List.of(candidate)).resolve(attemptCredential);

        assertThat(context.tenantId()).isEqualTo("tenant-b");
        assertThat(context.projectId()).isEqualTo("project-b");
        assertThat(context.taskId()).isEqualTo(tenantBTask);
        assertThat(context.attemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(context.leaseId()).isEqualTo(LEASE_ID);
    }

    @Test
    void tenantNeutralCredentialCannotResolveAnotherAttemptOrLeaseForSameAgent() {
        ActiveAttemptCandidate candidate = new ActiveAttemptCandidate(AGENT_ID, "tenant-b", "project-b",
                "team-b", TASK_ID, UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(600), true, true, true);
        AuthenticatedWorker attemptCredential = new AuthenticatedWorker(PROXY_CREDENTIAL_ID, AGENT_ID,
                null, null, null, "vault://tenant-b/project-b/model", ATTEMPT_ID, LEASE_ID);

        assertThatThrownBy(() -> resolver(List.of(candidate)).resolve(attemptCredential))
                .isInstanceOf(AttemptExecutionDeniedException.class);
    }

    @Test
    void attemptCredentialMustCarryBothAttemptAndLeaseButNoPartialTenantScope() {
        assertThatThrownBy(() -> new AuthenticatedWorker(PROXY_CREDENTIAL_ID, AGENT_ID,
                null, null, null, "model-ref", ATTEMPT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticatedWorker(PROXY_CREDENTIAL_ID, AGENT_ID,
                "tenant-a", null, "team-a", "model-ref", ATTEMPT_ID, LEASE_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticatedWorker(PROXY_CREDENTIAL_ID, AGENT_ID,
                null, null, null, "model-ref", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AuthenticatedWorker worker() {
        return new AuthenticatedWorker(PROXY_CREDENTIAL_ID, AGENT_ID, "tenant-a", "project-a", "team-a",
                "model-credential-a");
    }

    private static AttemptExecutionContextResolver resolver(List<ActiveAttemptCandidate> candidates) {
        return new AttemptExecutionContextResolver(ignored -> candidates, CLOCK);
    }

    private static ActiveAttemptCandidate activeCandidate() {
        return new ActiveAttemptCandidate(AGENT_ID, "tenant-a", "project-a", "team-a", TASK_ID, ATTEMPT_ID,
                LEASE_ID, NOW.plusSeconds(600), true, true, true);
    }
}
