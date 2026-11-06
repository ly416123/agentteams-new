package io.agentteams.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Exercises the published Gateway image's real gRPC session-authentication boundary. */
@Testcontainers(disabledWithoutDocker = true)
class GatewaySessionHandshakeTest {
    private static final String GATEWAY_IMAGE = "ghcr.io/ly416123/agentteams-agent-gateway"
            + "@sha256:40c919d31b0fe7bdf876237c36fff405a16c2208420ad42374f6dd67491e055f";
    private static final String SERVICE = "io.agentteams.contracts.v1.AgentChannel/Connect";
    private static final Metadata.Key<String> TOKEN_HEADER = Metadata.Key.of("x-agent-token", Metadata.ASCII_STRING_MARSHALLER);
    private static final MethodDescriptor.Marshaller<byte[]> BYTE_ARRAY = new MethodDescriptor.Marshaller<>() {
        @Override
        public java.io.InputStream stream(byte[] value) {
            return new java.io.ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(java.io.InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
    };
    private static final MethodDescriptor<byte[], byte[]> CONNECT = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
            .setFullMethodName(SERVICE)
            .setRequestMarshaller(BYTE_ARRAY)
            .setResponseMarshaller(BYTE_ARRAY)
            .build();

    private static final Network NETWORK = Network.newNetwork();
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withDatabaseName("gateway_test")
            .withUsername("gateway_test")
            .withPassword("gateway_test_password");
    private static GenericContainer<?> gateway;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startGatewayAndPrepareSchema() {
        POSTGRES.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        createSchema();
        gateway = new GenericContainer<>(DockerImageName.parse(GATEWAY_IMAGE))
                .withNetwork(NETWORK)
                .withExposedPorts(9090)
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/gateway_test")
                .withEnv("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername())
                .withEnv("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword())
                .withEnv("AGENTTEAMS_GATEWAY_NATS_ENABLED", "false")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(java.time.Duration.ofMinutes(2)));
        gateway.start();
    }

    @AfterAll
    static void stopGatewayAndDatabase() {
        if (gateway != null) gateway.stop();
        POSTGRES.stop();
        NETWORK.close();
    }

    @Test
    void validSessionAuthenticatesAndPersistsGatewayAndCanonicalAgentState() throws Exception {
        UUID agentId = UUID.randomUUID();
        String token = "test-session-" + UUID.randomUUID();
        insertAgent(agentId);
        insertSession(agentId, token, Instant.now().plusSeconds(3600), null);

        Handshake handshake = connect(agentId, token);
        try {
            byte[] ready = handshake.responses().poll(10, TimeUnit.SECONDS);
            assertThat(ready).as("Gateway never sent Ready; status=%s, logs:\n%s",
                    handshake.status().peek(), gateway.getLogs()).isNotNull();
            assertThat(readyAccepted(ready)).isTrue();
            assertThat(handshake.status().poll(10, TimeUnit.SECONDS)).isNull();
            assertThat(jdbc.queryForObject(
                    "SELECT presence FROM gateway_agent_state WHERE agent_id = ?", String.class, agentId.toString()))
                    .isEqualTo("ONLINE");
            assertThat(jdbc.queryForObject("SELECT phase FROM agents WHERE id = ?", String.class, agentId))
                    .isEqualTo("READY");
        } finally {
            handshake.call().cancel("test complete", null);
            handshake.channel().shutdownNow();
        }
    }

    @Test
    void unknownTokenIsRejectedAsUnauthenticated() throws Exception {
        assertRejected(UUID.randomUUID(), "unknown-" + UUID.randomUUID(), Instant.now().plusSeconds(3600), null,
                UUID.randomUUID());
    }

    @Test
    void expiredSessionIsRejectedAsUnauthenticated() throws Exception {
        UUID agentId = UUID.randomUUID();
        assertRejected(agentId, "expired-" + UUID.randomUUID(), Instant.now().minusSeconds(1), null, agentId);
    }

    @Test
    void revokedSessionIsRejectedAsUnauthenticated() throws Exception {
        UUID agentId = UUID.randomUUID();
        assertRejected(agentId, "revoked-" + UUID.randomUUID(), Instant.now().plusSeconds(3600), Instant.now(), agentId);
    }

    @Test
    void sessionCannotAuthenticateAnotherAgentsHello() throws Exception {
        UUID owner = UUID.randomUUID();
        assertRejected(owner, "bound-" + UUID.randomUUID(), Instant.now().plusSeconds(3600), null, UUID.randomUUID());
    }

    private static void assertRejected(UUID owner, String token, Instant expiresAt, Instant revokedAt, UUID helloAgent)
            throws Exception {
        insertAgent(owner);
        if (!helloAgent.equals(owner)) insertAgent(helloAgent);
        insertSession(owner, token, expiresAt, revokedAt);
        Handshake handshake = connect(helloAgent, token);
        try {
            Status terminal = handshake.status().poll(10, TimeUnit.SECONDS);
            if (terminal == null) {
                boolean readyReceived = !handshake.responses().isEmpty();
                Integer persistedState = jdbc.queryForObject(
                        "SELECT count(*) FROM gateway_agent_state WHERE agent_id = ?", Integer.class,
                        helloAgent.toString());
                throw new AssertionError("Gateway did not terminate a rejected session; readyReceived="
                        + readyReceived + ", persistedState=" + persistedState + ". Gateway logs:\n"
                        + gateway.getLogs());
            }
            assertThat(terminal.getCode()).withFailMessage("Gateway rejected the handshake: %s", terminal)
                    .isEqualTo(Status.Code.UNAUTHENTICATED);
        } finally {
            handshake.call().cancel("test complete", null);
            handshake.channel().shutdownNow();
        }
    }

    private static Handshake connect(UUID agentId, String token) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(gateway.getHost(), gateway.getMappedPort(9090))
                .usePlaintext().build();
        ClientCall<byte[], byte[]> call = channel.newCall(CONNECT, CallOptions.DEFAULT.withDeadlineAfter(60, TimeUnit.SECONDS));
        BlockingQueue<byte[]> responses = new LinkedBlockingQueue<>();
        BlockingQueue<Status> statuses = new LinkedBlockingQueue<>();
        Metadata headers = new Metadata();
        headers.put(TOKEN_HEADER, token);
        call.start(new ClientCall.Listener<>() {
            @Override
            public void onMessage(byte[] message) {
                responses.add(message);
                call.request(1);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                statuses.add(status);
            }
        }, headers);
        call.request(1);
        call.sendMessage(agentHello(agentId));
        return new Handshake(channel, call, responses, statuses);
    }

    private static byte[] agentHello(UUID agentId) {
        byte[] metadata = message(out -> {
            string(out, 1, UUID.randomUUID().toString());
            string(out, 2, agentId.toString());
        });
        byte[] version = message(out -> {
            out.writeUInt32(1, 2);
            out.writeUInt32(2, 0);
        });
        byte[] hello = message(out -> {
            out.writeByteArray(1, metadata);
            out.writeByteArray(2, version);
            string(out, 3, "AGENTSCOPE");
            string(out, 4, "handshake-test");
        });
        return message(out -> out.writeByteArray(1, hello));
    }

    private static boolean readyAccepted(byte[] response) throws IOException {
        CodedInputStream envelope = CodedInputStream.newInstance(response);
        byte[] ready = null;
        for (int tag; (tag = envelope.readTag()) != 0;) {
            if (WireFormat.getTagFieldNumber(tag) == 1
                    && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                ready = envelope.readByteArray();
            } else {
                envelope.skipField(tag);
            }
        }
        if (ready == null) return false;
        CodedInputStream agentReady = CodedInputStream.newInstance(ready);
        for (int tag; (tag = agentReady.readTag()) != 0;) {
            if (WireFormat.getTagFieldNumber(tag) == 2
                    && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_VARINT) {
                return agentReady.readBool();
            }
            agentReady.skipField(tag);
        }
        return false;
    }

    private static byte[] message(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void string(CodedOutputStream output, int field, String value) throws IOException {
        output.writeString(field, value);
    }

    private static void insertAgent(UUID id) {
        jdbc.update("INSERT INTO agents(id, name, phase, runtime, capabilities, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'PROVISIONING', '', '{}'::jsonb, now(), now(), 0)",
                id, "handshake-" + id);
    }

    private static void insertSession(UUID agentId, String token, Instant expiresAt, Instant revokedAt) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO agent_sessions(id, agent_id, token_sha256, expires_at, revoked_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), agentId, hash, java.sql.Timestamp.from(expiresAt),
                revokedAt == null ? null : java.sql.Timestamp.from(revokedAt));
    }

    private static void createSchema() {
        jdbc.execute("CREATE TABLE agents (id UUID PRIMARY KEY, name TEXT NOT NULL UNIQUE, phase TEXT NOT NULL, "
                + "runtime TEXT NOT NULL DEFAULT '', capabilities JSONB NOT NULL DEFAULT '{}'::jsonb, "
                + "created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE agent_sessions (id UUID PRIMARY KEY, agent_id UUID NOT NULL REFERENCES agents(id), "
                + "token_sha256 CHAR(64) NOT NULL UNIQUE, expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ, "
                + "created_at TIMESTAMPTZ NOT NULL)");
        jdbc.execute("CREATE TABLE gateway_agent_state (agent_id TEXT PRIMARY KEY, connection_id UUID NOT NULL, "
                + "presence TEXT NOT NULL, phase TEXT NOT NULL, runtime TEXT NOT NULL, runtime_version TEXT NOT NULL, "
                + "capabilities JSONB NOT NULL DEFAULT '{}'::jsonb, spec_digest TEXT, config_revision TEXT, "
                + "secret_generation TEXT, connected_at TIMESTAMPTZ, last_seen_at TIMESTAMPTZ NOT NULL, "
                + "disconnected_at TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL)");
        jdbc.execute("CREATE TABLE gateway_ack_cursors (agent_id TEXT PRIMARY KEY, "
                + "last_ack_sequence BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL)");
        jdbc.execute("CREATE TABLE gateway_commands (agent_id TEXT NOT NULL, sequence BIGINT NOT NULL, "
                + "event_id TEXT NOT NULL, attempt_id TEXT, command_bytes BYTEA NOT NULL, created_at TIMESTAMPTZ NOT NULL, "
                + "cancelled_at TIMESTAMPTZ, "
                + "PRIMARY KEY (agent_id, sequence), UNIQUE (agent_id, event_id))");
    }

    @FunctionalInterface
    private interface Writer {
        void write(CodedOutputStream output) throws IOException;
    }

    private record Handshake(ManagedChannel channel, ClientCall<byte[], byte[]> call,
            BlockingQueue<byte[]> responses, BlockingQueue<Status> status) { }
}
