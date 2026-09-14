package com.dwp.services.approval.attachment;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

@Component
public class ApprovalAttachmentAudit {
    private final AuditOutboxRecorder audit;
    public ApprovalAttachmentAudit(AuditOutboxRecorder audit) {this.audit=audit;}
    public void record(long tenant,long actor,UUID request,String action,String key,Map<String,Object> metadata) {
        record(tenant,actor,request,"APPROVAL_REQUEST",action,key,metadata);
    }
    public void recordPolicy(long tenant,long actor,UUID policy,String action,String key,Map<String,Object> metadata) {
        record(tenant,actor,policy,"APPROVAL_ATTACHMENT_POLICY",action,key,metadata);
    }
    private void record(long tenant,long actor,UUID target,String targetType,String action,String key,Map<String,Object> metadata) {
        audit.record(AuditEvent.builder().tenantId(tenant).category("SYSTEM_EVENT").action(action).outcome("SUCCESS")
                .severity("INFO").actorType("USER").actorId(Long.toString(actor)).sourceService("dwp-approval-server")
                .sourceModule("approval-attachments").targetType(targetType).targetId(target.toString())
                .correlationId(key).retentionClass("EXTENDED").afterState(metadata).build());
    }
}
