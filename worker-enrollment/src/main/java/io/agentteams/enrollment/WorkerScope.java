package io.agentteams.enrollment;

import java.util.Objects;

public record WorkerScope(String tenant, String project, String team) {
    public WorkerScope {
        requireText(tenant, "tenant");
        requireText(project, "project");
        requireText(team, "team");
    }

    public boolean equalsScope(WorkerScope other) {
        return other != null && tenant.equals(other.tenant)
                && project.equals(other.project) && team.equals(other.team);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
