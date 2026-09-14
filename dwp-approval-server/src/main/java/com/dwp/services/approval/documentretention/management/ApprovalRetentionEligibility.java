package com.dwp.services.approval.documentretention.management;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import com.dwp.services.approval.security.ApprovalRequestContext;
import static com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos.*;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos.Record;

@Component
public final class ApprovalRetentionEligibility {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalRetentionInventory inventory;
    private final ApprovalRetentionManagementRepository repository;
    public ApprovalRetentionEligibility(NamedParameterJdbcTemplate jdbc,ApprovalRetentionInventory inventory,ApprovalRetentionManagementRepository repository) {
        this.jdbc=jdbc;this.inventory=inventory;this.repository=repository;
    }
    public Record inspect(ApprovalRequestContext.Actor actor,String scope,UUID id,boolean lock) {
        var row=repository.request(actor,scope,id,lock);
        var policy=repository.policy(actor,scope,null,false);
        var snapshot=inventory.read(actor.tenantId(),id,policy.published());
        var args=Map.<String,Object>of("tenant",actor.tenantId(),"request",id);
        int objects=jdbc.queryForObject("SELECT count(*) FROM apr_attachment_uploads WHERE tenant_id=:tenant AND request_id=:request",args,Integer.class);
        boolean unsettled=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM apr_attachment_uploads WHERE tenant_id=:tenant AND request_id=:request AND state IN('UPLOADING','SCANNING'))
                OR EXISTS(SELECT 1 FROM apr_attachment_download_grants WHERE tenant_id=:tenant AND request_id=:request AND lease_until>clock_timestamp())
                OR EXISTS(SELECT 1 FROM apr_quorum_information_commands WHERE tenant_id=:tenant AND request_id=:request AND status='UNKNOWN')
                OR EXISTS(SELECT 1 FROM apr_quorum_information_rounds WHERE tenant_id=:tenant AND request_id=:request AND status='OPEN')
                OR EXISTS(SELECT 1 FROM apr_quorum_sla_timers WHERE tenant_id=:tenant AND request_id=:request AND status IN('PENDING','CLAIMED'))
            """,args,Boolean.class);
        boolean immutable=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM apr_request_payloads p JOIN apr_request_payload_versions v USING(tenant_id,request_id)
                WHERE p.tenant_id=:tenant AND p.request_id=:request AND v.revision_number=p.schema_version
                    AND v.payload_sha256=p.payload_sha256 AND v.payload=p.payload)
                AND EXISTS(SELECT 1 FROM apr_document_policy_heads WHERE tenant_id=:tenant AND resource_set_key=:scope)
            """,Map.of("tenant",actor.tenantId(),"request",id,"scope",scope),Boolean.class);
        if(!immutable) throw ApprovalRetentionErrors.unavailable();
        String status=(String)row.get("status");OffsetDateTime base=null;
        if("DRAFT".equals(status) && row.get("deleted_at")!=null) base=time(row.get("deleted_at")).plusDays(policy.published().deletedDraftRecoveryDays());
        else if(Set.of("APPROVED","REJECTED","WITHDRAWN","CANCELLED").contains(status) && row.get("completed_at")!=null) base=time(row.get("completed_at")).plusDays(policy.published().recordRetentionDays());
        OffsetDateTime deadline=later(later(base,time(row.get("retain_until"))),snapshot.retainedUntil());
        var heads=jdbc.queryForList("SELECT state,claim_id FROM apr_record_retention_heads WHERE tenant_id=:tenant AND request_id=:request FOR SHARE",args);
        String state=heads.isEmpty()?"LIVE":(String)heads.getFirst().get("state");UUID claim=heads.isEmpty()?null:(UUID)heads.getFirst().get("claim_id");
        OffsetDateTime now=jdbc.queryForObject("SELECT clock_timestamp()",Map.of(),OffsetDateTime.class);
        String reason=!"LIVE".equals(state)?"RECORD_ALREADY_CLAIMED":!Boolean.TRUE.equals(policy.published().allowPurge())?"POLICY_PURGE_DISABLED":
                !policy.published().allowedClassifications().contains((String)row.get("data_classification"))?"CLASSIFICATION_DENIED":
                Boolean.TRUE.equals(row.get("hold_active")) || row.get("pending_hold_id")!=null?"ACTIVE_OR_PENDING_HOLD":
                base==null?"RECORD_NOT_TERMINAL":snapshot.sharedLink()?"BLOCKED_SHARED_LINK":unsettled || snapshot.unsettled()?"RECORD_WORK_UNSETTLED":
                snapshot.rows()>policy.published().maxInventoryRows() || objects>policy.published().maxObjectsPerRecord()?"INVENTORY_CAP_EXCEEDED":
                deadline==null || deadline.isAfter(now)?"RETENTION_NOT_ELAPSED":"ELIGIBLE_FOR_DURABLE_INTENT";
        return new Record(id,scope,((Number)row.get("version")).longValue(),policy.policyId(),policy.version(),
                ((Number)row.get("hold_version")).longValue(),state,"ELIGIBLE_FOR_DURABLE_INTENT".equals(reason),reason,
                snapshot.sha256(),snapshot.rows(),ApprovalRetentionInventory.CATALOG_TABLES,objects,deadline,claim,policy.runtimeReadiness());
    }
    private static OffsetDateTime time(Object value) {
        if(value==null) return null;
        if(value instanceof OffsetDateTime t) return t;
        if(value instanceof java.sql.Timestamp t) return t.toInstant().atOffset(ZoneOffset.UTC);
        throw ApprovalRetentionErrors.unavailable();
    }
    private static OffsetDateTime later(OffsetDateTime a,OffsetDateTime b) {return a==null?b:b==null?a:a.isAfter(b)?a:b;}
}
