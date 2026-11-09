package io.agentteams.credentialproxy;

import java.util.Optional;

@FunctionalInterface
public interface ModelProxyAuthenticator {
    Optional<AuthenticatedWorker> authenticate(String token);

    /** Rechecks authorization without retaining the bearer token in a streaming response. */
    default Optional<AuthenticatedWorker> revalidate(AuthenticatedWorker worker) {
        return Optional.empty();
    }
}
