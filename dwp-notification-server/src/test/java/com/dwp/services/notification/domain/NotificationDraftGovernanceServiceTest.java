package com.dwp.services.notification.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.domain.NotificationModels.DraftDecisionRequest;
import com.dwp.services.notification.domain.NotificationModels.PolicyChannelRule;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicy;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateContent;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateRevision;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationDraftGovernanceServiceTest {

    private final NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
    private final NotificationAdminRepository policyReader = mock(NotificationAdminRepository.class);
    private final NotificationPolicyDraftRepository policyDrafts =
            mock(NotificationPolicyDraftRepository.class);
    private final NotificationTemplateRepository templates = mock(NotificationTemplateRepository.class);
    private final NotificationIdempotencyRepository idempotency =
            mock(NotificationIdempotencyRepository.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final NotificationDraftGovernanceService service =
            new NotificationDraftGovernanceService(
                    databaseScope, policyReader, policyDrafts, templates, idempotency, audit);
    private final NotificationRequestContext.Actor author = actor(17L);
    private final NotificationRequestContext.Actor reviewer = actor(29L);
    private final DraftDecisionRequest decision = new DraftDecisionRequest(
            "1", "The draft no longer satisfies the tenant delivery policy.");

    @Test
    void policyAuthorCanWithdrawAndTheDecisionIsAudited() {
        UUID policyId = UUID.randomUUID();
        TenantPolicy draft = policy(policyId, author.userId(), "DRAFT");
        TenantPolicy retired = policy(policyId, author.userId(), "RETIRED");
        Request receipt = receipt("policy-withdraw", "TENANT_NOTIFICATION_POLICY_DRAFT_WITHDRAW");
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt);
        when(policyReader.policy(42L, policyId))
                .thenReturn(Optional.of(draft))
                .thenReturn(Optional.of(retired));
        when(policyDrafts.withdraw(42L, 17L, policyId, 1L)).thenReturn(true);

        TenantPolicy result = service.withdrawPolicyDraft(
                author, policyId, decision, "policy-withdraw");

        assertThat(result.state()).isEqualTo("RETIRED");
        verify(idempotency).begin(
                author,
                "policy-withdraw",
                "TENANT_NOTIFICATION_POLICY_DRAFT_WITHDRAW",
                Map.of("draftId", policyId, "request", decision));
        verify(policyDrafts).withdraw(42L, 17L, policyId, 1L);
        verify(audit).record(argThat(event ->
                "notification.policy.draft.withdrawn".equals(event.action())
                        && "WITHDRAWN".equals(event.afterState().get("draftDecision"))
                        && decision.reason().equals(event.reason())));
        verify(idempotency).complete(author, receipt, retired);
    }

    @Test
    void policyRejectRequiresAnIndependentReviewerAndRecordsTheReason() {
        UUID policyId = UUID.randomUUID();
        TenantPolicy draft = policy(policyId, author.userId(), "DRAFT");
        TenantPolicy retired = policy(policyId, author.userId(), "RETIRED");
        Request receipt = receipt("policy-reject", "TENANT_NOTIFICATION_POLICY_DRAFT_REJECT");
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt);
        when(policyReader.policy(42L, policyId))
                .thenReturn(Optional.of(draft))
                .thenReturn(Optional.of(retired));
        when(policyDrafts.reject(42L, 29L, policyId, 1L)).thenReturn(true);

        TenantPolicy result = service.rejectPolicyDraft(
                reviewer, policyId, decision, "policy-reject");

        assertThat(result.state()).isEqualTo("RETIRED");
        verify(idempotency).begin(
                reviewer,
                "policy-reject",
                "TENANT_NOTIFICATION_POLICY_DRAFT_REJECT",
                Map.of("draftId", policyId, "request", decision));
        verify(policyDrafts).reject(42L, 29L, policyId, 1L);
        verify(audit).record(argThat(event ->
                "notification.policy.draft.rejected".equals(event.action())
                        && "DENY".equals(event.policyDecision())
                        && "REJECTED".equals(event.afterState().get("draftDecision"))
                        && decision.reason().equals(event.reason())));
    }

    @Test
    void nonAuthorCannotWithdrawAPolicyDraft() {
        UUID policyId = UUID.randomUUID();
        Request receipt = receipt("policy-withdraw", "TENANT_NOTIFICATION_POLICY_DRAFT_WITHDRAW");
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt);
        when(policyReader.policy(42L, policyId))
                .thenReturn(Optional.of(policy(policyId, author.userId(), "DRAFT")));

        assertForbidden(() -> service.withdrawPolicyDraft(
                reviewer, policyId, decision, "policy-withdraw"));
        verify(policyDrafts, never()).withdraw(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void policyAuthorCannotRejectTheirOwnDraft() {
        UUID policyId = UUID.randomUUID();
        Request receipt = receipt("policy-reject", "TENANT_NOTIFICATION_POLICY_DRAFT_REJECT");
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt);
        when(policyReader.policy(42L, policyId))
                .thenReturn(Optional.of(policy(policyId, author.userId(), "DRAFT")));

        assertForbidden(() -> service.rejectPolicyDraft(
                author, policyId, decision, "policy-reject"));
        verify(policyDrafts, never()).reject(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void aPolicyOutsideTheActorTenantReturnsNotFound() {
        UUID policyId = UUID.randomUUID();
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(
                receipt("policy-withdraw", "TENANT_NOTIFICATION_POLICY_DRAFT_WITHDRAW"));
        when(policyReader.policy(42L, policyId)).thenReturn(Optional.empty());

        assertCode(
                () -> service.withdrawPolicyDraft(author, policyId, decision, "policy-withdraw"),
                NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    void concurrentPolicyClosureReturnsConflict() {
        UUID policyId = UUID.randomUUID();
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(
                receipt("policy-withdraw", "TENANT_NOTIFICATION_POLICY_DRAFT_WITHDRAW"));
        when(policyReader.policy(42L, policyId))
                .thenReturn(Optional.of(policy(policyId, author.userId(), "DRAFT")));
        when(policyDrafts.withdraw(42L, 17L, policyId, 1L)).thenReturn(false);

        assertCode(
                () -> service.withdrawPolicyDraft(author, policyId, decision, "policy-withdraw"),
                NotificationErrorCode.NOTIFICATION_STALE_VERSION);
    }

    @Test
    void templateAuthorCanWithdrawThroughTheGuardedRetireTransition() {
        UUID revisionId = UUID.randomUUID();
        TemplateRevision draft = template(revisionId, author.userId(), "DRAFT");
        TemplateRevision retired = template(revisionId, author.userId(), "RETIRED");
        Request receipt = receipt("template-withdraw", "TENANT_NOTIFICATION_TEMPLATE_DRAFT_RETIRE");
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt);
        when(templates.revision(42L, revisionId))
                .thenReturn(Optional.of(draft))
                .thenReturn(Optional.of(retired));
        when(templates.retireDraft(42L, 17L, revisionId, 1)).thenReturn(true);

        TemplateRevision result = service.withdrawTemplateDraft(
                author, revisionId, decision, "template-withdraw");

        assertThat(result.state()).isEqualTo("RETIRED");
        verify(idempotency).begin(
                author,
                "template-withdraw",
                "TENANT_NOTIFICATION_TEMPLATE_DRAFT_RETIRE",
                Map.of("draftId", revisionId, "request", decision));
        verify(templates).retireDraft(42L, 17L, revisionId, 1);
        verify(audit).record(argThat(event ->
                "notification.template.draft.withdrawn".equals(event.action())
                        && "WITHDRAWN".equals(event.afterState().get("draftDecision"))));
        verify(idempotency).complete(author, receipt, retired);
    }

    @Test
    void nonAuthorCannotUseTheTemplateRetireTransition() {
        UUID revisionId = UUID.randomUUID();
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(
                receipt("template-withdraw", "TENANT_NOTIFICATION_TEMPLATE_DRAFT_RETIRE"));
        when(templates.revision(42L, revisionId))
                .thenReturn(Optional.of(template(revisionId, author.userId(), "DRAFT")));

        assertForbidden(() -> service.withdrawTemplateDraft(
                reviewer, revisionId, decision, "template-withdraw"));
        verify(templates, never()).retireDraft(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void templateRejectRequiresAnIndependentReviewerAndRecordsTheReason() {
        UUID revisionId = UUID.randomUUID();
        TemplateRevision draft = template(revisionId, author.userId(), "DRAFT");
        TemplateRevision retired = template(revisionId, author.userId(), "RETIRED");
        Request receipt = receipt("template-reject", "TENANT_NOTIFICATION_TEMPLATE_DRAFT_REJECT");
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt);
        when(templates.revision(42L, revisionId))
                .thenReturn(Optional.of(draft))
                .thenReturn(Optional.of(retired));
        when(templates.rejectDraft(42L, 29L, revisionId, 1)).thenReturn(true);

        TemplateRevision result = service.rejectTemplateDraft(
                reviewer, revisionId, decision, "template-reject");

        assertThat(result.state()).isEqualTo("RETIRED");
        verify(idempotency).begin(
                reviewer,
                "template-reject",
                "TENANT_NOTIFICATION_TEMPLATE_DRAFT_REJECT",
                Map.of("draftId", revisionId, "request", decision));
        verify(templates).rejectDraft(42L, 29L, revisionId, 1);
        verify(audit).record(argThat(event ->
                "notification.template.draft.rejected".equals(event.action())
                        && "DENY".equals(event.policyDecision())
                        && decision.reason().equals(event.reason())));
    }

    @Test
    void templateAuthorCannotRejectTheirOwnDraft() {
        UUID revisionId = UUID.randomUUID();
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(
                receipt("template-reject", "TENANT_NOTIFICATION_TEMPLATE_DRAFT_REJECT"));
        when(templates.revision(42L, revisionId))
                .thenReturn(Optional.of(template(revisionId, author.userId(), "DRAFT")));

        assertForbidden(() -> service.rejectTemplateDraft(
                author, revisionId, decision, "template-reject"));
        verify(templates, never()).rejectDraft(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void aClosedTemplateDraftReturnsConflict() {
        UUID revisionId = UUID.randomUUID();
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(
                receipt("template-reject", "TENANT_NOTIFICATION_TEMPLATE_DRAFT_REJECT"));
        when(templates.revision(42L, revisionId))
                .thenReturn(Optional.of(template(revisionId, author.userId(), "RETIRED")));

        assertCode(
                () -> service.rejectTemplateDraft(
                        reviewer, revisionId, decision, "template-reject"),
                NotificationErrorCode.NOTIFICATION_STALE_VERSION);
    }

    @Test
    void aTemplateOutsideTheActorTenantReturnsNotFound() {
        UUID revisionId = UUID.randomUUID();
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(
                receipt("template-withdraw", "TENANT_NOTIFICATION_TEMPLATE_DRAFT_RETIRE"));
        when(templates.revision(42L, revisionId)).thenReturn(Optional.empty());

        assertCode(
                () -> service.withdrawTemplateDraft(
                        author, revisionId, decision, "template-withdraw"),
                NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    void duplicatePolicyWithdrawalReplaysWithoutASecondStateTransitionOrAudit() {
        UUID policyId = UUID.randomUUID();
        TenantPolicy replay = policy(policyId, author.userId(), "RETIRED");
        Request receipt = new Request(
                "policy-withdraw",
                "TENANT_NOTIFICATION_POLICY_DRAFT_WITHDRAW",
                "hash",
                "{}");
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt);
        when(idempotency.replay(receipt, TenantPolicy.class)).thenReturn(replay);

        assertThat(service.withdrawPolicyDraft(author, policyId, decision, "policy-withdraw"))
                .isSameAs(replay);
        verifyNoInteractions(policyReader, policyDrafts, audit);
    }

    @Test
    void duplicatePolicyRejectionReplaysWithoutASecondStateTransitionOrAudit() {
        UUID policyId = UUID.randomUUID();
        TenantPolicy replay = policy(policyId, author.userId(), "RETIRED");
        Request receipt = new Request(
                "policy-reject",
                "TENANT_NOTIFICATION_POLICY_DRAFT_REJECT",
                "hash",
                "{}");
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt);
        when(idempotency.replay(receipt, TenantPolicy.class)).thenReturn(replay);

        assertThat(service.rejectPolicyDraft(
                reviewer, policyId, decision, "policy-reject")).isSameAs(replay);
        verifyNoInteractions(policyReader, policyDrafts, audit);
    }

    @Test
    void duplicateTemplateWithdrawalReplaysWithoutASecondStateTransitionOrAudit() {
        UUID revisionId = UUID.randomUUID();
        TemplateRevision replay = template(revisionId, author.userId(), "RETIRED");
        Request receipt = new Request(
                "template-withdraw",
                "TENANT_NOTIFICATION_TEMPLATE_DRAFT_RETIRE",
                "hash",
                "{}");
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt);
        when(idempotency.replay(receipt, TemplateRevision.class)).thenReturn(replay);

        assertThat(service.withdrawTemplateDraft(
                author, revisionId, decision, "template-withdraw")).isSameAs(replay);
        verifyNoInteractions(policyReader, policyDrafts, templates, audit);
    }

    @Test
    void duplicateTemplateRejectionReplaysWithoutASecondStateTransitionOrAudit() {
        UUID revisionId = UUID.randomUUID();
        TemplateRevision replay = template(revisionId, author.userId(), "RETIRED");
        Request receipt = new Request(
                "template-reject",
                "TENANT_NOTIFICATION_TEMPLATE_DRAFT_REJECT",
                "hash",
                "{}");
        when(idempotency.begin(any(), any(), any(), any())).thenReturn(receipt);
        when(idempotency.replay(receipt, TemplateRevision.class)).thenReturn(replay);

        assertThat(service.rejectTemplateDraft(
                reviewer, revisionId, decision, "template-reject")).isSameAs(replay);
        verifyNoInteractions(policyReader, policyDrafts, templates, audit);
    }

    private void assertForbidden(Runnable action) {
        assertCode(action, NotificationErrorCode.FORBIDDEN);
    }

    private void assertCode(Runnable action, NotificationErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(expected));
    }

    private NotificationRequestContext.Actor actor(long userId) {
        return new NotificationRequestContext.Actor(
                42L, userId, Set.of("TENANT_ADMIN"), Set.of(), false, "dwp-gateway");
    }

    private Request receipt(String key, String operation) {
        return new Request(key, operation, "hash", null);
    }

    private TenantPolicy policy(UUID policyId, Long createdBy, String state) {
        return new TenantPolicy(
                policyId,
                "APP",
                "messaging",
                "Messenger",
                "TENANT_POLICY",
                state,
                false,
                false,
                "IMMEDIATE",
                List.of(new PolicyChannelRule("IN_APP", true, "IMMEDIATE", true, 100)),
                "Protect user attention with governed routing",
                createdBy,
                null,
                null,
                "1",
                Instant.parse("2026-09-08T00:00:00Z"));
    }

    private TemplateRevision template(UUID revisionId, Long createdBy, String state) {
        return new TemplateRevision(
                revisionId,
                UUID.fromString("cf55f6c4-42b1-46d6-bfbf-f19941555e5f"),
                "MESSAGING.DIRECT_MESSAGE",
                "messaging",
                "IN_APP",
                "ko-KR",
                state,
                1,
                new TemplateContent("Message", "Preview", "Body", "Open"),
                "a".repeat(64),
                "Align the content with the company notification standard.",
                createdBy,
                null,
                null,
                null,
                "1",
                Instant.parse("2026-09-08T00:00:00Z"));
    }
}
