package io.agentteams.credentialproxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resolves a model proxy bearer token through a narrow database function; the token is never queried in plaintext. */
public final class JdbcModelProxyCredentialAuthenticator implements ModelProxyAuthenticator {
    private static final String AUTHENTICATE_SQL = """
            SELECT proxy_credential_id, agent_id, tenant_id, project_id, team_id, model_credential_ref
              FROM public.credential_proxy_authenticate(?)
            """;

    private final JdbcTemplate jdbc;

    public JdbcModelProxyCredentialAuthenticator(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<AuthenticatedWorker> authenticate(String token) {
        if (!validToken(token)) return Optional.empty();
        String tokenHash = sha256(token);
        List<AuthenticatedWorker> identities = jdbc.query(AUTHENTICATE_SQL,
                (rs, row) -> new AuthenticatedWorker(
                        rs.getObject("proxy_credential_id", java.util.UUID.class),
                        rs.getObject("agent_id", java.util.UUID.class),
                        rs.getString("tenant_id"),
                        rs.getString("project_id"),
                        rs.getString("team_id"),
                        rs.getString("model_credential_ref")), tokenHash);
        if (identities.size() != 1) return Optional.empty();
        return Optional.of(identities.getFirst());
    }

    private static boolean validToken(String token) {
        if (token == null || token.length() != 47 || !token.startsWith("atp_")) return false;
        for (int index = 4; index < token.length(); index++) {
            char value = token.charAt(index);
            if (!((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9') || value == '_' || value == '-')) return false;
        }
        return true;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
