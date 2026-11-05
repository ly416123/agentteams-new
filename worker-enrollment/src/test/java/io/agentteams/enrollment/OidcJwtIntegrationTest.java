package io.agentteams.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OidcJwtIntegrationTest {
    private static final String ISSUER = "https://issuer.enrollment-test.invalid";
    private static final String AUDIENCE = "agentteams-enrollment-test";
    private static final UUID AGENT_ID = UUID.fromString("55000000-0000-0000-0000-000000000001");
    private static final String KEY_ID = "enrollment-test-key";
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("enrollment_http_test")
            .withUsername("enrollment_test")
            .withPassword("enrollment_test_password");
    private static HttpServer jwksServer;
    private static KeyPair keyPair;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keyPair = generator.generateKeyPair();
            RSAKey publicJwk = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                    .keyID(KEY_ID).algorithm(JWSAlgorithm.RS256).build();
            byte[] body = new JWKSet(publicJwk).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            jwksServer.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            jwksServer.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @AfterAll
    static void stopLocalJwksServer() {
        if (jwksServer != null) jwksServer.stop(0);
    }

    @DynamicPropertySource
    static void oidcProperties(DynamicPropertyRegistry properties) {
        properties.add("agentteams.enrollment.oidc.issuer-uri", () -> ISSUER);
        properties.add("agentteams.enrollment.oidc.jwk-set-uri",
                () -> "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks");
        properties.add("agentteams.enrollment.oidc.audience", () -> AUDIENCE);
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void createSchemaAndScopedWorker() {
        jdbc.execute("DROP TABLE IF EXISTS worker_model_proxy_credentials CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS agent_sessions");
        jdbc.execute("DROP TABLE IF EXISTS worker_enrollment_agent_scopes CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS agents");
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'worker_enrollment') THEN "
                + "CREATE ROLE worker_enrollment LOGIN PASSWORD 'enrollment-test-password' "
                + "NOSUPERUSER NOBYPASSRLS; END IF; END $$");
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentteams_runtime') THEN "
                + "CREATE ROLE agentteams_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS; "
                + "END IF; END $$");
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
        PostgresScriptRunner.run(jdbc, "db/worker-enrollment-rls.sql");
        insertAgent("tenant-a", "project-a", "team-a");
    }

    @Test
    void acceptsLocallySignedTokenWithExpectedIssuerAndAudience() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/agents/{agentId}/sessions", AGENT_ID)
                        .header("Authorization", "Bearer " + token(ISSUER, AUDIENCE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttlSeconds\":3600,\"modelCredentialRef\":\"vault://tenant-a/project-a/chatgpt\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String accessToken = body.required("accessToken").asText();
        String modelProxyToken = body.required("modelProxyToken").asText();
        UUID sessionId = UUID.fromString(body.required("sessionId").asText());
        UUID modelProxyCredentialId = UUID.fromString(body.required("modelProxyCredentialId").asText());
        assertThat(accessToken).startsWith("ats_");
        assertThat(modelProxyToken).startsWith("atp_").isNotEqualTo(accessToken);
        assertThat(jdbc.queryForObject("SELECT token_sha256 FROM agent_sessions WHERE id = ?", String.class, sessionId))
                .isEqualTo(WorkerSessionService.sha256(accessToken));
        assertThat(jdbc.queryForObject("SELECT token_sha256 FROM agent_sessions WHERE id = ?", String.class, sessionId))
                .isNotEqualTo(accessToken);
        assertThat(jdbc.queryForObject("SELECT token_sha256 FROM worker_model_proxy_credentials WHERE id = ?",
                String.class, modelProxyCredentialId)).isEqualTo(WorkerSessionService.sha256(modelProxyToken));
        assertThat(jdbc.queryForObject("SELECT model_credential_ref FROM worker_model_proxy_credentials WHERE id = ?",
                String.class, modelProxyCredentialId)).isEqualTo("vault://tenant-a/project-a/chatgpt");
        assertThat(jdbc.queryForObject("SELECT token_sha256 FROM worker_model_proxy_credentials WHERE id = ?",
                String.class, modelProxyCredentialId)).isNotEqualTo(modelProxyToken);

        mvc.perform(delete("/api/v1/agents/{agentId}/sessions/{sessionId}", AGENT_ID, sessionId)
                        .header("Authorization", "Bearer " + token(ISSUER, AUDIENCE)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM agent_sessions WHERE id = ?",
                Boolean.class, sessionId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM worker_model_proxy_credentials WHERE id = ?",
                Boolean.class, modelProxyCredentialId)).isTrue();
    }

    @Test
    void rejectsLocallySignedTokenWithWrongAudience() throws Exception {
        mvc.perform(post("/api/v1/agents/{agentId}/sessions", AGENT_ID)
                        .header("Authorization", "Bearer " + token(ISSUER, "other-api")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsLocallySignedTokenWithWrongIssuer() throws Exception {
        mvc.perform(post("/api/v1/agents/{agentId}/sessions", AGENT_ID)
                        .header("Authorization", "Bearer " + token("https://untrusted-issuer.invalid", AUDIENCE)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTenantScopeMismatchWithoutWritingASession() throws Exception {
        mvc.perform(post("/api/v1/agents/{agentId}/sessions", AGENT_ID)
                        .header("Authorization", "Bearer " + token(ISSUER, AUDIENCE, "tenant-b", "project-a", "team-a")))
                .andExpect(status().isNotFound());
        assertThat(sessionCount()).isZero();
    }

    @Test
    void rejectsProjectScopeMismatchWithoutWritingASession() throws Exception {
        mvc.perform(post("/api/v1/agents/{agentId}/sessions", AGENT_ID)
                        .header("Authorization", "Bearer " + token(ISSUER, AUDIENCE, "tenant-a", "project-b", "team-a")))
                .andExpect(status().isNotFound());
        assertThat(sessionCount()).isZero();
    }

    @Test
    void rejectsTeamScopeMismatchWithoutWritingASession() throws Exception {
        mvc.perform(post("/api/v1/agents/{agentId}/sessions", AGENT_ID)
                        .header("Authorization", "Bearer " + token(ISSUER, AUDIENCE, "tenant-a", "project-a", "team-b")))
                .andExpect(status().isNotFound());
        assertThat(sessionCount()).isZero();
    }

    private static String token(String issuer, String audience) throws Exception {
        return token(issuer, audience, "tenant-a", "project-a", "team-a");
    }

    private static String token(String issuer, String audience, String tenant, String project, String team)
            throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("local-test-operator")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("tenant", tenant)
                .claim("project", project)
                .claim("team", team)
                .claim("permissions", List.of("worker:session:manage"))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
        signed.sign(new RSASSASigner((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate()));
        return signed.serialize();
    }

    private long sessionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM agent_sessions", Long.class);
    }

    private void insertAgent(String tenant, String project, String team) {
        jdbc.update("INSERT INTO agents(id, name, phase, metadata) VALUES (?, ?, ?, ?::jsonb)", AGENT_ID,
                "enrollment-http-agent", "READY", "{\"scope\":{\"tenant\":\"" + tenant
                        + "\",\"project\":\"" + project + "\",\"team\":\"" + team + "\"}}");
    }
}
