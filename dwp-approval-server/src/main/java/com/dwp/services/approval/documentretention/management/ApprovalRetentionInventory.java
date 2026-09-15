package com.dwp.services.approval.documentretention.management;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Fixed owner catalog; no client-controlled SQL identifiers or private-function grants. */
@Repository
public class ApprovalRetentionInventory {
    private record Table(String name,List<String> keys,String source,String predicate) {}
    private static final List<Table> TABLES=tables();
    public static final int CATALOG_TABLES=44;
    private static final String ROWS=rowsSql();
    private final NamedParameterJdbcTemplate jdbc;
    public ApprovalRetentionInventory(NamedParameterJdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record Snapshot(int rows,String sha256,OffsetDateTime retainedUntil,boolean sharedLink,boolean unsettled) {}
    public Snapshot read(long tenant,UUID request,ApprovalRetentionDtos.PublicRules rules) {
        var args=new HashMap<String,Object>();args.put("tenant",tenant);args.put("request",request.toString());
        args.put("receiptDays",rules.receiptRetentionDays());args.put("holdDays",rules.holdEvidenceRetentionDays());args.put("auditDays",rules.auditEvidenceRetentionDays());
        return jdbc.queryForObject(ROWS+"""
            SELECT count(*) AS row_count,
                encode(sha256(convert_to(COALESCE(string_agg(table_oid::oid::text||primary_key::text||
                    encode(sha256(convert_to(row_data::text,'UTF8')),'hex'),'|' ORDER BY table_oid::oid,primary_key::text),''),'UTF8')),'hex') inventory,
                max(GREATEST((row_data->>'retain_until')::timestamptz,(row_data->>'retention_until')::timestamptz,
                    (row_data->>'expires_at')::timestamptz,
                    GREATEST(CASE WHEN table_oid='public.sys_audit_outbox'::regclass
                                AND row_data->'payload'->>'targetType'='APPROVAL_OPERATION_BATCH'
                            THEN NULL ELSE (row_data->>'created_at')::timestamptz END,
                        (row_data->>'completed_at')::timestamptz,
                        (row_data->>'accepted_at')::timestamptz,(row_data->>'consented_at')::timestamptz,
                        (row_data->>'occurred_at')::timestamptz,
                        (row_data->>'audit_occurred_at')::timestamptz,
                        CASE jsonb_typeof(row_data->'payload'->'occurredAt')
                            WHEN 'number' THEN to_timestamp(
                                (row_data->'payload'->>'occurredAt')::double precision)
                            WHEN 'string' THEN
                                (row_data->'payload'->>'occurredAt')::timestamptz
                            ELSE NULL END)+make_interval(days=>CASE
                        WHEN table_oid IN('public.apr_document_hold_journal'::regclass,'public.apr_document_hold_proposals'::regclass) THEN :holdDays
                        WHEN table_oid IN('public.sys_audit_outbox'::regclass,
                            'public.apr_operation_batches'::regclass) THEN :auditDays
                        ELSE :receiptDays END))) deadline,
                COALESCE(bool_or(EXISTS(SELECT 1 FROM jsonb_path_query(COALESCE(row_data->'metadata',row_data->'payload'->'afterState','{}'::jsonb),
                    '$.requestedVersions[*].requestId') j WHERE j#>>'{}'<>:request)
                    OR (table_oid='public.apr_operation_batches'::regclass AND EXISTS(
                        SELECT 1 FROM public.apr_operation_items linked
                         WHERE linked.operation_id=(row_data->>'operation_id')::uuid
                           AND linked.tenant_id=:tenant
                           AND linked.request_id<>CAST(:request AS uuid)))),false) shared,
                COALESCE(bool_or(table_oid IN('public.apr_integration_outbox'::regclass,'public.sys_audit_outbox'::regclass)
                    AND ((row_data->>'status')<>'PUBLISHED' OR (row_data->>'locked_until')::timestamptz>clock_timestamp())),false) unsettled
            FROM rows
            """,args,(r,n)->new Snapshot(r.getInt("row_count"),r.getString("inventory"),r.getObject("deadline",OffsetDateTime.class),r.getBoolean("shared"),r.getBoolean("unsettled")));
    }
    public List<UUID> deliveredEvents(long tenant,UUID request,String consumer) {
        if ("AUDIT".equals(consumer)) return jdbc.query(ROWS+"""
                SELECT producer_event_id FROM (
                    SELECT (row_data->>'event_id')::uuid producer_event_id FROM rows
                     WHERE table_oid='public.sys_audit_outbox'::regclass
                    UNION
                    SELECT (row_data->>'audit_event_id')::uuid producer_event_id FROM rows
                     WHERE table_oid='public.apr_operation_batches'::regclass
                ) delivered ORDER BY producer_event_id
                """,
                Map.of("tenant",tenant,"request",request.toString()),(r,n)->r.getObject(1,UUID.class));
        if ("NOTIFICATION".equals(consumer)) return jdbc.query("SELECT event_id FROM apr_integration_outbox WHERE tenant_id=:tenant AND request_id=CAST(:request AS uuid) ORDER BY event_id",
                Map.of("tenant",tenant,"request",request.toString()),(r,n)->r.getObject(1,UUID.class));
        throw new IllegalArgumentException("Unknown declared copy owner");
    }
    private static String rowsSql() {
        if(TABLES.size()!=CATALOG_TABLES || TABLES.stream().map(Table::name).distinct().count()!=CATALOG_TABLES) throw new IllegalStateException("Exact "+CATALOG_TABLES+"-table catalog required");
        return "WITH rows(table_oid,primary_key,row_data) AS ("+TABLES.stream().map(t->{
            var keys=new ArrayList<String>();for(String key:t.keys()){keys.add("'"+key+"'");keys.add("r."+key);}
            return "SELECT 'public."+t.name()+"'::regclass,jsonb_build_object("+String.join(",",keys)+"),to_jsonb(r) FROM public."+t.name()+" r "+t.source()+" WHERE "+t.predicate();
        }).collect(java.util.stream.Collectors.joining(" UNION ALL "))+") ";
    }
    private static Table direct(String name,String... keys) {return table(name,"","r.tenant_id=:tenant AND r.request_id=CAST(:request AS uuid)",keys);}
    private static Table table(String name,String source,String where,String... keys) {return new Table(name,List.of(keys),source,where);}
    private static List<Table> tables() {
        String receipt="r.tenant_id=:tenant AND (r.metadata->>'requestId'=:request OR EXISTS(SELECT 1 FROM jsonb_path_query(r.metadata,'$.requestedVersions[*].requestId') j WHERE j#>>'{}'=:request))";
        String signature="JOIN public.apr_self_attestations s USING(tenant_id,signature_request_id)";
        String signatureWhere="s.tenant_id=:tenant AND s.request_id=CAST(:request AS uuid)";
        String externalSignature="JOIN public.apr_external_signature_requests s ON s.tenant_id=r.tenant_id AND s.resource_set_key=r.resource_set_key AND s.signature_request_id=r.signature_request_id AND s.owner_user_id=r.owner_user_id";
        String externalSignatureWhere="s.tenant_id=:tenant AND s.request_id=CAST(:request AS uuid)";
        return List.of(
            direct("apr_requests","tenant_id","request_id"),direct("apr_request_payloads","tenant_id","request_id"),
            direct("apr_request_payload_versions","payload_version_id"),direct("apr_steps","step_id"),direct("apr_tasks","task_id"),
            direct("apr_request_events","event_id"),direct("apr_draft_commands","command_id"),
            direct("apr_document_heads","tenant_id","request_id"),direct("apr_document_comments","comment_id"),
            direct("apr_document_hold_proposals","proposal_id"),direct("apr_document_hold_journal","entry_id"),
            table("apr_document_command_receipts","",receipt,"receipt_id"),direct("apr_attachment_uploads","upload_id"),
            direct("apr_attachment_selections","tenant_id","request_id"),direct("apr_attachment_preparations","preparation_id"),
            direct("apr_attachment_manifests","tenant_id","request_id","payload_revision"),direct("apr_attachment_download_grants","grant_id"),
            table("apr_attachment_command_receipts","","r.tenant_id=:tenant AND (r.metadata->>'requestId'=:request OR EXISTS(SELECT 1 FROM public.apr_attachment_uploads u WHERE u.tenant_id=:tenant AND u.request_id=CAST(:request AS uuid) AND (r.metadata->>'uploadId'=u.upload_id::text OR r.route LIKE '%/'||u.upload_id::text||'/%')) OR EXISTS(SELECT 1 FROM public.apr_attachment_download_grants g WHERE g.tenant_id=:tenant AND g.request_id=CAST(:request AS uuid) AND r.metadata->>'grantId'=g.grant_id::text))","tenant_id","actor_user_id","route","idempotency_key"),
            table("apr_attachment_cleanup_journal","JOIN public.apr_attachment_uploads u USING(tenant_id,upload_id)","u.tenant_id=:tenant AND u.request_id=CAST(:request AS uuid)","cleanup_id"),
            direct("apr_quorum_stage_runtime","tenant_id","request_id","step_id","generation"),
            direct("apr_quorum_candidates","tenant_id","request_id","step_id","generation","principal_user_id"),direct("apr_quorum_votes","vote_id"),
            direct("apr_quorum_prerequisites","tenant_id","request_id","step_id","generation","predecessor_step_id"),direct("apr_quorum_sla_timers","timer_id"),
            direct("apr_quorum_information_rounds","tenant_id","request_id","source_generation"),direct("apr_quorum_information_commands","tenant_id","request_id","idempotency_key"),
            direct("apr_integration_outbox","outbox_id"),
            table("apr_recovery_auditor_assignment_events","JOIN public.apr_integration_outbox o USING(outbox_id)","o.tenant_id=:tenant AND o.request_id=CAST(:request AS uuid)","assignment_event_id"),
            table("sys_audit_outbox","","r.tenant_id=:tenant AND (r.payload->>'approvalId'=:request OR r.payload->'afterState'->>'requestId'=:request OR (r.payload->>'targetType' IN('APPROVAL_REQUEST','APPROVAL_DOCUMENT') AND r.payload->>'targetId'=:request) OR (r.payload->>'targetType'='APPROVAL_TASK' AND EXISTS(SELECT 1 FROM public.apr_tasks t WHERE t.tenant_id=:tenant AND t.request_id=CAST(:request AS uuid) AND r.payload->>'targetId'=t.task_id::text)) OR (r.payload->>'targetType'='APPROVAL_OPERATION_BATCH' AND EXISTS(SELECT 1 FROM public.apr_operation_batches batch JOIN public.apr_operation_items item USING(operation_id) WHERE batch.tenant_id=:tenant AND item.tenant_id=:tenant AND item.request_id=CAST(:request AS uuid) AND r.event_id=batch.audit_event_id AND r.payload->>'targetId'=batch.operation_id::text)))","outbox_id"),
            direct("apr_quorum_information_admissions","tenant_id","request_id","idempotency_key"),
            direct("apr_quorum_information_completion_transactions","tenant_id","request_id","idempotency_key"),
            direct("apr_self_attestation_artifacts","tenant_id","request_id","artifact_id"),direct("apr_self_attestations","tenant_id","signature_request_id"),
            direct("apr_self_attestation_commands","tenant_id","actor_user_id","operation","idempotency_key"),
            table("apr_self_attestation_consents",signature,signatureWhere,"tenant_id","signature_request_id","consent_receipt_id"),
            table("apr_self_attestation_evidence",signature,signatureWhere,"tenant_id","signature_request_id","evidence_id"),
            table("apr_self_attestation_events",signature,signatureWhere,"tenant_id","signature_request_id","sequence"),
            direct("apr_system_sla_source_witnesses","tenant_id","event_id"),
            direct("apr_retention_original_command_witnesses","command_id"),
            direct("apr_external_signature_requests","tenant_id","resource_set_key","signature_request_id"),
            table("apr_external_signature_events",externalSignature,externalSignatureWhere,
                "tenant_id","resource_set_key","signature_request_id","event_id"),
            table("apr_external_signature_artifacts",externalSignature,externalSignatureWhere,
                "tenant_id","resource_set_key","signature_request_id","artifact_id"),
            table("apr_operation_batches","","r.tenant_id=:tenant AND EXISTS(SELECT 1 FROM public.apr_operation_items item WHERE item.operation_id=r.operation_id AND item.tenant_id=:tenant AND item.request_id=CAST(:request AS uuid))","operation_id"),
            direct("apr_operation_items","operation_id","item_sequence"));
    }
}
