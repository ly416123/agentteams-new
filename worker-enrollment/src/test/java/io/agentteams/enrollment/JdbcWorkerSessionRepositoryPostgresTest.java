package io.agentteams.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcWorkerSessionRepositoryPostgresTest {
    private static final UUID AGENT_ID = UUID.fromString("66000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("66000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_AGENT_ID = UUID.fromString("66000000-0000-0000-0000-000000000007");
    private static final UUID OTHER_SESSION_ID = UUID.fromString("66000000-0000-0000-0000-000000000008");
    private static final String TOKEN_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("enrollment_test")
            .withUsername("test_admin")
            .withPassword("test_admin_password");

    private JdbcTemplate jdbc;
    private JdbcWorkerSessionRepository repository;

    @BeforeEach
    void createLegacyCompatibleSchema() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        removeTestRoleIfPresent();
        jdbc.execute("DROP TABLE IF EXISTS worker_model_proxy_credentials CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS agent_sessions");
        jdbc.execute("DROP TABLE IF EXISTS agents");
        jdbc.execute("DROP TABLE IF EXISTS platform_identities");
        jdbc.execute("""
                CREATE TABLE agents (
                    id UUID PRIMARY KEY,
                    name TEXT NOT NULL,
                    phase TEXT NOT NULL,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
                )
                """);
        jdbc.execute("""
                CREATE TABLE agent_sessions (
                    id UUID PRIMARY KEY,
                    agent_id UUID NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
                    token_sha256 CHAR(64) NOT NULL UNIQUE,
                    expires_at TIMESTAMPTZ NOT NULL,
                    revoked_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL
                )
                """);
        jdbc.execute("CREATE TABLE platform_identities (id UUID PRIMARY KEY, subject TEXT NOT NULL)");
        jdbc.execute("CREATE ROLE worker_enrollment LOGIN PASSWORD 'enrollment-test-password' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("CREATE ROLE agentteams_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        jdbc.execute("GRANT CONNECT ON DATABASE enrollment_test TO worker_enrollment");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO worker_enrollment");
        jdbc.execute("CREATE ROLE worker_enrollment_scope_owner LOGIN BYPASSRLS");
        applyEnrollmentRls();
        repository = new JdbcWorkerSessionRepository(jdbc);
    }

    @AfterEach
    void cleanupRestrictedRole() {
        removeTestRoleIfPresent();
    }

    @Test
    void readsOnlyAgentsWithCompleteTenantProjectAndTeamScope() {
        insertAgent(AGENT_ID, "READY", "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}");
        UUID incompleteId = UUID.fromString("66000000-0000-0000-0000-000000000003");
        insertAgent(incompleteId, "READY", "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\"}}");

        assertThat(repository.findAgent(AGENT_ID))
                .contains(new WorkerSessionRepository.WorkerRegistration(
                        new WorkerScope("tenant-a", "project-a", "team-a"), "READY"));
        assertThat(repository.findAgent(incompleteId)).isEmpty();
    }

    @Test
    void persistsOnlyTokenHashAndRevocationIsIdempotent() {
        insertAgent(AGENT_ID, "OFFLINE", "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}");
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant expiresAt = createdAt.plusSeconds(3600);
        repository.insert(new WorkerSessionRepository.WorkerSessionRecord(
                SESSION_ID, AGENT_ID, TOKEN_HASH, expiresAt, createdAt));

        assertThat(jdbc.queryForObject("SELECT token_sha256 FROM agent_sessions WHERE id = ?", String.class, SESSION_ID))
                .isEqualTo(TOKEN_HASH);
        assertThat(repository.findSessionForUpdate(AGENT_ID, SESSION_ID))
                .contains(new WorkerSessionRepository.WorkerSessionOwnership(
                        new WorkerScope("tenant-a", "project-a", "team-a"), false));

        Instant revokedAt = createdAt.plusSeconds(30);
        assertThat(repository.revoke(AGENT_ID, SESSION_ID, revokedAt)).isTrue();
        assertThat(repository.revoke(AGENT_ID, SESSION_ID, revokedAt.plusSeconds(10))).isTrue();
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM agent_sessions WHERE id = ?", java.sql.Timestamp.class, SESSION_ID)
                .toInstant()).isEqualTo(revokedAt);
        assertThat(repository.findSessionForUpdate(AGENT_ID, SESSION_ID))
                .contains(new WorkerSessionRepository.WorkerSessionOwnership(
                        new WorkerScope("tenant-a", "project-a", "team-a"), true));
    }

    @Test
    void sessionLookupIsBoundToBothAgentAndSessionIds() {
        insertAgent(AGENT_ID, "READY", "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}");
        repository.insert(new WorkerSessionRepository.WorkerSessionRecord(
                SESSION_ID, AGENT_ID, TOKEN_HASH, Instant.now().plusSeconds(3600), Instant.now()));

        assertThat(repository.findSessionForUpdate(UUID.fromString("66000000-0000-0000-0000-000000000099"), SESSION_ID))
                .isEmpty();
    }

    @Test
    void enrollmentDatabaseRoleCanUseOnlyTheGrantedTables() {
        insertAgent(AGENT_ID, "READY", "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}");
        grantEnrollmentAgentColumns();
        grantEnrollmentAgentScopeColumns();
        grantEnrollmentSessionColumns();

        var restrictedDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "worker_enrollment", "enrollment-test-password");
        JdbcTemplate restrictedJdbc = new JdbcTemplate(restrictedDataSource);
        JdbcWorkerSessionRepository restrictedRepository = new JdbcWorkerSessionRepository(restrictedJdbc);
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(restrictedDataSource));
        transaction.executeWithoutResult(status -> {
            restrictedRepository.setExecutionScope(new WorkerScope("tenant-a", "project-a", "team-a"));
            assertThat(restrictedRepository.findAgent(AGENT_ID)).isPresent();
            restrictedRepository.insert(new WorkerSessionRepository.WorkerSessionRecord(
                    SESSION_ID, AGENT_ID, TOKEN_HASH, Instant.now().plusSeconds(3600), Instant.now()));
            restrictedRepository.insertModelProxyCredential(new ModelProxyCredentialRecord(
                    UUID.fromString("66000000-0000-0000-0000-000000000004"), SESSION_ID, AGENT_ID,
                    new WorkerScope("tenant-a", "project-a", "team-a"),
                    "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd",
                    "vault://tenant-a/project-a/chatgpt", Instant.now().plusSeconds(3600), Instant.now()));
            assertThat(restrictedJdbc.queryForObject("""
                    SELECT revoked_at IS NULL FROM worker_model_proxy_credentials
                     WHERE agent_id = ? AND session_id = ?
                    """, Boolean.class, AGENT_ID, SESSION_ID)).isTrue();
            assertThat(restrictedRepository.revoke(AGENT_ID, SESSION_ID, Instant.now())).isTrue();
            assertThat(restrictedJdbc.queryForObject("""
                    SELECT revoked_at IS NOT NULL FROM worker_model_proxy_credentials
                     WHERE agent_id = ? AND session_id = ?
                    """, Boolean.class, AGENT_ID, SESSION_ID)).isTrue();
        });
        assertThat(jdbc.queryForObject("SELECT token_sha256 FROM worker_model_proxy_credentials WHERE session_id = ?",
                String.class, SESSION_ID)).isEqualTo(
                        "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd");
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM worker_model_proxy_credentials WHERE session_id = ?",
                Boolean.class, SESSION_ID)).isTrue();
        assertThatThrownBy(
                () -> restrictedJdbc.queryForObject("SELECT count(*) FROM platform_identities", Long.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                () -> restrictedJdbc.queryForObject("SELECT token_sha256 FROM agent_sessions", String.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> restrictedJdbc.queryForObject(
                "SELECT token_sha256 FROM worker_model_proxy_credentials", String.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> restrictedJdbc.queryForObject(
                "SELECT model_credential_ref FROM worker_model_proxy_credentials", String.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                () -> restrictedJdbc.update("UPDATE agents SET phase = 'TERMINATED' WHERE id = ?", AGENT_ID))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void enrollmentRoleCannotIssueModelProxyCredentialOutsideCallerScope() {
        insertAgent(AGENT_ID, "READY", "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}");
        insertAgent(OTHER_AGENT_ID, "READY", "{\"scope\":{\"tenant\":\"tenant-b\",\"project\":\"project-b\",\"team\":\"team-b\"}}");
        repository.insert(new WorkerSessionRepository.WorkerSessionRecord(OTHER_SESSION_ID, OTHER_AGENT_ID,
                "1111111111111111111111111111111111111111111111111111111111111111",
                Instant.now().plusSeconds(3600), Instant.now()));
        grantEnrollmentAgentColumns();
        grantEnrollmentAgentScopeColumns();
        grantEnrollmentSessionColumns();

        var restrictedDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "worker_enrollment", "enrollment-test-password");
        JdbcWorkerSessionRepository restrictedRepository = new JdbcWorkerSessionRepository(
                new JdbcTemplate(restrictedDataSource));
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(restrictedDataSource));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            restrictedRepository.setExecutionScope(new WorkerScope("tenant-a", "project-a", "team-a"));
            restrictedRepository.insertModelProxyCredential(new ModelProxyCredentialRecord(
                    UUID.fromString("66000000-0000-0000-0000-000000000009"), OTHER_SESSION_ID, OTHER_AGENT_ID,
                    new WorkerScope("tenant-b", "project-b", "team-b"),
                    "2222222222222222222222222222222222222222222222222222222222222222",
                    "vault://tenant-b/project-b/chatgpt", Instant.now().plusSeconds(3600), Instant.now()));
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void agentScopeTriggerCanSyncWithoutGrantingWriterDirectScopeTableAccess() {
        UUID writerAgentId = UUID.fromString("66000000-0000-0000-0000-000000000006");
        jdbc.execute("CREATE ROLE agent_writer LOGIN PASSWORD 'agent-writer-test-password' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO agent_writer");
        jdbc.execute("GRANT SELECT (id) ON agents TO agent_writer");
        jdbc.execute("GRANT INSERT (id, name, phase, metadata) ON agents TO agent_writer");
        jdbc.execute("GRANT UPDATE (metadata) ON agents TO agent_writer");
        jdbc.execute("CREATE POLICY agent_writer_read ON agents FOR SELECT TO agent_writer USING (true)");
        jdbc.execute("CREATE POLICY agent_writer_write ON agents FOR ALL TO agent_writer USING (true) WITH CHECK (true)");
        JdbcTemplate writer = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "agent_writer", "agent-writer-test-password"));

        writer.update(
                "INSERT INTO agents(id, name, phase, metadata) VALUES (?, ?, ?, ?::jsonb)",
                writerAgentId, "agent-writer", "READY",
                "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}");
        assertThat(jdbc.queryForMap("SELECT tenant_id, project_id, team_id "
                        + "FROM worker_enrollment_agent_scopes WHERE agent_id = ?", writerAgentId))
                .containsEntry("tenant_id", "tenant-a")
                .containsEntry("project_id", "project-a")
                .containsEntry("team_id", "team-a");
        writer.update("UPDATE agents SET metadata = ?::jsonb WHERE id = ?",
                "{\"scope\":{\"tenant\":\"tenant-b\",\"project\":\"project-b\",\"team\":\"team-b\"}}",
                writerAgentId);
        assertThat(jdbc.queryForMap("SELECT tenant_id, project_id, team_id "
                        + "FROM worker_enrollment_agent_scopes WHERE agent_id = ?", writerAgentId))
                .containsEntry("tenant_id", "tenant-b")
                .containsEntry("project_id", "project-b")
                .containsEntry("team_id", "team-b");
        writer.update("UPDATE agents SET metadata = '{}'::jsonb WHERE id = ?", writerAgentId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_enrollment_agent_scopes WHERE agent_id = ?",
                Long.class, writerAgentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls OR rolcanlogin "
                        + "FROM pg_roles WHERE rolname = 'worker_enrollment_scope_owner'", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('agent_writer', "
                        + "'worker_enrollment_agent_scopes', 'INSERT')", Boolean.class))
                .isFalse();
    }

    @Test
    void rowPoliciesFollowTransactionLocalTenantProjectAndTeamScope() {
        UUID tenantBAgent = UUID.fromString("66000000-0000-0000-0000-000000000004");
        jdbc.execute("DROP TRIGGER IF EXISTS worker_enrollment_sync_agent_scope ON agents");
        jdbc.execute("DELETE FROM worker_enrollment_agent_scopes");
        insertAgent(AGENT_ID, "READY", "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}");
        insertAgent(tenantBAgent, "READY", "{\"scope\":{\"tenant\":\"tenant-b\",\"project\":\"project-b\",\"team\":\"team-b\"}}");
        grantEnrollmentAgentColumns();
        grantEnrollmentAgentScopeColumns();
        grantEnrollmentSessionColumns();
        applyEnrollmentRls();
        assertThat(jdbc.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'worker_enrollment'", Boolean.class))
                .isFalse();

        try (HikariDataSource restrictedDataSource = new HikariDataSource()) {
            restrictedDataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            restrictedDataSource.setUsername("worker_enrollment");
            restrictedDataSource.setPassword("enrollment-test-password");
            restrictedDataSource.setMaximumPoolSize(1);
            restrictedDataSource.setMinimumIdle(0);
            JdbcTemplate restrictedJdbc = new JdbcTemplate(restrictedDataSource);
            JdbcWorkerSessionRepository restrictedRepository = new JdbcWorkerSessionRepository(restrictedJdbc);
            TransactionTemplate transaction =
                    new TransactionTemplate(new DataSourceTransactionManager(restrictedDataSource));

            assertThat(restrictedRepository.findAgent(AGENT_ID)).isEmpty();
            assertThatThrownBy(() -> restrictedRepository.setExecutionScope(
                    new WorkerScope("tenant-a", "project-a", "team-a")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> transaction.execute(status -> {
                restrictedRepository.setExecutionScope(new WorkerScope("tenant-a", "project-a", "team-a"));
                return restrictedJdbc.queryForObject(
                        "SELECT metadata->>'scope' FROM agents WHERE id = ?", String.class, AGENT_ID);
            }))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            Integer firstConnectionPid = transaction.execute(status -> {
                restrictedRepository.setExecutionScope(new WorkerScope("tenant-a", "project-a", "team-a"));
                assertThat(restrictedRepository.findAgent(AGENT_ID)).isPresent();
                assertThat(restrictedRepository.findAgent(tenantBAgent)).isEmpty();
                restrictedRepository.insert(new WorkerSessionRepository.WorkerSessionRecord(
                        SESSION_ID, AGENT_ID, TOKEN_HASH, Instant.now().plusSeconds(3600), Instant.now()));
                return restrictedJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            });
            Integer secondConnectionPid = transaction.execute(status -> {
                restrictedRepository.setExecutionScope(new WorkerScope("tenant-b", "project-b", "team-b"));
                assertThat(restrictedRepositoryScope(restrictedDataSource))
                        .containsEntry("tenant_id", "tenant-b")
                        .containsEntry("project_id", "project-b")
                        .containsEntry("team_id", "team-b");
                assertThat(restrictedRepository.findAgent(tenantBAgent)).isPresent();
                assertThat(restrictedRepository.findAgent(AGENT_ID)).isEmpty();
                return restrictedJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            });
            assertThat(secondConnectionPid).isEqualTo(firstConnectionPid);

            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                restrictedRepository.setExecutionScope(new WorkerScope("tenant-a", "project-a", "team-a"));
                restrictedRepository.insert(new WorkerSessionRepository.WorkerSessionRecord(
                        UUID.fromString("66000000-0000-0000-0000-000000000005"), tenantBAgent,
                        "1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        Instant.now().plusSeconds(3600), Instant.now()));
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
            transaction.executeWithoutResult(status -> {
                assertThat(restrictedJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class))
                        .isEqualTo(firstConnectionPid);
                assertThat(restrictedJdbc.queryForObject(
                        "SELECT NULLIF(current_setting('agentteams.tenant_id', true), '')", String.class))
                        .isNull();
                assertThat(restrictedRepository.findAgent(AGENT_ID)).isEmpty();
            });
        }
    }

    private void insertAgent(UUID id, String phase, String metadata) {
        jdbc.update("INSERT INTO agents(id, name, phase, metadata) VALUES (?, ?, ?, ?::jsonb)",
                id, "agent-" + id, phase, metadata);
    }

    private void grantEnrollmentSessionColumns() {
        jdbc.execute("GRANT SELECT (id, agent_id, revoked_at) ON TABLE agent_sessions TO worker_enrollment");
        jdbc.execute("GRANT INSERT (id, agent_id, token_sha256, expires_at, revoked_at, created_at) "
                + "ON TABLE agent_sessions TO worker_enrollment");
        jdbc.execute("GRANT UPDATE (revoked_at) ON TABLE agent_sessions TO worker_enrollment");
    }

    private void grantEnrollmentAgentColumns() {
        jdbc.execute("GRANT SELECT (id, phase) ON TABLE agents TO worker_enrollment");
    }

    private void applyEnrollmentRls() {
        PostgresScriptRunner.run(jdbc, "db/worker-enrollment-rls.sql");
    }

    private void grantEnrollmentAgentScopeColumns() {
        jdbc.execute("GRANT SELECT (agent_id, tenant_id, project_id, team_id) "
                + "ON TABLE worker_enrollment_agent_scopes TO worker_enrollment");
    }

    private static java.util.Map<String, Object> restrictedRepositoryScope(HikariDataSource dataSource) {
        return new JdbcTemplate(dataSource).queryForMap("""
                SELECT current_setting('agentteams.tenant_id', true) AS tenant_id,
                       current_setting('agentteams.project_id', true) AS project_id,
                       current_setting('agentteams.team_id', true) AS team_id
                """);
    }

    private void removeTestRoleIfPresent() {
        Boolean writerExists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agent_writer')", Boolean.class);
        if (Boolean.TRUE.equals(writerExists)) {
            jdbc.execute("DROP POLICY IF EXISTS agent_writer_write ON agents");
            jdbc.execute("DROP POLICY IF EXISTS agent_writer_read ON agents");
            jdbc.execute("DROP OWNED BY agent_writer");
            jdbc.execute("DROP ROLE agent_writer");
        }
        Boolean runtimeExists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentteams_runtime')", Boolean.class);
        if (Boolean.TRUE.equals(runtimeExists)) {
            jdbc.execute("DROP POLICY IF EXISTS control_plane_runtime_agents ON agents");
            jdbc.execute("DROP POLICY IF EXISTS control_plane_runtime_sessions ON agent_sessions");
            jdbc.execute("DROP OWNED BY agentteams_runtime");
            jdbc.execute("DROP ROLE agentteams_runtime");
        }
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'worker_enrollment')", Boolean.class);
        if (Boolean.TRUE.equals(exists)) {
            jdbc.execute("DROP POLICY IF EXISTS worker_enrollment_agents_scope ON agents");
            jdbc.execute("DROP POLICY IF EXISTS worker_enrollment_sessions_scope ON agent_sessions");
            jdbc.execute("DROP POLICY IF EXISTS worker_enrollment_model_proxy_credentials_insert_scope "
                    + "ON worker_model_proxy_credentials");
            jdbc.execute("DROP POLICY IF EXISTS worker_enrollment_model_proxy_credentials_select_scope "
                    + "ON worker_model_proxy_credentials");
            jdbc.execute("DROP POLICY IF EXISTS worker_enrollment_model_proxy_credentials_update_scope "
                    + "ON worker_model_proxy_credentials");
            jdbc.execute("DROP POLICY IF EXISTS worker_enrollment_agent_scopes_scope "
                    + "ON worker_enrollment_agent_scopes");
            jdbc.execute("ALTER TABLE agents DISABLE ROW LEVEL SECURITY");
            jdbc.execute("ALTER TABLE agent_sessions DISABLE ROW LEVEL SECURITY");
            jdbc.execute("ALTER TABLE worker_model_proxy_credentials DISABLE ROW LEVEL SECURITY");
            jdbc.execute("DROP TRIGGER IF EXISTS worker_enrollment_sync_agent_scope ON agents");
            jdbc.execute("DROP FUNCTION IF EXISTS worker_enrollment_sync_agent_scope()");
            jdbc.execute("DROP OWNED BY worker_enrollment");
            jdbc.execute("DROP ROLE worker_enrollment");
            jdbc.execute("DROP TABLE IF EXISTS worker_model_proxy_credentials CASCADE");
            jdbc.execute("DROP TABLE IF EXISTS worker_enrollment_agent_scopes");
            jdbc.execute("DROP OWNED BY worker_enrollment_scope_owner");
            jdbc.execute("DROP ROLE worker_enrollment_scope_owner");
        }
    }
}
