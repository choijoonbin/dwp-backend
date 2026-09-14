package com.dwp.services.approval.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import java.util.Map;
import java.util.UUID;

final class ApprovalWorkflowQuorumEvidence {
    private final ApprovalWorkflowQuorumRuntimeStore store;
    private final AuditOutboxRecorder audit;

    ApprovalWorkflowQuorumEvidence(ApprovalWorkflowQuorumRuntimeStore store, AuditOutboxRecorder audit) {
        this.store = store;
        this.audit = audit;
    }

    UUID append(long tenant, UUID request, Long actor, String type, Map<String, Object> data) {
        UUID event = UUID.randomUUID();
        String body = store.json(Map.of("specVersion", "1.0", "eventType", type, "tenantId", tenant,
                "requestId", request.toString(), "correlationId", "", "payload", data));
        var p = store.scope(tenant, request).addValue("event", event).addValue("actorType", actor == null ? "SYSTEM" : "USER")
                .addValue("actor", actor == null ? "quorum-runtime" : actor.toString()).addValue("type", type)
                .addValue("data", store.json(data)).addValue("body", body).addValue("hash", store.hash(body))
                .addValue("outbox", UUID.randomUUID()).addValue("originator", actor);
        store.jdbc.update("""
                INSERT INTO apr_request_events(event_id,tenant_id,request_id,event_type,actor_type,actor_id,event_data)
                VALUES(:event,:tenant,:request,:type,:actorType,:actor,CAST(:data AS jsonb))
                """, p);
        store.jdbc.update("""
                INSERT INTO apr_integration_outbox(outbox_id,event_id,tenant_id,request_id,event_type,payload,payload_sha256,
                    event_originator_user_id,recovery_auditor_assignment_state,management_resource_set_key)
                SELECT :outbox,:event,request.tenant_id,request.request_id,:type,CAST(:body AS jsonb),:hash,
                    COALESCE(:originator,request.requester_user_id),'PENDING',request.management_resource_set_key
                  FROM apr_requests request WHERE request.tenant_id=:tenant AND request.request_id=:request
                """, p);
        audit.record(AuditEvent.builder().eventId(event).tenantId(tenant).category("SYSTEM_EVENT").action(type)
                .outcome("SUCCESS").severity("INFO").actorType(actor == null ? "SYSTEM" : "USER")
                .actorId(actor == null ? "quorum-runtime" : actor.toString()).sourceService("dwp-approval-server")
                .sourceModule("approval-workflow-quorum")
                .targetType("APPROVAL_REQUEST").targetId(request.toString()).approvalId(request.toString())
                .afterState(data).retentionClass("EXTENDED").build());
        return event;
    }
}
