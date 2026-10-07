package com.dwp.services.approval.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.security.ApprovalRequestContext;
import java.util.List;
import java.util.Map;

final class ApprovalServiceAudit {

    private ApprovalServiceAudit() { }

    static void record(
            AuditOutboxRecorder audit,
            ApprovalRequestContext.Actor actor,
            String action,
            String targetType,
            String targetId,
            String correlationId,
            Map<String, Object> afterState) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category(category(targetType))
                .action(action)
                .outcome("SUCCESS")
                .severity("INFO")
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-approval-server")
                .sourceModule("approval-decision-hub")
                .targetType(targetType)
                .targetId(targetId)
                .correlationId(correlationId)
                .approvalId(targetType.equals("APPROVAL_REQUEST") ? targetId : null)
                .afterState(afterState)
                .retentionClass("EXTENDED")
                .build());
    }

    private static String category(String targetType) {
        return switch (targetType) {
            case "APPROVAL_REQUEST", "APPROVAL_TASK" -> "SYSTEM_EVENT";
            default -> "ADMIN_CHANGE";
        };
    }
}
