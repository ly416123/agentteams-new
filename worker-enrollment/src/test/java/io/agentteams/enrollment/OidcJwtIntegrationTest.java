package io.agentteams.enrollment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OidcJwtIntegrationTest {
    private static final String ISSUER = "https://issuer.enrollment-test.invalid";
    private static final String AUDIENCE = "agentteams-enrollment-test";
    private static final UUID AGENT_ID = UUID.fromString("55000000-0000-0000-0000-000000000001");
    private static final String KEY_ID = "enrollment-test-key";
    private static HttpServer jwksServer;
    private static KeyPair keyPair;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WorkerSessionService sessions;

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
    }

    @Test
    void acceptsLocallySignedTokenWithExpectedIssuerAndAudience() throws Exception {
        UUID sessionId = UUID.fromString("55000000-0000-0000-0000-000000000002");
        when(sessions.issue(eq(AGENT_ID), any(WorkerScope.class), eq(Set.of("worker:session:manage")), any()))
                .thenReturn(new IssuedWorkerSession(sessionId, AGENT_ID, "ats_test-token", Instant.now().plusSeconds(3600)));

        mvc.perform(post("/api/v1/agents/{agentId}/sessions", AGENT_ID)
                        .header("Authorization", "Bearer " + token(ISSUER, AUDIENCE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttlSeconds\":3600}"))
                .andExpect(status().isOk());

        verify(sessions).issue(AGENT_ID, new WorkerScope("tenant-a", "project-a", "team-a"),
                Set.of("worker:session:manage"), java.time.Duration.ofSeconds(3600));
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

    private static String token(String issuer, String audience) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("local-test-operator")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("tenant", "tenant-a")
                .claim("project", "project-a")
                .claim("team", "team-a")
                .claim("permissions", List.of("worker:session:manage"))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
        signed.sign(new RSASSASigner((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate()));
        return signed.serialize();
    }
}
