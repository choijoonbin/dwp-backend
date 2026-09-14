package com.dwp.services.approval.domain;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeAttestationVerifier;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Appends causal metadata, never a credential, in the transaction that completed the command. */
public final class ApprovalWorkflowInformationAdmissionLedger {
    public static final Set<String> ADMISSION_FIELDS=Set.of("operation","commandSha256","rawBodySha256","receiptSha256",
            "roundId","taskId","stepKey","sourceGeneration","receiptGeneration","receiptRequestVersion","receiptPayloadRevision",
            "receiptPayloadSha256","materialChange","commandActorId","commandActorPersonPublicId","admissionSha256","admissionJti",
            "admissionIssuer","admissionKeyId","admissionSourceRevision","admissionSourceVectorSha256","admissionOwnerAuthRevision",
            "admissionOwnerPolicyRevision","admissionEvaluatedAt","admissionExpiresAt","acceptedAt","ownerProofJti","transportProofJti",
            "originalExpectedVersion","completedAt");
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalWorkflowQuorumRuntimeStore store;
    private final WorkflowRuntimeJson json=new WorkflowRuntimeJson();

    public static final class Accepted {
        private final Actor actor;
        private final UUID request;
        private final String key,transaction;
        private final Instant acceptedAt;
        private final JsonNode admission;
        private Accepted(Actor actor,UUID request,String key,String transaction,Instant acceptedAt,JsonNode admission) {
            this.actor=actor;this.request=request;this.key=key;this.transaction=transaction;this.acceptedAt=acceptedAt;this.admission=admission.deepCopy();
        }
        public JsonNode admission() {return admission.deepCopy();}
    }
    public ApprovalWorkflowInformationAdmissionLedger(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper) {
        this.jdbc=jdbc;this.store=new ApprovalWorkflowQuorumRuntimeStore(jdbc,mapper);
    }
    public Accepted request(Actor actor,ApprovalWorkflowQuorumInformationRuntime.RequestCommand command,
            ApprovalWorkflowQuorumInformationRuntime.Receipt receipt,WorkflowRuntimeAttestationVerifier.Verified proof) {
        if(command==null || command.expectedQuorum()==null) throw denied();
        return accept(actor,command.requestId(),command.idempotencyKey(),"REQUEST_INFO",command,command.rawBodySha256(),
                command.taskId(),command.expectedTaskVersion(),command.expectedRequestVersion(),null,receipt,proof);
    }
    public Accepted reply(Actor actor,ApprovalWorkflowQuorumInformationRuntime.ReplyCommand command,
            ApprovalWorkflowQuorumInformationRuntime.Receipt receipt,WorkflowRuntimeAttestationVerifier.Verified proof) {
        if(command==null) throw denied();
        return accept(actor,command.requestId(),command.idempotencyKey(),"REPLY",command,command.rawBodySha256(),
                command.requestId(),command.expectedRequestVersion(),command.expectedRequestVersion(),command.sourceGeneration(),receipt,proof);
    }
    private Accepted accept(Actor actor,UUID request,String key,String operation,Object command,String raw,UUID target,
            long expectedVersion,long expectedRequestVersion,Long signedGeneration,ApprovalWorkflowQuorumInformationRuntime.Receipt receipt,
            WorkflowRuntimeAttestationVerifier.Verified proof) {
        writable(actor);
        if(request==null || target==null || key==null || !key.matches("[A-Za-z0-9._:-]{1,120}") || !ApprovalWorkflowQuorum.sha256(raw)
                || expectedVersion<0 || expectedRequestVersion<0 || receipt==null || !"COMPLETED".equals(receipt.status())
                || receipt.roundId()==null || receipt.generation()<1 || receipt.requestVersion()!=Math.addExact(expectedRequestVersion,1)
                || receipt.payloadRevision()<1 || !ApprovalWorkflowQuorum.sha256(receipt.payloadSha256()) || proof==null
                || proof.operation()!=WorkflowRuntimeProtocol.Operation.INFORMATION_ADMISSION) throw denied();
        var signed=proof.bindings().get("command");
        String purpose=operation.equals("REQUEST_INFO")?"TASK_INFORMATION":"REQUEST_REPLY";
        String route=operation.equals("REQUEST_INFO")?"route.approvals.work.task-decision.action":"route.approvals.work.request-information-response.action";
        String path=operation.equals("REQUEST_INFO")?"/v1/tasks/"+target+"/decisions":"/v1/requests/"+request+"/information-response";
        if(signed==null || integer(signed,"tenantId",1)!=actor.tenantId() || integer(signed,"actorId",1)!=actor.userId()
                || !uuid(signed,"personPublicId").equals(actor.personPublicId()) || !text(signed,"commandPurpose",40).equals(purpose)
                || !uuid(signed,"targetId").equals(target) || !text(signed,"routeContractKey",200).equals(route)
                || !text(signed,"method",10).equals("POST") || !text(signed,"path",250).equals(path)
                || !text(signed,"idempotencyKey",120).equals(key) || !hash(signed,"rawBodySha256").equals(raw)
                || integer(signed,"expectedVersion",0)!=expectedVersion || signedGeneration==null && !signed.get("sourceGeneration").isNull()
                || signedGeneration!=null && integer(signed,"sourceGeneration",1)!=signedGeneration) throw denied();
        String digest=commandSha256(operation,actor,command);
        var p=store.scope(actor.tenantId(),request).addValue("key",key).addValue("round",receipt.roundId());
        var rows=jdbc.queryForList("SELECT operation,command_sha256,status,receipt::text,completed_at "
                +"FROM apr_quorum_information_commands WHERE tenant_id=:tenant AND request_id=:request AND idempotency_key=:key FOR SHARE",p);
        if(rows.size()!=1) throw denied();var row=rows.getFirst();
        String transaction=jdbc.queryForObject("SELECT pg_current_xact_id()::text",p,String.class);
        var markers=jdbc.queryForList("SELECT transaction_id::text FROM apr_quorum_information_completion_transactions WHERE tenant_id=:tenant "
                +"AND request_id=:request AND idempotency_key=:key AND command_sha256=:command",p.addValue("command",digest));
        if(!operation.equals(row.get("operation")) || !digest.equals(((String)row.get("command_sha256")).strip()) || !"COMPLETED".equals(row.get("status"))
                || markers.size()!=1 || !transaction.equals(markers.getFirst().get("transaction_id"))
                || !receiptSha256(receipt).equals(sha(json.bytes(json.parse(((String)row.get("receipt")).getBytes(java.nio.charset.StandardCharsets.UTF_8),2048))))) throw denied();
        var rounds=jdbc.queryForList("SELECT original.*,stage.stage_key FROM apr_quorum_information_rounds original "
                +"JOIN apr_quorum_stage_runtime stage ON stage.tenant_id=original.tenant_id AND stage.request_id=original.request_id "
                +"AND stage.step_id=original.step_id AND stage.generation=original.source_generation "
                +"WHERE original.tenant_id=:tenant AND original.request_id=:request AND original.round_id=:round",p);
        if(rounds.size()!=1) throw denied();var round=rounds.getFirst();
        long source=((Number)round.get("source_generation")).longValue();
        var context=store.read(round.get("context").toString(),ApprovalWorkflowQuorumRuntimeStore.Context.class);
        if(operation.equals("REQUEST_INFO") ? !target.equals(round.get("task_id")) || source!=receipt.generation()
                || !actor.userId().equals(round.get("actor_user_id")) || !actor.personPublicId().equals(round.get("actor_person_id"))
                : signedGeneration!=source || receipt.generation()!=source+1 || !"RESPONDED".equals(round.get("status"))
                || actor.userId()!=context.requesterUserId() || !actor.personPublicId().equals(context.requesterPersonId())) throw denied();
        Instant accepted=jdbc.queryForObject("SELECT clock_timestamp()",p,OffsetDateTime.class).toInstant();
        var authority=proof.authority();long evaluated=integer(authority,"evaluatedAt",1),expires=integer(authority,"expiresAt",1);
        Instant completed=((java.sql.Timestamp)row.get("completed_at")).toInstant();
        if(!proof.expiresAt().isAfter(accepted) || evaluated>completed.getEpochSecond() || completed.isAfter(accepted)
                || evaluated>accepted.getEpochSecond() || expires-evaluated>30) throw denied();
        // This token is already cryptographically verified. Only its public metadata and one-way digest leave memory.
        var parts=proof.token().split("\\.",-1);if(parts.length!=3) throw denied();
        var claims=json.parse(part(parts[1]),WorkflowRuntimeProtocol.BODY_MAX);var header=json.parse(part(parts[0]),2048);
        var doc=json.object();doc.put("operation",operation);doc.put("commandSha256",digest);doc.put("rawBodySha256",raw);
        doc.put("receiptSha256",receiptSha256(receipt));doc.put("roundId",receipt.roundId().toString());doc.put("taskId",round.get("task_id").toString());
        doc.put("stepKey",round.get("stage_key").toString());doc.put("sourceGeneration",source);doc.put("receiptGeneration",receipt.generation());
        doc.put("receiptRequestVersion",receipt.requestVersion());doc.put("receiptPayloadRevision",receipt.payloadRevision());doc.put("receiptPayloadSha256",receipt.payloadSha256());
        doc.put("materialChange",receipt.materialChange());doc.put("commandActorId",actor.userId());doc.put("commandActorPersonPublicId",actor.personPublicId().toString());
        doc.put("admissionSha256",sha(proof.token()));doc.put("admissionJti",uuid(claims,"jti").toString());doc.put("admissionIssuer",text(claims,"iss",160));
        doc.put("admissionKeyId",text(header,"kid",80));doc.put("admissionSourceRevision",text(authority,"sourceRevision",68));
        doc.put("admissionSourceVectorSha256",hash(authority,"sourceVectorSha256"));doc.put("admissionOwnerAuthRevision",text(authority,"ownerAuthRevision",200));
        doc.put("admissionOwnerPolicyRevision",text(authority,"ownerPolicyRevision",200));doc.put("admissionEvaluatedAt",evaluated);doc.put("admissionExpiresAt",expires);
        doc.put("acceptedAt",accepted.getEpochSecond());doc.put("ownerProofJti",uuid(claims,"sourceProofJti").toString());doc.put("transportProofJti",uuid(claims,"transportProofJti").toString());
        doc.put("originalExpectedVersion",expectedVersion);doc.put("completedAt",completed.getEpochSecond());exact(doc,ADMISSION_FIELDS);
        return new Accepted(actor,request,key,transaction,accepted,doc);
    }
    public void append(Accepted accepted) {
        if(accepted==null) throw denied();writable(accepted.actor);var doc=accepted.admission;
        var p=store.scope(accepted.actor.tenantId(),accepted.request).addValue("key",accepted.key);
        if(!accepted.transaction.equals(jdbc.queryForObject("SELECT pg_current_xact_id()::text",p,String.class))) throw denied();
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",p,OffsetDateTime.class).toInstant();
        if(!Instant.ofEpochSecond(integer(doc,"admissionExpiresAt",1)).isAfter(now)) throw denied();
        p.addValue("operation",text(doc,"operation",16)).addValue("command",hash(doc,"commandSha256")).addValue("raw",hash(doc,"rawBodySha256"))
                .addValue("receipt",hash(doc,"receiptSha256")).addValue("round",uuid(doc,"roundId")).addValue("generation",integer(doc,"sourceGeneration",1))
                .addValue("task",uuid(doc,"taskId")).addValue("actor",accepted.actor.userId()).addValue("person",accepted.actor.personPublicId())
                .addValue("jti",uuid(doc,"admissionJti")).addValue("accepted",java.sql.Timestamp.from(accepted.acceptedAt))
                .addValue("admission",new String(json.bytes(doc),java.nio.charset.StandardCharsets.UTF_8));
        int changed=jdbc.update("""
                INSERT INTO apr_quorum_information_admissions(tenant_id,request_id,idempotency_key,operation,command_sha256,raw_body_sha256,
                    receipt_sha256,round_id,source_generation,task_id,actor_user_id,actor_person_id,admission_jti,accepted_at,admission)
                VALUES(:tenant,:request,:key,:operation,:command,:raw,:receipt,:round,:generation,:task,:actor,:person,:jti,:accepted,CAST(:admission AS jsonb))
                """,p);
        if(changed!=1) throw denied();
    }
    public String commandSha256(String operation,Actor actor,Object command) {
        return store.hash(ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(Map.of("operation",operation,
                "tenantId",actor.tenantId(),"actorUserId",actor.userId(),"actorPersonId",actor.personPublicId().toString(),"command",store.object(store.json(command))))));
    }
    public String receiptSha256(ApprovalWorkflowQuorumInformationRuntime.Receipt receipt) {return sha(json.bytes(json.tree(receipt)));}
    private void writable(Actor actor) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw denied();
        try {if(actor==null || actor.personPublicId()==null || !actor.equals(ApprovalRequestContext.require())) throw denied();}
        catch(IllegalStateException error) {throw new BaseException(ErrorCode.FORBIDDEN);}
    }
}
