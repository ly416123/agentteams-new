package io.agentteams.credentialproxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Mints an unguessable Attempt token and stores only its digest through a narrow SQL function. */
public final class JdbcAttemptCredentialIssuer implements AttemptCredentialIssuer {
    private static final String ISSUE_SQL = """
            SELECT credential_id, expires_at
              FROM public.credential_proxy_issue_attempt(?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final SecureRandom random;
    private final int ttlSeconds;

    public JdbcAttemptCredentialIssuer(JdbcTemplate jdbc, int ttlSeconds) {
        this(jdbc, ttlSeconds, new SecureRandom());
    }

    JdbcAttemptCredentialIssuer(JdbcTemplate jdbc, int ttlSeconds, SecureRandom random) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.random = Objects.requireNonNull(random, "random");
        if (ttlSeconds < 1 || ttlSeconds > 300) {
            throw new IllegalArgumentException("attempt credential TTL must be between 1 and 300 seconds");
        }
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public IssuedAttemptCredential issue(String workerSessionToken, UUID taskId, UUID attemptId, UUID leaseId) {
        if (workerSessionToken == null || workerSessionToken.isBlank()
                || taskId == null || attemptId == null || leaseId == null) {
            throw new AttemptCredentialDeniedException();
        }
        String token = generateToken();
        String sessionHash = sha256(workerSessionToken);
        String tokenHash = sha256(token);
        UUID credentialId = UUID.randomUUID();
        List<Issuance> issued = jdbc.query(ISSUE_SQL, JdbcAttemptCredentialIssuer::map,
                sessionHash, taskId, attemptId, leaseId, tokenHash, credentialId, ttlSeconds);
        if (issued.size() != 1) throw new AttemptCredentialDeniedException();
        return new IssuedAttemptCredential(token, issued.getFirst().expiresAt());
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        try {
            return "atp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Issuance map(ResultSet result, int row) throws SQLException {
        return new Issuance(result.getObject("credential_id", UUID.class),
                result.getTimestamp("expires_at").toInstant());
    }

    private record Issuance(UUID credentialId, Instant expiresAt) { }
}
