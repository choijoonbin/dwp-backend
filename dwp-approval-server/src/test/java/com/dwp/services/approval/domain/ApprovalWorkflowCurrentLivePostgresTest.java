package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Actual request/head lock races; retention transitions and voter authority are explicit disposable fixtures. */
@Testcontainers
class ApprovalWorkflowCurrentLivePostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    NamedParameterJdbcTemplate named;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    ApprovalWorkflowQuorumDefinition definition;

    @BeforeEach void before() {
        f = new ApprovalWorkflowQuorumPostgresFixture(); f.initialize(PG);
        named = new NamedParameterJdbcTemplate(f.jdbc);
        definition = ApprovalWorkflowQuorumPostgresFixture.one(Mode.ALL, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PREPARED", "IRREVERSIBLE", "IRREVERSIBLE_BLOCKED", "OBJECTS_CONFIRMED", "LOCAL_DB_PURGED", "COMPLETE"})
    void stagedNonLiveDeniesEveryWorkflowCommandBeforeTaskVoteTimerAuditOrOutboxWrites(String state) {
        f.start(definition);
        var command = f.command("FINANCE", 101, 101, Decision.APPROVE);
        var actor = actor();
        var query = new ApprovalQueryRepository(named, mapper);
        var task = f.tx.execute(status -> query.taskDetail(actor, command.taskId()));
        assertNotNull(task);
        var commands = new ApprovalCommandRepository(named, mapper);
        f.jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,?)", f.request, state);
        String before = rows();
        hidden(() -> f.runtime.vote(command));
        hidden(() -> f.runtime.canonicalPins(42, f.request, definition));
        hidden(() -> f.runtime.start(42, f.request, f.pins, definition));
        hidden(() -> f.tx.executeWithoutResult(status -> new ApprovalWorkflowQuorumCancellation(store(), audit()).cancel(42, f.request, 99, ApprovalWorkflowQuorumPostgresFixture.person(99))));
        hidden(() -> f.tx.executeWithoutResult(status -> ApprovalWorkflowCommandLiveFence.task(named, 42, command.taskId())));
        hidden(() -> f.tx.executeWithoutResult(status -> commands.claim(actor, task, command.expectedTaskVersion(), "non-live")));
        hidden(() -> f.tx.executeWithoutResult(status -> commands.decide(actor, task, new ApprovalDtos.DecisionRequest("APPROVE", "", command.expectedTaskVersion()), "non-live")));
        assertEquals(before, rows());
    }

    @Test void missingHeadRemainsActualLegacyLiveAndValidVotePersistsExactlyOnce() {
        f.start(definition);
        var command = f.command("FINANCE", 101, 101, Decision.APPROVE);
        assertEquals(0L, f.count("apr_record_retention_heads"));
        assertEquals(Outcome.IN_PROGRESS, f.runtime.vote(command).outcome());
        assertEquals(1L, f.count("apr_quorum_votes"));
        assertEquals(0L, f.count("apr_record_retention_heads"));
    }

    @ParameterizedTest @ValueSource(strings = {"no-tx", "read-only", "repeatable-read", "wrong-datasource"})
    void invalidParentTransactionCannotReleaseLiveFenceAtAutoCommitOrReuseStaleSnapshot(String mode) {
        f.start(definition);
        var command = f.command("FINANCE", 101, 101, Decision.APPROVE);
        String before = rows();
        Runnable action;
        if ("no-tx".equals(mode)) action = () -> store().lockRequest(42, f.request);
        else {
            var tx = "wrong-datasource".equals(mode) ? new TransactionTemplate(new DataSourceTransactionManager(source())) : new TransactionTemplate(f.tx.getTransactionManager());
            if ("read-only".equals(mode)) tx.setReadOnly(true);
            if ("repeatable-read".equals(mode)) tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            action = () -> tx.executeWithoutResult(status -> ApprovalWorkflowCommandLiveFence.task(named, 42, command.taskId()));
        }
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, assertThrows(BaseException.class, action::run).getErrorCode());
        assertEquals(before, rows());
    }

    @Test void deletedRequestAndWrongTenantAreHiddenWithoutAnyNewWorkflowEvidence() {
        f.start(definition);
        var command = f.command("FINANCE", 101, 101, Decision.APPROVE);
        hidden(() -> f.tx.executeWithoutResult(status -> ApprovalWorkflowCommandLiveFence.task(named, 43, command.taskId())));
        f.jdbc.update("UPDATE apr_requests SET status='DRAFT',deleted_at=clock_timestamp(),deleted_by=99,deletion_reason='Disposable deleted draft' WHERE request_id=?", f.request);
        String before = rows();
        assertThrows(BaseException.class, () -> f.runtime.vote(command));
        assertEquals(before, rows());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void transactionStartedBeforeInitiallyAbsentHeadMustWaitAndDenyAfterConcurrentRetentionCommit(boolean taskFence) throws Exception {
        f.start(definition);
        var command = f.command("FINANCE", 101, 101, Decision.APPROVE);
        var snapshot = new CountDownLatch(1); var start = new CountDownLatch(1); var release = new CountDownLatch(1);
        String before = rows();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reader = pool.submit(() -> {
              try {return f.tx.execute(status -> {
                f.jdbc.queryForObject("SELECT count(*) FROM apr_record_retention_heads", Long.class);
                snapshot.countDown(); await(start);
                    if (taskFence) ApprovalWorkflowCommandLiveFence.task(named, 42, command.taskId());
                    else f.runtime.vote(command);
                    return "UNEXPECTED_ALLOWED";
                });} catch (BaseException denied) {return denied.getErrorCode().name();}
            });
            assertTrue(snapshot.await(10, TimeUnit.SECONDS));
            var writer = pool.submit(() -> f.tx.executeWithoutResult(status -> {
                f.jdbc.queryForObject("SELECT request_id FROM apr_requests WHERE request_id=? FOR UPDATE", UUID.class, f.request);
                f.jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,'IRREVERSIBLE')", f.request);
                start.countDown(); await(release);
            }));
            try {
                blocked(); assertFalse(reader.isDone()); release.countDown(); writer.get(10, TimeUnit.SECONDS);
                assertEquals("NOT_FOUND", reader.get(10, TimeUnit.SECONDS));
            } finally {start.countDown(); release.countDown();}
        }
        assertEquals(before, rows());
    }

    @Test void liveWriteLockOutlivesActualVoterResolutionAndBlocksLateRetentionTransitionUntilCommandCommit() throws Exception {
        f.start(definition);
        var command = f.command("FINANCE", 101, 101, Decision.APPROVE);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        f.onVoter = snapshot -> {assertEquals(0L, f.count("apr_record_retention_heads")); locked.countDown(); await(release);};
        try (var pool = Executors.newFixedThreadPool(2)) {
            var vote = pool.submit(() -> f.runtime.vote(command));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var writer = pool.submit(() -> f.tx.executeWithoutResult(status -> {
                f.jdbc.queryForObject("SELECT request_id FROM apr_requests WHERE request_id=? FOR UPDATE", UUID.class, f.request);
                f.jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,'PREPARED')", f.request);
            }));
            try {
                blocked(); assertFalse(writer.isDone()); release.countDown();
                assertEquals(Outcome.IN_PROGRESS, vote.get(10, TimeUnit.SECONDS).outcome()); writer.get(10, TimeUnit.SECONDS);
            } finally {release.countDown();}
        }
        assertEquals(1L, f.count("apr_quorum_votes"));
        String before = rows(); hidden(() -> f.runtime.vote(f.command("FINANCE", 102, 102, Decision.APPROVE))); assertEquals(before, rows());
    }

    @Test void twoTaskCommandsLockRequestForUpdateBeforeAnySharedOwnerProjectionWithoutSymmetricUpgrades() throws Exception {
        f.start(definition);
        var command = f.command("FINANCE", 101, 101, Decision.APPROVE);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var secondProjection = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> f.tx.execute(status -> {
                ApprovalWorkflowCommandLiveFence.task(named, 42, command.taskId());
                f.jdbc.queryForObject("SELECT request_id FROM apr_requests WHERE request_id=? FOR SHARE", UUID.class, f.request);
                locked.countDown(); await(release); return true;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> f.tx.execute(status -> {
                ApprovalWorkflowCommandLiveFence.task(named, 42, command.taskId()); secondProjection.countDown();
                f.jdbc.queryForObject("SELECT request_id FROM apr_requests WHERE request_id=? FOR SHARE", UUID.class, f.request); return true;
            }));
            try {
                blocked(); assertEquals(1L, secondProjection.getCount()); release.countDown();
                assertTrue(first.get(10, TimeUnit.SECONDS)); assertTrue(second.get(10, TimeUnit.SECONDS));
            } finally {release.countDown();}
        }
    }

    private ApprovalWorkflowQuorumRuntimeStore store() {return new ApprovalWorkflowQuorumRuntimeStore(named, mapper);}
    private AuditOutboxRecorder audit() {return new AuditOutboxRecorder(named, mapper, "dwp-approval-server", "test", "test");}
    private ApprovalRequestContext.Actor actor() {return new ApprovalRequestContext.Actor(101L, 42L, ApprovalWorkflowQuorumPostgresFixture.person(101), "Disposable actor", Set.of("WORKSPACE_MEMBER", "FINANCE_REVIEWER"), Set.of("ACTION.APPROVAL_TASK:VIEW", "ACTION.APPROVAL_TASK:APPROVE", "ACTION.APPROVAL_REQUEST:VIEW"));}
    private PGSimpleDataSource source() {var ds = new PGSimpleDataSource(); ds.setURL(PG.getJdbcUrl()); ds.setUser(PG.getUsername()); ds.setPassword(PG.getPassword()); return ds;}
    private static void hidden(Runnable action) {assertEquals(ErrorCode.NOT_FOUND, assertThrows(BaseException.class, action::run).getErrorCode());}
    private String rows() {
        var snapshot = new TreeMap<String, Object>();
        for (String table : List.of("apr_requests", "apr_request_payloads", "apr_steps", "apr_tasks", "apr_quorum_stage_runtime", "apr_quorum_candidates", "apr_quorum_votes", "apr_quorum_prerequisites", "apr_quorum_sla_timers", "apr_quorum_information_commands", "apr_quorum_information_completion_transactions", "apr_quorum_information_admissions", "apr_integration_outbox", "sys_audit_outbox")) {
            snapshot.put(table, f.jdbc.queryForList("SELECT to_jsonb(r)::text FROM " + table + " r ORDER BY to_jsonb(r)::text", String.class));
        }
        return ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(snapshot));
    }
    private static void await(CountDownLatch latch) {try {if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent transaction timed out");} catch (InterruptedException error) {Thread.currentThread().interrupt(); throw new IllegalStateException(error);}}
    private void blocked() throws Exception {for (int i = 0; i < 500; i++) {if (f.jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'", Long.class) > 0) return; Thread.sleep(20);} throw new IllegalStateException("Expected PostgreSQL lock wait was not observed");}
}
