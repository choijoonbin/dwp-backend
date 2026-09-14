package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalWorkflowQuorumRuntimePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    @BeforeEach void initialize() { f = new ApprovalWorkflowQuorumPostgresFixture(); f.initialize(POSTGRES); }

    @ParameterizedTest @EnumSource(Mode.class)
    void persistsRealTasksAndDeterministicThresholds(Mode mode) {
        Integer value = switch (mode) { case COUNT -> 2; case PERCENT -> 67; default -> null; };
        f.start(one(mode, value));
        int threshold = mode == Mode.ANY ? 1 : mode == Mode.COUNT ? 2 : 3;
        assertEquals(3, f.count("apr_quorum_candidates"));
        assertEquals(3, f.count("apr_tasks"));
        assertEquals(threshold, f.jdbc.queryForObject("SELECT threshold FROM apr_quorum_stage_runtime", Integer.class));
        for (int vote = 0; vote < threshold; vote++) {
            long user = 101 + vote;
            var receipt = f.runtime.vote(f.command("FINANCE", user, user, Decision.APPROVE));
            assertEquals(vote + 1, receipt.approved());
            assertEquals(vote + 1 == threshold ? "APPROVED" : "IN_REVIEW", receipt.requestStatus());
        }
        assertEquals(threshold, f.count("apr_quorum_votes"));
        assertEquals("APPROVED", f.status("FINANCE"));
        assertEquals(1 + threshold, f.count("apr_integration_outbox"));
        assertEquals(1 + threshold, f.count("sys_audit_outbox"));
        assertEquals(threshold, f.jdbc.queryForObject("SELECT count(*) FROM apr_tasks WHERE decision_payload_revision=1", Integer.class));
    }

    @Test void sameStageVersionConcurrentVotesHaveOneWinnerAndRetryCompletesOnce() throws Exception {
        f.start(one(Mode.COUNT, 2));
        var left = f.command("FINANCE", 101, 101, Decision.APPROVE);
        var right = f.command("FINANCE", 102, 102, Decision.APPROVE);
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> attempt(gate, left));
            var b = pool.submit(() -> attempt(gate, right));
            gate.countDown();
            boolean first = a.get(15, TimeUnit.SECONDS);
            boolean second = b.get(15, TimeUnit.SECONDS);
            assertNotEquals(first, second);
            assertEquals(1, f.count("apr_quorum_votes"));
            long retry = first ? 102 : 101;
            assertEquals("APPROVED", f.runtime.vote(f.command("FINANCE", retry, retry, Decision.APPROVE)).requestStatus());
            assertEquals(2, f.count("apr_quorum_votes"));
            assertEquals(3, f.count("apr_integration_outbox"));
            assertThrows(BaseException.class, () -> f.runtime.vote(left));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private boolean attempt(CountDownLatch gate, ApprovalWorkflowQuorumRuntime.VoteCommand command) throws Exception {
        assertTrue(gate.await(5, TimeUnit.SECONDS));
        try { f.runtime.vote(command); return true; } catch (BaseException expected) { return false; }
    }

    @Test void parallelRootsActivateJoinOnlyAfterBothActualApprovals() {
        f.start(ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(stage("LEFT", Mode.ANY, null, List.of()),
                stage("RIGHT", Mode.ANY, null, List.of()), stage("JOIN", Mode.ALL, null, List.of("LEFT", "RIGHT")))));
        assertEquals("IN_PROGRESS", f.status("LEFT"));
        assertEquals("IN_PROGRESS", f.status("RIGHT"));
        assertEquals("WAITING", f.status("JOIN"));
        assertEquals(6, f.count("apr_quorum_candidates"));
        f.runtime.vote(f.command("LEFT", 101, 101, Decision.APPROVE));
        assertEquals("WAITING", f.status("JOIN"));
        f.runtime.vote(f.command("RIGHT", 102, 102, Decision.APPROVE));
        assertEquals("IN_PROGRESS", f.status("JOIN"));
        assertEquals(9, f.count("apr_quorum_candidates"));
        assertEquals(2, f.count("apr_quorum_prerequisites"));
    }

    @Test void rejectionIsVetoAndCancelsOtherBranchesTasksAndTimers() {
        f.start(ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(stage("LEFT", Mode.ALL, null, List.of()),
                stage("RIGHT", Mode.ANY, null, List.of()), stage("JOIN", Mode.ANY, null, List.of("LEFT", "RIGHT")))));
        var receipt = f.runtime.vote(f.command("LEFT", 101, 101, Decision.REJECT));
        assertEquals("REJECTED", receipt.requestStatus());
        assertEquals("CANCELLED", f.status("RIGHT"));
        assertEquals("CANCELLED", f.status("JOIN"));
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM apr_tasks WHERE status='CLAIMED'", Integer.class));
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM apr_quorum_sla_timers WHERE status='PENDING'", Integer.class));
    }

    @Test void unknownOrTruncatedCandidatesRollbackAllStageTaskOutboxWrites() {
        var definition = one(Mode.ALL, null);
        f.prepare(definition);
        f.complete = false;
        assertThrows(BaseException.class, () -> f.runtime.start(TENANT, f.request, f.pins, definition));
        f.complete = true;
        f.truncated = true;
        assertThrows(BaseException.class, () -> f.runtime.start(TENANT, f.request, f.pins, definition));
        assertEquals(0, f.count("apr_steps"));
        assertEquals(0, f.count("apr_tasks"));
        assertEquals(0, f.count("sys_audit_outbox"));
    }

    @Test void revokedCandidateCannotVoteOrShrinkFrozenPoolAndRevokedAcceptedVoteBlocksCompletion() {
        f.start(one(Mode.COUNT, 2));
        f.runtime.vote(f.command("FINANCE", 101, 101, Decision.APPROVE));
        f.revoked.add(101L);
        assertThrows(BaseException.class, () -> f.runtime.vote(f.command("FINANCE", 102, 102, Decision.APPROVE)));
        assertEquals(1, f.count("apr_quorum_votes"));
        assertEquals(3, f.jdbc.queryForObject("SELECT eligible_count FROM apr_quorum_stage_runtime", Integer.class));
        f.revoked.clear();
        f.revoked.add(102L);
        assertThrows(BaseException.class, () -> f.runtime.vote(f.command("FINANCE", 102, 102, Decision.APPROVE)));
        assertEquals("IN_PROGRESS", f.status("FINANCE"));
        assertEquals(2, f.count("apr_integration_outbox"));
    }

    @Test void payloadChangeDeniesWithoutCommittingAnyDecision() {
        f.start(one(Mode.ANY, null));
        f.jdbc.update("UPDATE apr_request_payloads SET schema_version=2,payload_sha256=? WHERE request_id=?", "b".repeat(64), f.request);
        assertThrows(BaseException.class, () -> f.runtime.vote(f.command("FINANCE", 101, 101, Decision.APPROVE)));
        assertEquals(0, f.count("apr_quorum_votes"));
        assertEquals(1, f.count("sys_audit_outbox"));
    }

    @Test void frozenCandidatesVotesAndPublishedDefinitionCannotBeEdited() {
        f.start(one(Mode.ALL, null));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> f.jdbc.update("UPDATE apr_quorum_candidates SET principal_user_id=200 WHERE principal_user_id=101"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> f.jdbc.update("UPDATE apr_quorum_stage_runtime SET eligible_count=2,version=version+1"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> f.jdbc.update("UPDATE apr_workflow_versions SET definition_sha256=? WHERE workflow_version_id=?", "c".repeat(64), f.workflowVersion));
        f.runtime.vote(f.command("FINANCE", 101, 101, Decision.APPROVE));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> f.jdbc.update("DELETE FROM apr_quorum_votes"));
    }

    @Test void delegatedVoteRequiresActualActiveLocalGrantAndCurrentSourceAndDelegateAuthority() {
        f.start(one(Mode.ALL, null));
        f.delegate(200, 101);
        f.jdbc.update("UPDATE apr_delegations SET lifecycle_state='REVOKED' WHERE delegation_id=?", f.delegation);
        assertThrows(BaseException.class, () -> f.runtime.vote(f.command("FINANCE", 200, 101, Decision.APPROVE)));
        f.jdbc.update("UPDATE apr_delegations SET lifecycle_state='ACTIVE' WHERE delegation_id=?", f.delegation);
        f.revoked.add(101L);
        assertThrows(BaseException.class, () -> f.runtime.vote(f.command("FINANCE", 200, 101, Decision.APPROVE)));
        f.revoked.clear();
        f.revoked.add(200L);
        assertThrows(BaseException.class, () -> f.runtime.vote(f.command("FINANCE", 200, 101, Decision.APPROVE)));
        f.revoked.clear();
        f.runtime.vote(f.command("FINANCE", 200, 101, Decision.APPROVE));
        assertEquals(200L, f.jdbc.queryForObject("SELECT actor_user_id FROM apr_quorum_votes", Long.class));
    }

    @Test void expiredLeaseReclaimFencesOldWorkerAndProducesExactlyOneEscalation() {
        f.start(one(Mode.ALL, null));
        f.dueTimers();
        var old = f.sla.claim("old-worker", 60, 1).getFirst();
        f.jdbc.update("UPDATE apr_quorum_sla_timers SET lease_until=now()-interval '1 second' WHERE timer_id=?", old.timerId());
        var fresh = f.sla.claim("new-worker", 60, 2).stream().filter(lease -> lease.timerId().equals(old.timerId())).findFirst().orElseThrow();
        assertTrue(fresh.epoch() > old.epoch());
        assertFalse(f.sla.finish(old));
        assertEquals(1, f.count("sys_audit_outbox"));
        assertTrue(f.sla.finish(fresh));
        assertFalse(f.sla.finish(fresh));
        assertEquals(2, f.count("sys_audit_outbox"));
        assertEquals(2, f.count("apr_integration_outbox"));
    }

    @Test void source100CannotBeHealedBySource101WithSameRole() {
        f.pool = List.of(100L, 101L, 102L);
        f.start(one(Mode.ALL, null));
        f.delegate(200, 100);
        f.runtime.vote(f.command("FINANCE", 200, 100, Decision.APPROVE));
        f.withdrawnRoles.add(100L);
        f.delegate(200, 101);
        assertTrue(f.subject(100).active());
        assertTrue(f.subject(100).canApprove());
        assertThrows(BaseException.class, () -> f.runtime.vote(f.command("FINANCE", 102, 102, Decision.APPROVE)));
        assertEquals(100L, f.jdbc.queryForObject("SELECT principal_user_id FROM apr_quorum_votes", Long.class));
        assertEquals(1, f.count("apr_quorum_votes"));
        assertEquals(2, f.count("apr_integration_outbox"));
    }

    @Test void replacementGrantForSameSourceCannotHealRevokedOriginalVote() {
        f.start(one(Mode.COUNT, 2));
        var original = f.delegate(200, 101);
        f.runtime.vote(f.command("FINANCE", 200, 101, Decision.APPROVE));
        f.jdbc.update("UPDATE apr_delegations SET lifecycle_state='REVOKED' WHERE delegation_id=?", original);
        var replacement = f.delegate(200, 101);
        assertNotEquals(original, replacement);
        assertThrows(BaseException.class, () -> f.runtime.vote(f.command("FINANCE", 102, 102, Decision.APPROVE)));
        assertEquals(original.toString(), f.jdbc.queryForObject("SELECT evidence->>'delegationId' FROM apr_quorum_votes", String.class));
        assertEquals("IN_PROGRESS", f.status("FINANCE"));
    }

    @Test void terminalStageCancelsClaimedTimersAndUnknownAudienceCannotPublish() {
        f.start(one(Mode.ANY, null));
        f.dueTimers();
        var lease = f.sla.claim("worker", 60, 1).getFirst();
        f.unknown = true;
        assertThrows(BaseException.class, () -> f.sla.finish(lease));
        assertEquals(1, f.count("apr_integration_outbox"));
        f.unknown = false;
        f.runtime.vote(f.command("FINANCE", 101, 101, Decision.APPROVE));
        assertFalse(f.sla.finish(lease));
        assertEquals(2, f.count("apr_integration_outbox"));
    }

    @Test void chosenPolicyChangeDeniesRatherThanSilentlySwitchingRules() {
        f.start(one(Mode.COUNT, 2));
        f.jdbc.update("UPDATE apr_policy_rules SET rule_payload='{\"minimumLength\":12}'::jsonb,version=version+1 "
                + "WHERE tenant_id=42 AND policy_key='REQUIRE_REJECT_REASON'");
        assertThrows(BaseException.class, () -> f.runtime.vote(f.command("FINANCE", 101, 101, Decision.APPROVE)));
        assertEquals(0, f.count("apr_quorum_votes"));
        assertEquals(1, f.count("apr_integration_outbox"));
    }

    @Test void exactGenerationTaskAndTenantBindingsCannotBorrowAnotherSeat() {
        f.start(one(Mode.ALL, null));
        var real = f.command("FINANCE", 101, 101, Decision.APPROVE);
        var wrongGeneration = new ApprovalWorkflowQuorumRuntime.VoteCommand(real.tenantId(), real.requestId(), real.stepId(), 2,
                real.taskId(), real.expectedTaskVersion(), real.expectedStageVersion(), real.expectedPins(), 101, 101, real.decision(), "");
        assertThrows(BaseException.class, () -> f.runtime.vote(wrongGeneration));
        var wrongPrincipal = new ApprovalWorkflowQuorumRuntime.VoteCommand(real.tenantId(), real.requestId(), real.stepId(), 1,
                real.taskId(), real.expectedTaskVersion(), real.expectedStageVersion(), real.expectedPins(), 102, 102, real.decision(), "");
        assertThrows(BaseException.class, () -> f.runtime.vote(wrongPrincipal));
        var wrongTenant = new ApprovalWorkflowQuorumRuntime.VoteCommand(77, real.requestId(), real.stepId(), 1,
                real.taskId(), real.expectedTaskVersion(), real.expectedStageVersion(), real.expectedPins(), 101, 101, real.decision(), "");
        assertThrows(BaseException.class, () -> f.runtime.vote(wrongTenant));
        assertEquals(0, f.count("apr_quorum_votes"));
    }

    @Test void unavailableNextStageRollsBackThresholdVoteAndCompletionAtomically() {
        f.start(ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(stage("FIRST", Mode.ANY, null, List.of()),
                stage("NEXT", Mode.ALL, null, List.of("FIRST")))));
        f.complete = false;
        assertThrows(BaseException.class, () -> f.runtime.vote(f.command("FIRST", 101, 101, Decision.APPROVE)));
        assertEquals("IN_PROGRESS", f.status("FIRST"));
        assertEquals("WAITING", f.status("NEXT"));
        assertEquals(0, f.count("apr_quorum_votes"));
        assertEquals(1, f.count("apr_integration_outbox"));
    }

    @Test void readOnlySimulationUsesDatabasePinsAndUnknownAuthorityNeverReportsQuorum() {
        var definition = one(Mode.PERCENT, 67);
        f.prepare(definition);
        f.jdbc.update("UPDATE apr_requests SET status='DRAFT',submitted_at=NULL,due_at=NULL WHERE request_id=?", f.request);
        var input = new ApprovalWorkflowQuorumSimulation.Input(f.pins, REQUESTER, person(REQUESTER), 1, "a".repeat(64), List.of());
        var result = f.simulation.simulate(TENANT, f.request, input);
        assertTrue(result.readOnly());
        assertEquals(3, result.stages().getFirst().threshold());
        f.unknown = true;
        var unknown = f.simulation.simulate(TENANT, f.request, input);
        assertEquals(ApprovalWorkflowQuorumSimulation.Status.UNKNOWN, unknown.status());
        assertNull(unknown.stages().getFirst().threshold());
        var stale = new ApprovalWorkflowQuorumSimulation.Input(f.pins, REQUESTER, person(REQUESTER), 2, "a".repeat(64), List.of());
        assertThrows(BaseException.class, () -> f.simulation.simulate(TENANT, f.request, stale));
        assertEquals(0, f.count("apr_steps"));
        assertEquals(0, f.count("apr_tasks"));
        assertEquals(0, f.count("apr_quorum_votes"));
        assertEquals(0, f.count("apr_quorum_sla_timers"));
        assertEquals(0, f.count("sys_audit_outbox"));
        assertEquals(0, f.count("apr_integration_outbox"));
    }

    @Test void delegationRevocationHoldingLocalRowLockWinsBeforeVoteFinalization() throws Exception {
        f.start(one(Mode.ALL, null));
        f.delegate(200, 101);
        var revokeReady = new CountDownLatch(1);
        var commitRevoke = new CountDownLatch(1);
        var voterResolved = new CountDownLatch(1);
        f.onVoter = snapshot -> voterResolved.countDown();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var revoke = executor.submit(() -> f.tx.executeWithoutResult(status -> {
                f.jdbc.update("UPDATE apr_delegations SET lifecycle_state='REVOKED' WHERE delegation_id=?", f.delegation);
                revokeReady.countDown();
                try { assertTrue(commitRevoke.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }));
            assertTrue(revokeReady.await(5, TimeUnit.SECONDS));
            var command = f.command("FINANCE", 200, 101, Decision.APPROVE);
            var vote = executor.submit(() -> assertThrows(BaseException.class, () -> f.runtime.vote(command)));
            assertTrue(voterResolved.await(5, TimeUnit.SECONDS));
            assertFalse(vote.isDone());
            commitRevoke.countDown();
            revoke.get(10, TimeUnit.SECONDS);
            vote.get(10, TimeUnit.SECONDS);
            assertEquals(0, f.count("apr_quorum_votes"));
            assertEquals(1, f.count("apr_integration_outbox"));
        } finally {
            commitRevoke.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void sourceRoleWithdrawnAfterInitialDelegateResolutionIsRecheckedUnderGrantLock() {
        f.start(one(Mode.ANY, null));
        f.delegate(200, 101);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        f.onVoter = snapshot -> { if (calls.incrementAndGet() == 2) f.withdrawnRoles.add(101L); };
        assertThrows(BaseException.class, () -> f.runtime.vote(f.command("FINANCE", 200, 101, Decision.APPROVE)));
        assertEquals(2, calls.get());
        assertEquals(0, f.count("apr_quorum_votes"));
        assertEquals(1, f.count("sys_audit_outbox"));
    }
}
