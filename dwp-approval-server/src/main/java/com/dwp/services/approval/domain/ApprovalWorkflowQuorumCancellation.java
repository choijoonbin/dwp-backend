package com.dwp.services.approval.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.util.Map;
import java.util.UUID;

final class ApprovalWorkflowQuorumCancellation {
    private final ApprovalWorkflowQuorumRuntimeStore store;
    private final ApprovalWorkflowQuorumEvidence evidence;

    ApprovalWorkflowQuorumCancellation(ApprovalWorkflowQuorumRuntimeStore store, AuditOutboxRecorder audit) {
        this.store = store;
        evidence = new ApprovalWorkflowQuorumEvidence(store, audit);
    }

    void cancel(long tenant, UUID request, long actor, UUID person) {
        store.lockRequest(tenant, request);
        var p = store.scope(tenant, request).addValue("actor", actor).addValue("person", person)
                .addValue("now", ApprovalWorkflowQuorumRuntimeStore.time(store.now()));
        var owners = store.jdbc.queryForList("""
                SELECT request_id FROM apr_requests WHERE tenant_id=:tenant AND request_id=:request
                  AND requester_user_id=:actor AND requester_person_public_id=:person AND deleted_at IS NULL
                """, p);
        if (owners.size() != 1) throw new BaseException(ErrorCode.FORBIDDEN, "Only the bound requester can cancel this workflow.");
        store.stages(tenant, request, true);
        store.jdbc.update("""
                UPDATE apr_quorum_stage_runtime SET status='CANCELLED',version=version+1,completed_at=:now
                 WHERE tenant_id=:tenant AND request_id=:request AND status IN ('WAITING','IN_PROGRESS')
                """, p);
        store.jdbc.update("""
                UPDATE apr_quorum_sla_timers SET status='CANCELLED',version=version+1,lease_owner=NULL,lease_until=NULL
                 WHERE tenant_id=:tenant AND request_id=:request AND status IN ('PENDING','CLAIMED')
                """, p);
        evidence.append(tenant, request, actor, "Approval.Quorum.Cancelled", Map.of("contract", ApprovalWorkflowQuorum.CONTRACT));
    }
}
