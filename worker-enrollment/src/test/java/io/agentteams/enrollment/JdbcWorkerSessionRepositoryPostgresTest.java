package io.agentteams.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcWorkerSessionRepositoryPostgresTest {
    private static final UUID AGENT_ID = UUID.fromString("66000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("66000000-0000-0000-0000-000000000002");
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
        jdbc.execute("CREATE ROLE worker_enrollment_test LOGIN PASSWORD 'enrollment-test-password'");
        jdbc.execute("GRANT CONNECT ON DATABASE enrollment_test TO worker_enrollment_test");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO worker_enrollment_test");
        jdbc.execute("GRANT SELECT ON TABLE agents TO worker_enrollment_test");
        jdbc.execute("GRANT SELECT, INSERT, UPDATE ON TABLE agent_sessions TO worker_enrollment_test");

        var restrictedDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "worker_enrollment_test", "enrollment-test-password");
        JdbcTemplate restrictedJdbc = new JdbcTemplate(restrictedDataSource);
        JdbcWorkerSessionRepository restrictedRepository = new JdbcWorkerSessionRepository(restrictedJdbc);
        assertThat(restrictedRepository.findAgent(AGENT_ID)).isPresent();
        restrictedRepository.insert(new WorkerSessionRepository.WorkerSessionRecord(
                SESSION_ID, AGENT_ID, TOKEN_HASH, Instant.now().plusSeconds(3600), Instant.now()));
        assertThat(restrictedRepository.revoke(AGENT_ID, SESSION_ID, Instant.now())).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> restrictedJdbc.queryForObject("SELECT count(*) FROM platform_identities", Long.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> restrictedJdbc.update("UPDATE agents SET phase = 'TERMINATED' WHERE id = ?", AGENT_ID))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private void insertAgent(UUID id, String phase, String metadata) {
        jdbc.update("INSERT INTO agents(id, name, phase, metadata) VALUES (?, ?, ?, ?::jsonb)",
                id, "agent-" + id, phase, metadata);
    }

    private void removeTestRoleIfPresent() {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'worker_enrollment_test')", Boolean.class);
        if (Boolean.TRUE.equals(exists)) {
            jdbc.execute("DROP OWNED BY worker_enrollment_test");
            jdbc.execute("DROP ROLE worker_enrollment_test");
        }
    }
}
