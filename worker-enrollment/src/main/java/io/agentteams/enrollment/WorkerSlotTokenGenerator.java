package io.agentteams.enrollment;

@FunctionalInterface
public interface WorkerSlotTokenGenerator {
    String generate();
}
