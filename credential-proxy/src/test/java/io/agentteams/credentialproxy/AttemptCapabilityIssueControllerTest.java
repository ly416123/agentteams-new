package io.agentteams.credentialproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AttemptCapabilityIssueControllerTest {
    private static final UUID TASK = UUID.fromString("82000000-0000-0000-0000-000000000001");
    private static final UUID ASSIGNMENT = UUID.fromString("82000000-0000-0000-0000-000000000002");
    private static final UUID ATTEMPT = UUID.fromString("82000000-0000-0000-0000-000000000003");
    private static final UUID LEASE = UUID.fromString("82000000-0000-0000-0000-000000000004");

    @Test
    void requiresSlotSessionBearerBeforeIssuingCapability() throws Exception {
        AttemptExecutionCapabilityIssuer issuer = mock(AttemptExecutionCapabilityIssuer.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AttemptCapabilityIssueController(issuer)).build();

        mvc.perform(post("/internal/v1/attempt-capabilities")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isUnauthorized());

        verify(issuer, never()).issue(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsOnlyTheOneTimeCapabilityWithoutScopeOrCaching() throws Exception {
        AttemptExecutionCapabilityIssuer issuer = mock(AttemptExecutionCapabilityIssuer.class);
        Instant expiry = Instant.parse("2026-11-06T08:00:00Z");
        when(issuer.issue("slot-session-secret", TASK, ASSIGNMENT, ATTEMPT, LEASE))
                .thenReturn(new IssuedAttemptCapability("cap_one_time_secret", expiry));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AttemptCapabilityIssueController(issuer)).build();

        var result = mvc.perform(post("/internal/v1/attempt-capabilities")
                        .header("Authorization", "Bearer slot-session-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(result.getResponse().getContentAsString())
                .contains("cap_one_time_secret", "2026-11-06T08:00:00Z")
                .doesNotContain("tenant", "project", "team", "credential_ref");
        verify(issuer).issue("slot-session-secret", TASK, ASSIGNMENT, ATTEMPT, LEASE);
    }

    @Test
    void hidesAssignmentExistenceWhenSlotCannotClaimIt() throws Exception {
        AttemptExecutionCapabilityIssuer issuer = mock(AttemptExecutionCapabilityIssuer.class);
        doThrow(new AttemptCredentialDeniedException())
                .when(issuer).issue("slot-session-secret", TASK, ASSIGNMENT, ATTEMPT, LEASE);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AttemptCapabilityIssueController(issuer)).build();

        var result = mvc.perform(post("/internal/v1/attempt-capabilities")
                        .header("Authorization", "Bearer slot-session-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .isEqualTo("{\"error\":\"attempt_capability_issue_denied\"}")
                .doesNotContain(TASK.toString(), ATTEMPT.toString(), "tenant");
    }

    private static String requestBody() {
        return "{\"taskId\":\"" + TASK + "\",\"assignmentId\":\"" + ASSIGNMENT
                + "\",\"attemptId\":\"" + ATTEMPT + "\",\"leaseId\":\"" + LEASE + "\"}";
    }
}
