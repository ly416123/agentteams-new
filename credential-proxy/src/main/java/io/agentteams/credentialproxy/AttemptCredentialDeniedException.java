package io.agentteams.credentialproxy;

/** Deliberately does not distinguish an invalid Worker session from an inactive Attempt. */
public final class AttemptCredentialDeniedException extends RuntimeException {
    public AttemptCredentialDeniedException() {
        super("attempt credential issuance denied");
    }
}
