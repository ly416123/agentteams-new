package io.agentteams.enrollment;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** OIDC-protected provisioning and revocation API for tenant-neutral fixed Worker slots. */
@RestController
@RequestMapping("/api/v1/worker-slots/{slotId}/sessions")
public class WorkerSlotSessionController {
    private final WorkerSlotSessionService sessions;
    private final EnrollmentOidcProperties oidc;

    public WorkerSlotSessionController(WorkerSlotSessionService sessions, EnrollmentOidcProperties oidc) {
        this.sessions = sessions;
        this.oidc = oidc;
    }

    @PostMapping
    public ResponseEntity<IssueSessionResponse> issue(@PathVariable("slotId") UUID slotId,
            @RequestBody(required = false) IssueSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Duration ttl = request == null || request.ttlSeconds() == null
                ? null : Duration.ofSeconds(request.ttlSeconds());
        IssuedWorkerSlotSession issued = sessions.issue(slotId, jwt.getSubject(), permissions(jwt), ttl);
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(new IssueSessionResponse(issued.sessionId(), issued.slotId(), issued.accessToken(),
                        issued.expiresAt()));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revoke(@PathVariable("slotId") UUID slotId,
            @PathVariable("sessionId") UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        sessions.revoke(slotId, sessionId, permissions(jwt));
        return ResponseEntity.noContent().build();
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

    public record IssueSessionRequest(Long ttlSeconds) { }

    public record IssueSessionResponse(UUID sessionId, UUID slotId, String accessToken, Instant expiresAt) { }
}
