package io.agentteams.enrollment;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents/{agentId}/sessions")
public class WorkerSessionController {
    private final WorkerSessionService sessions;
    private final EnrollmentOidcProperties oidc;

    public WorkerSessionController(WorkerSessionService sessions, EnrollmentOidcProperties oidc) {
        this.sessions = sessions;
        this.oidc = oidc;
    }

    @PostMapping
    public IssueSessionResponse issue(@PathVariable("agentId") UUID agentId,
            @RequestBody(required = false) IssueSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        WorkerScope caller = callerScope(jwt);
        Duration ttl = request == null || request.ttlSeconds() == null
                ? null : Duration.ofSeconds(request.ttlSeconds());
        IssuedWorkerSession issued = sessions.issue(agentId, caller, permissions(jwt), ttl);
        return new IssueSessionResponse(issued.sessionId(), issued.agentId(), issued.accessToken(), issued.expiresAt());
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revoke(@PathVariable("agentId") UUID agentId,
            @PathVariable("sessionId") UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        sessions.revoke(agentId, sessionId, callerScope(jwt), permissions(jwt));
        return ResponseEntity.noContent().build();
    }

    private WorkerScope callerScope(Jwt jwt) {
        return new WorkerScope(stringClaim(jwt, oidc.tenantClaim()), stringClaim(jwt, oidc.projectClaim()),
                stringClaim(jwt, oidc.teamClaim()));
    }

    private Set<String> permissions(Jwt jwt) {
        Object raw = jwt.getClaims().get(oidc.permissionsClaim());
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (raw instanceof Collection<?> collection) {
            collection.stream().filter(String.class::isInstance).map(String.class::cast).forEach(values::add);
        } else if (raw instanceof String string) {
            for (String value : string.split("[ ,]+")) if (!value.isBlank()) values.add(value);
        }
        return Set.copyOf(values);
    }

    private static String stringClaim(Jwt jwt, String claim) {
        Object value = jwt.getClaims().get(claim);
        if (!(value instanceof String text) || text.isBlank()) {
            throw WorkerEnrollmentException.forbidden("required OIDC scope claim is missing");
        }
        return text;
    }

    public record IssueSessionRequest(Long ttlSeconds) { }

    public record IssueSessionResponse(UUID sessionId, UUID agentId, String accessToken, Instant expiresAt) { }
}
