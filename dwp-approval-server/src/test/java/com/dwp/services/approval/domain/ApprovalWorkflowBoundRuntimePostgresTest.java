package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual engine transactions and target IDs; the fixture authority is not production Auth activation evidence. */
@Testcontainers(disabledWithoutDocker=true)
class ApprovalWorkflowBoundRuntimePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    NamedParameterJdbcTemplate jdbc;
    Bound authority;
    final ThreadLocal<UUID> currentTask=new ThreadLocal<>();
    @BeforeEach void initialize() {
        f=new ApprovalWorkflowQuorumPostgresFixture();f.initialize(POSTGRES);jdbc=new NamedParameterJdbcTemplate(f.jdbc);authority=new Bound();
        var mapper=new ObjectMapper().findAndRegisterModules();
        f.runtime=new ApprovalWorkflowQuorumRuntime(jdbc,mapper,f.tx,authority,new AuditOutboxRecorder(jdbc,mapper,"dwp-approval-server","test","test"));
        actor(REQUESTER);
    }
    @AfterEach void clear() {ApprovalRequestContext.clear();currentTask.remove();}
    void actor(long id) {ApprovalRequestContext.set(id,TENANT,person(id),Set.of("FINANCE_REVIEWER"),Set.of("ACTION.APPROVAL_TASK:UPDATE"));}
    final class Bound implements ApprovalWorkflowBoundAuthority {
        final List<com.fasterxml.jackson.databind.JsonNode> targets=new java.util.concurrent.CopyOnWriteArrayList<>();
        int activations;
        volatile boolean unknown;
        @Override public CandidatePool candidates(Pins pins,UUID request,ApprovalWorkflowQuorumDefinition.Stage stage,Instant now) {throw unavailable("Unbound enumeration must not run.");}
        @Override public CurrentAuthority voter(Snapshot snapshot,long actor,long principal,Instant now) {throw unavailable("Unbound voter must not run.");}
        @Override public CandidatePool activation(long tenant,UUID request,UUID step,long generation,Instant now) {
            var store=new ApprovalWorkflowQuorumRuntimeStore(jdbc,new ObjectMapper());
            var row=store.generation(tenant,request,generation,true).stream().filter(item->item.stepId().equals(step)).findFirst().orElseThrow();
            assertEquals("WAITING",row.status());assertNull(row.snapshot());assertEquals(generation,row.generation());
            String route=currentTask.get()==null?"route.approvals.work.request-submit.action":"route.approvals.work.task-decision.action";
            String path=currentTask.get()==null?"/v1/requests/"+request+"/submit":"/v1/tasks/"+currentTask.get()+"/decisions";
            var context=new com.dwp.services.approval.security.ApprovalDecisionRevisionContext.Evidence("psr-"+"a".repeat(64),
                    java.time.OffsetDateTime.now().plusMinutes(1),"ctx","scope",route,"111");
            var source=ApprovalWorkflowRuntimeSource.activation(jdbc,tenant,request,step,generation,
                    new com.dwp.services.approval.workflowauthority.WorkflowRuntimeActionContext.Current(ApprovalRequestContext.require(),context,"NORMAL"),"POST",path,"fixture-key",null);
            assertEquals(25,source.bindings().get("owner").size());assertEquals(15,source.bindings().get("stage").size());
            if(generation==1) {assertEquals("INITIAL",source.bindings().get("stage").get("poolMode").textValue());assertTrue(source.bindings().get("stage").get("candidateCount").isNull());}
            var stage=ApprovalWorkflowQuorumDefinition.compile(row.definition()).stages().stream().filter(item->item.key().equals(row.key())).findFirst().orElseThrow();
            activations++;return f.candidates(row.context().pins(),request,stage,now);
        }
        @Override public CurrentAuthority voter(Snapshot snapshot,ApprovalWorkflowRuntimeTarget.Use use,UUID task,UUID evidence,Instant now) {
            var target=ApprovalWorkflowRuntimeTarget.seal(jdbc,snapshot,use,task,evidence).json();targets.add(target);
            if(unknown) return null;
            return f.voter(snapshot,target.get("actorId").longValue(),target.get("principalId").longValue(),now);
        }
    }
    private ApprovalWorkflowQuorumRuntime.Receipt vote(long actor,long principal) {
        actor(actor);var command=f.command("FINANCE",actor,principal,Decision.APPROVE);currentTask.set(command.taskId());return f.runtime.vote(command);
    }
    @Test void engineUsesActualCastAndImmutableVoteUuidRecheckWithoutUnboundFallback() {
        f.start(one(Mode.COUNT,2));vote(101,101);UUID first=f.jdbc.queryForObject("SELECT vote_id FROM apr_quorum_votes",UUID.class);
        vote(102,102);assertEquals(2,f.count("apr_quorum_votes"));assertEquals(1,authority.activations);
        assertTrue(authority.targets.stream().anyMatch(target->"VOTE_RECHECK".equals(target.get("use").textValue()) && first.toString().equals(target.get("evidenceId").textValue())));
        assertEquals(2,authority.targets.stream().filter(target->"CAST".equals(target.get("use").textValue())).count());
    }
    @Test void secondBoundRefreshKeepsOriginalDelegationAndReplacementCannotHeal() {
        f.start(one(Mode.COUNT,2));var original=f.delegate(200,101);vote(200,101);
        assertEquals(2,authority.targets.stream().filter(target->"CAST".equals(target.get("use").textValue())).count());
        f.jdbc.update("UPDATE apr_delegations SET lifecycle_state='REVOKED' WHERE delegation_id=?",original);f.delegate(200,101);
        assertThrows(BaseException.class,()->vote(102,102));assertEquals(1,f.count("apr_quorum_votes"));assertEquals(2,f.count("apr_integration_outbox"));
    }
    @Test void stagedJoinActivationReceivesItsActualStepOnlyAfterBothBranchesComplete() {
        f.start(ApprovalWorkflowQuorumDefinition.fromStages(60,List.of(stage("LEFT",Mode.ANY,null,List.of()),stage("RIGHT",Mode.ANY,null,List.of()),
                stage("JOIN",Mode.ANY,null,List.of("LEFT","RIGHT")))));
        actor(101);var left=f.command("LEFT",101,101,Decision.APPROVE);currentTask.set(left.taskId());f.runtime.vote(left);assertEquals(2,authority.activations);
        actor(102);var right=f.command("RIGHT",102,102,Decision.APPROVE);currentTask.set(right.taskId());f.runtime.vote(right);assertEquals(3,authority.activations);assertEquals("IN_PROGRESS",f.status("JOIN"));
    }
    @Test void unknownBoundSourceRollsBackVoteCasAndAuditWithoutFallback() {
        f.start(one(Mode.ANY,null));authority.unknown=true;assertThrows(BaseException.class,()->vote(101,101));
        assertEquals(0,f.count("apr_quorum_votes"));assertEquals("IN_PROGRESS",f.status("FINANCE"));assertEquals(1,f.count("sys_audit_outbox"));
    }
    @Test void informationEngineUsesOriginalRoundAndTaskIdentityAndItsDigestExcludesResponseState() {
        var definition=one(Mode.ALL,null);f.prepareDraft(definition);f.bindTypedForm(definition);f.pool=List.of(100L,101L,102L);
        var payload=java.util.Map.<String,Object>of("summary","Runtime submission","amount","20");
        String json=ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(payload));
        String hash=ApprovalFormSchemaV2Canonical.sha256(json);
        f.jdbc.update("UPDATE apr_request_payloads SET payload=?::jsonb,payload_sha256=? WHERE request_id=?",json,hash,f.request);
        f.jdbc.update("UPDATE apr_requests SET status='IN_REVIEW',submitted_at=now(),due_at=now()+interval '60 minutes' WHERE request_id=?",f.request);
        f.pins=f.runtime.canonicalPins(TENANT,f.request,definition);f.runtime.start(TENANT,f.request,f.pins,definition);
        var mapper=new ObjectMapper().findAndRegisterModules();var info=new ApprovalWorkflowQuorumInformationRuntime(jdbc,mapper,f.tx,authority,
                new AuditOutboxRecorder(jdbc,mapper,"dwp-approval-server","test","test"));
        actor(100);var task=f.command("FINANCE",100,100,Decision.APPROVE);currentTask.set(task.taskId());
        var request=new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(f.request,task.taskId(),task.expectedTaskVersion(),0,
                new ApprovalWorkflowQuorumFacade.ExpectedVote(1,task.expectedStageVersion(),f.pins,1,hash),"information-key","Please add evidence");
        var receipt=info.request(ApprovalRequestContext.require(),request);assertEquals("COMPLETED",receipt.status());
        assertEquals(3,authority.targets.stream().filter(target->"SEAT_RECHECK".equals(target.get("use").textValue())).count());
        var store=new ApprovalWorkflowQuorumRuntimeStore(jdbc,mapper);var frozen=store.generation(TENANT,f.request,1L,false).getFirst().snapshot();
        actor(REQUESTER);var before=f.tx.execute(status->ApprovalWorkflowRuntimeTarget.seal(jdbc,frozen,ApprovalWorkflowRuntimeTarget.Use.INFORMATION_RECHECK,task.taskId(),receipt.roundId()).json());
        currentTask.remove();long version=f.jdbc.queryForObject("SELECT version FROM apr_requests WHERE request_id=?",Long.class,f.request);
        var response=info.reply(ApprovalRequestContext.require(),new ApprovalWorkflowQuorumInformationRuntime.ReplyCommand(f.request,version,1,"reply-key","Evidence attached",java.util.Map.of()),
                (a,r,form,sha,schema,merged,submitting,expected)->merged);
        assertEquals("COMPLETED",response.status());assertEquals(2,response.generation());assertEquals(0,f.count("apr_quorum_votes"));
        var after=f.tx.execute(status->ApprovalWorkflowRuntimeTarget.seal(jdbc,frozen,ApprovalWorkflowRuntimeTarget.Use.INFORMATION_RECHECK,task.taskId(),receipt.roundId()).json());
        assertEquals(before.get("evidenceSha256"),after.get("evidenceSha256"));
        assertTrue(authority.targets.stream().anyMatch(target->"INFORMATION_RECHECK".equals(target.get("use").textValue())
                && receipt.roundId().toString().equals(target.get("evidenceId").textValue()) && task.taskId().toString().equals(target.get("taskId").textValue())));
        assertEquals(6,f.count("apr_quorum_candidates"));assertEquals(1,f.count("apr_quorum_information_rounds"));
    }
    @Test void concurrentBoundVotesKeepOneCasWinnerAndRecheckActualPriorVoteOnRetry() throws Exception {
        f.start(one(Mode.COUNT,2));var left=f.command("FINANCE",101,101,Decision.APPROVE);var right=f.command("FINANCE",102,102,Decision.APPROVE);
        var gate=new CountDownLatch(1);var threads=Executors.newFixedThreadPool(2);
        try {
            var a=threads.submit(()->attempt(left,gate));var b=threads.submit(()->attempt(right,gate));gate.countDown();
            boolean won=a.get(15,TimeUnit.SECONDS);assertNotEquals(won,b.get(15,TimeUnit.SECONDS));assertEquals(1,f.count("apr_quorum_votes"));
            assertEquals("APPROVED",vote(won?102:101,won?102:101).requestStatus());assertEquals(2,f.count("apr_quorum_votes"));
        } finally {threads.shutdownNow();assertTrue(threads.awaitTermination(5,TimeUnit.SECONDS));}
    }
    private boolean attempt(ApprovalWorkflowQuorumRuntime.VoteCommand command,CountDownLatch gate) throws Exception {
        assertTrue(gate.await(5,TimeUnit.SECONDS));actor(command.actorUserId());currentTask.set(command.taskId());
        try {f.runtime.vote(command);return true;} catch(BaseException expected) {return false;} finally {ApprovalRequestContext.clear();currentTask.remove();}
    }
}
