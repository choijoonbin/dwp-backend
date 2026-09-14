package com.dwp.services.approval.workflowauthority;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorum;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Separate crossServiceTest gate: real Auth DB/Redis/filter/HTTP, explicit test owner proof, not installed Approval PEP evidence. */
class WorkflowRuntimeActualAuthHttpTest {
    static final String HARNESS="com.dwp.services.auth.service.WorkflowRuntimeActualAuthHarness";
    final WorkflowRuntimeJson json=new WorkflowRuntimeJson();
    static RSAKey key(String id) throws Exception {return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();}
    @Test void clientUsesRealAuthCurrentRoleAndDatabaseRevokesAtTheFixedEndpoint() throws Exception {
        if(!Boolean.getBoolean("dwp.workflow.cross-service-gate")) {
            throw new IllegalStateException("This opt-in gate requires the coherent Auth test harness classpath; absence is not success, skip, or activation evidence.");
        }
        Class<?> type;
        try {type=Class.forName(HARNESS);} catch(ClassNotFoundException missing) {
            throw new IllegalStateException("Required actual Auth harness is absent; the cross-service gate is NOT verified.",missing);
        }
        var owner=key("joint-owner");var transport=key("joint-transport");var attestation=key("joint-auth");
        Object fixture=type.getConstructor(RSAKey.class,RSAKey.class,RSAKey.class).newInstance(owner,transport,attestation);
        try(var close=(AutoCloseable)fixture) {
            long tenant=(Long)call(fixture,"tenantId",new Class<?>[0]);long requester=910098,caller=910099,source=910100,other=910101;
            UUID requesterPerson=UUID.randomUUID(),callerPerson=UUID.randomUUID(),sourcePerson=UUID.randomUUID(),otherPerson=UUID.randomUUID();
            var subjects=java.util.Map.of(requester,requesterPerson,caller,callerPerson,source,sourcePerson,other,otherPerson);
            for(var entry:subjects.entrySet()) {
                call(fixture,"subject",new Class<?>[]{long.class,UUID.class},entry.getKey(),entry.getValue());
                call(fixture,"workspace",new Class<?>[]{long.class},entry.getKey());
            }
            for(long user:List.of(caller,source,other)) {
                for(var resource:List.of("APP.APPROVALS","ACTION.APPROVAL_TASK","ACTION.APPROVAL_FORM"))
                    call(fixture,"grant",new Class<?>[]{long.class,String.class,String.class,long.class},user,resource,"VIEW",caller);
                call(fixture,"grant",new Class<?>[]{long.class,String.class,String.class,long.class},user,"ACTION.APPROVAL_TASK","APPROVE",caller);
            }
            long role=(Long)call(fixture,"role",new Class<?>[]{String.class,List.class},"JOINT_REVIEWER",List.of(source,other));
            var current=json.tree(call(fixture,"current",new Class<?>[]{long.class,String.class},caller,"route.approvals.work.task-decision.action"));
            assertEquals("ALLOWED",current.get("decision").textValue());assertFalse(current.get("effectiveReadOnly").booleanValue());
            var keys=new WorkflowRuntimeKeys(owner,transport,new JWKSet(attestation.toPublicJWK()).toString());
            var issuer=new WorkflowRuntimeProofIssuer(keys,json,Clock.systemUTC());
            var client=new WorkflowRuntimeAuthorityClient((URI)call(fixture,"endpoint",new Class<?>[0]),new WorkflowRuntimeAttestationVerifier(keys,json,Clock.systemUTC()));
            var bindings=bindings(tenant,caller,callerPerson,requester,requesterPerson,current);
            var exchange=issuer.issue(Operation.CANDIDATES,caller,bindings,Instant.now().plusSeconds(30));
            var candidates=client.evaluate(exchange);assertEquals(role,candidates.result().get("role").get("roleId").longValue());
            assertEquals(2,candidates.result().get("members").size());assertTrue(candidates.authority().get("sourceRevision").textValue().startsWith("awr-"));
            assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->client.evaluate(exchange)).getErrorCode());
            var voter=bindings.deepCopy();var target=json.object();target.put("use","CAST");target.put("stepKey","REVIEW");
            target.put("taskId",bindings.get("owner").get("path").textValue().substring(10,46));target.putNull("evidenceId");target.putNull("evidenceSha256");target.put("sourceGeneration",1);
            target.put("actorId",caller);target.put("actorPersonPublicId",callerPerson.toString());target.put("principalId",source);target.put("principalPersonPublicId",sourcePerson.toString());
            var delegation=json.object();delegation.put("id",UUID.randomUUID().toString());delegation.put("delegatorUserId",source);delegation.put("delegateUserId",caller);
            delegation.put("delegatePersonPublicId",callerPerson.toString());delegation.put("workflowVersionId",bindings.get("owner").get("workflowVersionId").textValue());
            delegation.put("authorityRoleId",role);delegation.put("startsAt",Instant.now().minusSeconds(60).getEpochSecond());delegation.put("endsAt",Instant.now().plusSeconds(60).getEpochSecond());
            target.set("delegation",delegation);voter.set("target",target);
            var accepted=client.evaluate(issuer.issue(Operation.VOTER,caller,voter,Instant.now().plusSeconds(30)));
            assertEquals(source,accepted.result().get("principal").get("userId").longValue());
            var db=(JdbcTemplate)call(fixture,"jdbc",new Class<?>[0]);db.update("DELETE FROM com_role_members WHERE tenant_id=? AND role_id=? AND user_id=?",tenant,role,source);
            assertEquals(1L,db.queryForObject("SELECT count(*) FROM com_role_members WHERE tenant_id=? AND role_id=? AND user_id=?",Long.class,tenant,role,other));
            assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->client.evaluate(issuer.issue(Operation.VOTER,caller,voter,Instant.now().plusSeconds(30)))).getErrorCode());
            db.update("INSERT INTO com_role_members(tenant_id,role_id,user_id) VALUES(?,?,?)",tenant,role,source);
            db.update("UPDATE com_users SET person_public_id=? WHERE tenant_id=? AND user_id=?",UUID.randomUUID(),tenant,source);
            assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->client.evaluate(issuer.issue(Operation.VOTER,caller,voter,Instant.now().plusSeconds(30)))).getErrorCode());
            db.update("UPDATE com_users SET person_public_id=? WHERE tenant_id=? AND user_id=?",sourcePerson,tenant,source);
            db.update("UPDATE com_users SET status='INACTIVE' WHERE tenant_id=? AND user_id=?",tenant,caller);
            assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->client.evaluate(issuer.issue(Operation.CANDIDATES,caller,bindings,Instant.now().plusSeconds(30)))).getErrorCode());
        }
    }
    private com.fasterxml.jackson.databind.node.ObjectNode bindings(long tenant,long caller,UUID callerPerson,long requester,UUID requesterPerson,JsonNode current) {
        var definition=ApprovalWorkflowQuorumDefinition.fromStages(60,List.of(new ApprovalWorkflowQuorumDefinition.Stage("REVIEW","Review","JOINT_REVIEWER",
                new ApprovalWorkflowQuorum.Rule(ApprovalWorkflowQuorum.Mode.ALL,null),15,List.of())));
        var request=UUID.randomUUID();var task=UUID.randomUUID();var owner=json.object();owner.put("tenantId",tenant);owner.put("actorId",caller);owner.put("personPublicId",callerPerson.toString());
        owner.put("requestId",request.toString());owner.put("requestVersion",0);owner.put("workflowVersionId",UUID.randomUUID().toString());owner.put("workflowVersion",1);
        owner.put("workflowDefinitionSha256",definition.sha256());owner.put("formVersionId",UUID.randomUUID().toString());owner.put("formSchemaSha256","a".repeat(64));
        owner.put("payloadRevision",1);owner.put("payloadSha256","b".repeat(64));owner.put("policyVersion",1);owner.put("policySha256","c".repeat(64));
        owner.put("contextKey",current.get("contextKey").textValue());owner.put("contextScopeKey",current.get("scopes").get(0).get("key").textValue());
        owner.put("decisionRevision","psr-"+"d".repeat(64));owner.put("accessMode","NORMAL");owner.put("routeContractKey","route.approvals.work.task-decision.action");
        owner.put("managementResourceSetKey","RS_JOINT");owner.putArray("roleCodes").add("JOINT_REVIEWER");owner.put("publishedDefinition",definition.canonicalJson());
        owner.put("method","POST");owner.put("path","/v1/tasks/"+task+"/decisions");owner.put("idempotencyKey","joint-key");
        var stage=json.object();stage.put("poolMode","SEALED");stage.put("stepKey","REVIEW");stage.put("generation",1);stage.put("requesterUserId",requester);
        stage.put("requesterPersonPublicId",requesterPerson.toString());stage.put("candidateRole","JOINT_REVIEWER");stage.put("sourceStageRevision",1);
        stage.put("snapshotSha256","e".repeat(64));stage.put("candidateSetSha256","f".repeat(64));stage.put("candidateCount",2);stage.put("requiredVotes",2);stage.put("rejectCommentMinLength",4);
        stage.putNull("admissionAttestation");stage.putNull("admissionAttestationSha256");stage.putNull("commandRawBodySha256");
        var result=json.object();result.set("owner",owner);result.set("stage",stage);return result;
    }
    private static Object call(Object target,String name,Class<?>[] types,Object...args) throws Exception {
        try {return target.getClass().getMethod(name,types).invoke(target,args);} catch(InvocationTargetException error) {
            if(error.getCause() instanceof Exception failure) throw failure;throw error;
        }
    }
}
