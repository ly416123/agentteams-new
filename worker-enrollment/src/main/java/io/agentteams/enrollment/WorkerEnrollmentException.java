package io.agentteams.enrollment;

import org.springframework.http.HttpStatus;

public final class WorkerEnrollmentException extends RuntimeException {
    private final HttpStatus status;

    public WorkerEnrollmentException(String message) {
        this(HttpStatus.NOT_FOUND, message);
    }

    private WorkerEnrollmentException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static WorkerEnrollmentException forbidden(String message) {
        return new WorkerEnrollmentException(HttpStatus.FORBIDDEN, message);
    }

    public static WorkerEnrollmentException badRequest(String message) {
        return new WorkerEnrollmentException(HttpStatus.BAD_REQUEST, message);
    }
}
