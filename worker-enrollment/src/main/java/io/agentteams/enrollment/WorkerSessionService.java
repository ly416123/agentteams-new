package io.agentteams.enrollment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerSessionService {
    public static final String MANAGE_PERMISSION = "worker:session:manage";
    public static final Duration MIN_TTL = Duration.ofHours(1);
    public static final Duration MAX_TTL = Duration.ofDays(30);

    private final WorkerSessionRepository repository;
    private final WorkerTokenGenerator tokens;
    private final ModelProxyTokenGenerator modelProxyTokens;
    private final Clock clock;
    private final Duration defaultTtl;

    public WorkerSessionService(WorkerSessionRepository repository, WorkerTokenGenerator tokens,
            ModelProxyTokenGenerator modelProxyTokens,
            Clock clock, @Value("${agentteams.enrollment.default-ttl:7d}") Duration defaultTtl) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.modelProxyTokens = Objects.requireNonNull(modelProxyTokens, "modelProxyTokens");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defaultTtl = validateTtl(defaultTtl);
    }

    @Transactional
    public IssuedWorkerSession issue(UUID agentId, WorkerScope callerScope, Set<String> permissions,
            Duration requestedTtl) {
        return issue(agentId, callerScope, permissions, requestedTtl, null);
    }

    @Transactional
    public IssuedWorkerSession issue(UUID agentId, WorkerScope callerScope, Set<String> permissions,
            Duration requestedTtl, String modelCredentialRef) {
        requirePermission(permissions);
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(callerScope, "callerScope");
        if (modelCredentialRef != null) validateModelCredentialRef(modelCredentialRef);
        Duration ttl = requestedTtl == null ? defaultTtl : validateTtl(requestedTtl);

        repository.setExecutionScope(callerScope);
        WorkerSessionRepository.WorkerRegistration worker = repository.findAgent(agentId)
                .orElseThrow(() -> new WorkerEnrollmentException("Worker is not available in caller scope"));
        if (!callerScope.equalsScope(worker.scope()) || !isEnrollable(worker.phase())) {
            throw new WorkerEnrollmentException("Worker is not available in caller scope");
        }

        String accessToken = requireGeneratedToken(tokens.generate());
        Instant now = clock.instant();
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = now.plus(ttl);
        repository.insert(new WorkerSessionRepository.WorkerSessionRecord(sessionId, agentId,
                sha256(accessToken), expiresAt, now));
        UUID proxyCredentialId = null;
        String modelProxyToken = null;
        if (modelCredentialRef != null) {
            modelProxyToken = requireGeneratedModelProxyToken(modelProxyTokens.generate());
            proxyCredentialId = UUID.randomUUID();
            repository.insertModelProxyCredential(new ModelProxyCredentialRecord(proxyCredentialId, sessionId,
                    agentId, callerScope, sha256(modelProxyToken), modelCredentialRef, expiresAt, now));
        }
        return new IssuedWorkerSession(sessionId, agentId, accessToken, expiresAt,
                proxyCredentialId, modelProxyToken);
    }

    @Transactional
    public void revoke(UUID agentId, UUID sessionId, WorkerScope callerScope, Set<String> permissions) {
        requirePermission(permissions);
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(callerScope, "callerScope");
        repository.setExecutionScope(callerScope);
        Optional<WorkerSessionRepository.WorkerSessionOwnership> session =
                repository.findSessionForUpdate(agentId, sessionId);
        if (session.isEmpty() || !callerScope.equalsScope(session.get().scope())) {
            throw new WorkerEnrollmentException("Worker session is not available in caller scope");
        }
        if (!repository.revoke(agentId, sessionId, clock.instant())) {
            throw new WorkerEnrollmentException("Worker session is not available in caller scope");
        }
    }

    private static void requirePermission(Set<String> permissions) {
        if (permissions == null || !permissions.contains(MANAGE_PERMISSION)) {
            throw WorkerEnrollmentException.forbidden("worker session management permission is required");
        }
    }

    private static Duration validateTtl(Duration ttl) {
        if (ttl == null || ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw WorkerEnrollmentException.badRequest("worker session lifetime must be between 1 hour and 30 days");
        }
        return ttl;
    }

    private static String requireGeneratedToken(String token) {
        if (token == null || token.isBlank()) throw new IllegalStateException("worker token generator returned no token");
        return token;
    }

    private static String requireGeneratedModelProxyToken(String token) {
        if (token == null || !token.startsWith("atp_") || token.length() != 47
                || token.substring(4).chars().anyMatch(ch -> !((ch >= 'A' && ch <= 'Z')
                        || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-'))) {
            throw new IllegalStateException("model proxy token generator returned an invalid token");
        }
        return token;
    }

    private static void validateModelCredentialRef(String ref) {
        if (ref.isBlank() || ref.length() > 256 || !ref.equals(ref.trim())
                || ref.chars().anyMatch(Character::isISOControl)) {
            throw WorkerEnrollmentException.badRequest("model credential reference is invalid");
        }
    }

    private static boolean isEnrollable(String phase) {
        return "PROVISIONING".equals(phase) || "READY".equals(phase) || "OFFLINE".equals(phase);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
