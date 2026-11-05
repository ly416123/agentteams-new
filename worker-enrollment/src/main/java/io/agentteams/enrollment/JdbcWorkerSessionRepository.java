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
                SELECT agent.phase, scoped.tenant_id, scoped.project_id, scoped.team_id
                  FROM agents agent
                  JOIN worker_enrollment_agent_scopes scoped ON scoped.agent_id = agent.id
                 WHERE agent.id = ?
                   AND NULLIF(BTRIM(scoped.tenant_id), '') IS NOT NULL
                   AND NULLIF(BTRIM(scoped.project_id), '') IS NOT NULL
                   AND NULLIF(BTRIM(scoped.team_id), '') IS NOT NULL
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
    public void insertModelProxyCredential(ModelProxyCredentialRecord credential) {
        jdbc.update("""
                INSERT INTO worker_model_proxy_credentials(
                    id, session_id, agent_id, tenant_id, project_id, team_id,
                    token_sha256, model_credential_ref, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, credential.id(), credential.sessionId(), credential.agentId(),
                credential.scope().tenant(), credential.scope().project(), credential.scope().team(),
                credential.tokenSha256(), credential.modelCredentialRef(),
                Timestamp.from(credential.expiresAt()), Timestamp.from(credential.createdAt()));
    }

    @Override
    public Optional<WorkerSessionOwnership> findSessionForUpdate(UUID agentId, UUID sessionId) {
        return jdbc.query("""
                SELECT session.revoked_at, scoped.tenant_id, scoped.project_id, scoped.team_id
                  FROM agent_sessions session
                  JOIN worker_enrollment_agent_scopes scoped ON scoped.agent_id = session.agent_id
                 WHERE session.agent_id = ? AND session.id = ?
                   AND NULLIF(BTRIM(scoped.tenant_id), '') IS NOT NULL
                   AND NULLIF(BTRIM(scoped.project_id), '') IS NOT NULL
                   AND NULLIF(BTRIM(scoped.team_id), '') IS NOT NULL
                 FOR UPDATE OF session
                """, (rs, row) -> new WorkerSessionOwnership(scope(rs), rs.getTimestamp("revoked_at") != null),
                agentId, sessionId).stream().findFirst();
    }

    @Override
    public boolean revoke(UUID agentId, UUID sessionId, Instant revokedAt) {
        int revoked = jdbc.update("""
                UPDATE agent_sessions
                   SET revoked_at = COALESCE(revoked_at, ?)
                 WHERE agent_id = ? AND id = ?
                """, Timestamp.from(revokedAt), agentId, sessionId);
        if (revoked == 1) {
            jdbc.update("""
                    UPDATE worker_model_proxy_credentials
                       SET revoked_at = COALESCE(revoked_at, ?)
                     WHERE agent_id = ? AND session_id = ?
                    """, Timestamp.from(revokedAt), agentId, sessionId);
        }
        return revoked == 1;
    }

    private WorkerRegistration registration(ResultSet rs, int row) throws SQLException {
        return new WorkerRegistration(scope(rs), rs.getString("phase"));
    }

    private static WorkerScope scope(ResultSet rs) throws SQLException {
        return new WorkerScope(rs.getString("tenant_id"), rs.getString("project_id"), rs.getString("team_id"));
    }
}
