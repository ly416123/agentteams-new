package io.agentteams.enrollment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.oauth2.jwt.JwtException;

@RestControllerAdvice
public class WorkerEnrollmentErrorHandler {
    @ExceptionHandler(WorkerEnrollmentException.class)
    public ResponseEntity<ErrorResponse> handle(WorkerEnrollmentException error) {
        String code = error.status().is4xxClientError() ? "WORKER_ENROLLMENT_REQUEST_REJECTED"
                : "WORKER_ENROLLMENT_FAILED";
        return ResponseEntity.status(error.status()).body(new ErrorResponse(code));
    }

    @ExceptionHandler(JwtException.class)
    public ResponseEntity<ErrorResponse> handleInvalidJwt() {
        return ResponseEntity.status(401).body(new ErrorResponse("INVALID_BEARER_TOKEN"));
    }

    public record ErrorResponse(String code) { }
}
