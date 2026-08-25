package com.dwp.services.approval.domain;

import com.dwp.services.approval.security.ApprovalRequestContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Pure response projection for Approval home insights and operational signals. */
final class ApprovalResponseAssembler {

    private ApprovalResponseAssembler() {
    }

    static ApprovalDtos.OperationsResponse operations(
            ApprovalQueryRepository queries,
            ApprovalRequestContext.Actor actor) {
        int failed = queries.failedIntegrationCount(actor.tenantId());
        int pending = queries.pendingIntegrationCount(actor.tenantId());
        int delegations = queries.activeDelegationCount(actor.tenantId());
        int overdue = queries.adminPulse(actor.tenantId()).overdueTasks();
        return new ApprovalDtos.OperationsResponse(
                Instant.now(),
                List.of(
                        signal("sla", overdue == 0 ? "HEALTHY" : "ATTENTION",
                                "SLA 준수", "SLA assurance",
                                "기한을 넘긴 결재를 추적합니다.",
                                "Track approval tasks beyond their due time.", overdue),
                        signal("integration", failed == 0 ? "HEALTHY" : "DEGRADED",
                                "통합 전달", "Integration delivery",
                                "실패·격리된 아웃박스 이벤트를 감시합니다.",
                                "Monitor failed and isolated outbox events.", failed),
                        signal("outbox", pending < 25 ? "HEALTHY" : "ATTENTION",
                                "이벤트 백로그", "Event backlog",
                                "외부 전달을 기다리는 이벤트입니다.",
                                "Events waiting for external delivery.", pending),
                        signal("delegation", "INFORMATIONAL",
                                "활성 위임", "Active delegations",
                                "현재 효력이 있는 사용자 위임입니다.",
                                "User delegations currently in effect.", delegations)),
                queries.breachedTasks(actor.tenantId(), 20),
                queries.integrationDeliveries(actor.tenantId(), 50));
    }

    private static ApprovalDtos.OperationSignal signal(
            String key, String status, String labelKo, String labelEn,
            String descriptionKo, String descriptionEn, int count) {
        return new ApprovalDtos.OperationSignal(
                key, status, labelKo, labelEn, descriptionKo, descriptionEn, count);
    }

    static List<ApprovalDtos.DecisionInsight> insights(
            ApprovalDtos.ApprovalMetrics metrics,
            ApprovalDtos.AdminPulse pulse) {
        List<ApprovalDtos.DecisionInsight> values = new ArrayList<>();
        if (metrics.overdue() > 0) {
            values.add(new ApprovalDtos.DecisionInsight(
                    "overdue", "critical",
                    "기한을 넘긴 결정이 있습니다", "Decisions have passed their due time",
                    "리스크가 높은 항목부터 검토해 업무 지연을 줄이세요.",
                    "Review high-risk items first to reduce downstream delay.",
                    "/approvals/inbox"));
        }
        if (metrics.needsInformation() > 0) {
            values.add(new ApprovalDtos.DecisionInsight(
                    "needs-info", "warning",
                    "추가 정보 응답이 필요합니다", "More information is required",
                    "요청자 응답을 완료하면 결재 흐름이 다시 시작됩니다.",
                    "Respond to restart the approval flow.",
                    "/approvals/requests/needs-info"));
        }
        if (pulse != null && pulse.draftWorkflows() > 0) {
            values.add(new ApprovalDtos.DecisionInsight(
                    "draft-workflow", "info",
                    "게시 대기 중인 프로세스가 있습니다", "A process is waiting to be published",
                    "설계자와 게시 책임자의 직무 분리 검토가 필요합니다.",
                    "Designer and publisher separation requires review.",
                    "/approvals/admin/workflows"));
        }
        if (values.isEmpty()) {
            values.add(new ApprovalDtos.DecisionInsight(
                    "healthy", "success",
                    "결재 흐름이 안정적입니다", "Approval flow is healthy",
                    "현재 즉시 조치가 필요한 SLA 또는 통합 문제가 없습니다.",
                    "No SLA or integration issue requires immediate action.",
                    "/approvals/inbox"));
        }
        return List.copyOf(values);
    }

    static ApprovalDtos.ApprovalMetrics emptyMetrics() {
        return new ApprovalDtos.ApprovalMetrics(0, 0, 0, 0, 0, 0, 100);
    }
}
