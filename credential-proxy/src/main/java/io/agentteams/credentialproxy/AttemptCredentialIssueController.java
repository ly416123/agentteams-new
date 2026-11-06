package io.agentteams.credentialproxy;

import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal Worker endpoint. Scope is resolved from the active Task, never from the request body. */
@RestController
public final class AttemptCredentialIssueController {
    private final AttemptCredentialIssuer issuer;

    public AttemptCredentialIssueController(AttemptCredentialIssuer issuer) {
        this.issuer = issuer;
    }

    @PostMapping(path = "/internal/v1/attempt-credentials", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CredentialResponse> issue(
            @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false)
            String authorization,
            @RequestBody IssueRequest body) {
        String workerSessionToken = bearerToken(authorization);
        IssuedAttemptCredential issued = issuer.issue(workerSessionToken,
                body.taskId(), body.attemptId(), body.leaseId());
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(new CredentialResponse(issued.token(), issued.expiresAt()));
    }

    @ExceptionHandler(AttemptCredentialDeniedException.class)
    public ResponseEntity<Map<String, String>> denied() {
        return ResponseEntity.status(403).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "attempt_credential_issue_denied"));
    }

    @ExceptionHandler(ModelProxyException.class)
    public ResponseEntity<Map<String, String>> unauthenticated(ModelProxyException error) {
        return ResponseEntity.status(error.statusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "worker_session_required"));
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

    public record IssueRequest(UUID taskId, UUID attemptId, UUID leaseId) { }
    public record CredentialResponse(String token,
            @JsonFormat(shape = JsonFormat.Shape.STRING) java.time.Instant expiresAt) { }
}
