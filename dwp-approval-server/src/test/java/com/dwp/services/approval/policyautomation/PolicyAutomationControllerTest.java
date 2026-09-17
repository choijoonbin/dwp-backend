package com.dwp.services.approval.policyautomation;

import com.dwp.core.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PolicyAutomationControllerTest {
    private final PolicyAutomationEndpointService endpoints =
            mock(PolicyAutomationEndpointService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new PolicyAutomationController(endpoints))
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
            .build();

    @Test
    void mapsSuccessForbiddenAndUnavailableWithoutInventingAResponse() throws Exception {
        when(endpoints.calendars()).thenReturn(List.of());
        mvc.perform(get("/v1/admin/policies/automation/calendars"))
                .andExpect(status().isOk());

        when(endpoints.delegations()).thenReturn(List.of());
        mvc.perform(get("/v1/admin/policies/automation/delegations"))
                .andExpect(status().isOk());

        when(endpoints.channels()).thenThrow(PolicyAutomationRejected.forbidden("revoked"));
        mvc.perform(get("/v1/admin/policies/automation/channels"))
                .andExpect(status().isForbidden());

        when(endpoints.policy(org.mockito.ArgumentMatchers.any()))
                .thenThrow(PolicyAutomationRejected.unavailable("authority unavailable"));
        mvc.perform(get("/v1/admin/policies/automation/rules/{id}", UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void rejectsBodyHeaderVersionMismatch() throws Exception {
        UUID channelId = UUID.randomUUID();
        mvc.perform(put("/v1/admin/policies/automation/channels/{id}", channelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-DWP-Expected-Object-Version", "1")
                        .header("X-DWP-Step-Up-Challenge", "signed")
                        .header("Idempotency-Key", "policy-version")
                        .header("X-DWP-Expected-Decision-Revision", "decision")
                        .content("""
                                {"channelId":"%s","channelKey":"CHANNEL.MAIL",
                                 "channelType":"EMAIL","lifecycle":"DRAFT","expectedVersion":2}
                                """.formatted(channelId)))
                .andExpect(status().isConflict());
    }

    @Test
    void crossScopeDetailFailsClosed() throws Exception {
        UUID policyId = UUID.randomUUID();
        when(endpoints.policy(policyId)).thenThrow(
                PolicyAutomationRejected.unavailable("not in selected resource set"));
        mvc.perform(get("/v1/admin/policies/automation/rules/{id}", policyId))
                .andExpect(status().isServiceUnavailable());

        UUID delegationId = UUID.randomUUID();
        when(endpoints.delegation(delegationId)).thenThrow(
                PolicyAutomationRejected.unavailable("not in selected resource set"));
        mvc.perform(get("/v1/admin/policies/automation/delegations/{id}", delegationId))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void delegationReviewRequiresExactBodyAndHeaderVersion() throws Exception {
        UUID delegationId = UUID.randomUUID();
        mvc.perform(post("/v1/admin/policies/automation/delegations/{id}/reviews",
                        delegationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-DWP-Expected-Object-Version", "3")
                        .header("X-DWP-Step-Up-Challenge", "signed")
                        .header("Idempotency-Key", "delegation-review")
                        .header("X-DWP-Expected-Decision-Revision", "decision")
                        .content("""
                                {"reviewId":"%s","disposition":"REMEDIATION_REQUESTED",
                                 "reviewEvidenceSha256":"%s",
                                 "expectedDelegationVersion":2}
                                """.formatted(UUID.randomUUID(), "a".repeat(64))))
                .andExpect(status().isConflict());
    }

    @Test
    void lowRiskDelegationReviewDoesNotRequireStepUpHeader() throws Exception {
        UUID delegationId = UUID.randomUUID();
        mvc.perform(post("/v1/admin/policies/automation/delegations/{id}/reviews",
                        delegationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-DWP-Expected-Object-Version", "3")
                        .header("Idempotency-Key", "delegation-low-risk")
                        .header("X-DWP-Expected-Decision-Revision", "decision")
                        .content("""
                                {"reviewId":"%s","disposition":"REMEDIATION_REQUESTED",
                                 "reviewEvidenceSha256":"%s",
                                 "expectedDelegationVersion":3}
                                """.formatted(UUID.randomUUID(), "a".repeat(64))))
                .andExpect(status().isOk());
        verify(endpoints).reviewDelegation(
                org.mockito.ArgumentMatchers.eq(delegationId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.argThat(headers ->
                        headers.challenge() == null
                                && "delegation-low-risk".equals(headers.idempotencyKey())));
    }

    @Test
    void highRiskPolicyPublishStillRequiresStepUpHeader() throws Exception {
        UUID policyId = UUID.randomUUID();
        mvc.perform(post("/v1/admin/policies/automation/rules/{id}/publish", policyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-DWP-Expected-Object-Version", "1")
                        .header("Idempotency-Key", "policy-publish")
                        .header("X-DWP-Expected-Decision-Revision", "decision")
                        .content("""
                                {"revisionId":"%s","expectedVersion":1,
                                 "reviewEvidenceSha256":"%s"}
                                """.formatted(UUID.randomUUID(), "a".repeat(64))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delegationAdministrationDispatchesAndFencesTargetsVersionsAndKillSwitch() throws Exception {
        UUID delegationId = UUID.randomUUID();
        String draft = """
                {"delegationId":"%s","delegatorUserId":17,"delegateUserId":18,
                 "delegatedRoleCodes":["APPROVAL_ADMIN"],"scopeType":"ALL",
                 "startsAt":"2026-09-17T00:00:00Z","endsAt":"2026-09-18T00:00:00Z",
                 "reason":"Quarter close coverage","expectedVersion":0}
                """.formatted(delegationId);
        mvc.perform(post("/v1/admin/policies/automation/delegations")
                        .contentType(MediaType.APPLICATION_JSON).content(draft)
                        .header("X-DWP-Expected-Object-Version", "0")
                        .header("Idempotency-Key", "delegation-create")
                        .header("X-DWP-Expected-Decision-Revision", "decision"))
                .andExpect(status().isOk());
        mvc.perform(put("/v1/admin/policies/automation/delegations/{id}", delegationId)
                        .contentType(MediaType.APPLICATION_JSON).content(draft)
                        .header("X-DWP-Expected-Object-Version", "1")
                        .header("Idempotency-Key", "delegation-update")
                        .header("X-DWP-Expected-Decision-Revision", "decision"))
                .andExpect(status().isConflict());
        mvc.perform(post("/v1/admin/policies/automation/delegations/{id}/revoke", delegationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"reason\":\"Emergency access revocation\"}")
                        .header("X-DWP-Expected-Object-Version", "1")
                        .header("Idempotency-Key", "delegation-revoke")
                        .header("X-DWP-Expected-Decision-Revision", "decision"))
                .andExpect(status().isOk());

        UUID killSwitchId = UUID.randomUUID();
        String kill = """
                {"killSwitchId":"%s","expectedControlVersion":0,
                 "reason":"Emergency tenant delegation shutdown"}
                """.formatted(killSwitchId);
        mvc.perform(post("/v1/admin/policies/automation/delegations/kill-switch")
                        .contentType(MediaType.APPLICATION_JSON).content(kill)
                        .header("X-DWP-Expected-Object-Version", "0")
                        .header("Idempotency-Key", "delegation-kill")
                        .header("X-DWP-Expected-Decision-Revision", "decision"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/policies/automation/delegations/kill-switch")
                        .contentType(MediaType.APPLICATION_JSON).content(kill)
                        .header("X-DWP-Expected-Object-Version", "0")
                        .header("X-DWP-Step-Up-Challenge", "signed")
                        .header("Idempotency-Key", "delegation-kill")
                        .header("X-DWP-Expected-Decision-Revision", "decision"))
                .andExpect(status().isOk());

        verify(endpoints).createDelegation(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(endpoints).changeDelegationState(org.mockito.ArgumentMatchers.eq(delegationId),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.any());
        verify(endpoints).killSwitch(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
