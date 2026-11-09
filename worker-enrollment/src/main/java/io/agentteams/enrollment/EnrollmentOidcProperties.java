package io.agentteams.enrollment;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentteams.enrollment.oidc")
public class EnrollmentOidcProperties {
    private String issuerUri = "";
    private String jwkSetUri = "";
    private String audience = "";
    private String tenantClaim = "tenant";
    private String projectClaim = "project";
    private String teamClaim = "team";
    private String permissionsClaim = "permissions";
    private boolean allowInsecureLoopback;

    public void validate() {
        requireAbsoluteUri(issuerUri, "issuer-uri", allowInsecureLoopback);
        requireAbsoluteUri(jwkSetUri, "jwk-set-uri", allowInsecureLoopback);
        requireText(audience, "audience");
        requireText(tenantClaim, "tenant-claim");
        requireText(projectClaim, "project-claim");
        requireText(teamClaim, "team-claim");
        requireText(permissionsClaim, "permissions-claim");
    }

    private static void requireAbsoluteUri(String value, String field, boolean allowInsecureLoopback) {
        requireText(value, field);
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("agentteams.enrollment.oidc." + field + " must be an absolute HTTP(S) URI");
        }
        URI uri = URI.create(value);
        if ("https".equalsIgnoreCase(uri.getScheme())) return;
        if (allowInsecureLoopback && "http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost())) return;
        throw new IllegalStateException("agentteams.enrollment.oidc." + field
                + " must use HTTPS; HTTP is allowed only for loopback test or development endpoints");
    }

    private static boolean isLoopbackHost(String host) {
        return host != null && ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("agentteams.enrollment.oidc." + field + " is required");
        }
    }

    public String issuerUri() { return issuerUri; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
    public String jwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
    public String audience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String tenantClaim() { return tenantClaim; }
    public void setTenantClaim(String tenantClaim) { this.tenantClaim = tenantClaim; }
    public String projectClaim() { return projectClaim; }
    public void setProjectClaim(String projectClaim) { this.projectClaim = projectClaim; }
    public String teamClaim() { return teamClaim; }
    public void setTeamClaim(String teamClaim) { this.teamClaim = teamClaim; }
    public String permissionsClaim() { return permissionsClaim; }
    public void setPermissionsClaim(String permissionsClaim) { this.permissionsClaim = permissionsClaim; }
    public boolean isAllowInsecureLoopback() { return allowInsecureLoopback; }
    public void setAllowInsecureLoopback(boolean allowInsecureLoopback) {
        this.allowInsecureLoopback = allowInsecureLoopback;
    }
}
