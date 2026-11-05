package io.agentteams.credentialproxy;

public final class AttemptExecutionDeniedException extends RuntimeException {
    public AttemptExecutionDeniedException() {
        super("Worker has no unique active execution");
    }
}
