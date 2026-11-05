package io.agentteams.credentialproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcActiveAttemptRepositoryPostgresTest {
    private static final UUID AGENT = id("88000000-0000-0000-0000-000000000001");
    private static final UUID TASK = id("88000000-0000-0000-0000-000000000002");
    private static final UUID ATTEMPT = id("88000000-0000-0000-0000-000000000003");
    private static final UUID LEASE = id("88000000-0000-0000-0000-000000000004");
    private static final UUID PROXY_CREDENTIAL_ID = id("88000000-0000-0000-0000-000000000005");
    private static final UUID SESSION_ID = id("88000000-0000-0000-0000-000000000006");
    private static final String PROXY_TOKEN = "atp_" + "A".repeat(43);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("credential_proxy_test")
            .withUsername("test_admin")
            .withPassword("test_admin_password");

    private JdbcTemplate jdbc;
    private JdbcActiveAttemptRepository repository;
    private HttpServer modelProvider;
    private java.util.concurrent.ScheduledExecutorService streamMonitor;
    private Path providerCredentialRoot;

    @AfterEach
    void stopIntegrationResources() throws Exception {
        if (modelProvider != null) modelProvider.stop(0);
        if (streamMonitor != null) streamMonitor.shutdownNow();
        if (providerCredentialRoot != null) {
            try (var paths = Files.walk(providerCredentialRoot)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                });
            }
        }
    }

    @BeforeEach
    void createSchemaAndInstallNarrowFunction() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("DROP FUNCTION IF EXISTS credential_proxy_revalidate(UUID) CASCADE");
        jdbc.execute("DROP FUNCTION IF EXISTS credential_proxy_authenticate(TEXT) CASCADE");
        jdbc.execute("DROP FUNCTION IF EXISTS credential_proxy_active_attempts(UUID) CASCADE");
        jdbc.execute("DROP FUNCTION IF EXISTS credential_proxy_active_attempts(UUID, TEXT, TEXT, TEXT) CASCADE");
        jdbc.execute("DROP ROLE IF EXISTS credential_proxy_test");
        jdbc.execute("DROP TABLE IF EXISTS worker_model_proxy_credentials, agent_sessions, worker_enrollment_agent_scopes CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS task_assignments, task_attempts, agent_leases, resource_scopes CASCADE");
        jdbc.execute("DO $$ BEGIN "
                + "IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'credential_proxy_runtime') THEN EXECUTE 'DROP OWNED BY credential_proxy_runtime'; END IF; "
                + "IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'worker_enrollment') THEN EXECUTE 'DROP OWNED BY worker_enrollment'; END IF; "
                + "IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentteams_runtime') THEN EXECUTE 'DROP OWNED BY agentteams_runtime'; END IF; "
                + "IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'credential_proxy_query') THEN EXECUTE 'DROP OWNED BY credential_proxy_query'; END IF; "
                + "IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'credential_proxy_query_owner') THEN EXECUTE 'DROP OWNED BY credential_proxy_query_owner'; END IF; "
                + "IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'credential_proxy_query') AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'credential_proxy_runtime') THEN EXECUTE 'REVOKE credential_proxy_query FROM credential_proxy_runtime'; END IF; END $$");
        jdbc.execute("DROP ROLE IF EXISTS credential_proxy_runtime");
        jdbc.execute("DROP ROLE IF EXISTS worker_enrollment");
        jdbc.execute("DROP ROLE IF EXISTS agentteams_runtime");
        jdbc.execute("DROP ROLE IF EXISTS credential_proxy_query");
        jdbc.execute("DROP ROLE IF EXISTS credential_proxy_query_owner");
        jdbc.execute("CREATE TABLE task_attempts (id UUID PRIMARY KEY, task_id UUID NOT NULL, lease_id UUID NOT NULL, phase TEXT NOT NULL, lease_expires_at TIMESTAMPTZ NOT NULL)");
        jdbc.execute("CREATE TABLE task_assignments (id UUID PRIMARY KEY, task_id UUID NOT NULL, attempt_id UUID NOT NULL, agent_id UUID NOT NULL, phase TEXT NOT NULL, released_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE agent_leases (id UUID PRIMARY KEY, agent_id UUID NOT NULL, task_attempt_id UUID NOT NULL, expires_at TIMESTAMPTZ NOT NULL, released_at TIMESTAMPTZ, status TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE resource_scopes (resource_type TEXT NOT NULL, resource_id UUID NOT NULL, tenant_id TEXT NOT NULL, project_id TEXT NOT NULL, team TEXT NOT NULL, PRIMARY KEY(resource_type, resource_id))");
        jdbc.execute("CREATE TABLE agent_sessions (id UUID PRIMARY KEY, agent_id UUID NOT NULL, expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE worker_enrollment_agent_scopes (agent_id UUID PRIMARY KEY, tenant_id TEXT NOT NULL, project_id TEXT NOT NULL, team_id TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE worker_model_proxy_credentials (id UUID PRIMARY KEY, session_id UUID NOT NULL, agent_id UUID NOT NULL, tenant_id TEXT NOT NULL, project_id TEXT NOT NULL, team_id TEXT NOT NULL, token_sha256 CHAR(64) NOT NULL UNIQUE, model_credential_ref TEXT NOT NULL, expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL)");
        jdbc.execute("ALTER TABLE agent_sessions ENABLE ROW LEVEL SECURITY");
        jdbc.execute("ALTER TABLE worker_enrollment_agent_scopes ENABLE ROW LEVEL SECURITY");
        jdbc.execute("ALTER TABLE worker_model_proxy_credentials ENABLE ROW LEVEL SECURITY");
        jdbc.execute("CREATE ROLE credential_proxy_query NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("CREATE ROLE credential_proxy_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("CREATE ROLE worker_enrollment NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("CREATE ROLE agentteams_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("CREATE FUNCTION public.credential_proxy_active_attempts(UUID, TEXT, TEXT, TEXT) "
                + "RETURNS INTEGER LANGUAGE SQL STABLE SECURITY DEFINER AS 'SELECT 1'");
        jdbc.execute("GRANT EXECUTE ON FUNCTION public.credential_proxy_active_attempts(UUID, TEXT, TEXT, TEXT) "
                + "TO PUBLIC, credential_proxy_query, credential_proxy_runtime, worker_enrollment, agentteams_runtime");
        jdbc.execute("GRANT credential_proxy_query TO credential_proxy_runtime");
        String script = new String(new ClassPathResource("db/credential-proxy.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        executePostgresScript(script);
        repository = new JdbcActiveAttemptRepository(jdbc);
    }

    @Test
    void resolvesOnlyActiveAttemptInsideAuthenticatedWorkerAndTaskScope() throws Exception {
        insertScope("WORKER", AGENT, "tenant-a", "project-a", "team-a");
        insertScope("TASK", TASK, "tenant-a", "project-a", "team-a");
        insertProxyCredential();
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
    void returnsEveryConcurrentAttemptSoResolverCanFailClosed() throws Exception {
        insertScope("WORKER", AGENT, "tenant-a", "project-a", "team-a");
        insertProxyCredential();
        UUID secondTask = id("88000000-0000-0000-0000-000000000032");
        insertScope("TASK", TASK, "tenant-a", "project-a", "team-a");
        insertScope("TASK", secondTask, "tenant-a", "project-a", "team-a");
        insertActive(AGENT, TASK, ATTEMPT, LEASE);
        insertActive(AGENT, secondTask, id("88000000-0000-0000-0000-000000000033"),
                id("88000000-0000-0000-0000-000000000034"));

        assertThat(repository.findActiveAttempts(worker())).hasSize(2);
    }

    @Test
    void mismatchedWorkerScopeHasNoVisibleActiveAttempts() throws Exception {
        insertScope("WORKER", AGENT, "tenant-a", "project-a", "team-a");
        insertScope("TASK", TASK, "tenant-a", "project-a", "team-a");
        insertProxyCredential();
        insertActive(AGENT, TASK, ATTEMPT, LEASE);

        var otherTenantWorker = new AuthenticatedWorker(id("88000000-0000-0000-0000-000000000099"), AGENT,
                "tenant-b", "project-a", "team-a", "model-credential-a");
        assertThat(repository.findActiveAttempts(otherTenantWorker)).isEmpty();
    }

    @Test
    void attemptLookupDerivesScopeFromTheProxyCredentialInsteadOfCallerArguments() throws Exception {
        insertScope("WORKER", AGENT, "tenant-a", "project-a", "team-a");
        insertProxyCredential();
        insertScope("TASK", TASK, "tenant-a", "project-a", "team-a");
        insertActive(AGENT, TASK, ATTEMPT, LEASE);
        UUID otherTask = id("88000000-0000-0000-0000-000000000042");
        insertScope("WORKER", id("88000000-0000-0000-0000-000000000041"), "tenant-b", "project-b", "team-b");
        insertScope("TASK", otherTask, "tenant-b", "project-b", "team-b");
        insertActive(id("88000000-0000-0000-0000-000000000041"), otherTask,
                id("88000000-0000-0000-0000-000000000043"), id("88000000-0000-0000-0000-000000000044"));
        insertProxyCredentialForOtherAgent(id("88000000-0000-0000-0000-000000000041"),
                id("88000000-0000-0000-0000-000000000045"), id("88000000-0000-0000-0000-000000000046"),
                "tenant-b", "project-b", "team-b", "atp_" + "B".repeat(43));
        jdbc.execute("CREATE ROLE credential_proxy_test LOGIN PASSWORD 'proxy-test-password'");
        jdbc.execute("GRANT credential_proxy_query TO credential_proxy_test");
        var restricted = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "credential_proxy_test", "proxy-test-password"));

        var rows = restricted.query("SELECT task_id FROM public.credential_proxy_active_attempts(?)",
                (rs, row) -> rs.getObject("task_id", UUID.class), PROXY_CREDENTIAL_ID);

        assertThat(rows).containsExactly(TASK);
        assertThat(restricted.queryForObject("""
                SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'public' AND p.proname = 'credential_proxy_active_attempts'
                   AND pg_get_function_identity_arguments(p.oid) = 'p_proxy_credential_id uuid'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT to_regprocedure("
                + "'public.credential_proxy_active_attempts(uuid,text,text,text)') IS NOT NULL", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT has_function_privilege('credential_proxy_query', "
                + "'public.credential_proxy_active_attempts(uuid,text,text,text)', 'EXECUTE')", Boolean.class))
                .isFalse();
        for (String runtimeRole : new String[] {
                "credential_proxy_runtime", "worker_enrollment", "agentteams_runtime"}) {
            assertThat(jdbc.queryForObject("SELECT has_function_privilege(?, "
                    + "'public.credential_proxy_active_attempts(uuid,text,text,text)', 'EXECUTE')",
                    Boolean.class, runtimeRole)).as(runtimeRole).isFalse();
        }
        assertThatThrownBy(() -> restricted.queryForObject("SELECT public.credential_proxy_active_attempts("
                + "?::uuid, ?::text, ?::text, ?::text)", Integer.class, AGENT, "tenant-b", "project-b", "team-b"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void proxyDatabaseRoleCanOnlyExecuteTheNarrowFunction() throws Exception {
        insertScope("WORKER", AGENT, "tenant-a", "project-a", "team-a");
        insertScope("TASK", TASK, "tenant-a", "project-a", "team-a");
        insertActive(AGENT, TASK, ATTEMPT, LEASE);
        insertProxyCredential();
        jdbc.execute("CREATE ROLE credential_proxy_test LOGIN PASSWORD 'proxy-test-password'");
        jdbc.execute("GRANT credential_proxy_query TO credential_proxy_test");

        var restricted = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "credential_proxy_test", "proxy-test-password"));
        var restrictedRepository = new JdbcActiveAttemptRepository(restricted);
        assertThat(restrictedRepository.findActiveAttempts(worker())).hasSize(1);
        assertThat(new JdbcModelProxyCredentialAuthenticator(restricted).authenticate(PROXY_TOKEN))
                .contains(new AuthenticatedWorker(PROXY_CREDENTIAL_ID, AGENT, "tenant-a", "project-a", "team-a",
                        "vault://tenant-a/project-a/chatgpt"));
        assertThat(new JdbcModelProxyCredentialAuthenticator(restricted).revalidate(
                new AuthenticatedWorker(PROXY_CREDENTIAL_ID, AGENT, "tenant-a", "project-a", "team-a",
                        "vault://tenant-a/project-a/chatgpt"))).isPresent();
        assertThatThrownBy(() -> restricted.queryForObject("SELECT count(*) FROM task_assignments", Long.class))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> restricted.queryForObject("SELECT token_sha256 FROM worker_model_proxy_credentials",
                String.class)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void deniesProxyCredentialWhenTokenSessionIsRevokedExpiredOrScopeChanges() throws Exception {
        insertProxyCredential();
        var authenticator = new JdbcModelProxyCredentialAuthenticator(jdbc);

        AuthenticatedWorker authenticated = new AuthenticatedWorker(
                PROXY_CREDENTIAL_ID, AGENT, "tenant-a", "project-a", "team-a", "vault://tenant-a/project-a/chatgpt");
        assertThat(authenticator.authenticate(PROXY_TOKEN)).contains(authenticated);
        assertThat(authenticator.revalidate(authenticated)).contains(authenticated);
        assertThat(authenticator.authenticate("invalid-token")).isEmpty();

        jdbc.update("UPDATE worker_model_proxy_credentials SET model_credential_ref = 'vault://tenant-a/project-a/rotated' WHERE id = ?",
                PROXY_CREDENTIAL_ID);
        assertThat(authenticator.revalidate(authenticated)).get()
                .extracting(AuthenticatedWorker::modelCredentialRef)
                .isEqualTo("vault://tenant-a/project-a/rotated");
        jdbc.update("UPDATE worker_model_proxy_credentials SET model_credential_ref = ? WHERE id = ?",
                authenticated.modelCredentialRef(), PROXY_CREDENTIAL_ID);

        jdbc.update("UPDATE worker_model_proxy_credentials SET revoked_at = statement_timestamp() WHERE id = ?",
                PROXY_CREDENTIAL_ID);
        assertThat(authenticator.authenticate(PROXY_TOKEN)).isEmpty();
        assertThat(authenticator.revalidate(authenticated)).isEmpty();
        jdbc.update("UPDATE worker_model_proxy_credentials SET revoked_at = NULL, expires_at = now() - interval '1 second' WHERE id = ?",
                PROXY_CREDENTIAL_ID);
        assertThat(authenticator.authenticate(PROXY_TOKEN)).isEmpty();
        assertThat(authenticator.revalidate(authenticated)).isEmpty();
        jdbc.update("UPDATE worker_model_proxy_credentials SET expires_at = now() + interval '10 minutes' WHERE id = ?",
                PROXY_CREDENTIAL_ID);
        jdbc.update("UPDATE worker_enrollment_agent_scopes SET tenant_id = 'tenant-b' WHERE agent_id = ?", AGENT);
        assertThat(authenticator.authenticate(PROXY_TOKEN)).isEmpty();
        assertThat(authenticator.revalidate(authenticated)).isEmpty();
        jdbc.update("UPDATE worker_enrollment_agent_scopes SET tenant_id = 'tenant-a' WHERE agent_id = ?", AGENT);
        jdbc.update("UPDATE agent_sessions SET revoked_at = statement_timestamp() WHERE id = ?", SESSION_ID);
        assertThat(authenticator.authenticate(PROXY_TOKEN)).isEmpty();
        assertThat(authenticator.revalidate(authenticated)).isEmpty();
    }

    @Test
    void httpRequestUsesDatabaseAttemptScopeAndForwardsOnlyTheResolvedProviderCredential() throws Exception {
        insertScope("WORKER", AGENT, "tenant-a", "project-a", "team-a");
        insertScope("TASK", TASK, "tenant-a", "project-a", "team-a");
        insertProxyCredential();
        insertActive(AGENT, TASK, ATTEMPT, LEASE);
        Path providerToken = createProviderCredential("tenant-a", "project-a", "team-a", "provider-token-a");
        HttpProxyFixture fixture = startHttpProxy(providerToken);

        MvcResult result = fixture.mvc().perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + PROXY_TOKEN)
                        .content("{\"model\":\"gpt-5.6-luna\",\"input\":[]}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        fixture.mvc().perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string("data: forwarded\n\n"));
        assertThat(fixture.upstreamCalls().get()).isEqualTo(1);
        assertThat(fixture.upstreamAuthorization().get()).isEqualTo("Bearer provider-token-a");
    }

    @Test
    void httpRequestCannotUseAttemptFromAnotherTenantAndNeverCallsProvider() throws Exception {
        insertScope("WORKER", AGENT, "tenant-a", "project-a", "team-a");
        insertScope("TASK", TASK, "tenant-b", "project-a", "team-a");
        insertProxyCredential();
        insertActive(AGENT, TASK, ATTEMPT, LEASE);
        Path providerToken = createProviderCredential("tenant-a", "project-a", "team-a", "provider-token-a");
        HttpProxyFixture fixture = startHttpProxy(providerToken);

        fixture.mvc().perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + PROXY_TOKEN)
                        .content("{\"model\":\"gpt-5.6-luna\",\"input\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"model_proxy_request_denied\"}"));

        assertThat(fixture.upstreamCalls().get()).isZero();
    }

    @Test
    void httpRequestRejectsAmbiguousConcurrentAttemptsBeforeCallingProvider() throws Exception {
        insertScope("WORKER", AGENT, "tenant-a", "project-a", "team-a");
        insertScope("TASK", TASK, "tenant-a", "project-a", "team-a");
        UUID secondTask = id("88000000-0000-0000-0000-000000000052");
        insertScope("TASK", secondTask, "tenant-a", "project-a", "team-a");
        insertProxyCredential();
        insertActive(AGENT, TASK, ATTEMPT, LEASE);
        insertActive(AGENT, secondTask, id("88000000-0000-0000-0000-000000000053"),
                id("88000000-0000-0000-0000-000000000054"));
        Path providerToken = createProviderCredential("tenant-a", "project-a", "team-a", "provider-token-a");
        HttpProxyFixture fixture = startHttpProxy(providerToken);

        fixture.mvc().perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + PROXY_TOKEN)
                        .content("{\"model\":\"gpt-5.6-luna\",\"input\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"model_proxy_request_denied\"}"));

        assertThat(fixture.upstreamCalls().get()).isZero();
    }

    private Path createProviderCredential(String tenant, String project, String team, String value) throws Exception {
        providerCredentialRoot = Files.createTempDirectory("credential-proxy-provider-");
        Path token = providerCredentialRoot.resolve(tenant).resolve(project).resolve(team).resolve("provider-token");
        Files.createDirectories(token.getParent());
        Files.writeString(token, value, StandardCharsets.UTF_8);
        jdbc.update("UPDATE worker_model_proxy_credentials SET model_credential_ref = ? WHERE id = ?",
                "file:" + tenant + "/" + project + "/" + team + "/provider-token", PROXY_CREDENTIAL_ID);
        return token;
    }

    private HttpProxyFixture startHttpProxy(Path providerToken) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        modelProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelProvider.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = "data: forwarded\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        modelProvider.start();
        streamMonitor = Executors.newSingleThreadScheduledExecutor();
        jdbc.execute("CREATE ROLE credential_proxy_test LOGIN PASSWORD 'proxy-test-password'");
        jdbc.execute("GRANT credential_proxy_query TO credential_proxy_test");
        JdbcTemplate proxyRuntime = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "credential_proxy_test", "proxy-test-password"));
        ModelProxyService service = new ModelProxyService(
                new JdbcModelProxyCredentialAuthenticator(proxyRuntime),
                new AttemptExecutionContextResolver(new JdbcActiveAttemptRepository(proxyRuntime), Clock.systemUTC()),
                new FileCredentialSource(providerToken.getParent().getParent().getParent().getParent()),
                URI.create("http://127.0.0.1:" + modelProvider.getAddress().getPort() + "/v1/responses"),
                Set.of("gpt-5.6-luna"), HttpClient.newHttpClient(), Clock.systemUTC(), streamMonitor,
                new Semaphore(4));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ModelProxyController(service)).build();
        return new HttpProxyFixture(mvc, calls, authorization);
    }

    private record HttpProxyFixture(MockMvc mvc, AtomicInteger upstreamCalls,
            AtomicReference<String> upstreamAuthorization) { }

    private void insertScope(String type, UUID id, String tenant, String project, String team) {
        jdbc.update("INSERT INTO resource_scopes(resource_type, resource_id, tenant_id, project_id, team) VALUES (?, ?, ?, ?, ?)",
                type, id, tenant, project, team);
    }

    private void insertProxyCredential() throws Exception {
        jdbc.update("INSERT INTO worker_enrollment_agent_scopes VALUES (?, 'tenant-a', 'project-a', 'team-a')", AGENT);
        jdbc.update("INSERT INTO agent_sessions VALUES (?, ?, now() + interval '10 minutes', NULL)", SESSION_ID, AGENT);
        jdbc.update("INSERT INTO worker_model_proxy_credentials VALUES (?, ?, ?, 'tenant-a', 'project-a', 'team-a', ?, 'vault://tenant-a/project-a/chatgpt', now() + interval '10 minutes', NULL, now())",
                PROXY_CREDENTIAL_ID, SESSION_ID, AGENT, sha256(PROXY_TOKEN));
    }

    private void insertProxyCredentialForOtherAgent(UUID agent, UUID session, UUID credential,
            String tenant, String project, String team, String token) throws Exception {
        jdbc.update("INSERT INTO agent_sessions VALUES (?, ?, now() + interval '10 minutes', NULL)", session, agent);
        jdbc.update("INSERT INTO worker_enrollment_agent_scopes VALUES (?, ?, ?, ?)", agent, tenant, project, team);
        jdbc.update("INSERT INTO worker_model_proxy_credentials VALUES (?, ?, ?, ?, ?, ?, ?, 'vault://tenant-b/project-b/chatgpt', now() + interval '10 minutes', NULL, now())",
                credential, session, agent, tenant, project, team, sha256(token));
    }

    private void insertActive(UUID agent, UUID task, UUID attempt, UUID lease) {
        jdbc.update("INSERT INTO agent_leases VALUES (?, ?, ?, now() + interval '10 minutes', NULL, 'ACTIVE')",
                lease, agent, attempt);
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

    private static String sha256(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
    }

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
