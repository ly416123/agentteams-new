package io.agentteams.credentialproxy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.sun.net.httpserver.HttpServer;

class ModelProxyControllerTest {
    private static final String PROXY_TOKEN = "atp_" + "A".repeat(43);
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private HttpServer upstream;
    private MockMvc mvc;
    private ScheduledExecutorService streamMonitor;

    @BeforeEach
    void setUp() throws Exception {
        streamMonitor = Executors.newSingleThreadScheduledExecutor();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "data: streamed\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        AuthenticatedWorker worker = new AuthenticatedWorker(
                UUID.fromString("88000000-0000-0000-0000-000000000005"),
                UUID.fromString("88000000-0000-0000-0000-000000000001"),
                "tenant-a", "project-a", "team-a", "file:provider_credential_worker_1");
        ModelProxyService service = new ModelProxyService(
                token -> PROXY_TOKEN.equals(token) ? Optional.of(worker) : Optional.empty(),
                new AttemptExecutionContextResolver(ignored -> List.of(new ActiveAttemptCandidate(
                        worker.agentId(), worker.tenantId(), worker.projectId(), worker.teamId(),
                        UUID.fromString("88000000-0000-0000-0000-000000000002"),
                        UUID.fromString("88000000-0000-0000-0000-000000000003"),
                        UUID.fromString("88000000-0000-0000-0000-000000000004"),
                        NOW.plusSeconds(60), true, true, true)), Clock.fixed(NOW, ZoneOffset.UTC)),
                context -> "provider-secret".toCharArray(),
                URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1/responses"),
                Set.of("gpt-5.6-luna"), HttpClient.newHttpClient(),
                Clock.fixed(NOW, ZoneOffset.UTC), streamMonitor);
        mvc = MockMvcBuilders.standaloneSetup(new ModelProxyController(service)).build();
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) upstream.stop(0);
        if (streamMonitor != null) streamMonitor.shutdownNow();
    }

    @Test
    void acceptsProxyBearerAndStreamsResponsesApiOutput() throws Exception {
        MvcResult result = mvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + PROXY_TOKEN)
                        .content("{\"model\":\"gpt-5.6-luna\",\"input\":[]}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string("data: streamed\n\n"));
    }

    @Test
    void rejectsMissingBearerWithoutCallingUpstream() throws Exception {
        mvc.perform(post("/v1/responses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-5.6-luna\",\"input\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"model_proxy_request_denied\"}"));
    }

    @Test
    void rejectsOversizedHttpRequest() throws Exception {
        byte[] oversized = new byte[4 * 1024 * 1024 + 1];
        mvc.perform(post("/v1/responses").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + PROXY_TOKEN).content(oversized))
                .andExpect(status().isPayloadTooLarge());
    }
}
