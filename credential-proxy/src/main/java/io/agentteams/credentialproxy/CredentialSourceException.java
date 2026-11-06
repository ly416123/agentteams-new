package io.agentteams.credentialproxy;

public final class CredentialSourceException extends RuntimeException {
    public CredentialSourceException() {
        super("credential reference could not be resolved");
    }
}
