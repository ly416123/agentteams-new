package io.agentteams.enrollment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkerSessionRepository implements WorkerSessionRepository {
    private final JdbcTemplate jdbc;

    public JdbcWorkerSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void setExecutionScope(WorkerScope scope) {
        Objects.requireNonNull(scope, "scope");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Worker enrollment scope requires an active database transaction");
        }
        jdbc.queryForObject("SELECT set_config('agentteams.tenant_id', ?, true)",
                String.class, scope.tenant());
        jdbc.queryForObject("SELECT set_config('agentteams.project_id', ?, true)",
                String.class, scope.project());
        jdbc.queryForObject("SELECT set_config('agentteams.team_id', ?, true)",
                String.class, scope.team());
    }

    @Override
    public Optional<WorkerRegistration> findAgent(UUID agentId) {
        return jdbc.query("""
                SELECT phase,
                       NULLIF(BTRIM(metadata #>> '{scope,tenant}'), '') AS tenant_id,
                       NULLIF(BTRIM(metadata #>> '{scope,project}'), '') AS project_id,
                       NULLIF(BTRIM(metadata #>> '{scope,team}'), '') AS team_id
                  FROM agents
                 WHERE id = ?
                   AND NULLIF(BTRIM(metadata #>> '{scope,tenant}'), '') IS NOT NULL
                   AND NULLIF(BTRIM(metadata #>> '{scope,project}'), '') IS NOT NULL
                   AND NULLIF(BTRIM(metadata #>> '{scope,team}'), '') IS NOT NULL
                """, this::registration, agentId).stream().findFirst();
    }

    @Override
    public void insert(WorkerSessionRecord session) {
        jdbc.update("""
                INSERT INTO agent_sessions(id, agent_id, token_sha256, expires_at, revoked_at, created_at)
                VALUES (?, ?, ?, ?, NULL, ?)
                """, session.id(), session.agentId(), session.tokenSha256(),
                Timestamp.from(session.expiresAt()), Timestamp.from(session.createdAt()));
    }

    @Override
    public Optional<WorkerSessionOwnership> findSessionForUpdate(UUID agentId, UUID sessionId) {
        return jdbc.query("""
                SELECT session.revoked_at,
                       NULLIF(BTRIM(agent.metadata #>> '{scope,tenant}'), '') AS tenant_id,
                       NULLIF(BTRIM(agent.metadata #>> '{scope,project}'), '') AS project_id,
                       NULLIF(BTRIM(agent.metadata #>> '{scope,team}'), '') AS team_id
                  FROM agent_sessions session
                  JOIN agents agent ON agent.id = session.agent_id
                 WHERE session.agent_id = ? AND session.id = ?
                   AND NULLIF(BTRIM(agent.metadata #>> '{scope,tenant}'), '') IS NOT NULL
                   AND NULLIF(BTRIM(agent.metadata #>> '{scope,project}'), '') IS NOT NULL
                   AND NULLIF(BTRIM(agent.metadata #>> '{scope,team}'), '') IS NOT NULL
                 FOR UPDATE OF session
                """, (rs, row) -> new WorkerSessionOwnership(scope(rs), rs.getTimestamp("revoked_at") != null),
                agentId, sessionId).stream().findFirst();
    }

    @Override
    public boolean revoke(UUID agentId, UUID sessionId, Instant revokedAt) {
        return jdbc.update("""
                UPDATE agent_sessions
                   SET revoked_at = COALESCE(revoked_at, ?)
                 WHERE agent_id = ? AND id = ?
                """, Timestamp.from(revokedAt), agentId, sessionId) == 1;
    }

    private WorkerRegistration registration(ResultSet rs, int row) throws SQLException {
        return new WorkerRegistration(scope(rs), rs.getString("phase"));
    }

    private static WorkerScope scope(ResultSet rs) throws SQLException {
        return new WorkerScope(rs.getString("tenant_id"), rs.getString("project_id"), rs.getString("team_id"));
    }
}
