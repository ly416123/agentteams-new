package io.agentteams.credentialproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelProxyServiceTest {
    private static final String PROXY_TOKEN = "atp_" + "A".repeat(43);
    private static final String REQUEST = """
            {"model":"gpt-5.6-luna","input":[],"stream":false,"store":true}
            """;
    private static final UUID AGENT = UUID.fromString("88000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger upstreamCalls = new AtomicInteger();
    private final AtomicReference<String> upstreamAuthorization = new AtomicReference<>();
    private final AtomicReference<JsonNode> upstreamRequest = new AtomicReference<>();
    private final AtomicReference<Integer> upstreamStatus = new AtomicReference<>(200);
    private HttpServer upstream;

    @BeforeEach
    void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/responses", exchange -> {
            upstreamCalls.incrementAndGet();
            upstreamAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            upstreamRequest.set(json.readTree(exchange.getRequestBody()));
            int status = upstreamStatus.get();
            byte[] response = (status == 200 ? "data: response.delta\n\n" : "provider detail with credentials")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", status == 200
                    ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, 0);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) upstream.stop(0);
    }

    @Test
    void replacesWorkerTokenAndStreamsSuccessfulResponse() throws Exception {
        ModelProxyService service = service(token -> Optional.of(worker()), activeAttempts(), context ->
                "provider-secret".toCharArray());

        try (ModelProxyResponse response = service.forward(PROXY_TOKEN, REQUEST.getBytes())) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.contentType()).isEqualTo("text/event-stream");
            assertThat(readBody(response.body())).isEqualTo("data: response.delta\n\n");
        }

        assertThat(upstreamCalls).hasValue(1);
        assertThat(upstreamAuthorization).hasValue("Bearer provider-secret");
        assertThat(upstreamRequest.get().path("store").asBoolean()).isFalse();
        assertThat(upstreamRequest.get().path("stream").asBoolean()).isTrue();
    }

    @Test
    void rejectsInvalidProxyTokenBeforeCallingProvider() {
        ModelProxyService service = service(token -> Optional.empty(), activeAttempts(), context ->
                "provider-secret".toCharArray());

        assertThatThrownBy(() -> service.forward("invalid", REQUEST.getBytes()))
                .isInstanceOfSatisfying(ModelProxyException.class,
                        error -> assertThat(error.statusCode()).isEqualTo(401));
        assertThat(upstreamCalls).hasValue(0);
    }

    @Test
    void rejectsRequestWhenWorkerHasNoUniqueActiveAttempt() {
        ModelProxyService service = service(token -> Optional.of(worker()), worker -> List.of(), context ->
                "provider-secret".toCharArray());

        assertThatThrownBy(() -> service.forward(PROXY_TOKEN, REQUEST.getBytes()))
                .isInstanceOfSatisfying(ModelProxyException.class,
                        error -> assertThat(error.statusCode()).isEqualTo(403));
        assertThat(upstreamCalls).hasValue(0);
    }

    @Test
    void rejectsOversizedRequestBeforeAuthenticatingWorker() {
        AtomicBoolean authenticated = new AtomicBoolean();
        ModelProxyService service = service(token -> {
            authenticated.set(true);
            return Optional.of(worker());
        }, activeAttempts(), context -> "provider-secret".toCharArray());

        assertThatThrownBy(() -> service.forward(PROXY_TOKEN, new byte[4 * 1024 * 1024 + 1]))
                .isInstanceOfSatisfying(ModelProxyException.class,
                        error -> assertThat(error.statusCode()).isEqualTo(413));
        assertThat(authenticated).isFalse();
        assertThat(upstreamCalls).hasValue(0);
    }

    @Test
    void rejectsModelOutsideTheConfiguredAllowlist() {
        ModelProxyService service = service(token -> Optional.of(worker()), activeAttempts(), context ->
                "provider-secret".toCharArray());
        byte[] request = "{\"model\":\"other-model\",\"input\":[]}".getBytes();

        assertThatThrownBy(() -> service.forward(PROXY_TOKEN, request))
                .isInstanceOfSatisfying(ModelProxyException.class,
                        error -> assertThat(error.statusCode()).isEqualTo(400));
        assertThat(upstreamCalls).hasValue(0);
    }

    @Test
    void doesNotPassProviderErrorBodyBackToWorker() {
        upstreamStatus.set(429);
        ModelProxyService service = service(token -> Optional.of(worker()), activeAttempts(), context ->
                "provider-secret".toCharArray());

        assertThatThrownBy(() -> service.forward(PROXY_TOKEN, REQUEST.getBytes()))
                .isInstanceOfSatisfying(ModelProxyException.class,
                        error -> assertThat(error.statusCode()).isEqualTo(502));
    }

    @Test
    void failsClosedWhenProviderCredentialCannotBeResolved() {
        ModelProxyService service = service(token -> Optional.of(worker()), activeAttempts(), context -> {
            throw new CredentialSourceException();
        });

        assertThatThrownBy(() -> service.forward(PROXY_TOKEN, REQUEST.getBytes()))
                .isInstanceOfSatisfying(ModelProxyException.class,
                        error -> assertThat(error.statusCode()).isEqualTo(503));
        assertThat(upstreamCalls).hasValue(0);
    }

    private ModelProxyService service(ModelProxyAuthenticator authenticator,
            ActiveAttemptRepository attempts, CredentialSource credentials) {
        URI endpoint = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1/responses");
        return new ModelProxyService(authenticator, new AttemptExecutionContextResolver(attempts,
                Clock.fixed(NOW, ZoneOffset.UTC)), credentials, endpoint,
                Set.of("gpt-5.6-luna"), HttpClient.newHttpClient());
    }

    private static ActiveAttemptRepository activeAttempts() {
        return worker -> List.of(new ActiveAttemptCandidate(worker.agentId(), worker.tenantId(), worker.projectId(),
                worker.teamId(), UUID.fromString("88000000-0000-0000-0000-000000000002"),
                UUID.fromString("88000000-0000-0000-0000-000000000003"),
                UUID.fromString("88000000-0000-0000-0000-000000000004"), NOW.plusSeconds(60), true, true, true));
    }

    private static AuthenticatedWorker worker() {
        return new AuthenticatedWorker(UUID.fromString("88000000-0000-0000-0000-000000000005"), AGENT,
                "tenant-a", "project-a", "team-a", "file:tenant-a/project-a/chatgpt-token");
    }

    private static String readBody(InputStream body) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        body.transferTo(output);
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
