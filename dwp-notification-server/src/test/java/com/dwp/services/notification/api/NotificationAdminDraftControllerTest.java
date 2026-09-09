package com.dwp.services.notification.api;

import com.dwp.services.notification.domain.NotificationAdminService;
import com.dwp.services.notification.domain.NotificationDraftGovernanceService;
import com.dwp.services.notification.domain.NotificationModels.DraftDecisionRequest;
import com.dwp.services.notification.domain.NotificationModels.PolicyChannelRule;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicy;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateContent;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateRevision;
import com.dwp.services.notification.domain.NotificationTemplateService;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAdminDraftControllerTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of("TENANT_ADMIN"), Set.of(), false, "dwp-gateway");

    private final NotificationAdminService adminService = mock(NotificationAdminService.class);
    private final NotificationTemplateService templateService = mock(NotificationTemplateService.class);
    private final NotificationDraftGovernanceService governance =
            mock(NotificationDraftGovernanceService.class);
    private final NotificationAdminController controller =
            new NotificationAdminController(adminService, templateService, governance);
    private final DraftDecisionRequest request = new DraftDecisionRequest(
            "1", "The draft no longer satisfies the tenant delivery policy.");

    @AfterEach
    void clearContext() {
        NotificationRequestContext.clear();
    }

    @Test
    void exposesPolicyWithdrawAndRejectCommandsForTheCurrentActor() {
        UUID withdrawId = UUID.randomUUID();
        UUID rejectId = UUID.randomUUID();
        TenantPolicy withdrawn = policy(withdrawId);
        TenantPolicy rejected = policy(rejectId);
        when(governance.withdrawPolicyDraft(ACTOR, withdrawId, request, "withdraw-key"))
                .thenReturn(withdrawn);
        when(governance.rejectPolicyDraft(ACTOR, rejectId, request, "reject-key"))
                .thenReturn(rejected);
        NotificationRequestContext.set(ACTOR);

        assertThat(controller.withdrawPolicyDraft(withdrawId, "withdraw-key", request).data())
                .isSameAs(withdrawn);
        assertThat(controller.rejectPolicyDraft(rejectId, "reject-key", request).data())
                .isSameAs(rejected);
        verify(governance).withdrawPolicyDraft(ACTOR, withdrawId, request, "withdraw-key");
        verify(governance).rejectPolicyDraft(ACTOR, rejectId, request, "reject-key");
    }

    @Test
    void exposesTemplateWithdrawRejectAndTheCompatibleRetireAlias() {
        UUID revisionId = UUID.randomUUID();
        TemplateRevision retired = template(revisionId);
        when(governance.withdrawTemplateDraft(ACTOR, revisionId, request, "withdraw-key"))
                .thenReturn(retired);
        when(governance.withdrawTemplateDraft(ACTOR, revisionId, request, "retire-key"))
                .thenReturn(retired);
        when(governance.rejectTemplateDraft(ACTOR, revisionId, request, "reject-key"))
                .thenReturn(retired);
        NotificationRequestContext.set(ACTOR);

        assertThat(controller.withdrawTemplateDraft(
                revisionId, "withdraw-key", request).data()).isSameAs(retired);
        assertThat(controller.retireTemplateDraft(
                revisionId, "retire-key", request).data()).isSameAs(retired);
        assertThat(controller.rejectTemplateDraft(
                revisionId, "reject-key", request).data()).isSameAs(retired);
        verify(governance).withdrawTemplateDraft(ACTOR, revisionId, request, "withdraw-key");
        verify(governance).withdrawTemplateDraft(ACTOR, revisionId, request, "retire-key");
        verify(governance).rejectTemplateDraft(ACTOR, revisionId, request, "reject-key");
    }

    private TenantPolicy policy(UUID policyId) {
        return new TenantPolicy(
                policyId,
                "APP",
                "messaging",
                "Messenger",
                "TENANT_POLICY",
                "RETIRED",
                false,
                false,
                "IMMEDIATE",
                List.of(new PolicyChannelRule("IN_APP", true, "IMMEDIATE", true, 100)),
                "Protect user attention with governed routing",
                17L,
                null,
                null,
                "1",
                Instant.parse("2026-09-08T00:00:00Z"));
    }

    private TemplateRevision template(UUID revisionId) {
        return new TemplateRevision(
                revisionId,
                UUID.fromString("cf55f6c4-42b1-46d6-bfbf-f19941555e5f"),
                "MESSAGING.DIRECT_MESSAGE",
                "messaging",
                "IN_APP",
                "ko-KR",
                "RETIRED",
                1,
                new TemplateContent("Message", "Preview", "Body", "Open"),
                "a".repeat(64),
                "Align the content with the company notification standard.",
                17L,
                null,
                null,
                null,
                "1",
                Instant.parse("2026-09-08T00:00:00Z"));
    }
}
