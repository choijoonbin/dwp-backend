package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeActionContext;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeAttestationVerifier;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Read-only SEALED evidence. Enumeration cannot replace the database's immutable seats or denominator. */
public final class ApprovalWorkflowRuntimeSource {
    private final Snapshot snapshot;
    private final JsonNode bindings;
    private final Instant expiresAt;
    private ApprovalWorkflowRuntimeSource(Snapshot snapshot, JsonNode bindings, Instant expiresAt) {
        this.snapshot=snapshot;this.bindings=bindings.deepCopy();this.expiresAt=expiresAt;
    }
    public JsonNode bindings() { return bindings.deepCopy(); }
    public Snapshot snapshot() { return snapshot; }
    public Instant expiresAt() { return expiresAt; }

    public static ApprovalWorkflowRuntimeSource seal(NamedParameterJdbcTemplate jdbc, Snapshot snapshot,
            WorkflowRuntimeActionContext.Current current, String method, String path, String originalKey,
            WorkflowRuntimeAttestationVerifier.Verified admission) {
        if(snapshot==null) throw unavailable("A frozen snapshot is required.");
        return sealState(jdbc,snapshot.pins().tenantId(),snapshot.requestId(),snapshot.stepId(),snapshot.generation(),snapshot,
                current,method,path,originalKey,admission);
    }
    public static ApprovalWorkflowRuntimeSource activation(NamedParameterJdbcTemplate jdbc,long tenant,UUID request,UUID step,long generation,
            WorkflowRuntimeActionContext.Current current,String method,String path,String originalKey,
            WorkflowRuntimeAttestationVerifier.Verified admission) {
        return sealState(jdbc,tenant,request,step,generation,null,current,method,path,originalKey,admission);
    }
    private static ApprovalWorkflowRuntimeSource sealState(NamedParameterJdbcTemplate jdbc,long tenant,UUID request,UUID stepId,long generation,Snapshot snapshot,
            WorkflowRuntimeActionContext.Current current,String method,String path,String originalKey,
            WorkflowRuntimeAttestationVerifier.Verified admission) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || request==null || stepId==null || generation<1 || current==null
                || current.context()==null || !current.actor().equals(ApprovalRequestContext.require())) throw unavailable("A current locked source is required.");
        var actor=current.actor();var context=current.context();
        if (actor.tenantId()!=tenant || actor.personPublicId()==null || !"POST".equals(method)
                || originalKey==null || !originalKey.matches("[A-Za-z0-9._:-]{1,120}")
                || !Set.of("NORMAL","ELEVATED").contains(current.mode())) throw new BaseException(ErrorCode.FORBIDDEN);
        if (context.validUntil()==null || !context.validUntil().toInstant().isAfter(Instant.now())) throw unavailable("The current action expired.");
        String route=context.routeContractKey();
        boolean valid=switch(route) {
            case "route.approvals.work.request-submit.action" -> path.equals("/v1/requests/"+request+"/submit");
            case "route.approvals.work.request-information-response.action" -> path.equals("/v1/requests/"+request+"/information-response");
            case "route.approvals.work.task-decision.action" -> canonicalTaskPath(path);
            default -> false;
        };
        if (!valid) throw new BaseException(ErrorCode.FORBIDDEN);
        var store=new ApprovalWorkflowQuorumRuntimeStore(jdbc,new ObjectMapper());
        var p=store.scope(actor.tenantId(),request).addValue("step",stepId).addValue("generation",generation);
        if (jdbc.queryForList("SELECT tenant_id FROM apr_tenants WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE' FOR SHARE",p).size()!=1)
            throw new BaseException(ErrorCode.FORBIDDEN);
        var requests=jdbc.queryForList("SELECT version FROM apr_requests WHERE tenant_id=:tenant AND request_id=:request "
                +"AND deleted_at IS NULL AND status IN ('IN_REVIEW','NEEDS_INFO') FOR UPDATE",p);
        if(requests.size()!=1) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var rows=store.generation(actor.tenantId(),request,generation,true);
        var row=rows.stream().filter(item->item.stepId().equals(stepId)).findFirst().orElseThrow(ApprovalWorkflowQuorumRuntimeStore::conflict);
        var declared=ApprovalWorkflowQuorumDefinition.compile(row.definition()).stages().stream().filter(item->item.key().equals(row.key())).findFirst().orElseThrow();
        store.verify(row.context(),actor.tenantId(),request,row.definition());
        String poolMode="SEALED";Snapshot frozen=snapshot;
        if(snapshot!=null) {
            if (!snapshot.equals(row.snapshot()) || row.version()<1 || !Set.of("IN_PROGRESS","APPROVED","CANCELLED").contains(row.status())
                    || !snapshot.pins().equals(row.context().pins()) || snapshot.payloadRevision()!=row.context().payloadRevision()
                    || !snapshot.payloadSha256().equals(row.context().payloadSha256())) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        } else {
            if(!"WAITING".equals(row.status()) || row.snapshot()!=null || row.version()!=0) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
            if(!declared.predecessors().stream().allMatch(key->rows.stream().anyMatch(previous->previous.key().equals(key)
                    && Set.of("APPROVED","SKIPPED").contains(previous.status())))
                    || !declared.predecessors().isEmpty() && declared.predecessors().stream().allMatch(key->rows.stream().anyMatch(previous->previous.key().equals(key)
                        && "SKIPPED".equals(previous.status())))) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
            if(declared.routeCondition()!=null) {
                var condition=jdbc.queryForMap("SELECT form.schema_payload::text AS schema,payload.payload::text AS payload FROM apr_requests request "
                        +"JOIN apr_form_versions form ON form.tenant_id=request.tenant_id AND form.form_version_id=request.form_version_id "
                        +"JOIN apr_request_payloads payload ON payload.tenant_id=request.tenant_id AND payload.request_id=request.request_id "
                        +"WHERE request.tenant_id=:tenant AND request.request_id=:request",p);
                if(!ApprovalWorkflowStageCondition.matches((String)condition.get("schema"),store.object((String)condition.get("payload")),declared.routeCondition()))
                    throw ApprovalWorkflowQuorumRuntimeStore.conflict();
            }
            poolMode=generation==1?"INITIAL":"REBUILD";
            if(generation>1) {
                var rounds=jdbc.queryForList("SELECT material_change,retained_snapshots::text AS retained,response_payload_revision,response_payload_sha256 "
                        +"FROM apr_quorum_information_rounds WHERE tenant_id=:tenant AND request_id=:request AND target_generation=:generation AND status='RESPONDED'",p);
                if(rounds.size()!=1 || ((Number)rounds.getFirst().get("response_payload_revision")).intValue()!=row.context().payloadRevision()
                        || !((String)rounds.getFirst().get("response_payload_sha256")).trim().equals(row.context().payloadSha256())) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
                if(Boolean.FALSE.equals(rounds.getFirst().get("material_change"))) {
                    var retained=store.object((String)rounds.getFirst().get("retained")).get(row.key());
                    if(retained!=null) {
                        frozen=store.json.convertValue(retained,Snapshot.class);poolMode="RETAINED";
                        if(frozen.generation()!=generation-1 || !frozen.requestId().equals(request) || !frozen.pins().equals(row.context().pins())
                                || frozen.payloadRevision()!=row.context().payloadRevision() || !frozen.payloadSha256().equals(row.context().payloadSha256())
                                || !frozen.rule().equals(declared.quorum()) || !frozen.candidateRole().equals(declared.candidateRole())) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
                    }
                }
            }
        }
        var seats=jdbc.queryForList("SELECT principal_user_id,principal_person_id FROM apr_quorum_candidates "
                +"WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND generation=:generation ORDER BY principal_user_id",p);
        var actual=seats.stream().map(seat->new Candidate(((Number)seat.get("principal_user_id")).longValue(),
                (java.util.UUID)seat.get("principal_person_id"))).toList();
        if (snapshot==null ? !actual.isEmpty() : !actual.equals(snapshot.candidates())) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var metadata=jdbc.queryForMap("SELECT eligible_count,threshold,candidate_sha256 FROM apr_quorum_stage_runtime "
                +"WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND generation=:generation",p);
        if (snapshot!=null && (((Number)metadata.get("eligible_count")).intValue()!=snapshot.candidates().size()
                || ((Number)metadata.get("threshold")).intValue()!=snapshot.rule().threshold(snapshot.candidates().size())
                || !store.hash(store.json(snapshot.candidates())).equals(((String)metadata.get("candidate_sha256")).trim())))
            throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var workflows=jdbc.queryForList("SELECT workflow.management_resource_set_key,version.definition::text AS definition FROM apr_workflow_versions version "
                +"JOIN apr_workflow_definitions workflow ON workflow.tenant_id=version.tenant_id AND workflow.workflow_id=version.workflow_id "
                +"WHERE version.tenant_id=:tenant AND version.workflow_version_id=:workflow AND workflow.lifecycle_state='PUBLISHED' FOR SHARE OF workflow,version",
                p.addValue("workflow",row.context().pins().workflowVersionId()));
        if(workflows.size()!=1) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var workflow=workflows.getFirst();
        var json=new WorkflowRuntimeJson();var owner=json.object();var pins=row.context().pins();
        owner.put("tenantId",actor.tenantId());owner.put("actorId",actor.userId());owner.put("personPublicId",actor.personPublicId().toString());
        owner.put("requestId",request.toString());owner.put("requestVersion",((Number)requests.getFirst().get("version")).longValue());
        owner.put("workflowVersionId",pins.workflowVersionId().toString());owner.put("workflowVersion",pins.workflowVersion());
        owner.put("workflowDefinitionSha256",pins.workflowDefinitionSha256());owner.put("formVersionId",row.context().formVersionId().toString());
        owner.put("formSchemaSha256",pins.formSchemaSha256());owner.put("payloadRevision",row.context().payloadRevision());owner.put("payloadSha256",row.context().payloadSha256());
        owner.put("policyVersion",pins.policyVersion());owner.put("policySha256",pins.policySha256());owner.put("contextKey",context.contextKey());
        owner.put("contextScopeKey",context.contextScopeKey());owner.put("decisionRevision",context.revision());owner.put("accessMode",current.mode());
        owner.put("routeContractKey",route);owner.put("managementResourceSetKey",(String)workflow.get("management_resource_set_key"));
        owner.set("roleCodes",json.tree(ApprovalWorkflowQuorumDefinition.compile(row.definition()).stages().stream()
                .map(ApprovalWorkflowQuorumDefinition.Stage::candidateRole).distinct().sorted().toList()));
        owner.put("publishedDefinition",ApprovalWorkflowQuorumDefinition.compile((String)workflow.get("definition")).canonicalJson());
        owner.put("method",method);owner.put("path",path);owner.put("idempotencyKey",originalKey);
        var stage=json.object();stage.put("poolMode",poolMode);stage.put("stepKey",row.key());stage.put("generation",generation);
        stage.put("requesterUserId",row.context().requesterUserId());stage.put("requesterPersonPublicId",row.context().requesterPersonId().toString());
        stage.put("candidateRole",declared.candidateRole());stage.put("sourceStageRevision",row.version());
        if(frozen==null) {
            stage.putNull("snapshotSha256");stage.putNull("candidateSetSha256");stage.putNull("candidateCount");stage.putNull("requiredVotes");
        } else {
            stage.put("snapshotSha256",WorkflowRuntimeJson.sha(json.bytes(json.tree(frozen))));
            stage.put("candidateSetSha256",WorkflowRuntimeJson.sha(json.bytes(json.tree(frozen.candidates()))));
            stage.put("candidateCount",frozen.candidates().size());stage.put("requiredVotes",frozen.rule().threshold(frozen.candidates().size()));
        }
        stage.put("rejectCommentMinLength",row.context().rejectLength());
        Instant expires=context.validUntil().toInstant();
        if (admission==null) {
            if (route.equals("route.approvals.work.request-information-response.action")) throw unavailable("Current information admission is required.");
            stage.putNull("admissionAttestation");stage.putNull("admissionAttestationSha256");stage.putNull("commandRawBodySha256");
        } else {
            var command=admission.bindings().get("command");
            if (admission.operation()!=WorkflowRuntimeProtocol.Operation.INFORMATION_ADMISSION || !admission.expiresAt().isAfter(Instant.now())
                    || command==null || command.get("tenantId").longValue()!=actor.tenantId() || command.get("actorId").longValue()!=actor.userId()
                    || !actor.personPublicId().toString().equals(command.get("personPublicId").textValue())
                    || !route.equals(command.get("routeContractKey").textValue()) || !path.equals(command.get("path").textValue())
                    || !originalKey.equals(command.get("idempotencyKey").textValue()) || !context.contextKey().equals(command.get("contextKey").textValue())
                    || !context.contextScopeKey().equals(command.get("contextScopeKey").textValue()) || !context.revision().equals(command.get("decisionRevision").textValue())
                    || !current.mode().equals(command.get("accessMode").textValue())) throw new BaseException(ErrorCode.FORBIDDEN);
            if(route.equals("route.approvals.work.request-information-response.action")) {
                long sourceGeneration=command.get("sourceGeneration").longValue(),expectedVersion=command.get("expectedVersion").longValue();
                if(sourceGeneration!=(snapshot==null?generation-1:generation)
                        || ((Number)requests.getFirst().get("version")).longValue()!=(snapshot==null?Math.addExact(expectedVersion,1):expectedVersion))
                    throw ApprovalWorkflowQuorumRuntimeStore.conflict();
            }
            stage.put("admissionAttestation",admission.token());stage.put("admissionAttestationSha256",WorkflowRuntimeJson.sha(admission.token().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            stage.put("commandRawBodySha256",command.get("rawBodySha256").textValue());
            if(admission.expiresAt().isBefore(expires)) expires=admission.expiresAt();
        }
        var bindings=json.object();bindings.set("owner",owner);bindings.set("stage",stage);
        return new ApprovalWorkflowRuntimeSource(frozen,bindings,expires);
    }
    public void requireSame(ApprovalWorkflowRuntimeSource current) {
        var json=new WorkflowRuntimeJson();
        if(current==null || !java.util.Arrays.equals(json.bytes(bindings),json.bytes(current.bindings)))
            throw ApprovalWorkflowQuorumRuntimeStore.conflict();
    }
    private static boolean canonicalTaskPath(String path) {
        if(path==null || !path.startsWith("/v1/tasks/") || !path.endsWith("/decisions")) return false;
        try {String id=path.substring(10,path.length()-10);return java.util.UUID.fromString(id).toString().equals(id);}
        catch(IllegalArgumentException error) {return false;}
    }
}
