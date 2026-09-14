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
    public static final int CATALOG_TABLES=39;
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
                max(GREATEST((row_data->>'retain_until')::timestamptz,(row_data->>'expires_at')::timestamptz,
                    GREATEST((row_data->>'created_at')::timestamptz,(row_data->>'completed_at')::timestamptz,
                        (row_data->>'accepted_at')::timestamptz,(row_data->>'consented_at')::timestamptz,
                        (row_data->>'occurred_at')::timestamptz)+make_interval(days=>CASE
                        WHEN table_oid IN('public.apr_document_hold_journal'::regclass,'public.apr_document_hold_proposals'::regclass) THEN :holdDays
                        WHEN table_oid='public.sys_audit_outbox'::regclass THEN :auditDays ELSE :receiptDays END))) deadline,
                COALESCE(bool_or(EXISTS(SELECT 1 FROM jsonb_path_query(COALESCE(row_data->'metadata',row_data->'payload'->'afterState','{}'::jsonb),
                    '$.requestedVersions[*].requestId') j WHERE j#>>'{}'<>:request)),false) shared,
                COALESCE(bool_or(table_oid IN('public.apr_integration_outbox'::regclass,'public.sys_audit_outbox'::regclass)
                    AND ((row_data->>'status')<>'PUBLISHED' OR (row_data->>'locked_until')::timestamptz>clock_timestamp())),false) unsettled
            FROM rows
            """,args,(r,n)->new Snapshot(r.getInt("row_count"),r.getString("inventory"),r.getObject("deadline",OffsetDateTime.class),r.getBoolean("shared"),r.getBoolean("unsettled")));
    }
    public List<UUID> deliveredEvents(long tenant,UUID request,String consumer) {
        if ("AUDIT".equals(consumer)) return jdbc.query(ROWS+"SELECT (row_data->>'outbox_id')::uuid FROM rows WHERE table_oid='public.sys_audit_outbox'::regclass ORDER BY row_data->>'outbox_id'",
                Map.of("tenant",tenant,"request",request.toString()),(r,n)->r.getObject(1,UUID.class));
        if ("NOTIFICATION".equals(consumer)) return jdbc.query("SELECT outbox_id FROM apr_integration_outbox WHERE tenant_id=:tenant AND request_id=CAST(:request AS uuid) ORDER BY outbox_id",
                Map.of("tenant",tenant,"request",request.toString()),(r,n)->r.getObject(1,UUID.class));
        throw new IllegalArgumentException("Unknown declared copy owner");
    }
    private static String rowsSql() {
        if(TABLES.size()!=CATALOG_TABLES || TABLES.stream().map(Table::name).distinct().count()!=CATALOG_TABLES) throw new IllegalStateException("Exact 39-table catalog required");
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
            table("sys_audit_outbox","","r.tenant_id=:tenant AND (r.payload->>'approvalId'=:request OR r.payload->'afterState'->>'requestId'=:request OR (r.payload->>'targetType' IN('APPROVAL_REQUEST','APPROVAL_DOCUMENT') AND r.payload->>'targetId'=:request) OR (r.payload->>'targetType'='APPROVAL_TASK' AND EXISTS(SELECT 1 FROM public.apr_tasks t WHERE t.tenant_id=:tenant AND t.request_id=CAST(:request AS uuid) AND r.payload->>'targetId'=t.task_id::text)))","outbox_id"),
            direct("apr_quorum_information_admissions","tenant_id","request_id","idempotency_key"),
            direct("apr_quorum_information_completion_transactions","tenant_id","request_id","idempotency_key"),
            direct("apr_self_attestation_artifacts","tenant_id","request_id","artifact_id"),direct("apr_self_attestations","tenant_id","signature_request_id"),
            direct("apr_self_attestation_commands","tenant_id","actor_user_id","operation","idempotency_key"),
            table("apr_self_attestation_consents",signature,signatureWhere,"tenant_id","signature_request_id","consent_receipt_id"),
            table("apr_self_attestation_evidence",signature,signatureWhere,"tenant_id","signature_request_id","evidence_id"),
            table("apr_self_attestation_events",signature,signatureWhere,"tenant_id","signature_request_id","sequence"),
            direct("apr_system_sla_source_witnesses","tenant_id","event_id"),
            direct("apr_retention_original_command_witnesses","command_id"));
    }
}
