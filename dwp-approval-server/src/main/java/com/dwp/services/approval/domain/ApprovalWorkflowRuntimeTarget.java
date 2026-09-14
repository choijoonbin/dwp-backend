package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The signed target comes from locked seat/vote/round rows, never an inferred prior vote or caller principal. */
public final class ApprovalWorkflowRuntimeTarget {
    public enum Use { CAST, SEAT_RECHECK, VOTE_RECHECK, INFORMATION_RECHECK }
    private final JsonNode value;
    private final Snapshot snapshot;
    private ApprovalWorkflowRuntimeTarget(JsonNode value,Snapshot snapshot) { this.value=value.deepCopy();this.snapshot=snapshot; }
    public JsonNode json() { return value.deepCopy(); }

    public static ApprovalWorkflowRuntimeTarget seal(NamedParameterJdbcTemplate jdbc, Snapshot snapshot,
            Use use, UUID taskId, UUID evidenceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || snapshot==null || use==null || taskId==null) throw unavailable("A locked target is required.");
        var actor=ApprovalRequestContext.require();
        if (actor.tenantId()!=snapshot.pins().tenantId() || actor.personPublicId()==null) throw new BaseException(ErrorCode.FORBIDDEN);
        var store=new ApprovalWorkflowQuorumRuntimeStore(jdbc,new ObjectMapper());
        var p=store.scope(snapshot.pins().tenantId(),snapshot.requestId()).addValue("step",snapshot.stepId())
                .addValue("generation",snapshot.generation()).addValue("task",taskId).addValue("evidence",evidenceId);
        if (jdbc.queryForList("SELECT request_id FROM apr_requests WHERE tenant_id=:tenant AND request_id=:request "
                + "AND deleted_at IS NULL AND status IN ('IN_REVIEW','NEEDS_INFO') FOR UPDATE",p).size()!=1) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var stages=jdbc.queryForList("SELECT stage_key,snapshot::text FROM apr_quorum_stage_runtime WHERE tenant_id=:tenant AND request_id=:request "
                + "AND step_id=:step AND generation=:generation FOR UPDATE",p);
        if (stages.size()!=1 || stages.getFirst().get("snapshot")==null
                || !snapshot.equals(store.read((String)stages.getFirst().get("snapshot"),Snapshot.class))) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var seats=jdbc.queryForList("""
                SELECT candidate.principal_user_id,candidate.principal_person_id,task.status,task.assignee_user_id,task.assignee_person_public_id
                  FROM apr_quorum_candidates candidate JOIN apr_tasks task ON task.tenant_id=candidate.tenant_id
                   AND task.request_id=candidate.request_id AND task.step_id=candidate.step_id AND task.task_id=candidate.task_id
                 WHERE candidate.tenant_id=:tenant AND candidate.request_id=:request AND candidate.step_id=:step
                   AND candidate.generation=:generation AND candidate.task_id=:task FOR UPDATE OF task
                """,p);
        if (seats.size()!=1) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var seat=seats.getFirst();long principal=((Number)seat.get("principal_user_id")).longValue();UUID person=(UUID)seat.get("principal_person_id");
        if (use==Use.CAST && (!"CLAIMED".equals(seat.get("status")) || !seat.get("principal_user_id").equals(seat.get("assignee_user_id"))
                || !person.equals(seat.get("assignee_person_public_id")))) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        long voter;UUID voterPerson,delegationId=null;Map<String,Object> evidence=null;
        if (use==Use.CAST || use==Use.SEAT_RECHECK) {
            if (evidenceId!=null) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
            voter=use==Use.CAST?actor.userId():principal;voterPerson=use==Use.CAST?actor.personPublicId():person;
        } else {
            if (evidenceId==null) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
            var rows=use==Use.VOTE_RECHECK?jdbc.queryForList("SELECT vote_id,tenant_id,request_id,step_id,generation,stage_version,actor_user_id,actor_person_id,"
                    + "principal_user_id,principal_person_id,task_id,decision,evidence::text,accepted_at FROM apr_quorum_votes "
                    + "WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND generation=:generation AND task_id=:task AND vote_id=:evidence",p)
                :jdbc.queryForList("SELECT round_id,tenant_id,request_id,source_generation,target_generation,step_id,task_id,source_stage_version,actor_user_id,actor_person_id,"
                    + "principal_user_id,principal_person_id,delegation_id,context::text,retained_snapshots::text,reason,opened_at FROM apr_quorum_information_rounds "
                    + "WHERE tenant_id=:tenant AND request_id=:request AND step_id=:step AND source_generation=:generation AND task_id=:task AND round_id=:evidence",p);
            if (rows.size()!=1) throw ApprovalWorkflowQuorumRuntimeStore.conflict();evidence=rows.getFirst();
            voter=((Number)evidence.get("actor_user_id")).longValue();voterPerson=(UUID)evidence.get("actor_person_id");
            if (((Number)evidence.get("principal_user_id")).longValue()!=principal || !person.equals(evidence.get("principal_person_id"))) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
            if (use==Use.VOTE_RECHECK) {
                var vote=store.read((String)evidence.get("evidence"),Vote.class);delegationId=vote.delegationId();
                if (vote.actorUserId()!=voter || !vote.actorPersonPublicId().equals(voterPerson) || vote.principalUserId()!=principal
                        || !vote.principalPersonPublicId().equals(person) || !vote.pins().equals(snapshot.pins())) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
            } else delegationId=(UUID)evidence.get("delegation_id");
        }
        var json=new WorkflowRuntimeJson();var target=json.object();target.put("use",use.name());target.put("stepKey",(String)stages.getFirst().get("stage_key"));
        target.put("taskId",taskId.toString());target.put("sourceGeneration",snapshot.generation());target.put("actorId",voter);target.put("actorPersonPublicId",voterPerson.toString());
        target.put("principalId",principal);target.put("principalPersonPublicId",person.toString());
        if (evidenceId==null) {target.putNull("evidenceId");target.putNull("evidenceSha256");}
        else {target.put("evidenceId",evidenceId.toString());target.put("evidenceSha256",evidenceDigest(evidence));}
        if (voter==principal) {
            if (delegationId!=null || !voterPerson.equals(person)) throw new BaseException(ErrorCode.FORBIDDEN);target.putNull("delegation");
        } else {
            p.addValue("delegation",delegationId).addValue("actor",voter).addValue("principal",principal).addValue("person",voterPerson)
                    .addValue("role",snapshot.candidateRole());
            var grants=jdbc.queryForList("""
                    SELECT delegation.delegation_id,delegation.starts_at,delegation.ends_at
                      FROM apr_delegations delegation JOIN apr_requests request ON request.tenant_id=delegation.tenant_id AND request.request_id=:request
                      JOIN apr_workflow_versions workflow ON workflow.tenant_id=request.tenant_id AND workflow.workflow_version_id=request.workflow_version_id
                     WHERE delegation.tenant_id=:tenant AND delegation.delegator_user_id=:principal AND delegation.delegate_user_id=:actor
                       AND delegation.delegate_person_public_id=:person AND delegation.lifecycle_state='ACTIVE'
                       AND jsonb_exists(delegation.delegated_role_codes,:role)
                       AND (delegation.scope_type='ALL' OR delegation.scope_type='WORKFLOW' AND delegation.workflow_id=workflow.workflow_id)
                       AND delegation.starts_at<=clock_timestamp() AND delegation.ends_at>clock_timestamp()
                    """+(delegationId==null?"":" AND delegation.delegation_id=:delegation")+" FOR SHARE OF delegation",p);
            // A new cast has no prior grant UUID; ambiguity denies. Rechecks require the immutable original UUID.
            if (grants.size()!=1 || use!=Use.CAST && delegationId==null) throw new BaseException(ErrorCode.FORBIDDEN,"The original delegation is not current.");
            var grant=grants.getFirst();var delegated=json.object();delegated.put("id",grant.get("delegation_id").toString());delegated.put("delegatorUserId",principal);
            delegated.put("delegateUserId",voter);delegated.put("delegatePersonPublicId",voterPerson.toString());delegated.put("workflowVersionId",snapshot.pins().workflowVersionId().toString());
            // Role ID is resolved by Auth, not invented from a role code. The subsequent typed source supplies the verified mapping.
            delegated.putNull("authorityRoleId");delegated.put("startsAt",instant(grant.get("starts_at")).getEpochSecond());
            delegated.put("endsAt",instant(grant.get("ends_at")).getEpochSecond());target.set("delegation",delegated);
        }
        if(voter==snapshot.requesterUserId() || principal==snapshot.requesterUserId() || voterPerson.equals(snapshot.requesterPersonPublicId()))
            throw new BaseException(ErrorCode.SOD_CONFLICT);
        return new ApprovalWorkflowRuntimeTarget(target,snapshot);
    }

    public ApprovalWorkflowRuntimeTarget withVerifiedRole(com.dwp.services.approval.workflowauthority.WorkflowRuntimeAttestationVerifier.Verified proof) {
        if (proof==null || proof.operation()!=com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.Operation.CANDIDATES
                || !proof.expiresAt().isAfter(java.time.Instant.now())) throw unavailable("Current signed role evidence is required.");
        var owner=proof.bindings().get("owner");var stage=proof.bindings().get("stage");
        if (owner==null || stage==null || owner.get("tenantId").longValue()!=snapshot.pins().tenantId()
                || !snapshot.requestId().toString().equals(owner.get("requestId").textValue())
                || !snapshot.pins().workflowVersionId().toString().equals(owner.get("workflowVersionId").textValue())
                || owner.get("actorId").longValue()!=ApprovalRequestContext.require().userId()
                || !ApprovalRequestContext.require().personPublicId().toString().equals(owner.get("personPublicId").textValue())
                || !"SEALED".equals(stage.get("poolMode").textValue()) || stage.get("generation").longValue()!=snapshot.generation()
                || !WorkflowRuntimeJson.sha(new WorkflowRuntimeJson().bytes(new WorkflowRuntimeJson().tree(snapshot))).equals(stage.get("snapshotSha256").textValue())
                || stage.get("candidateCount").intValue()!=snapshot.candidates().size()
                || stage.get("requiredVotes").intValue()!=snapshot.rule().threshold(snapshot.candidates().size())
                || owner.get("payloadRevision").intValue()!=snapshot.payloadRevision() || !snapshot.payloadSha256().equals(owner.get("payloadSha256").textValue())
                || !value.get("stepKey").textValue().equals(stage.get("stepKey").textValue())
                || !snapshot.candidateRole().equals(proof.result().get("role").get("roleCode").textValue())) throw new BaseException(ErrorCode.FORBIDDEN);
        long roleId=proof.result().get("role").get("roleId").longValue();var copy=(com.fasterxml.jackson.databind.node.ObjectNode)value.deepCopy();
        if (!copy.get("delegation").isNull()) ((com.fasterxml.jackson.databind.node.ObjectNode)copy.get("delegation")).put("authorityRoleId",roleId);
        return new ApprovalWorkflowRuntimeTarget(copy,snapshot);
    }
    private static String evidenceDigest(Map<String,Object> evidence) {
        var json=new WorkflowRuntimeJson();var fields=new java.util.TreeMap<String,Object>();
        evidence.forEach((key,value)->fields.put(key,value instanceof UUID?value.toString():value instanceof OffsetDateTime || value instanceof java.sql.Timestamp
                ?instant(value).toString():Set.of("context","evidence","retained_snapshots").contains(key)&&value instanceof String text
                ?json.parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8),262144):value));
        return WorkflowRuntimeJson.sha(json.bytes(json.tree(fields)));
    }
    private static java.time.Instant instant(Object value) {
        if(value instanceof OffsetDateTime time) return time.toInstant();if(value instanceof java.sql.Timestamp time) return time.toInstant();
        throw unavailable("The original delegation time cannot be verified.");
    }
}
