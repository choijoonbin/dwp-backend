package com.dwp.services.approval.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalHighRiskCommandGuard;
import com.dwp.services.approval.security.ApprovalOwnerPredicateEvaluator;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.when;

class ApprovalServiceTest {

    private final ApprovalQueryRepository queries = mock(ApprovalQueryRepository.class);
    private final ApprovalCommandRepository commands = mock(ApprovalCommandRepository.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
    private final ApprovalService service = new ApprovalService(queries, commands, audit, identities);

    @BeforeEach
    void setContext() {
        ApprovalRequestContext.set(
                17L,
                42L,
                null,
                Set.of("FINANCE_APPROVERS"),
                Set.of(
                        "APP.APPROVALS:VIEW",
                        "ACTION.APPROVAL_TASK:VIEW",
                        "ACTION.APPROVAL_TASK:UPDATE",
                        "ACTION.APPROVAL_TASK:APPROVE"));
    }

    @AfterEach
    void clearContext() {
        ApprovalRequestContext.clear();
    }

    @Test
    void exposesDecisionActionsOnlyWhileTheTaskIsOpen() {
        UUID taskId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ApprovalDtos.TaskSummary pending = summary(taskId, requestId, "PENDING");
        ApprovalDtos.TaskSummary approved = summary(taskId, requestId, "APPROVED");

        when(queries.requestPayload(42L, requestId)).thenReturn(Map.of());
        when(queries.requestFormSchema(42L, requestId)).thenReturn(Map.of(
                "schemaVersion", 1,
                "fields", List.of(Map.of("key", "summary", "labelKo", "요청 개요"))));
        when(queries.timeline(42L, requestId)).thenReturn(List.of());
        when(queries.taskDetail(ApprovalRequestContext.require(), taskId))
                .thenReturn(new ApprovalQueryRepository.TaskAccess(
                        pending, 99L, null, "FINANCE_APPROVERS", false, null))
                .thenReturn(new ApprovalQueryRepository.TaskAccess(
                        approved, 99L, 17L, "FINANCE_APPROVERS", false, null));

        ApprovalDtos.TaskDetail open = service.task(taskId);
        ApprovalDtos.TaskDetail completed = service.task(taskId);

        assertThat(open.canClaim()).isTrue();
        assertThat(open.canDecide()).isTrue();
        assertThat(open.formSchema()).containsEntry("schemaVersion", 1);
        assertThat(completed.canClaim()).isFalse();
        assertThat(completed.canDecide()).isFalse();
    }

    @Test
    void blocksSelfApprovalInTheDetailContract() {
        UUID taskId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ApprovalDtos.TaskSummary pending = summary(taskId, requestId, "PENDING");
        when(queries.taskDetail(ApprovalRequestContext.require(), taskId))
                .thenReturn(new ApprovalQueryRepository.TaskAccess(
                        pending, 17L, 17L, "FINANCE_APPROVERS", false, null));
        when(queries.requestPayload(42L, requestId)).thenReturn(Map.of());
        when(queries.timeline(42L, requestId)).thenReturn(List.of());
        when(queries.isBlockingPolicyActive(
                42L, "BLOCK_SELF_APPROVAL", "RS_APPROVALS")).thenReturn(true);

        ApprovalDtos.TaskDetail detail = service.task(taskId);

        assertThat(detail.selfApprovalBlocked()).isTrue();
        assertThat(detail.canDecide()).isFalse();
    }

    @Test
    void exposesDelegatedTasksWithoutGrantingSelfApproval() {
        UUID taskId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ApprovalDtos.TaskSummary pending = summary(taskId, requestId, "PENDING");
        when(queries.taskDetail(ApprovalRequestContext.require(), taskId))
                .thenReturn(new ApprovalQueryRepository.TaskAccess(
                        pending, 99L, 23L, "FINANCE_APPROVERS", true, 23L));
        when(queries.requestPayload(42L, requestId)).thenReturn(Map.of());
        when(queries.timeline(42L, requestId)).thenReturn(List.of());
        when(identities.require(42L, 23L))
                .thenReturn(subject(23L, List.of("FINANCE_APPROVERS")));

        ApprovalDtos.TaskDetail detail = service.task(taskId);

        assertThat(detail.canClaim()).isFalse();
        assertThat(detail.canDecide()).isTrue();
        assertThat(detail.selfApprovalBlocked()).isFalse();
    }

    @Test
    void acceptsDelegationOfADirectAssignmentWhileTheOriginalAssigneeIsActive() {
        UUID taskId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ApprovalDtos.TaskSummary pending = summary(taskId, requestId, "PENDING");
        ApprovalQueryRepository.TaskAccess access = new ApprovalQueryRepository.TaskAccess(
                pending, 99L, 23L, "FINANCE_APPROVERS", true, 23L);
        when(queries.taskDetail(ApprovalRequestContext.require(), taskId)).thenReturn(access);
        when(identities.require(42L, 23L)).thenReturn(subject(23L, List.of()));
        when(queries.requestPayload(42L, requestId)).thenReturn(Map.of());
        when(queries.timeline(42L, requestId)).thenReturn(List.of());

        ApprovalWorkflowCommandFenceTestSupport.runWithoutLiveDatabase(
                () -> service.claim(taskId, 0L, "direct-delegation"));

        verify(commands).claim(ApprovalRequestContext.require(), access, 0L, "direct-delegation");
        verify(queries, times(2)).taskDetail(ApprovalRequestContext.require(), taskId);
        ArgumentCaptor<com.dwp.audit.AuditEvent> event =
                ArgumentCaptor.forClass(com.dwp.audit.AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().category()).isEqualTo("SYSTEM_EVENT");
    }

    @Test
    void rejectsRoleBasedDelegationAfterTheOriginalApproverLosesTheRole() {
        UUID taskId = UUID.randomUUID();
        ApprovalDtos.TaskSummary pending = summary(taskId, UUID.randomUUID(), "PENDING");
        ApprovalQueryRepository.TaskAccess access = new ApprovalQueryRepository.TaskAccess(
                pending, 99L, null, "FINANCE_APPROVERS", true, 23L);
        when(queries.taskDetail(ApprovalRequestContext.require(), taskId)).thenReturn(access);
        when(identities.require(42L, 23L)).thenReturn(subject(23L, List.of("WORKSPACE_MEMBER")));

        ApprovalWorkflowCommandFenceTestSupport.runWithoutLiveDatabase(() ->
                assertThatThrownBy(() -> service.claim(taskId, 0L, "stale-role-delegation"))
                        .isInstanceOf(com.dwp.core.exception.BaseException.class)
                        .hasMessageContaining("no longer holds"));
    }

    @Test
    void rejectsAClaimedDelegatedReadAfterTheOriginalApproverLosesTheRole() {
        UUID taskId = UUID.randomUUID();
        ApprovalDtos.TaskSummary pending = summary(taskId, UUID.randomUUID(), "CLAIMED");
        ApprovalQueryRepository.TaskAccess access = new ApprovalQueryRepository.TaskAccess(
                pending, 99L, 17L, "FINANCE_APPROVERS", "RS_APPROVALS",
                false, 23L, "FINANCE_APPROVERS");
        when(queries.taskDetail(ApprovalRequestContext.require(), taskId)).thenReturn(access);
        when(identities.require(42L, 23L)).thenReturn(subject(23L, List.of()));

        assertThatThrownBy(() -> service.task(taskId))
                .isInstanceOf(com.dwp.core.exception.BaseException.class)
                .hasMessageContaining("no longer holds");
        verify(queries, never()).requestPayload(42L, pending.requestId());
    }

    @Test
    void redactsSupersededContentWhenCurrentOwnerAuthorityIsRevoked() {
        UUID taskId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ApprovalDtos.TaskSummary completed = summary(taskId, requestId, "SUPERSEDED");
        ApprovalQueryRepository.TaskAccess access = new ApprovalQueryRepository.TaskAccess(
                completed, 99L, 17L, "FINANCE_APPROVERS", false, null);
        ApprovalOwnerPredicateEvaluator owner = mock(ApprovalOwnerPredicateEvaluator.class);
        ApprovalService governed = new ApprovalService(
                queries, commands, audit, identities,
                mock(ApprovalHighRiskCommandGuard.class), owner);
        when(queries.taskDetail(ApprovalRequestContext.require(), taskId)).thenReturn(access);
        when(owner.completedContentAccess(ApprovalRequestContext.require(), access))
                .thenReturn(new ApprovalOwnerPredicateEvaluator.ContentReadDecision(
                        false, "CURRENT_PERMISSION_REVOKED"));

        ApprovalDtos.TaskDetail detail = governed.task(taskId);

        assertThat(detail.contentAccess().state()).isEqualTo("REDACTED");
        assertThat(detail.contentAccess().reason()).isEqualTo("CURRENT_PERMISSION_REVOKED");
        assertThat(detail.task().title()).isEqualTo("Restricted approval");
        assertThat(detail.task().summary()).isEmpty();
        assertThat(detail.payload()).isEmpty();
        assertThat(detail.formSchema()).isEmpty();
        assertThat(detail.timeline()).isEmpty();
        assertThat(detail.canClaim()).isFalse();
        assertThat(detail.canDecide()).isFalse();
        verify(queries, never()).requestPayload(42L, requestId);
        verify(queries, never()).decisionPayload(42L, taskId);
        verify(queries, never()).requestFormSchema(42L, requestId);
        verify(queries, never()).timeline(42L, requestId);
    }

    @Test
    void updatesAnOwnedDelegationWithVersionedIdempotentAuditEvidence() {
        UUID delegationId = UUID.randomUUID();
        Instant startsAt = Instant.now().plusSeconds(60);
        ApprovalDelegationUpdateRequest request = new ApprovalDelegationUpdateRequest(
                23L, "ALL", null, startsAt, startsAt.plusSeconds(86_400),
                "Cover approvals during the finance close", 4L);
        ApprovalDelegationCommandSupport.UpdateReplay replay =
                new ApprovalDelegationCommandSupport.UpdateReplay(
                        false, "delegation-update-4", "a".repeat(64), UUID.randomUUID());
        ApprovalDelegationCommandSupport.Updated updated =
                new ApprovalDelegationCommandSupport.Updated(
                        delegationId, replay.idempotencyKey(), replay.fingerprint(), 5L);
        ApprovalIdentityDirectory.Subject delegate = subject(23L, List.of("FINANCE_APPROVERS"));
        when(commands.delegationUpdateReplay(
                ApprovalRequestContext.require(), delegationId, request, "delegation-update-4"))
                .thenReturn(replay);
        when(identities.require(42L, 23L)).thenReturn(delegate);
        when(commands.updateDelegation(
                ApprovalRequestContext.require(), delegationId, request, delegate, replay))
                .thenReturn(updated);
        when(queries.delegations(ApprovalRequestContext.require())).thenReturn(List.of());

        assertThat(service.updateDelegation(
                delegationId, request, "delegation-update-4", "correlation-4")).isEmpty();

        ArgumentCaptor<com.dwp.audit.AuditEvent> event =
                ArgumentCaptor.forClass(com.dwp.audit.AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().action()).isEqualTo("approval.delegation.updated");
        assertThat(event.getValue().targetId()).isEqualTo(delegationId.toString());
        assertThat(event.getValue().afterState())
                .containsEntry("idempotencyKey", "delegation-update-4")
                .containsEntry("commandFingerprint", "a".repeat(64))
                .containsEntry("version", 5L);
    }

    @Test
    void returnsTheCurrentProjectionForAnExactDelegationUpdateReplay() {
        UUID delegationId = UUID.randomUUID();
        Instant startsAt = Instant.now().plusSeconds(60);
        ApprovalDelegationUpdateRequest request = new ApprovalDelegationUpdateRequest(
                23L, "ALL", null, startsAt, startsAt.plusSeconds(86_400),
                "Cover approvals during the finance close", 4L);
        ApprovalDelegationCommandSupport.UpdateReplay replay =
                new ApprovalDelegationCommandSupport.UpdateReplay(
                        true, "delegation-update-4", "b".repeat(64), UUID.randomUUID());
        when(commands.delegationUpdateReplay(
                ApprovalRequestContext.require(), delegationId, request, "delegation-update-4"))
                .thenReturn(replay);
        when(queries.delegations(ApprovalRequestContext.require())).thenReturn(List.of());

        assertThat(service.updateDelegation(
                delegationId, request, "delegation-update-4", "correlation-4")).isEmpty();

        verify(commands, never()).updateDelegation(
                same(ApprovalRequestContext.require()), eq(delegationId), same(request),
                org.mockito.ArgumentMatchers.any(), same(replay));
        verify(identities, never()).require(42L, 23L);
        verify(audit, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsWorkflowPublicationWhenACandidateRoleIsInactive() {
        UUID workflowId = UUID.randomUUID();
        when(queries.workflowCandidateRoles(42L, workflowId))
                .thenReturn(List.of("FINANCE_APPROVERS"));
        when(identities.requireRole(42L, "FINANCE_APPROVERS"))
                .thenReturn(new ApprovalIdentityDirectory.RoleEligibility(
                        42L, "FINANCE_APPROVERS", "INACTIVE", 3, true));

        assertThatThrownBy(() -> service.publishWorkflow(
                workflowId, 2L, "inactive-role"))
                .isInstanceOf(com.dwp.core.exception.BaseException.class)
                .hasMessageContaining("inactive or has no eligible approver");
        verify(commands, never()).publishWorkflow(
                ApprovalRequestContext.require(), workflowId, 2L, "inactive-role");
    }

    @Test
    void rejectsSubmissionWhenACandidateRoleHasNoEligibleUsers() {
        UUID requestId = UUID.randomUUID();
        when(queries.requestCandidateRoles(ApprovalRequestContext.require(), requestId))
                .thenReturn(List.of("FINANCE_APPROVERS"));
        when(identities.requireRole(42L, "FINANCE_APPROVERS"))
                .thenReturn(new ApprovalIdentityDirectory.RoleEligibility(
                        42L, "FINANCE_APPROVERS", "ACTIVE", 0, false));

        assertThatThrownBy(() -> service.submit(requestId, 1L, "unstaffed-role"))
                .isInstanceOf(com.dwp.core.exception.BaseException.class)
                .hasMessageContaining("inactive or has no eligible approver");
        verify(commands, never()).submit(
                ApprovalRequestContext.require(), requestId, 1L, "unstaffed-role");
    }

    @Test
    void reportsAnOwnedLegacyDraftReadyOnlyAfterAuthoritativeServerChecks() {
        UUID requestId = UUID.randomUUID();
        when(queries.requestCandidateRoles(ApprovalRequestContext.require(), requestId))
                .thenReturn(List.of("FINANCE_APPROVERS"));
        when(identities.requireRole(42L, "FINANCE_APPROVERS"))
                .thenReturn(new ApprovalIdentityDirectory.RoleEligibility(
                        42L, "FINANCE_APPROVERS", "ACTIVE", 2, true));

        ApprovalDtos.RequestPreflight result = service.preflight(requestId, 7L);

        assertThat(result.ready()).isTrue();
        assertThat(result.expectedVersion()).isEqualTo(7L);
        assertThat(result.workflowContract()).isEqualTo("LEGACY_SEQUENTIAL");
        assertThat(result.checks())
                .extracting(ApprovalDtos.RequestPreflightCheck::code)
                .containsExactly(
                        "OWNED_DRAFT",
                        "CONCURRENCY",
                        "FORM_SCHEMA_AND_ROUTE",
                        "ATTACHMENT_EVIDENCE",
                        "CANDIDATE_AUTHORITY");
        verify(commands).preflightLegacySubmit(
                ApprovalRequestContext.require(), requestId, 7L);
    }

    @Test
    void failsClosedWhenTheDraftVersionChangesBeforePreflight() {
        UUID requestId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new com.dwp.core.exception.BaseException(
                        com.dwp.core.common.ErrorCode.RESOURCE_CONFLICT))
                .when(commands)
                .preflightLegacySubmit(ApprovalRequestContext.require(), requestId, 7L);

        assertThatThrownBy(() -> service.preflight(requestId, 7L))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);

        verify(queries, never()).requestCandidateRoles(
                ApprovalRequestContext.require(), requestId);
    }

    @Test
    void retriesAnIsolatedIntegrationDeliveryWithExtendedAuditEvidence() {
        UUID outboxId = UUID.randomUUID();
        when(queries.adminPulse(42L)).thenReturn(new ApprovalDtos.AdminPulse(
                1, 0, 2, 0, 1, List.of()));
        when(queries.breachedTasks(42L, 20)).thenReturn(List.of());
        when(queries.integrationDeliveries(ApprovalRequestContext.require(), 50))
                .thenReturn(List.of());

        ApprovalDtos.OperationsResponse response = service.retryIntegrationDelivery(
                outboxId, "approval-retry-correlation");

        verify(commands).retryIntegrationDelivery(ApprovalRequestContext.require(), outboxId);
        ArgumentCaptor<com.dwp.audit.AuditEvent> event =
                ArgumentCaptor.forClass(com.dwp.audit.AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().action())
                .isEqualTo("approval.integration.delivery.retried");
        assertThat(event.getValue().category()).isEqualTo("ADMIN_CHANGE");
        assertThat(event.getValue().targetType())
                .isEqualTo("APPROVAL_INTEGRATION_EVENT");
        assertThat(event.getValue().targetId()).isEqualTo(outboxId.toString());
        assertThat(event.getValue().correlationId())
                .isEqualTo("approval-retry-correlation");
        assertThat(event.getValue().afterState())
                .containsEntry("outboxId", outboxId.toString());
        assertThat(event.getValue().retentionClass()).isEqualTo("EXTENDED");
        assertThat(response.integrationDeliveries()).isEmpty();
    }

    @Test
    void bindsHeaderVersionedRecoveryToAnEmptyCanonicalPayload() {
        UUID outboxId = UUID.randomUUID();
        long expectedVersion = 2L;
        ApprovalHighRiskCommandGuard guard = mock(ApprovalHighRiskCommandGuard.class);
        ApprovalService governed = new ApprovalService(
                queries, commands, audit, identities, guard,
                mock(ApprovalOwnerPredicateEvaluator.class));
        ApprovalStepUpHeaders headers = ApprovalStepUpHeaders.of(
                "signed-challenge", "retry-idempotency", "D-A-OPS-R1", expectedVersion);
        when(queries.adminPulse(42L)).thenReturn(new ApprovalDtos.AdminPulse(
                0, 0, 0, 0, 0, List.of()));
        when(queries.breachedTasks(42L, 20)).thenReturn(List.of());
        when(queries.integrationDeliveries(ApprovalRequestContext.require(), 50))
                .thenReturn(List.of());

        governed.retryIntegrationDelivery(
                outboxId, expectedVersion, "retry-correlation", headers);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(guard).begin(
                eq(ApprovalRequestContext.require()),
                eq("approvals.operations.execute"),
                eq("OUTBOX_EVENT"),
                eq(outboxId),
                eq(expectedVersion),
                eq("/api/approvals/v1/admin/operations/events/" + outboxId + "/retry"),
                payload.capture(),
                same(headers));
        assertThat(payload.getValue()).isEqualTo(Map.of());
        verify(commands).retryIntegrationDelivery(
                ApprovalRequestContext.require(), outboxId, expectedVersion);
    }

    @Test
    void buildsThePersonalHomeFlowFromTheVerifiedActorScope() {
        ApprovalRequestContext.Actor actor = ApprovalRequestContext.require();
        ApprovalDtos.ApprovalMetrics metrics = new ApprovalDtos.ApprovalMetrics(
                1, 0, 1, 0, 2, 8.5, 95.0);
        List<ApprovalDtos.StageMetric> flow = List.of(
                new ApprovalDtos.StageMetric("IN_REVIEW", 3, 1));
        when(queries.metrics(actor)).thenReturn(metrics);
        when(queries.tasks(actor, "INBOX", 6)).thenReturn(List.of());
        when(queries.requests(actor, "SUBMITTED", 5)).thenReturn(List.of());
        when(queries.flow(actor)).thenReturn(flow);

        ApprovalDtos.HomeResponse home = service.home();

        assertThat(home.flow()).isEqualTo(flow);
        assertThat(home.administrator()).isFalse();
        verify(queries).flow(actor);
    }

    @Test
    void doesNotQueryTaskOrRequestFlowWithoutEitherReadPermission() {
        ApprovalRequestContext.set(
                17L,
                42L,
                null,
                Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.APPROVALS:VIEW"));
        ApprovalRequestContext.Actor actor = ApprovalRequestContext.require();

        ApprovalDtos.HomeResponse home = service.home();

        assertThat(home.flow()).isEmpty();
        assertThat(home.focusQueue()).isEmpty();
        assertThat(home.recentRequests()).isEmpty();
        verify(queries, org.mockito.Mockito.never()).metrics(actor);
        verify(queries, org.mockito.Mockito.never()).flow(actor);
    }

    @Test
    void doesNotExposeOperationsPulseForAnUnrelatedApprovalAdminPermission() {
        ApprovalRequestContext.set(
                17L,
                42L,
                null,
                Set.of("APPROVAL_DESIGNER"),
                Set.of("APP.APPROVALS:VIEW", "ADMIN.APPROVAL_DESIGN:VIEW"));

        ApprovalDtos.HomeResponse home = service.home();

        assertThat(home.administrator()).isFalse();
        assertThat(home.adminPulse()).isNull();
        verify(queries, never()).adminPulse(42L);
    }

    @Test
    void exposesOperationsPulseOnlyWithTheExactOperationsReadContract() {
        ApprovalRequestContext.set(
                17L,
                42L,
                null,
                Set.of("APPROVAL_OPERATOR"),
                Set.of("APP.APPROVALS:VIEW", "ADMIN.APPROVAL_OPERATIONS:VIEW"));
        ApprovalDtos.AdminPulse pulse = new ApprovalDtos.AdminPulse(1, 2, 3, 4, 5, List.of());
        when(queries.adminPulse(42L)).thenReturn(pulse);

        ApprovalDtos.HomeResponse home = service.home();

        assertThat(home.administrator()).isTrue();
        assertThat(home.adminPulse()).isEqualTo(pulse);
        verify(queries).adminPulse(42L);
    }

    @Test
    void hidesMutationActionsWhenTheActorOnlyHasTaskReadPermission() {
        ApprovalRequestContext.set(
                17L,
                42L,
                null,
                Set.of("FINANCE_APPROVERS"),
                Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_TASK:VIEW"));
        UUID taskId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(queries.taskDetail(ApprovalRequestContext.require(), taskId))
                .thenReturn(new ApprovalQueryRepository.TaskAccess(
                        summary(taskId, requestId, "PENDING"),
                        99L,
                        null,
                        "FINANCE_APPROVERS",
                        false,
                        null));
        when(queries.requestPayload(42L, requestId)).thenReturn(Map.of());
        when(queries.requestFormSchema(42L, requestId)).thenReturn(Map.of());
        when(queries.timeline(42L, requestId)).thenReturn(List.of());

        ApprovalDtos.TaskDetail detail = service.task(taskId);

        assertThat(detail.canClaim()).isFalse();
        assertThat(detail.canDecide()).isFalse();
    }

    private ApprovalDtos.TaskSummary summary(UUID taskId, UUID requestId, String status) {
        return new ApprovalDtos.TaskSummary(
                taskId,
                requestId,
                "APR-2026-0001",
                "Capital request",
                "Evidence-backed approval",
                "투자·구매 승인",
                "Capital purchase approval",
                "PRIMARY_REVIEW",
                "Primary review",
                1,
                "Requester",
                "Finance",
                status,
                "HIGH",
                "CONFIDENTIAL",
                72,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                0L);
    }

    private ApprovalIdentityDirectory.Subject subject(long userId, List<String> roles) {
        return new ApprovalIdentityDirectory.Subject(
                42L,
                userId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Delegator",
                "delegator@sk.com",
                "Approver",
                "ACTIVE",
                roles);
    }
}
