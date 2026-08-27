package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.domain.ApprovalCommandRepository;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalQueryRepository;
import com.dwp.services.approval.domain.ApprovalService;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalTaskDetailGovernedActionsTest {

    @AfterEach
    void clear() {
        ApprovalPilotAuthorizationContext.clear();
        ApprovalRequestContext.clear();
    }

    @Test
    void legacyManageCannotExpandGovernedTaskResponseActions() {
        UUID taskId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Set<String> permissions = Set.of(
                "APP.APPROVALS:VIEW",
                "ACTION.APPROVAL_TASK:VIEW",
                "ACTION.APPROVAL_TASK:MANAGE");
        ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(
                new ObjectMapper().findAndRegisterModules());
        ApprovalPilotPepRegistry.Decision detailRoute = registry.authorize(
                new ApprovalPilotPepRegistry.RequestEvidence(
                        "GET", "/v1/tasks/" + taskId, permissions, "", Set.of(),
                        "route.approvals.work.task-detail.data",
                        ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL));
        assertThat(detailRoute.allowed()).isTrue();
        ApprovalPilotAuthorizationContext.set(detailRoute.authorities());
        ApprovalRequestContext.set(17L, 42L, null, Set.of(), permissions);

        ApprovalQueryRepository queries = mock(ApprovalQueryRepository.class);
        ApprovalDtos.TaskSummary summary = new ApprovalDtos.TaskSummary(
                taskId, requestId, "APR-1", "요청", "Request", "검토", "Review",
                "REVIEW", "Review", 1, "Requester", "Finance", "PENDING",
                "NORMAL", "INTERNAL", 1, Instant.parse("2026-08-21T08:00:00Z"),
                Instant.parse("2026-08-22T08:00:00Z"), 3L);
        when(queries.taskDetail(any(), eq(taskId))).thenReturn(
                new ApprovalQueryRepository.TaskAccess(
                        summary, 99L, null, "FINANCE_APPROVERS", true, 23L));
        when(queries.requestPayload(42L, requestId)).thenReturn(Map.of());
        when(queries.requestFormSchema(42L, requestId)).thenReturn(Map.of());
        when(queries.timeline(42L, requestId)).thenReturn(List.of());
        ApprovalService service = new ApprovalService(
                queries,
                mock(ApprovalCommandRepository.class),
                mock(AuditOutboxRecorder.class),
                mock(ApprovalIdentityDirectory.class));

        ApprovalDtos.TaskDetail detail = service.task(taskId);

        assertThat(detail.canClaim()).isFalse();
        assertThat(detail.canDecide()).isFalse();
    }
}
