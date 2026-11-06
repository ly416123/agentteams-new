package io.agentteams.credentialproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
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

class AttemptCredentialIssueControllerTest {
    private static final UUID TASK = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID ATTEMPT = UUID.fromString("81000000-0000-0000-0000-000000000002");
    private static final UUID LEASE = UUID.fromString("81000000-0000-0000-0000-000000000003");

    @Test
    void requiresWorkerSessionBearerBeforeIssuingAttemptCapability() throws Exception {
        AttemptCredentialIssuer issuer = mock(AttemptCredentialIssuer.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AttemptCredentialIssueController(issuer)).build();

        mvc.perform(post("/internal/v1/attempt-credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isUnauthorized());

        verify(issuer, never()).issue(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void issuesOnlyTheActiveAttemptCredentialForTheAuthenticatedWorker() throws Exception {
        AttemptCredentialIssuer issuer = mock(AttemptCredentialIssuer.class);
        Instant expiry = Instant.parse("2026-11-06T08:00:00Z");
        when(issuer.issue("worker-session-secret", TASK, ATTEMPT, LEASE))
                .thenReturn(new IssuedAttemptCredential("atp_one_time_secret", expiry));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AttemptCredentialIssueController(issuer)).build();

        var result = mvc.perform(post("/internal/v1/attempt-credentials")
                        .header("Authorization", "Bearer worker-session-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(result.getResponse().getContentAsString())
                .contains("atp_one_time_secret", "2026-11-06T08:00:00Z")
                .doesNotContain("tenant", "project", "team", "model_credential_ref");
        verify(issuer).issue("worker-session-secret", TASK, ATTEMPT, LEASE);
    }

    @Test
    void hidesWhetherAnAttemptExistsWhenIssuanceIsDenied() throws Exception {
        AttemptCredentialIssuer issuer = mock(AttemptCredentialIssuer.class);
        doThrow(new AttemptCredentialDeniedException())
                .when(issuer).issue("worker-session-secret", TASK, ATTEMPT, LEASE);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AttemptCredentialIssueController(issuer)).build();

        var result = mvc.perform(post("/internal/v1/attempt-credentials")
                        .header("Authorization", "Bearer worker-session-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .isEqualTo("{\"error\":\"attempt_credential_issue_denied\"}")
                .doesNotContain(TASK.toString(), ATTEMPT.toString(), "tenant");
    }

    private static String requestBody() {
        return "{\"taskId\":\"" + TASK + "\",\"attemptId\":\"" + ATTEMPT
                + "\",\"leaseId\":\"" + LEASE + "\"}";
    }
}
