package io.agentteams.enrollment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;
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

@WebMvcTest(WorkerSlotSessionController.class)
@Import({OidcResourceServerConfiguration.class, WorkerEnrollmentErrorHandler.class,
        WorkerSlotSessionControllerSecurityTest.PropertiesConfiguration.class})
@TestPropertySource(properties = {
        "agentteams.enrollment.oidc.issuer-uri=https://identity.example.test/issuer",
        "agentteams.enrollment.oidc.jwk-set-uri=https://identity.example.test/keys",
        "agentteams.enrollment.oidc.audience=agentteams-api"
})
class WorkerSlotSessionControllerSecurityTest {
    private static final UUID SLOT_ID = UUID.fromString("55000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("55000000-0000-0000-0000-000000000002");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WorkerSlotSessionService sessions;

    @Test
    void requiresAuthenticatedOidcBearerForSlotSessionIssue() throws Exception {
        mvc.perform(post("/api/v1/worker-slots/{slotId}/sessions", SLOT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsOperatorsWithoutDedicatedSlotPermission() throws Exception {
        doThrow(WorkerEnrollmentException.forbidden("Worker slot session management permission is required"))
                .when(sessions).issue(any(), any(), anySet(), any());
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .claim("permissions", Set.of("worker:session:manage"))
                .subject("operator").build();

        mvc.perform(post("/api/v1/worker-slots/{slotId}/sessions", SLOT_ID).with(jwt().jwt(jwt)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKER_ENROLLMENT_REQUEST_REJECTED"));
    }

    @Test
    void issuesTenantNeutralSessionWithoutTenantClaimsAndPreventsCaching() throws Exception {
        when(sessions.issue(any(), any(), anySet(), any())).thenReturn(new IssuedWorkerSlotSession(
                SESSION_ID, SLOT_ID, "one-time-slot-secret", Instant.parse("2026-11-07T00:00:00Z")));
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .claim("permissions", Set.of(WorkerSlotSessionService.MANAGE_PERMISSION))
                .subject("platform-operator").build();

        mvc.perform(post("/api/v1/worker-slots/{slotId}/sessions", SLOT_ID).with(jwt().jwt(jwt)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.slotId").value(SLOT_ID.toString()))
                .andExpect(jsonPath("$.accessToken").value("one-time-slot-secret"));
    }

    @TestConfiguration
    @EnableConfigurationProperties(EnrollmentOidcProperties.class)
    static class PropertiesConfiguration { }
}
