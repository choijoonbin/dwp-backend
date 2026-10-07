package com.dwp.services.approval.domain;

import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import java.time.Instant;
import java.util.List;

final class ApprovalHomeAssembler {

    private ApprovalHomeAssembler() { }

    static ApprovalDtos.HomeResponse assemble(
            ApprovalQueryRepository queries, ApprovalRequestContext.Actor actor) {
        boolean governedWorkSurface = ApprovalPilotAuthorizationContext.current().isPresent();
        boolean canUseTasks = governedWorkSurface
                ? actor.hasPermission("ACTION.APPROVAL_TASK", "VIEW")
                : actor.hasPermission("ACTION.APPROVAL_TASK", "VIEW", "MANAGE");
        boolean canUseRequests = governedWorkSurface
                ? actor.hasPermission("ACTION.APPROVAL_REQUEST", "VIEW")
                : actor.hasPermission("ACTION.APPROVAL_REQUEST", "VIEW", "MANAGE");
        ApprovalDtos.ApprovalMetrics rawMetrics = canUseTasks || canUseRequests
                ? queries.metrics(actor)
                : ApprovalResponseAssembler.emptyMetrics();
        ApprovalDtos.ApprovalMetrics metrics = new ApprovalDtos.ApprovalMetrics(
                canUseTasks ? rawMetrics.pending() : 0,
                canUseTasks ? rawMetrics.dueToday() : 0,
                canUseTasks ? rawMetrics.overdue() : 0,
                canUseTasks ? rawMetrics.needsInformation() : 0,
                canUseRequests ? rawMetrics.myRequestsInFlight() : 0,
                canUseRequests ? rawMetrics.averageCycleHours() : 0,
                canUseRequests ? rawMetrics.slaCompliancePercent() : 100);
        List<ApprovalDtos.TaskSummary> tasks = canUseTasks
                ? queries.tasks(actor, "INBOX", 6) : List.of();
        List<ApprovalDtos.RequestSummary> requests = canUseRequests
                ? queries.requests(actor, "SUBMITTED", 5) : List.of();
        boolean canViewOperations = !governedWorkSurface && actor.hasPermission(
                "ADMIN.APPROVAL_OPERATIONS", "VIEW", "MANAGE");
        ApprovalDtos.AdminPulse pulse = canViewOperations
                ? queries.adminPulse(actor.tenantId()) : null;
        return new ApprovalDtos.HomeResponse(
                Instant.now(), metrics, tasks, requests,
                canUseTasks || canUseRequests ? queries.flow(actor) : List.of(),
                ApprovalResponseAssembler.insights(metrics, pulse),
                canViewOperations, pulse);
    }
}
