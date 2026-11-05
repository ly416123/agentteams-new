package io.agentteams.credentialproxy;

import java.util.Optional;

@FunctionalInterface
public interface ModelProxyAuthenticator {
    Optional<AuthenticatedWorker> authenticate(String token);
}
