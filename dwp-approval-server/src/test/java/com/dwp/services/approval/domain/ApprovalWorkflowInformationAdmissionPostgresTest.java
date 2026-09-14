package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.workflowauthority.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real business SQL and real private verifier; signed fixtures are not installed Auth/PEP activation proof. */
@Testcontainers
class ApprovalWorkflowInformationAdmissionPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    static RSAKey owner,transport,auth;
    ApprovalWorkflowQuorumPostgresFixture f;
    ApprovalWorkflowQuorumInformationRuntime info;
    ApprovalWorkflowInformationAdmissionLedger ledger;
    final WorkflowRuntimeJson json=new WorkflowRuntimeJson();
    @BeforeAll static void keys() throws Exception {owner=key("admission-owner");transport=key("admission-transport");auth=key("admission-auth");}
    static RSAKey key(String id) throws Exception {return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();}
    @BeforeEach void initialize() {
        initialize(POSTGRES);
    }
    void initialize(PostgreSQLContainer<?> postgres) {
        f=new ApprovalWorkflowQuorumPostgresFixture();f.initialize(postgres);
        var definition=one(Mode.ALL,null);f.prepareDraft(definition);f.bindTypedForm(definition);f.pool=List.of(100L,101L,102L);
        String payload=ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(Map.of("summary","Runtime submission","amount","20")));
        f.jdbc.update("UPDATE apr_request_payloads SET payload=?::jsonb,payload_sha256=? WHERE request_id=?",payload,sha(payload),f.request);
        f.jdbc.update("UPDATE apr_requests SET status='IN_REVIEW',submitted_at=now(),due_at=now()+interval '60 minutes' WHERE request_id=?",f.request);
        f.pins=f.runtime.canonicalPins(TENANT,f.request,definition);f.runtime.start(TENANT,f.request,f.pins,definition);
        var named=new NamedParameterJdbcTemplate(f.jdbc);var mapper=new ObjectMapper().findAndRegisterModules();
        info=new ApprovalWorkflowQuorumInformationRuntime(named,mapper,f.tx,f,new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test"));
        ledger=new ApprovalWorkflowInformationAdmissionLedger(named,mapper);actor(100);
    }
    @AfterEach void clear() {ApprovalRequestContext.clear();}
    void actor(long user) {ApprovalRequestContext.set(user,TENANT,person(user),Set.of("FINANCE_REVIEWER"),Set.of("ACTION.APPROVAL_TASK:UPDATE"));}
    ApprovalWorkflowQuorumInformationRuntime.RequestCommand command() {
        var vote=f.command("FINANCE",100,100,Decision.APPROVE);
        // Distinguish task CAS from request CAS: they are not interchangeable in signed command admission.
        f.jdbc.update("UPDATE apr_tasks SET version=version+4 WHERE task_id=?",vote.taskId());
        String payloadSha=f.jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?",String.class,f.request).strip();
        return new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(f.request,vote.taskId(),vote.expectedTaskVersion()+4,0,
                new ApprovalWorkflowQuorumFacade.ExpectedVote(1,vote.expectedStageVersion(),f.pins,1,payloadSha),"info-admission","Please attach evidence",sha("original-info-body"));
    }
    WorkflowRuntimeAttestationVerifier.Verified proof(ApprovalWorkflowQuorumInformationRuntime.RequestCommand command) {
        return proof("TASK_INFORMATION",command.taskId(),command.expectedTaskVersion(),null,command.idempotencyKey(),command.rawBodySha256());
    }
    WorkflowRuntimeAttestationVerifier.Verified proof(String purpose,UUID target,long expected,Long generation,String key,String raw) {
        try {
            Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);var clock=Clock.fixed(now,ZoneOffset.UTC);
            var keys=new WorkflowRuntimeKeys(owner,transport,new JWKSet(auth.toPublicJWK()).toString());
            boolean request=purpose.equals("TASK_INFORMATION");var actor=ApprovalRequestContext.require();var command=json.object();
            command.put("tenantId",TENANT);command.put("actorId",actor.userId());command.put("personPublicId",actor.personPublicId().toString());
            command.put("commandPurpose",purpose);command.put("targetId",target.toString());
            command.put("routeContractKey",request?"route.approvals.work.task-decision.action":"route.approvals.work.request-information-response.action");
            command.put("method","POST");command.put("path",request?"/v1/tasks/"+target+"/decisions":"/v1/requests/"+target+"/information-response");
            command.put("idempotencyKey",key);command.put("rawBodySha256",raw);command.put("contextKey","receipt-context");command.put("contextScopeKey","receipt-scope");
            command.put("decisionRevision","psr-"+"a".repeat(64));command.put("accessMode","NORMAL");command.put("rolloutState","111");
            command.put("authorityValidUntil",now.plusSeconds(30).getEpochSecond());command.put("expectedVersion",expected);
            if(generation==null) command.putNull("sourceGeneration");else command.put("sourceGeneration",generation);
            var bindings=json.object();bindings.set("command",command);var exchange=new WorkflowRuntimeProofIssuer(keys,json,clock)
                    .issue(WorkflowRuntimeProtocol.Operation.INFORMATION_ADMISSION,actor.userId(),bindings,now.plusSeconds(30));
            var result=json.object();for(String field:Set.of("tenantId","personPublicId","commandPurpose","targetId","idempotencyKey","rawBodySha256",
                    "expectedVersion","sourceGeneration","contextKey","contextScopeKey","decisionRevision","routeContractKey","accessMode","rolloutState","authorityValidUntil")) result.set(field,command.get(field));
            var authority=json.object();authority.put("ownerAuthRevision","auth-current");authority.put("ownerPolicyRevision","policy-current");
            authority.put("sourceRevision","awr-"+"d".repeat(64));authority.put("sourceVectorSha256","e".repeat(64));authority.put("evaluatedAt",now.getEpochSecond());
            authority.put("expiresAt",exchange.expiresAt().getEpochSecond());var op=exchange.operation();var mapper=new ObjectMapper();
            var claims=new JWTClaimsSet.Builder().issuer(op.attestationIssuer()).claim("aud",op.attestationAudience()).subject(exchange.actorId())
                    .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(exchange.expiresAt())).jwtID(UUID.randomUUID().toString())
                    .claim("purpose",op.attestationPurpose()).claim("operation",op.name()).claim("sourceProofJti",exchange.ownerJti().toString())
                    .claim("transportProofJti",exchange.transportJti().toString()).claim("bodySha256",exchange.bodySha256()).claim("bindingsSha256",exchange.bindingsSha256())
                    .claim("authority",mapper.convertValue(authority,Object.class)).claim("result",mapper.convertValue(result,Object.class)).build();
            var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(auth.getKeyID()).build(),claims);
            jwt.sign(new RSASSASigner(auth));var response=json.object();response.put("attestation",jwt.serialize());
            return new WorkflowRuntimeAttestationVerifier(keys,json,clock).verify(json.bytes(response),exchange);
        } catch(Exception error) {throw new AssertionError(error);}
    }
    ApprovalWorkflowInformationAdmissionLedger.Accepted complete(ApprovalWorkflowQuorumInformationRuntime.RequestCommand command,WorkflowRuntimeAttestationVerifier.Verified proof) {
        var receipt=info.request(ApprovalRequestContext.require(),command);
        return ledger.request(ApprovalRequestContext.require(),command,receipt,proof);
    }
    ObjectNode stamped() {
        var command=command();var proof=proof(command);
        return f.tx.execute(tx->{var accepted=complete(command,proof);ledger.append(accepted);return (ObjectNode)accepted.admission();});
    }
    @Test void genuineVerifiedAdmissionBindsActualTaskVersionAndCanonicalReceiptInSameCompletionTransaction() {
        var command=command();var proof=proof(command);
        var doc=f.tx.execute(tx->{var accepted=complete(command,proof);ledger.append(accepted);return accepted.admission();});
        assertEquals(30,doc.size());assertEquals(command.expectedTaskVersion(),doc.get("originalExpectedVersion").longValue());
        assertNotEquals(doc.get("receiptRequestVersion").longValue()-1,doc.get("originalExpectedVersion").longValue());
        assertEquals(sha(proof.token()),doc.get("admissionSha256").textValue());assertEquals(1,f.count("apr_quorum_information_admissions"));
        String persisted=f.jdbc.queryForObject("SELECT admission::text FROM apr_quorum_information_admissions",String.class);
        assertFalse(persisted.contains(proof.token()));assertFalse(persisted.contains("sourceProof\""));assertFalse(persisted.contains("transportToken"));
        assertEquals(doc.get("commandSha256").textValue(),f.jdbc.queryForObject("SELECT command_sha256 FROM apr_quorum_information_commands",String.class).strip());
    }
    @Test void historicalCompletedCommandCannotBeBackfilledInAnotherTransaction() {
        var command=command();var proof=proof(command);var receipt=info.request(ApprovalRequestContext.require(),command);
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->f.tx.execute(tx->ledger.request(ApprovalRequestContext.require(),command,receipt,proof))).getErrorCode());
        assertEquals(0,f.count("apr_quorum_information_admissions"));assertEquals(1,f.count("apr_quorum_information_commands"));
    }
    @Test void acceptedMetadataCannotCrossTransactionAndReceiptCopiesCannotMutatePrivateAcceptance() {
        var command=command();var proof=proof(command);var accepted=f.tx.execute(tx->complete(command,proof));
        ((ObjectNode)accepted.admission()).put("commandActorId",101);
        assertEquals(100,accepted.admission().get("commandActorId").longValue());
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->f.tx.execute(tx->{ledger.append(accepted);return null;})).getErrorCode());
        assertEquals(0,f.count("apr_quorum_information_admissions"));
    }
    @Test void mismatchedSignedRawBodyOrTaskVersionRollsBackAllBusinessWrites() {
        var command=command();var wrong=proof("TASK_INFORMATION",command.taskId(),command.expectedTaskVersion(),null,command.idempotencyKey(),sha("other-body"));
        assertThrows(BaseException.class,()->f.tx.execute(tx->{ledger.append(complete(command,wrong));return null;}));
        assertEquals("IN_REVIEW",f.jdbc.queryForObject("SELECT status FROM apr_requests WHERE request_id=?",String.class,f.request));
        assertEquals(0,f.count("apr_quorum_information_commands"));assertEquals(0,f.count("apr_quorum_information_rounds"));
        assertEquals(0,f.count("apr_quorum_information_admissions"));assertEquals(1,f.count("apr_integration_outbox"));
    }
    @Test void appendOnlyEvidenceRejectsUpdateDeleteAndRawJwtFields() {
        stamped();assertThrows(DataAccessException.class,()->f.jdbc.update("UPDATE apr_quorum_information_admissions SET actor_user_id=101"));
        assertThrows(DataAccessException.class,()->f.jdbc.update("DELETE FROM apr_quorum_information_admissions"));
        assertEquals(1,f.count("apr_quorum_information_admissions"));
    }
    @Test void nullOrUnverifiedProofNeverCreatesAnAdmissionAndLegacyRawlessCommandsCannotStamp() {
        var command=command();assertThrows(BaseException.class,()->f.tx.execute(tx->{ledger.append(complete(command,null));return null;}));
        assertEquals(0,f.count("apr_quorum_information_commands"));assertEquals(0,f.count("apr_quorum_information_admissions"));
        var legacy=new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(command.requestId(),command.taskId(),command.expectedTaskVersion(),
                command.expectedRequestVersion(),command.expectedQuorum(),command.idempotencyKey(),command.reason());
        var proof=proof(command);assertThrows(BaseException.class,()->f.tx.execute(tx->{ledger.append(complete(legacy,proof));return null;}));
        assertEquals(0,f.count("apr_quorum_information_admissions"));
    }
    @Test void replyStampBelongsToRequesterButRetainsOriginalRoundActorTaskAndGeneration() {
        var doc=stamped();actor(REQUESTER);String raw=sha("reply-body");
        var reply=new ApprovalWorkflowQuorumInformationRuntime.ReplyCommand(f.request,1,1,"reply-admission","Evidence attached",Map.of(),raw);
        var proof=proof("REQUEST_REPLY",f.request,1,1L,reply.idempotencyKey(),raw);
        var accepted=f.tx.execute(tx->{var receipt=info.reply(ApprovalRequestContext.require(),reply,(a,r,form,sha,schema,payload,submitting,version)->payload);
            var xid=f.jdbc.queryForMap("SELECT pg_current_xact_id()::text AS parent,marker.transaction_id::text AS recorded,command.xmin::text AS child "
                    +"FROM apr_quorum_information_commands command JOIN apr_quorum_information_completion_transactions marker USING(tenant_id,request_id,idempotency_key) WHERE idempotency_key='reply-admission'");
            assertNotEquals(xid.get("parent"),xid.get("child"));assertEquals(xid.get("parent"),xid.get("recorded"));
            var sealed=ledger.reply(ApprovalRequestContext.require(),reply,receipt,proof);ledger.append(sealed);return sealed.admission();});
        assertEquals(REQUESTER,accepted.get("commandActorId").longValue());assertEquals(doc.get("taskId"),accepted.get("taskId"));
        assertEquals(doc.get("roundId"),accepted.get("roundId"));assertEquals(1,accepted.get("sourceGeneration").longValue());
        assertEquals(2,accepted.get("receiptGeneration").longValue());assertEquals(2,f.count("apr_quorum_information_admissions"));
    }
    @Test void databaseAlsoRejectsOutOfTransactionBackfillEvenWithOtherwiseValidMetadata() {
        var doc=stamped();assertThrows(DataAccessException.class,()->f.jdbc.update("""
                INSERT INTO apr_quorum_information_admissions SELECT tenant_id,request_id,idempotency_key,operation,command_sha256,
                    raw_body_sha256,receipt_sha256,round_id,source_generation,task_id,actor_user_id,actor_person_id,admission_jti,accepted_at,admission
                FROM apr_quorum_information_admissions
                """));assertEquals(1,f.count("apr_quorum_information_admissions"));assertEquals(30,doc.size());
    }
    @Test void nativeTransactionMarkerIsNotAnAuthorityStampAndCannotBeManuallyBackfilled() {
        var command=command();info.request(ApprovalRequestContext.require(),command);
        assertEquals(1,f.count("apr_quorum_information_completion_transactions"));assertEquals(0,f.count("apr_quorum_information_admissions"));
        assertThrows(DataAccessException.class,()->f.jdbc.update("""
                INSERT INTO apr_quorum_information_completion_transactions SELECT tenant_id,request_id,idempotency_key,command_sha256,pg_current_xact_id()
                FROM apr_quorum_information_commands
                """));
        assertThrows(DataAccessException.class,()->f.jdbc.update("DELETE FROM apr_quorum_information_completion_transactions"));
        assertThrows(DataAccessException.class,()->f.jdbc.update("UPDATE apr_quorum_information_completion_transactions SET transaction_id=pg_current_xact_id()"));
        assertTrue(f.jdbc.queryForObject("SELECT '4294967297'::xid8>'4294967296'::xid8",Boolean.class));
    }
    @Test void parentRollbackRemovesCompletionMarkerAndGenuineAdmissionTogether() {
        var command=command();var proof=proof(command);
        assertThrows(BaseException.class,()->f.tx.execute(tx->{ledger.append(complete(command,proof));throw new BaseException(ErrorCode.FORBIDDEN);}));
        assertEquals(0,f.count("apr_quorum_information_commands"));assertEquals(0,f.count("apr_quorum_information_completion_transactions"));
        assertEquals(0,f.count("apr_quorum_information_admissions"));assertEquals(0,f.count("apr_quorum_information_rounds"));
    }
    @Test void savepointRollbackRemovesNestedMarkerAndParentCanOnlyStampARealNewCompletion() {
        var command=command();var proof=proof(command);var nested=new org.springframework.transaction.support.TransactionTemplate(f.tx.getTransactionManager());
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        f.tx.execute(tx->{
            nested.execute(child->{ledger.append(complete(command,proof));child.setRollbackOnly();return null;});
            assertEquals(0,f.count("apr_quorum_information_completion_transactions"));assertEquals(0,f.count("apr_quorum_information_admissions"));
            assertEquals(0,f.count("apr_quorum_information_commands"));ledger.append(complete(command,proof));return null;
        });
        assertEquals(1,f.count("apr_quorum_information_completion_transactions"));assertEquals(1,f.count("apr_quorum_information_admissions"));
    }
    @Test void failedSqlSavepointCannotLeakMarkerOrAdmissionAndDoesNotHealTheParentCommand() {
        var command=command();var proof=proof(command);var nested=new org.springframework.transaction.support.TransactionTemplate(f.tx.getTransactionManager());
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        f.tx.execute(tx->{
            assertThrows(DataAccessException.class,()->nested.execute(child->{ledger.append(complete(command,proof));f.jdbc.queryForObject("SELECT 1/0",Integer.class);return null;}));
            assertEquals(0,f.count("apr_quorum_information_completion_transactions"));assertEquals(0,f.count("apr_quorum_information_admissions"));
            ledger.append(complete(command,proof));return null;
        });assertEquals(1,f.count("apr_quorum_information_admissions"));
    }
    @Test void concurrentOtherTransactionCannotSeeOrAdoptAnUncommittedCommandOrItsNativeMarker() throws Exception {
        var command=command();var proof=proof(command);var visible=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var receipt=new java.util.concurrent.atomic.AtomicReference<ApprovalWorkflowQuorumInformationRuntime.Receipt>();var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result=worker.submit(()->{actor(100);try {return f.tx.execute(tx->{receipt.set(info.request(ApprovalRequestContext.require(),command));visible.countDown();
                try {if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("reader did not release the disposable transaction");}
                catch(InterruptedException error) {Thread.currentThread().interrupt();throw new AssertionError(error);}return true;});} finally {ApprovalRequestContext.clear();}});
            assertTrue(visible.await(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,f.count("apr_quorum_information_commands"));assertEquals(0,f.count("apr_quorum_information_completion_transactions"));
            assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->f.tx.execute(tx->ledger.request(ApprovalRequestContext.require(),command,receipt.get(),proof))).getErrorCode());
            release.countDown();assertTrue(result.get(15,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,f.count("apr_quorum_information_completion_transactions"));
            assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->f.tx.execute(tx->ledger.request(ApprovalRequestContext.require(),command,receipt.get(),proof))).getErrorCode());
            assertEquals(0,f.count("apr_quorum_information_admissions"));
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
}
