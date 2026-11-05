package io.agentteams.credentialproxy;

/** Loads provider credentials by a non-secret reference. Returned arrays are owned by the caller. */
public interface CredentialSource {
    char[] resolve(AttemptExecutionContext context);
}
