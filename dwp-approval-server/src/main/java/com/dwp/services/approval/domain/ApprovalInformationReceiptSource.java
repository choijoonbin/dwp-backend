package com.dwp.services.approval.domain;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.informationreplay.InformationReceiptBody;
import com.dwp.services.approval.informationreplay.InformationReceiptInstalledContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Completed receipt evidence only. Never calls the write-time task/round recheck or enumerates a new role pool. */
public final class ApprovalInformationReceiptSource {
    public static final class Seal {
        private final JsonNode bindings,material;
        private final ApprovalWorkflowQuorumInformationRuntime.Receipt receipt;
        private final Instant deadline;
        private Seal(JsonNode bindings,JsonNode material,ApprovalWorkflowQuorumInformationRuntime.Receipt receipt,Instant deadline) {
            this.bindings=bindings.deepCopy();this.material=material.deepCopy();this.receipt=receipt;this.deadline=deadline;
        }
        public JsonNode bindings() {return bindings.deepCopy();}
        public ApprovalWorkflowQuorumInformationRuntime.Receipt receipt() {return receipt;}
        public Instant deadline() {return deadline;}
        public void requireSame(Seal current) {
            var json=new WorkflowRuntimeJson();
            if(current==null || !java.util.Arrays.equals(json.bytes(material),json.bytes(current.material))
                    || !java.util.Arrays.equals(json.bytes(bindings),json.bytes(current.bindings))) throw denied();
        }
    }
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalWorkflowQuorumRuntimeStore store;
    private final WorkflowRuntimeJson json=new WorkflowRuntimeJson();
    public ApprovalInformationReceiptSource(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper) {
        this.jdbc=jdbc;store=new ApprovalWorkflowQuorumRuntimeStore(jdbc,mapper);
    }
    public Seal capture(InformationReceiptInstalledContext.Seal owner,InformationReceiptBody lookup) {
        if(owner==null || lookup==null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw denied();
        var actor=owner.actor();if(!actor.equals(ApprovalRequestContext.require())) throw denied();
        var p=store.scope(actor.tenantId(),owner.requestId()).addValue("key",owner.key()).addValue("actor",actor.userId()).addValue("person",actor.personPublicId());
        // Non-actors cannot discover whether another subject's command/receipt exists.
        var rows=jdbc.queryForList("""
                SELECT admission.admission::text,command.receipt::text,command.operation,command.command_sha256,
                       original.*,stage.stage_key,stage.snapshot::text,stage.version AS current_stage_version,
                       stage.definition_canonical,stage.candidate_sha256,stage.eligible_count,stage.threshold
                  FROM apr_quorum_information_admissions admission JOIN apr_quorum_information_commands command
                    ON command.tenant_id=admission.tenant_id AND command.request_id=admission.request_id AND command.idempotency_key=admission.idempotency_key
                  JOIN apr_quorum_information_rounds original ON original.tenant_id=admission.tenant_id AND original.request_id=admission.request_id
                   AND original.round_id=admission.round_id AND original.source_generation=admission.source_generation AND original.task_id=admission.task_id
                  JOIN apr_quorum_stage_runtime stage ON stage.tenant_id=original.tenant_id AND stage.request_id=original.request_id
                   AND stage.step_id=original.step_id AND stage.generation=original.source_generation
                 WHERE admission.tenant_id=:tenant AND admission.request_id=:request AND admission.idempotency_key=:key
                   AND admission.actor_user_id=:actor AND admission.actor_person_id=:person AND command.status='COMPLETED'
                """,p);
        if(rows.size()!=1) throw new BaseException(ErrorCode.NOT_FOUND);var row=rows.getFirst();
        var admission=json.parse(((String)row.get("admission")).getBytes(StandardCharsets.UTF_8),8192);
        exact(admission,ApprovalWorkflowInformationAdmissionLedger.ADMISSION_FIELDS);
        var original=store.read(row.get("context").toString(),ApprovalWorkflowQuorumRuntimeStore.Context.class);
        var snapshot=store.read((String)row.get("snapshot"),ApprovalWorkflowQuorum.Snapshot.class);
        if(snapshot==null || snapshot.generation()!=((Number)row.get("source_generation")).longValue()
                || !snapshot.requestId().equals(owner.requestId()) || !snapshot.stepId().equals(row.get("step_id"))
                || !snapshot.pins().equals(original.pins()) || snapshot.payloadRevision()!=original.payloadRevision()
                || !snapshot.payloadSha256().equals(original.payloadSha256()) || !lookup.operation().equals(row.get("operation"))) throw denied();
        boolean info=lookup.operation().equals("REQUEST_INFO");
        if(info ? !actor.userId().equals(row.get("actor_user_id")) || !actor.personPublicId().equals(row.get("actor_person_id"))
                : actor.userId()!=original.requesterUserId() || !actor.personPublicId().equals(original.requesterPersonId())) throw denied();
        var head=head(actor.tenantId(),owner.requestId());
        var definition=ApprovalWorkflowQuorumDefinition.compile((String)head.get("definition"));
        var policy=store.policy(actor.tenantId(),owner.requestId(),false);
        var current=store.context(actor.tenantId(),owner.requestId(),definition,policy,false);
        if(!current.pins().equals(original.pins()) || !current.formVersionId().equals(original.formVersionId())
                || current.requesterUserId()!=original.requesterUserId() || !current.requesterPersonId().equals(original.requesterPersonId())) throw denied();
        var schema=new ApprovalFormSchemaV2Compiler().compile(store.object((String)head.get("schema")));
        String payload=ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(store.object((String)head.get("payload"))));
        if(!schema.sha256().equals(current.pins().formSchemaSha256()) || !sha(payload).equals(current.payloadSha256())) throw denied();
        var declared=definition.stages().stream().filter(stage->stage.key().equals(row.get("stage_key"))).findFirst().orElseThrow(()->denied());
        if(!definition.sha256().equals(ApprovalWorkflowQuorumDefinition.compile((String)row.get("definition_canonical")).sha256())
                || !declared.candidateRole().equals(snapshot.candidateRole()) || !declared.quorum().equals(snapshot.rule())) throw denied();
        var seats=jdbc.queryForList("SELECT principal_user_id,principal_person_id,task_id FROM apr_quorum_candidates WHERE tenant_id=:tenant AND request_id=:request "
                +"AND step_id=:step AND generation=:generation ORDER BY principal_user_id",p.addValue("step",row.get("step_id")).addValue("generation",snapshot.generation()));
        var candidates=seats.stream().map(seat->new ApprovalWorkflowQuorum.Candidate(((Number)seat.get("principal_user_id")).longValue(),(UUID)seat.get("principal_person_id"))).toList();
        if(!candidates.equals(snapshot.candidates()) || ((Number)row.get("eligible_count")).intValue()!=candidates.size()
                || ((Number)row.get("threshold")).intValue()!=snapshot.rule().threshold(candidates.size())
                || !store.hash(store.json(candidates)).equals(((String)row.get("candidate_sha256")).strip())
                || seats.stream().noneMatch(seat->seat.get("task_id").equals(row.get("task_id")) && seat.get("principal_user_id").equals(row.get("principal_user_id"))
                    && seat.get("principal_person_id").equals(row.get("principal_person_id")))) throw denied();
        ApprovalInformationReceiptCommand.digest(store,actor,owner.requestId(),owner.key(),lookup,admission,original);
        var receipt=store.read((String)row.get("receipt"),ApprovalWorkflowQuorumInformationRuntime.Receipt.class);
        if(!"COMPLETED".equals(receipt.status()) || !receipt.roundId().equals(row.get("round_id"))
                || !sha(json.bytes(json.tree(receipt))).equals(hash(admission,"receiptSha256"))) throw denied();
        var root=json.object();var publicOwner=json.object();var pins=current.pins();
        publicOwner.put("tenantId",actor.tenantId());publicOwner.put("actorId",actor.userId());publicOwner.put("personPublicId",actor.personPublicId().toString());
        publicOwner.put("requestId",owner.requestId().toString());publicOwner.put("requestVersion",((Number)head.get("version")).longValue());
        publicOwner.put("workflowVersionId",pins.workflowVersionId().toString());publicOwner.put("workflowVersion",pins.workflowVersion());
        publicOwner.put("workflowDefinitionSha256",pins.workflowDefinitionSha256());publicOwner.put("formVersionId",current.formVersionId().toString());
        publicOwner.put("formSchemaSha256",pins.formSchemaSha256());publicOwner.put("payloadRevision",current.payloadRevision());publicOwner.put("payloadSha256",current.payloadSha256());
        publicOwner.put("policyVersion",pins.policyVersion());publicOwner.put("policySha256",pins.policySha256());publicOwner.put("contextKey",owner.context().contextKey());
        publicOwner.put("contextScopeKey",owner.context().contextScopeKey());publicOwner.put("decisionRevision",owner.context().revision());publicOwner.put("accessMode",owner.mode());
        publicOwner.put("routeContractKey",InformationReceiptInstalledContext.ROUTE);publicOwner.put("managementResourceSetKey",(String)head.get("management_resource_set_key"));
        publicOwner.set("roleCodes",json.tree(definition.stages().stream().map(ApprovalWorkflowQuorumDefinition.Stage::candidateRole).distinct().sorted().toList()));
        publicOwner.put("publishedDefinition",definition.canonicalJson());publicOwner.put("method","POST");publicOwner.put("path",owner.path());publicOwner.put("idempotencyKey",owner.key());
        var source=json.object();source.put("stepKey",(String)row.get("stage_key"));source.put("generation",snapshot.generation());
        source.put("sourceStageRevision",((Number)row.get("source_stage_version")).longValue());source.put("snapshotSha256",sha(json.bytes(json.tree(snapshot))));
        source.put("candidateSetSha256",sha(json.bytes(json.tree(candidates))));source.put("candidateCount",candidates.size());source.put("requiredVotes",snapshot.rule().threshold(candidates.size()));
        source.put("candidateRole",snapshot.candidateRole());source.put("requesterUserId",snapshot.requesterUserId());source.put("requesterPersonPublicId",snapshot.requesterPersonPublicId().toString());
        source.put("rejectCommentMinLength",original.rejectLength());source.put("originalPayloadRevision",original.payloadRevision());source.put("originalPayloadSha256",original.payloadSha256());
        var target=json.object();target.put("use","RECEIPT_ORIGINAL_INFORMATION");target.put("stepKey",(String)row.get("stage_key"));target.put("taskId",row.get("task_id").toString());
        target.put("evidenceId",row.get("round_id").toString());target.put("evidenceSha256",roundDigest(row));target.put("sourceGeneration",snapshot.generation());
        target.put("actorId",((Number)row.get("actor_user_id")).longValue());target.put("actorPersonPublicId",row.get("actor_person_id").toString());
        target.put("principalId",((Number)row.get("principal_user_id")).longValue());target.put("principalPersonPublicId",row.get("principal_person_id").toString());
        Instant deadline=owner.context().validUntil().toInstant();
        var delegation=delegation(row,current,owner.requestId());target.set("delegation",delegation);
        if(!delegation.isNull()) {Instant ends=Instant.ofEpochSecond(integer(delegation,"endsAt",1));if(ends.isBefore(deadline)) deadline=ends;}
        root.set("owner",publicOwner);root.set("source",source);root.set("target",target);root.set("admission",admission);
        var material=json.object();material.set("head",json.tree(head));material.put("stageVersion",((Number)row.get("current_stage_version")).longValue());
        material.put("roundVersion",((Number)row.get("version")).longValue());material.put("roundStatus",(String)row.get("status"));material.put("payload",payload);
        return new Seal(root,material,receipt,deadline);
    }
    private Map<String,Object> head(long tenant,UUID request) {
        var rows=jdbc.queryForList("""
                SELECT request.version,request.status,request.management_resource_set_key,workflow.version AS workflow_revision,
                       form.version AS form_revision,workflow.current_version,form.current_version AS form_head,
                       workflow_version.definition::text AS definition,form_version.schema_payload::text AS schema,payload.payload::text AS payload
                  FROM apr_requests request JOIN apr_tenants tenant ON tenant.tenant_id=request.tenant_id AND tenant.lifecycle_state='ACTIVE'
                  JOIN apr_workflow_versions workflow_version ON workflow_version.tenant_id=request.tenant_id AND workflow_version.workflow_version_id=request.workflow_version_id
                  JOIN apr_workflow_definitions workflow ON workflow.tenant_id=workflow_version.tenant_id AND workflow.workflow_id=workflow_version.workflow_id
                  JOIN apr_form_versions form_version ON form_version.tenant_id=request.tenant_id AND form_version.form_version_id=request.form_version_id
                  JOIN apr_forms form ON form.tenant_id=form_version.tenant_id AND form.form_id=form_version.form_id
                  JOIN apr_form_categories category ON category.tenant_id=form.tenant_id AND category.category_id=form.category_id
                  JOIN apr_form_workflow_bindings binding ON binding.tenant_id=request.tenant_id AND binding.form_id=form.form_id AND binding.workflow_id=workflow.workflow_id
                  JOIN apr_request_payloads payload ON payload.tenant_id=request.tenant_id AND payload.request_id=request.request_id
                 WHERE request.tenant_id=:tenant AND request.request_id=:request AND request.deleted_at IS NULL
                   AND workflow.lifecycle_state='PUBLISHED' AND workflow_version.lifecycle_state='PUBLISHED'
                   AND (workflow_version.effective_from IS NULL OR workflow_version.effective_from<=clock_timestamp())
                   AND (workflow_version.effective_to IS NULL OR workflow_version.effective_to>clock_timestamp())
                   AND form.lifecycle_state IN ('DRAFT','PUBLISHED') AND form_version.lifecycle_state='PUBLISHED' AND category.lifecycle_state='ACTIVE'
                   AND binding.lifecycle_state='ACTIVE' AND (binding.effective_from IS NULL OR binding.effective_from<=clock_timestamp())
                   AND (binding.effective_to IS NULL OR binding.effective_to>clock_timestamp())
                   AND workflow.management_resource_set_key=request.management_resource_set_key
                   AND form.management_resource_set_key=request.management_resource_set_key AND category.management_resource_set_key=request.management_resource_set_key
                """,store.scope(tenant,request));
        if(rows.size()!=1) throw denied();var head=rows.getFirst();
        ApprovalManagementScopeContext.current().ifPresent(scope->{if(!scope.resourceSetKey().equals(head.get("management_resource_set_key"))) throw denied();});
        return head;
    }
    private JsonNode delegation(Map<String,Object> row,ApprovalWorkflowQuorumRuntimeStore.Context context,UUID request) {
        if(row.get("delegation_id")==null) {
            if(!row.get("actor_user_id").equals(row.get("principal_user_id")) || !row.get("actor_person_id").equals(row.get("principal_person_id"))) throw denied();
            return com.fasterxml.jackson.databind.node.NullNode.instance;
        }
        var p=store.scope(context.pins().tenantId(),request).addValue("id",row.get("delegation_id")).addValue("principal",row.get("principal_user_id"))
                .addValue("actor",row.get("actor_user_id")).addValue("person",row.get("actor_person_id")).addValue("role",store.read((String)row.get("snapshot"),ApprovalWorkflowQuorum.Snapshot.class).candidateRole());
        var grants=jdbc.queryForList("""
                SELECT delegation.delegation_id,delegation.starts_at,delegation.ends_at FROM apr_delegations delegation
                  JOIN apr_workflow_versions workflow ON workflow.tenant_id=delegation.tenant_id AND workflow.workflow_version_id=:workflow
                 WHERE delegation.tenant_id=:tenant AND delegation.delegation_id=:id AND delegation.delegator_user_id=:principal
                   AND delegation.delegate_user_id=:actor AND delegation.delegate_person_public_id=:person AND delegation.lifecycle_state='ACTIVE'
                   AND jsonb_exists(delegation.delegated_role_codes,:role) AND (delegation.scope_type='ALL' OR delegation.scope_type='WORKFLOW' AND delegation.workflow_id=workflow.workflow_id)
                   AND delegation.starts_at<=clock_timestamp() AND delegation.ends_at>clock_timestamp()
                """,p.addValue("workflow",context.pins().workflowVersionId()));
        if(grants.size()!=1) throw denied();var grant=grants.getFirst();var value=json.object();value.put("id",grant.get("delegation_id").toString());
        value.put("delegatorUserId",((Number)row.get("principal_user_id")).longValue());value.put("delegateUserId",((Number)row.get("actor_user_id")).longValue());
        value.put("delegatePersonPublicId",row.get("actor_person_id").toString());value.put("workflowVersionId",context.pins().workflowVersionId().toString());
        value.put("roleCode",p.getValue("role").toString());value.put("startsAt",instant(grant.get("starts_at")).getEpochSecond());value.put("endsAt",instant(grant.get("ends_at")).getEpochSecond());
        return value;
    }
    private String roundDigest(Map<String,Object> row) {
        var immutable=new java.util.TreeMap<String,Object>();
        for(String key:Set.of("round_id","tenant_id","request_id","source_generation","target_generation","step_id","task_id","source_stage_version",
                "actor_user_id","actor_person_id","principal_user_id","principal_person_id","delegation_id","context","retained_snapshots","reason","opened_at")) {
            Object value=row.get(key);immutable.put(key,value instanceof UUID?value.toString():value instanceof OffsetDateTime || value instanceof java.sql.Timestamp?instant(value).toString()
                    :Set.of("context","retained_snapshots").contains(key)?json.parse(value.toString().getBytes(StandardCharsets.UTF_8),262144):value);
        }
        return sha(json.bytes(json.tree(immutable)));
    }
    private static Instant instant(Object value) {
        if(value instanceof OffsetDateTime time) return time.toInstant();if(value instanceof java.sql.Timestamp time) return time.toInstant();throw denied();
    }
}
