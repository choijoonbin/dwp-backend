package com.dwp.services.approval.document;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ApprovalDocumentAudit {
    private final AuditOutboxRecorder audit;
    public ApprovalDocumentAudit(AuditOutboxRecorder audit) { this.audit = audit; }
    public void record(ApprovalRequestContext.Actor actor, UUID id, String action, String key, Map<String, Object> metadata) {
        audit.record(AuditEvent.builder().tenantId(actor.tenantId()).category("SYSTEM_EVENT").action(action)
                .outcome("SUCCESS").severity("INFO").actorType("USER").actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles())).sourceService("dwp-approval-server").sourceModule("approval-document-tools")
                .targetType("APPROVAL_DOCUMENT").targetId(id.toString()).correlationId(key).retentionClass("EXTENDED")
                .afterState(metadata).build());
    }
}
