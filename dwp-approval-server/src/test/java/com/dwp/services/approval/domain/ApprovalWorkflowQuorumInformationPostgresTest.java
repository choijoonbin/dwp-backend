package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalWorkflowQuorumInformationPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    ApprovalWorkflowQuorumInformationRuntime info;
    ApprovalWorkflowQuorumDefinition definition;
    AtomicInteger normalized = new AtomicInteger();

    @BeforeEach void initialize() {
        f = new ApprovalWorkflowQuorumPostgresFixture(); f.initialize(POSTGRES);
        definition = one(Mode.ALL, null); f.prepareDraft(definition); f.bindTypedForm(definition);
        var payload = Map.<String, Object>of("summary", "Runtime submission", "amount", "20");
        String json = ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(payload));
        f.jdbc.update("UPDATE apr_request_payloads SET payload=?::jsonb,payload_sha256=? WHERE request_id=?",
                json, ApprovalFormSchemaV2Canonical.sha256(json), f.request);
        f.jdbc.update("UPDATE apr_requests SET status='IN_REVIEW',submitted_at=now(),due_at=now()+interval '60 minutes' WHERE request_id=?", f.request);
        f.pool = List.of(100L, 101L, 102L); f.pins = f.runtime.canonicalPins(TENANT, f.request, definition);
        f.runtime.start(TENANT, f.request, f.pins, definition);
        var named = new NamedParameterJdbcTemplate(f.jdbc); var mapper = new ObjectMapper().findAndRegisterModules();
        info = new ApprovalWorkflowQuorumInformationRuntime(named, mapper, f.tx, f,
                new AuditOutboxRecorder(named, mapper, "dwp-approval-server", "test", "test"));
    }
    private Actor actor(long user) { return new Actor(user, TENANT, person(user), "Actor", Set.of("FINANCE_REVIEWER"), Set.of("ACTION.APPROVAL_TASK:UPDATE")); }
    private long version() { return f.jdbc.queryForObject("SELECT version FROM apr_requests WHERE request_id=?", Long.class, f.request); }
    private String hash() { return f.jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?", String.class, f.request).strip(); }
    private ApprovalWorkflowQuorumInformationRuntime.RequestCommand command() {
        var vote = f.command("FINANCE", 100, 100, Decision.APPROVE);
        return new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(f.request, vote.taskId(), vote.expectedTaskVersion(), version(),
                new ApprovalWorkflowQuorumFacade.ExpectedVote(1, vote.expectedStageVersion(), f.pins, 1, hash()), "information-1", "Please attach evidence");
    }
    private ApprovalWorkflowQuorumInformationRuntime.ReplyCommand reply(Map<String, Object> patch) {
        return new ApprovalWorkflowQuorumInformationRuntime.ReplyCommand(f.request, version(), 1, "response-1", "Evidence attached", patch);
    }
    private ApprovalFormPayloadNormalization port() {
        return (actor, request, form, schemaHash, schema, merged, submitting, expectedVersion) -> {
            normalized.incrementAndGet(); assertEquals(REQUESTER, actor.userId()); assertEquals(f.request, request);
            assertEquals(f.jdbc.queryForObject("SELECT form_version_id FROM apr_requests WHERE request_id=?", UUID.class, f.request), form);
            assertEquals(version(), expectedVersion); assertTrue(submitting);
            var compiled = new ApprovalFormSchemaV2Compiler().compile(new ApprovalCommandPayloadSupport(new ObjectMapper()).object(schema, "Schema"));
            assertEquals(compiled.sha256(), schemaHash);
            return new ApprovalFormSchemaV2Evaluator().evaluate(compiled, merged, true).payload();
        };
    }
    private String snapshot() {
        return f.jdbc.queryForObject("""
                SELECT jsonb_build_object('requests',(SELECT jsonb_agg(to_jsonb(r)) FROM apr_requests r WHERE request_id=?),
                    'stages',(SELECT jsonb_agg(to_jsonb(s) ORDER BY generation,stage_key) FROM apr_quorum_stage_runtime s WHERE request_id=?),
                    'tasks',(SELECT jsonb_agg(to_jsonb(t) ORDER BY task_id) FROM apr_tasks t WHERE request_id=?),
                    'rounds',(SELECT jsonb_agg(to_jsonb(r)) FROM apr_quorum_information_rounds r WHERE request_id=?),
                    'votes',(SELECT count(*) FROM apr_quorum_votes),'events',(SELECT count(*) FROM apr_request_events),
                    'payload',(SELECT to_jsonb(p) FROM apr_request_payloads p WHERE request_id=?))::text
                """, String.class, f.request, f.request, f.request, f.request, f.request);
    }

    @Test void informationRequestIsNotAVoteAndCancelsOldGenerationAndClaimedSlaLease() {
        f.runtime.vote(f.command("FINANCE", 101, 101, Decision.APPROVE));
        f.dueTimers(); var leases = f.sla.claim("info-worker", 30, 10); assertFalse(leases.isEmpty());
        var receipt = info.request(actor(100), command()); assertEquals("COMPLETED", receipt.status());
        assertEquals("NEEDS_INFO", f.jdbc.queryForObject("SELECT status FROM apr_requests WHERE request_id=?", String.class, f.request));
        assertEquals(1, f.count("apr_quorum_votes")); assertEquals(3, f.count("apr_quorum_candidates"));
        assertEquals("CANCELLED", f.status("FINANCE"));
        long events = f.count("apr_request_events"); leases.forEach(lease -> assertFalse(f.sla.finish(lease)));
        assertEquals(events, f.count("apr_request_events"));
    }

    @Test void nonMaterialNormalizedReplyRetainsFrozenDenominatorAndNeverCountsOldVotes() {
        f.runtime.vote(f.command("FINANCE", 101, 101, Decision.APPROVE)); var old = f.command("FINANCE", 102, 102, Decision.APPROVE);
        info.request(actor(100), command()); f.pool = List.of(100L, 101L, 102L, 104L);
        var response = info.reply(actor(REQUESTER), reply(Map.of("amount", "20.000")), port());
        assertFalse(response.materialChange()); assertEquals(2, response.generation()); assertEquals(1, response.payloadRevision());
        assertEquals(1, f.count("apr_quorum_votes")); assertEquals(6, f.count("apr_quorum_candidates"));
        assertEquals(3, f.jdbc.queryForObject("SELECT threshold FROM apr_quorum_stage_runtime WHERE request_id=? AND generation=2", Integer.class, f.request));
        assertEquals(2, f.jdbc.queryForObject("SELECT count(*) FROM apr_tasks WHERE request_id=? AND status='SUPERSEDED'", Integer.class, f.request));
        String before = snapshot(); assertThrows(BaseException.class, () -> f.runtime.vote(old)); assertEquals(before, snapshot());
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM apr_quorum_votes WHERE request_id=? AND generation=2", Integer.class, f.request));
    }

    @Test void materialCanonicalReplyCreatesNewPayloadEvidenceAndGeneration() {
        info.request(actor(100), command()); var response = info.reply(actor(REQUESTER), reply(Map.of("amount", "25.00")), port());
        assertTrue(response.materialChange()); assertEquals(2, response.payloadRevision()); assertEquals(2, response.generation());
        assertEquals("25", f.jdbc.queryForObject("SELECT payload->>'amount' FROM apr_request_payloads WHERE request_id=?", String.class, f.request));
        assertEquals(1, f.jdbc.queryForObject("SELECT count(*) FROM apr_request_payload_versions WHERE request_id=? AND revision_number=2", Integer.class, f.request));
    }

    @Test void unknownCommandCommitsOnlyDurableIntentAndSameKeyRecoversWithoutDuplicateRound() {
        var command = command(); String before = snapshot(); f.unknown = true;
        assertEquals("UNKNOWN", info.request(actor(100), command).status()); assertEquals(before, snapshot());
        assertEquals(1, f.count("apr_quorum_information_commands")); f.unknown = false;
        var receipt = info.request(actor(100), command); assertEquals("COMPLETED", receipt.status());
        assertEquals(receipt, info.request(actor(100), command)); assertEquals(1, f.count("apr_quorum_information_rounds"));
        var changed = new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(command.requestId(), command.taskId(), command.expectedTaskVersion(),
                command.expectedRequestVersion(), command.expectedQuorum(), command.idempotencyKey(), "Different explanation");
        before = snapshot(); assertThrows(BaseException.class, () -> info.request(actor(100), changed)); assertEquals(before, snapshot());
    }

    @Test void originalSourceRoleWithdrawalCannotBeHealedByAlternateSameRoleMember() {
        info.request(actor(100), command()); f.withdrawnRoles.add(100L); assertTrue(f.subject(101).roles().contains("FINANCE_REVIEWER"));
        String before = snapshot(); var error = assertThrows(BaseException.class, () -> info.reply(actor(REQUESTER), reply(Map.of()), port()));
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCode()); assertEquals(0, normalized.get()); assertEquals(before, snapshot());
    }

    @Test void replacementDelegationCannotHealOriginalInformationDecision() {
        f.delegate(104, 100); info.request(actor(104), command()); f.delegate(104, 100);
        String before = snapshot(); var error = assertThrows(BaseException.class, () -> info.reply(actor(REQUESTER), reply(Map.of()), port()));
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCode()); assertEquals(before, snapshot()); assertEquals(0, normalized.get());
    }

    @Test void currentFormDraftAndAdvancedWorkflowHeadDoNotReplaceImmutableRequestPins() {
        info.request(actor(100), command());
        f.jdbc.update("UPDATE apr_forms SET lifecycle_state='DRAFT' WHERE form_id=(SELECT form_id FROM apr_form_versions WHERE form_version_id="
                + "(SELECT form_version_id FROM apr_requests WHERE request_id=?))", f.request);
        f.jdbc.update("UPDATE apr_workflow_definitions SET current_version=91 WHERE workflow_id=?", f.workflow);
        var response = info.reply(actor(REQUESTER), reply(Map.of()), port()); assertEquals("COMPLETED", response.status());
        assertEquals(f.workflowVersion, f.jdbc.queryForObject("SELECT workflow_version_id FROM apr_requests WHERE request_id=?", UUID.class, f.request));
        assertEquals(1, normalized.get());
    }

    @Test void unknownNormalizationCommitsNoPayloadTaskOrGenerationChangesAndCanRetrySameCommand() {
        info.request(actor(100), command()); var reply = reply(Map.of("amount", "30")); String before = snapshot();
        assertEquals("UNKNOWN", info.reply(actor(REQUESTER), reply, (a,r,f,s,j,m,b,v) -> { throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); }).status());
        assertEquals(before, snapshot()); var receipt = info.reply(actor(REQUESTER), reply, port()); assertEquals("COMPLETED", receipt.status());
        assertEquals(receipt, info.reply(actor(REQUESTER), reply, port())); assertEquals(1, normalized.get());
    }

    @Test void lateGenerationAuthorityUnknownRollsBackBusinessSavepointButCommitsOriginalIntent() {
        info.request(actor(100), command()); var command = reply(Map.of("amount", "30")); String before = snapshot();
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean(true);
        var authority = new ApprovalWorkflowQuorumAuthority() {
            public CandidatePool candidates(Pins pins, UUID request, ApprovalWorkflowQuorumDefinition.Stage stage, java.time.Instant now) {
                return unavailable.get() ? null : f.candidates(pins, request, stage, now);
            }
            public CurrentAuthority voter(Snapshot snapshot, long actor, long principal, java.time.Instant now) {
                return f.voter(snapshot, actor, principal, now);
            }
        };
        var named = new NamedParameterJdbcTemplate(f.jdbc); var mapper = new ObjectMapper().findAndRegisterModules();
        var engine = new ApprovalWorkflowQuorumInformationRuntime(named, mapper, f.tx, authority,
                new AuditOutboxRecorder(named, mapper, "dwp-approval-server", "test", "test"));
        assertEquals("UNKNOWN", engine.reply(actor(REQUESTER), command, port()).status()); assertEquals(before, snapshot());
        assertEquals("UNKNOWN", f.jdbc.queryForObject("SELECT status FROM apr_quorum_information_commands WHERE idempotency_key='response-1'", String.class));
        unavailable.set(false); assertEquals("COMPLETED", engine.reply(actor(REQUESTER), command, port()).status());
        assertEquals(2, f.jdbc.queryForObject("SELECT MAX(generation) FROM apr_quorum_stage_runtime", Integer.class));
        assertEquals(1, f.count("apr_quorum_information_rounds"));
    }

    @Test void roleRevocationDuringNormalizationCannotFinalizeOrHealUsingOtherRoleMember() {
        info.request(actor(100), command()); String before = snapshot();
        var original = port();
        var error = assertThrows(BaseException.class, () -> info.reply(actor(REQUESTER), reply(Map.of("amount", "30")),
                (a,r,f,s,j,m,b,v) -> { var result = original.normalize(a,r,f,s,j,m,b,v); this.f.withdrawnRoles.add(100L); return result; }));
        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCode()); assertEquals(before, snapshot()); assertEquals(1, normalized.get());
    }

    @Test void completedReplayMustStillMatchCurrentEffectiveBindingAndPolicy() {
        var command = command(); info.request(actor(100), command());
        f.jdbc.update("UPDATE apr_form_workflow_bindings SET effective_from=now()-interval '2 hours',effective_to=now()-interval '1 second' WHERE workflow_id=?", f.workflow);
        String before = snapshot(); assertThrows(BaseException.class, () -> info.request(actor(100), command)); assertEquals(before, snapshot());
    }

    @Test void staleClientPayloadEvidenceCannotCreateAnyInformationIntentOrBusinessWrite() {
        var original = command(); var expected = original.expectedQuorum();
        var command = new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(original.requestId(), original.taskId(),
                original.expectedTaskVersion(), original.expectedRequestVersion(), new ApprovalWorkflowQuorumFacade.ExpectedVote(
                    expected.generation(), expected.stageVersion(), expected.pins(), expected.payloadRevision(), "0".repeat(64)),
                original.idempotencyKey(), original.reason());
        String before = snapshot(); var error = assertThrows(BaseException.class, () -> info.request(actor(100), command));
        assertEquals(ErrorCode.RESOURCE_CONFLICT, error.getErrorCode()); assertEquals(before, snapshot());
        assertEquals(0, f.count("apr_quorum_information_commands"));
    }

    @Test void delegatedVoterCannotMakeAnotherInformationDecisionUsingOwnUnusedSeat() {
        f.delegate(100, 101); f.runtime.vote(f.command("FINANCE", 100, 101, Decision.APPROVE));
        String before = snapshot(); var error = assertThrows(BaseException.class, () -> info.request(actor(100), command()));
        assertEquals(ErrorCode.RESOURCE_CONFLICT, error.getErrorCode()); assertEquals(before, snapshot());
        assertEquals(0, f.count("apr_quorum_information_commands"));
    }

    @Test void normalizedTypedReplyCannotBypassExistingConditionalBinding() {
        f.jdbc.update("UPDATE apr_form_workflow_bindings SET binding_type='CONDITIONAL',condition_payload="
                + "'{\"all\":[{\"field\":\"amount\",\"operator\":\"GTE\",\"value\":20}]}'::jsonb WHERE workflow_id=?", f.workflow);
        info.request(actor(100), command()); String before = snapshot();
        assertThrows(BaseException.class, () -> info.reply(actor(REQUESTER), reply(Map.of("amount", "10.00")), port()));
        assertEquals(before, snapshot()); assertEquals(0, f.jdbc.queryForObject(
                "SELECT count(*) FROM apr_quorum_information_commands WHERE idempotency_key='response-1'", Integer.class));
        assertEquals("COMPLETED", info.reply(actor(REQUESTER), reply(Map.of("amount", "20.000")), port()).status());
    }

    @Test void concurrentVoteAndInformationRequestHaveExactlyOneDurableWinner() throws Exception {
        var command = command(); var vote = f.command("FINANCE", 101, 101, Decision.APPROVE); var ready = new CountDownLatch(1);
        var winners = new AtomicInteger(); var conflicts = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> compete(ready, () -> info.request(actor(100), command), winners, conflicts));
            var two = workers.submit(() -> compete(ready, () -> f.runtime.vote(vote), winners, conflicts));
            ready.countDown(); one.get(20, TimeUnit.SECONDS); two.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1, winners.get()); assertEquals(1, conflicts.get());
        assertEquals(1, f.count("apr_quorum_information_rounds") + f.count("apr_quorum_votes"));
    }

    private void compete(CountDownLatch ready, Runnable action, AtomicInteger winners, AtomicInteger conflicts) {
        try { ready.await(); action.run(); winners.incrementAndGet(); }
        catch (BaseException error) { assertEquals(ErrorCode.RESOURCE_CONFLICT, error.getErrorCode()); conflicts.incrementAndGet(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
