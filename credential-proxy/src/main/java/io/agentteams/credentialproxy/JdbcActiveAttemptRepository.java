package io.agentteams.credentialproxy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads Attempt authorization through the narrowly granted database function. */
public final class JdbcActiveAttemptRepository implements ActiveAttemptRepository {
    private static final String ACTIVE_ATTEMPTS_SQL = """
            SELECT agent_id, tenant_id, project_id, team_id, task_id, attempt_id,
                   lease_id, lease_expires_at, lease_active, attempt_active, assignment_active
              FROM public.credential_proxy_active_attempts(?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public JdbcActiveAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<ActiveAttemptCandidate> findActiveAttempts(AuthenticatedWorker worker) {
        Objects.requireNonNull(worker, "worker");
        return jdbc.query(ACTIVE_ATTEMPTS_SQL, JdbcActiveAttemptRepository::map,
                worker.agentId(), worker.tenantId(), worker.projectId(), worker.teamId());
    }

    private static ActiveAttemptCandidate map(ResultSet rs, int row) throws SQLException {
        return new ActiveAttemptCandidate(
                rs.getObject("agent_id", java.util.UUID.class),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("team_id"),
                rs.getObject("task_id", java.util.UUID.class),
                rs.getObject("attempt_id", java.util.UUID.class),
                rs.getObject("lease_id", java.util.UUID.class),
                rs.getTimestamp("lease_expires_at").toInstant(),
                rs.getBoolean("lease_active"),
                rs.getBoolean("attempt_active"),
                rs.getBoolean("assignment_active"));
    }
}
