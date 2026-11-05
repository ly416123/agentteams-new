package io.agentteams.credentialproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcActiveAttemptRepositoryPostgresTest {
    private static final UUID AGENT = id("88000000-0000-0000-0000-000000000001");
    private static final UUID TASK = id("88000000-0000-0000-0000-000000000002");
    private static final UUID ATTEMPT = id("88000000-0000-0000-0000-000000000003");
    private static final UUID LEASE = id("88000000-0000-0000-0000-000000000004");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("credential_proxy_test")
            .withUsername("test_admin")
            .withPassword("test_admin_password");

    private JdbcTemplate jdbc;
    private JdbcActiveAttemptRepository repository;

    @BeforeEach
    void createSchemaAndInstallNarrowFunction() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("DROP FUNCTION IF EXISTS credential_proxy_active_attempts(UUID, TEXT, TEXT, TEXT) CASCADE");
        jdbc.execute("DROP ROLE IF EXISTS credential_proxy_test");
        jdbc.execute("DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'credential_proxy_query') THEN EXECUTE 'DROP OWNED BY credential_proxy_query'; END IF; IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'credential_proxy_query_owner') THEN EXECUTE 'DROP OWNED BY credential_proxy_query_owner'; END IF; END $$");
        jdbc.execute("DROP ROLE IF EXISTS credential_proxy_query");
        jdbc.execute("DROP ROLE IF EXISTS credential_proxy_query_owner");
        jdbc.execute("DROP TABLE IF EXISTS task_assignments, task_attempts, agent_leases, resource_scopes CASCADE");
        jdbc.execute("CREATE TABLE task_attempts (id UUID PRIMARY KEY, task_id UUID NOT NULL, lease_id UUID NOT NULL, phase TEXT NOT NULL, lease_expires_at TIMESTAMPTZ NOT NULL)");
        jdbc.execute("CREATE TABLE task_assignments (id UUID PRIMARY KEY, task_id UUID NOT NULL, attempt_id UUID NOT NULL, agent_id UUID NOT NULL, phase TEXT NOT NULL, released_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE agent_leases (id UUID PRIMARY KEY, agent_id UUID NOT NULL, task_attempt_id UUID NOT NULL, expires_at TIMESTAMPTZ NOT NULL, released_at TIMESTAMPTZ, status TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE resource_scopes (resource_type TEXT NOT NULL, resource_id UUID NOT NULL, tenant_id TEXT NOT NULL, project_id TEXT NOT NULL, team TEXT NOT NULL, PRIMARY KEY(resource_type, resource_id))");
        String script = new String(new ClassPathResource("db/credential-proxy.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        executePostgresScript(script);
        repository = new JdbcActiveAttemptRepository(jdbc);
    }

    @Test
    void resolvesOnlyActiveAttemptInsideAuthenticatedWorkerAndTaskScope() {
        insertScope("WORKER", AGENT, "tenant-a", "project-a", "team-a");
        insertScope("TASK", TASK, "tenant-a", "project-a", "team-a");
        insertActive(AGENT, TASK, ATTEMPT, LEASE);
        insertScope("TASK", id("88000000-0000-0000-0000-000000000012"), "tenant-b", "project-a", "team-a");
        insertActive(AGENT, id("88000000-0000-0000-0000-000000000012"),
                id("88000000-0000-0000-0000-000000000013"), id("88000000-0000-0000-0000-000000000014"));
        insertExpired(AGENT, id("88000000-0000-0000-0000-000000000022"),
                id("88000000-0000-0000-0000-000000000023"), id("88000000-0000-0000-0000-000000000024"));

        var candidates = repository.findActiveAttempts(worker());

        assertThat(candidates).containsExactly(new ActiveAttemptCandidate(AGENT, "tenant-a", "project-a", "team-a",
                TASK, ATTEMPT, LEASE, candidates.getFirst().leaseExpiresAt(), true, true, true));
    }

    @Test
    void returnsEveryConcurrentAttemptSoResolverCanFailClosed() {
        insertScope("WORKER", AGENT, "tenant-a", "project-a", "team-a");
        UUID secondTask = id("88000000-0000-0000-0000-000000000032");
        insertScope("TASK", TASK, "tenant-a", "project-a", "team-a");
        insertScope("TASK", secondTask, "tenant-a", "project-a", "team-a");
        insertActive(AGENT, TASK, ATTEMPT, LEASE);
        insertActive(AGENT, secondTask, id("88000000-0000-0000-0000-000000000033"),
                id("88000000-0000-0000-0000-000000000034"));

        assertThat(repository.findActiveAttempts(worker())).hasSize(2);
    }

    @Test
    void mismatchedWorkerScopeHasNoVisibleActiveAttempts() {
        insertScope("WORKER", AGENT, "tenant-a", "project-a", "team-a");
        insertScope("TASK", TASK, "tenant-a", "project-a", "team-a");
        insertActive(AGENT, TASK, ATTEMPT, LEASE);

        var otherTenantWorker = new AuthenticatedWorker(id("88000000-0000-0000-0000-000000000099"), AGENT,
                "tenant-b", "project-a", "team-a", "model-credential-a");
        assertThat(repository.findActiveAttempts(otherTenantWorker)).isEmpty();
    }

    @Test
    void proxyDatabaseRoleCanOnlyExecuteTheNarrowFunction() throws Exception {
        insertScope("WORKER", AGENT, "tenant-a", "project-a", "team-a");
        insertScope("TASK", TASK, "tenant-a", "project-a", "team-a");
        insertActive(AGENT, TASK, ATTEMPT, LEASE);
        jdbc.execute("CREATE ROLE credential_proxy_test LOGIN PASSWORD 'proxy-test-password'");
        jdbc.execute("GRANT credential_proxy_query TO credential_proxy_test");

        var restricted = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "credential_proxy_test", "proxy-test-password"));
        var restrictedRepository = new JdbcActiveAttemptRepository(restricted);
        assertThat(restrictedRepository.findActiveAttempts(worker())).hasSize(1);
        assertThatThrownBy(() -> restricted.queryForObject("SELECT count(*) FROM task_assignments", Long.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private void insertScope(String type, UUID id, String tenant, String project, String team) {
        jdbc.update("INSERT INTO resource_scopes(resource_type, resource_id, tenant_id, project_id, team) VALUES (?, ?, ?, ?, ?)",
                type, id, tenant, project, team);
    }

    private void insertActive(UUID agent, UUID task, UUID attempt, UUID lease) {
        jdbc.update("INSERT INTO agent_leases VALUES (?, ?, ?, now(), NULL, 'ACTIVE')", lease, agent, attempt);
        jdbc.update("INSERT INTO task_attempts VALUES (?, ?, ?, 'RUNNING', now() + interval '10 minutes')",
                attempt, task, lease);
        jdbc.update("INSERT INTO task_assignments VALUES (?, ?, ?, ?, 'RUNNING', NULL)",
                id("99000000-0000-0000-0000-" + String.format("%012d", Math.abs(task.hashCode()))), task, attempt, agent);
    }

    private void insertExpired(UUID agent, UUID task, UUID attempt, UUID lease) {
        insertScope("TASK", task, "tenant-a", "project-a", "team-a");
        jdbc.update("INSERT INTO agent_leases VALUES (?, ?, ?, now() - interval '2 minutes', now() - interval '1 minute', 'RELEASED')",
                lease, agent, attempt);
        jdbc.update("INSERT INTO task_attempts VALUES (?, ?, ?, 'SUCCEEDED', now() - interval '1 minute')",
                attempt, task, lease);
        jdbc.update("INSERT INTO task_assignments VALUES (?, ?, ?, ?, 'SUCCEEDED', now() - interval '1 minute')",
                id("99000000-0000-0000-0000-000000000022"), task, attempt, agent);
    }

    private static AuthenticatedWorker worker() {
        return new AuthenticatedWorker(id("88000000-0000-0000-0000-000000000005"), AGENT,
                "tenant-a", "project-a", "team-a", "model-credential-a");
    }

    private static UUID id(String value) { return UUID.fromString(value); }

    private void executePostgresScript(String script) {
        StringBuilder statement = new StringBuilder();
        boolean dollarQuoted = false;
        for (int index = 0; index < script.length(); index++) {
            if (script.startsWith("$$", index)) {
                dollarQuoted = !dollarQuoted;
                statement.append("$$");
                index++;
            } else if (script.charAt(index) == ';' && !dollarQuoted) {
                if (!statement.toString().isBlank()) jdbc.execute(statement.toString());
                statement.setLength(0);
            } else {
                statement.append(script.charAt(index));
            }
        }
        if (!statement.toString().isBlank()) jdbc.execute(statement.toString());
    }
}
