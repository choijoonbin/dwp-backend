package com.dwp.services.approval.forms;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Form-only request seal. A mandatory no-write verifier retains independent workflow policy/authority. */
@Repository
public class ApprovalFormRequestVersionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalFormMaterialCodec codec;
    private final ApprovalFormVersionPolicyAuthority authority;
    public ApprovalFormRequestVersionRepository(NamedParameterJdbcTemplate jdbc,ApprovalFormMaterialCodec codec,
            ApprovalFormVersionPolicyAuthority authority) { this.jdbc=jdbc;this.codec=codec;this.authority=authority; }
    @Transactional public <T> T existingRequest(Actor caller,UUID requestId,long expectedVersion,
            UUID requestedFormId,UUID requestedWorkflowId,Function<ApprovalFormRequestVersionPin,T> originalWorkflowVerifier) {
        if(jdbc==null||codec==null||authority==null||originalWorkflowVerifier==null
                ||!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        if(requestId==null||expectedVersion<0||expectedVersion>9007199254740991L) throw conflict();
        var window=authority.require(ApprovalFormPublishedRoutePin.Operation.EXISTING_REQUEST,requestId);
        Actor actor=window.actor();
        if(caller==null||!actor.tenantId().equals(caller.tenantId())||!actor.userId().equals(caller.userId())
                ||!Objects.equals(actor.personPublicId(),caller.personPublicId())) throw conflict();
        var pin=load(actor,requestId,expectedVersion,window.action(),window.evidence().routeContractKey());
        if(requestedFormId!=null&&!requestedFormId.equals(pin.formId())
                ||requestedWorkflowId!=null&&!requestedWorkflowId.equals(pin.workflow().workflowId())) throw conflict();
        T verified=originalWorkflowVerifier.apply(pin);if(verified==null) throw unavailable();
        if(!pin.sameObservation(load(actor,requestId,expectedVersion,window.action(),window.evidence().routeContractKey()))) throw conflict();
        authority.unchanged(window,ApprovalFormPublishedRoutePin.Operation.EXISTING_REQUEST,requestId);
        return verified;
    }
    private ApprovalFormRequestVersionPin load(Actor actor,UUID requestId,long expectedVersion,String action,String route) {
        var rows=jdbc.query("""
            SELECT r.request_id,r.version AS request_revision,r.status,r.data_classification,r.management_resource_set_key,
                   f.form_id,f.version AS form_revision,v.form_version_id,v.schema_payload::text,v.schema_sha256,
                   c.category_id,c.version AS category_revision,w.workspace_version,
                   m.schema_sha256 AS material_schema,m.metadata_payload::text,m.route_payload::text,m.material_sha256,m.provenance,m.captured_at,
                   workflow.workflow_id,wv.workflow_version_id,wv.version_number,wv.definition::text,wv.definition_sha256,
                   wv.effective_from,wv.effective_to,
                   COALESCE((SELECT jsonb_agg(jsonb_build_object('stepId',s.step_id,'key',s.step_key,'sequence',s.sequence_number,
                     'mode',s.approval_mode,'candidateRole',s.candidate_role,'status',s.status,'startedAt',s.started_at,'dueAt',s.due_at,'version',s.version)
                     ORDER BY s.sequence_number) FROM apr_steps s WHERE s.tenant_id=r.tenant_id AND s.request_id=r.request_id),'[]'::jsonb)::text AS stored_steps
              FROM apr_requests r JOIN apr_tenants tenant ON tenant.tenant_id=r.tenant_id AND tenant.lifecycle_state='ACTIVE'
              JOIN apr_form_versions v ON v.tenant_id=r.tenant_id AND v.form_version_id=r.form_version_id
              JOIN apr_forms f ON f.tenant_id=v.tenant_id AND f.form_id=v.form_id
              LEFT JOIN apr_form_version_material m ON m.tenant_id=v.tenant_id AND m.form_id=v.form_id AND m.form_version_id=v.form_version_id
              JOIN apr_form_categories c ON c.tenant_id=f.tenant_id AND c.category_id=CASE WHEN m.provenance='PUBLISH_SNAPSHOT'
                AND (m.metadata_payload->>'categoryId') ~ '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
                THEN (m.metadata_payload->>'categoryId')::uuid ELSE f.category_id END
              JOIN apr_workflow_versions wv ON wv.tenant_id=r.tenant_id AND wv.workflow_version_id=r.workflow_version_id
              JOIN apr_workflow_definitions workflow ON workflow.tenant_id=wv.tenant_id AND workflow.workflow_id=wv.workflow_id
              LEFT JOIN apr_form_workspaces w ON w.tenant_id=f.tenant_id AND w.form_id=f.form_id
             WHERE r.tenant_id=:tenant AND r.request_id=:request AND r.requester_user_id=:actor
               AND r.requester_person_public_id IS NOT DISTINCT FROM CAST(:person AS uuid)
               AND r.version=:expected AND r.status=:status AND r.deleted_at IS NULL
               AND f.lifecycle_state IN('DRAFT','PUBLISHED') AND v.lifecycle_state='PUBLISHED' AND c.lifecycle_state='ACTIVE'
               AND workflow.lifecycle_state='PUBLISHED' AND wv.lifecycle_state='PUBLISHED'
               AND (wv.effective_from IS NULL OR wv.effective_from<=clock_timestamp())
               AND (wv.effective_to IS NULL OR wv.effective_to>clock_timestamp())
               AND f.management_resource_set_key=r.management_resource_set_key
               AND c.management_resource_set_key=r.management_resource_set_key
               AND workflow.management_resource_set_key=r.management_resource_set_key
             FOR UPDATE OF r FOR SHARE OF tenant,f,v,c,workflow,wv
            """,new MapSqlParameterSource().addValue("tenant",actor.tenantId()).addValue("actor",actor.userId())
                .addValue("person",actor.personPublicId()).addValue("request",requestId).addValue("expected",expectedVersion)
                .addValue("status",action.equals("INFORMATION")?"NEEDS_INFO":"DRAFT"),(row,index)->{
            String schema=row.getString("schema_payload"),sha=hash(row.getString("schema_sha256"));codec.stored(schema,sha);
            String classification=row.getString("data_classification");
            if(!Set.of("INTERNAL","CONFIDENTIAL","RESTRICTED").contains(classification)) throw unavailable();
            UUID wfId=row.getObject("workflow_id",UUID.class),wfVersion=row.getObject("workflow_version_id",UUID.class);
            String definition=row.getString("definition"),definitionSha=hash(row.getString("definition_sha256"));codec.object(definition);
            int number=row.getInt("version_number");if(number<1) throw unavailable();
            var from=instant(row.getObject("effective_from",OffsetDateTime.class));var to=instant(row.getObject("effective_to",OffsetDateTime.class));
            String provenance=row.getString("provenance"),digest=row.getString("material_sha256");
            Map<String,Object> metadata=Map.of();Instant captured=null;
            if(provenance==null) {
                if(digest!=null||row.getString("material_schema")!=null||row.getString("metadata_payload")!=null||row.getString("route_payload")!=null) throw unavailable();
                provenance="UNRECORDED_HISTORICAL_METADATA";
            } else {
                if(!Set.of("PUBLISH_SNAPSHOT","LEGACY_CAPTURE_TIME").contains(provenance)) throw unavailable();
                metadata=codec.object(row.getString("metadata_payload"));var capturedRoute=codec.object(row.getString("route_payload"));
                digest=hash(digest);captured=instant(row.getObject("captured_at",OffsetDateTime.class));
                if(captured==null||!sha.equals(row.getString("material_schema"))||!digest.equals(codec.material(sha,metadata,capturedRoute))) throw unavailable();
                var published=ApprovalFormVersionPolicyRepository.workflow(capturedRoute);
                if(provenance.equals("PUBLISH_SNAPSHOT")&&(!published.workflowId().equals(wfId)||!published.workflowVersionId().equals(wfVersion)
                        ||published.workflowVersionNumber()!=number||!published.definitionSha256().equals(definitionSha)
                        ||!published.dataClassification().equals(classification)||!Objects.equals(published.effectiveFrom(),from)
                        ||!Objects.equals(published.effectiveTo(),to))) throw conflict();
            }
            String resource=row.getString("management_resource_set_key");
            if(resource==null||!resource.matches("[A-Z][A-Z0-9_]{2,79}")) throw unavailable();
            Long workspace=row.getObject("workspace_version",Long.class);if(workspace!=null) revision(workspace);
            if(provenance.equals("PUBLISH_SNAPSHOT")&&(workspace==null||!row.getObject("category_id",UUID.class).toString().equals(metadata.get("categoryId")))) throw conflict();
            String binding=provenance.equals("PUBLISH_SNAPSHOT")?null:legacyBinding(actor,row.getObject("form_id",UUID.class),wfId);
            return ApprovalFormRequestVersionPin.verified(new ApprovalFormRequestVersionPin.Observation(actor.tenantId(),actor.userId(),actor.personPublicId(),
                requestId,revision(row.getObject("request_revision")),row.getString("status"),row.getObject("form_id",UUID.class),
                row.getObject("form_version_id",UUID.class),sha,schema,revision(row.getObject("form_revision")),workspace,resource,
                row.getObject("category_id",UUID.class),revision(row.getObject("category_revision")),digest,provenance,metadata,captured,
                new ApprovalFormRequestVersionPin.WorkflowVersion(wfId,wfVersion,number,definitionSha,definition,classification,from,to),
                row.getString("stored_steps"),binding,route));
        });
        if(rows.size()!=1) throw conflict();return rows.getFirst();
    }
    private String legacyBinding(Actor actor,UUID formId,UUID workflowId) {
        var rows=jdbc.query("""
            SELECT jsonb_build_object('bindingId',binding_id,'version',version,'type',binding_type,'condition',condition_payload,
              'priority',priority,'effectiveFrom',effective_from,'effectiveTo',effective_to)::text
              FROM apr_form_workflow_bindings WHERE tenant_id=:tenant AND form_id=:form AND workflow_id=:workflow
               AND lifecycle_state='ACTIVE' AND (effective_from IS NULL OR effective_from<=clock_timestamp())
               AND (effective_to IS NULL OR effective_to>clock_timestamp()) FOR SHARE
            """,new MapSqlParameterSource().addValue("tenant",actor.tenantId()).addValue("form",formId).addValue("workflow",workflowId),
            (row,index)->row.getString(1));
        if(rows.size()!=1) throw conflict();return rows.getFirst();
    }
    private static long revision(Object value) {
        if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0||((Number)value).longValue()>9007199254740991L) throw unavailable();
        return ((Number)value).longValue();
    }
    private static String hash(String value) { if(value==null||!value.matches("[a-f0-9]{64}")) throw unavailable();return value; }
    private static Instant instant(OffsetDateTime value) { return value==null?null:value.toInstant(); }
    private static BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT,"The owned request's immutable form/workflow binding changed."); }
    private static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"The original request form version cannot be sealed."); }
}
