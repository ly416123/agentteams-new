package io.agentteams.credentialproxy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class AttemptExecutionContextResolver {
    public static final Duration MAX_CONTEXT_LIFETIME = Duration.ofMinutes(5);

    private final ActiveAttemptRepository attempts;
    private final Clock clock;

    public AttemptExecutionContextResolver(ActiveAttemptRepository attempts, Clock clock) {
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AttemptExecutionContext resolve(AuthenticatedWorker worker) {
        Objects.requireNonNull(worker, "worker");
        List<ActiveAttemptCandidate> candidates = attempts.findActiveAttempts(worker);
        if (candidates == null || candidates.size() != 1) throw new AttemptExecutionDeniedException();

        ActiveAttemptCandidate candidate = candidates.getFirst();
        Instant now = clock.instant();
        if (candidate == null
                || !worker.agentId().equals(candidate.agentId())
                || !worker.tenantId().equals(candidate.tenantId())
                || !worker.projectId().equals(candidate.projectId())
                || !worker.teamId().equals(candidate.teamId())
                || !candidate.leaseActive()
                || !candidate.attemptActive()
                || !candidate.assignmentActive()
                || candidate.leaseExpiresAt() == null
                || !candidate.leaseExpiresAt().isAfter(now)
                || blank(candidate.tenantId())
                || blank(candidate.projectId())
                || blank(candidate.teamId())
                || candidate.taskId() == null
                || candidate.attemptId() == null
                || candidate.leaseId() == null) {
            throw new AttemptExecutionDeniedException();
        }

        Instant contextExpiry = now.plus(MAX_CONTEXT_LIFETIME);
        if (candidate.leaseExpiresAt().isBefore(contextExpiry)) contextExpiry = candidate.leaseExpiresAt();
        return new AttemptExecutionContext(1, candidate.tenantId(), candidate.projectId(), candidate.teamId(),
                candidate.agentId(), candidate.taskId(), candidate.attemptId(), candidate.leaseId(),
                worker.modelCredentialRef(), now, contextExpiry);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
