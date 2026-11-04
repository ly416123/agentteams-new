package io.agentteams.enrollment;

@FunctionalInterface
public interface WorkerTokenGenerator {
    String generate();
}
