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
            SELECT proxy_credential_id, agent_id, tenant_id, project_id, team_id, model_credential_ref,
                   attempt_id, lease_id
              FROM public.credential_proxy_authenticate_v2(?)
            """;
    private static final String REVALIDATE_SQL = """
            SELECT proxy_credential_id, agent_id, tenant_id, project_id, team_id, model_credential_ref,
                   attempt_id, lease_id
              FROM public.credential_proxy_revalidate_v2(?)
            """;
    private static final String CAPABILITY_AUTHENTICATE_SQL = """
            SELECT proxy_credential_id, agent_id, tenant_id, project_id, team_id, model_credential_ref,
                   attempt_id, lease_id
              FROM public.credential_proxy_authenticate_attempt_capability(?)
            """;
    private static final String CAPABILITY_REVALIDATE_SQL = """
            SELECT proxy_credential_id, agent_id, tenant_id, project_id, team_id, model_credential_ref,
                   attempt_id, lease_id
              FROM public.credential_proxy_revalidate_attempt_capability(?)
            """;

    private final JdbcTemplate jdbc;

    public JdbcModelProxyCredentialAuthenticator(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<AuthenticatedWorker> authenticate(String token) {
        if (!validToken(token)) return Optional.empty();
        boolean capability = token.startsWith("cap_");
        return readIdentity(capability ? CAPABILITY_AUTHENTICATE_SQL : AUTHENTICATE_SQL,
                sha256(token), capability ? AuthenticatedWorker.CredentialKind.EXECUTION_CAPABILITY : null);
    }

    @Override
    public Optional<AuthenticatedWorker> revalidate(AuthenticatedWorker worker) {
        Objects.requireNonNull(worker, "worker");
        boolean capability = worker.credentialKind() == AuthenticatedWorker.CredentialKind.EXECUTION_CAPABILITY;
        return readIdentity(capability ? CAPABILITY_REVALIDATE_SQL : REVALIDATE_SQL,
                worker.proxyCredentialId(), worker.credentialKind());
    }

    private Optional<AuthenticatedWorker> readIdentity(String sql, Object parameter,
            AuthenticatedWorker.CredentialKind credentialKind) {
        List<AuthenticatedWorker> identities = jdbc.query(sql, (rs, row) -> {
            java.util.UUID attemptId = rs.getObject("attempt_id", java.util.UUID.class);
            java.util.UUID leaseId = rs.getObject("lease_id", java.util.UUID.class);
            AuthenticatedWorker.CredentialKind resolvedKind = credentialKind != null ? credentialKind
                    : attemptId == null ? AuthenticatedWorker.CredentialKind.TENANT
                    : AuthenticatedWorker.CredentialKind.ATTEMPT;
            return new AuthenticatedWorker(
                    rs.getObject("proxy_credential_id", java.util.UUID.class),
                    rs.getObject("agent_id", java.util.UUID.class),
                    rs.getString("tenant_id"),
                    rs.getString("project_id"),
                    rs.getString("team_id"),
                    rs.getString("model_credential_ref"), attemptId, leaseId, resolvedKind);
        }, parameter);
        if (identities.size() != 1) return Optional.empty();
        return Optional.of(identities.getFirst());
    }

    private static boolean validToken(String token) {
        if (token == null || token.length() != 47
                || !(token.startsWith("atp_") || token.startsWith("cap_"))) return false;
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
