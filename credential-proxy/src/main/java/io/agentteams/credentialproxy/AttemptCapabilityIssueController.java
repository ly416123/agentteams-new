package io.agentteams.credentialproxy;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Internal slot endpoint; authorization is derived from the slot bearer and live Assignment rows. */
@RestController
public final class AttemptCapabilityIssueController {
    private final AttemptExecutionCapabilityIssuer issuer;

    public AttemptCapabilityIssueController(AttemptExecutionCapabilityIssuer issuer) {
        this.issuer = issuer;
    }

    @PostMapping(path = "/internal/v1/attempt-capabilities", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CapabilityResponse> issue(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody IssueRequest body) {
        String slotSessionToken = bearerToken(authorization);
        IssuedAttemptCapability issued = issuer.issue(slotSessionToken,
                body.taskId(), body.assignmentId(), body.attemptId(), body.leaseId());
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(new CapabilityResponse(issued.token(), issued.expiresAt()));
    }

    @ExceptionHandler(AttemptCredentialDeniedException.class)
    public ResponseEntity<Map<String, String>> denied() {
        return ResponseEntity.status(403).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "attempt_capability_issue_denied"));
    }

    @ExceptionHandler(ModelProxyException.class)
    public ResponseEntity<Map<String, String>> unauthenticated(ModelProxyException error) {
        return ResponseEntity.status(error.statusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "worker_slot_session_required"));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || authorization.length() <= 7
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ModelProxyException(401);
        }
        String token = authorization.substring(7);
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new ModelProxyException(401);
        }
        return token;
    }

    public record IssueRequest(UUID taskId, UUID assignmentId, UUID attemptId, UUID leaseId) { }
    public record CapabilityResponse(String token,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt) { }
}
