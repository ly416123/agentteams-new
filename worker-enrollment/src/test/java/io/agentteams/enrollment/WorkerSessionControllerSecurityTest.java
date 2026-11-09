package io.agentteams.enrollment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WorkerSessionController.class)
@Import({OidcResourceServerConfiguration.class, WorkerEnrollmentErrorHandler.class,
        WorkerSessionControllerSecurityTest.PropertiesConfiguration.class})
@TestPropertySource(properties = {
        "agentteams.enrollment.oidc.issuer-uri=https://identity.example.test/issuer",
        "agentteams.enrollment.oidc.jwk-set-uri=https://identity.example.test/keys",
        "agentteams.enrollment.oidc.audience=agentteams-api"
})
class WorkerSessionControllerSecurityTest {
    private static final UUID AGENT_ID = UUID.fromString("44000000-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WorkerSessionService sessions;

    @Test
    void requiresAnAuthenticatedOidcBearerForEnrollment() throws Exception {
        mvc.perform(post("/api/v1/agents/{agentId}/sessions", AGENT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAuthenticatedOperatorsWithoutTheEnrollmentPermission() throws Exception {
        doThrow(WorkerEnrollmentException.forbidden("worker session management permission is required"))
                .when(sessions).issue(any(), any(), any(), anySet(), isNull(), isNull());
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .claim("tenant", "tenant-a").claim("project", "project-a").claim("team", "team-a")
                .subject("operator").build();

        mvc.perform(post("/api/v1/agents/{agentId}/sessions", AGENT_ID)
                        .with(jwt().jwt(jwt)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKER_ENROLLMENT_REQUEST_REJECTED"));
    }

    @TestConfiguration
    @EnableConfigurationProperties(EnrollmentOidcProperties.class)
    static class PropertiesConfiguration { }
}
