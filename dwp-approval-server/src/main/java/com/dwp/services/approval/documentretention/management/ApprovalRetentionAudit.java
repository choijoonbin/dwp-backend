package com.dwp.services.approval.documentretention.management;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.security.ApprovalRequestContext;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public final class ApprovalRetentionAudit {
    private final AuditOutboxRecorder recorder;
    public ApprovalRetentionAudit(AuditOutboxRecorder recorder) {this.recorder=recorder;}
    public void record(ApprovalRequestContext.Actor actor,UUID target,String action,String key,long version) {
        // Control evidence deliberately contains no record payload or selected record identifiers.
        recorder.record(AuditEvent.builder().tenantId(actor.tenantId()).category("SYSTEM_EVENT").action(action)
                .outcome("SUCCESS").severity("INFO").actorType("USER").actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles())).sourceService("dwp-approval-server").sourceModule("approval-retention")
                .targetType("APPROVAL_RETENTION_CONTROL").targetId(target.toString()).correlationId(key)
                .retentionClass("EXTENDED").afterState(Map.of("controlId",target,"version",version)).build());
    }
}
