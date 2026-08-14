package com.dwp.services.approval.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalServiceTest {

    private final ApprovalQueryRepository queries = mock(ApprovalQueryRepository.class);
    private final ApprovalCommandRepository commands = mock(ApprovalCommandRepository.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final ApprovalService service = new ApprovalService(queries, commands, audit);

    @BeforeEach
    void setContext() {
        ApprovalRequestContext.set(
                17L,
                42L,
                null,
                Set.of("FINANCE_APPROVERS"),
                Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_TASK:APPROVE"));
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
        when(queries.timeline(42L, requestId)).thenReturn(List.of());
        when(queries.taskDetail(ApprovalRequestContext.require(), taskId))
                .thenReturn(new ApprovalQueryRepository.TaskAccess(
                        pending, 99L, null, "FINANCE_APPROVERS"))
                .thenReturn(new ApprovalQueryRepository.TaskAccess(
                        approved, 99L, 17L, "FINANCE_APPROVERS"));

        ApprovalDtos.TaskDetail open = service.task(taskId);
        ApprovalDtos.TaskDetail completed = service.task(taskId);

        assertThat(open.canClaim()).isTrue();
        assertThat(open.canDecide()).isTrue();
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
                        pending, 17L, 17L, "FINANCE_APPROVERS"));
        when(queries.requestPayload(42L, requestId)).thenReturn(Map.of());
        when(queries.timeline(42L, requestId)).thenReturn(List.of());

        ApprovalDtos.TaskDetail detail = service.task(taskId);

        assertThat(detail.selfApprovalBlocked()).isTrue();
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
}
