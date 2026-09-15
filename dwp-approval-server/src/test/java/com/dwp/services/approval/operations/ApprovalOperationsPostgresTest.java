package com.dwp.services.approval.operations;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalOperationsPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-14T01:00:00Z");
    private static final UUID ACTOR_PERSON = UUID.fromString(
            "00000000-0000-4000-8000-000000000017");
    private static final UUID CANDIDATE_PERSON = UUID.fromString(
            "00000000-0000-4000-8000-000000000301");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ApprovalOperationsRepository repository;
    private ApprovalOperationsAuthority authority;
    private ApprovalOperationsService service;
    private AtomicReference<ApprovalOperationsAuthority.Current> current;
    private CountDownLatch observationBarrier;
    private CountDownLatch taskObservationReached;
    private CountDownLatch releaseTaskObservation;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        JdbcTemplate bootstrap = new JdbcTemplate(dataSource);
        bootstrap.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        bootstrap.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        flyway.clean();
        flyway.migrate();

        jdbc = bootstrap;
        jdbc.execute("SELECT seed_approval_tenant(42)");
        seedTaskReferenceData();
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        repository = new ApprovalOperationsRepository(named, objectMapper);
        authority = mock(ApprovalOperationsAuthority.class);
        current = new AtomicReference<>(current(17, ACTOR_PERSON, "RS_APPROVALS"));
        when(authority.requireCurrent(any(), any(), anyLong()))
                .thenAnswer(invocation -> current.get());
        when(authority.verifyStepUp(any(), any(), any(), any(), anyLong(), any(), any(), any()))
                .thenAnswer(invocation -> new ApprovalStepUpVerifier.VerifiedChallenge(
                        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        "test-issuer", null, NOW.plusSeconds(300)));
        when(authority.observeDelivery(any(), any())).thenAnswer(invocation -> {
            awaitObservationBarrier();
            ApprovalOperationsRepository.DeliveryRow row = invocation.getArgument(1);
            return new ApprovalOperationsAuthority.DeliveryObservation(
                    row.auditorUserId(), UUID.randomUUID(), row.assignmentRevision(), NOW);
        });
        when(authority.observeTask(any(), any(), anyLong(), any())).thenAnswer(invocation -> {
            awaitTaskObservationRelease();
            ApprovalOperationsRepository.TaskRow row = invocation.getArgument(1);
            long userId = invocation.getArgument(2);
            UUID personId = invocation.getArgument(3);
            return new ApprovalOperationsAuthority.TaskObservation(
                    userId, personId, row.stepCandidateRole(), "role-observation-1", NOW);
        });
        ApprovalOperationsAudit audit = new ApprovalOperationsAudit(new AuditOutboxRecorder(
                named, objectMapper, "dwp-approval-server", "operations-test", "test"));
        service = new ApprovalOperationsService(
                repository, authority, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void allSixNewDeliveryCommandsCommitTheirExactNativeTransitions() {
        UUID batchRetry = outbox("FAILED", 2, null);
        UUID singleDead = outbox("FAILED", 3, null);
        UUID singleReplay = outbox("DEAD", 4, null);
        UUID batchDead = outbox("FAILED", 5, null);
        UUID batchReplay = outbox("DEAD", 6, null);
        UUID reconcile = outbox("SENDING", 7, NOW.minusSeconds(60));

        run(() -> service.retryBatch(deliveryCommand(batchRetry, 2, "retry-batch"),
                headers("retry-batch", 0)));
        run(() -> service.deadLetter(singleDead, 3L,
                new ApprovalOperationsDtos.Reason("Isolate delivery"), headers("dead-single", 3)));
        run(() -> service.replay(singleReplay, 4L,
                new ApprovalOperationsDtos.Reason("Replay dead letter"), headers("replay-single", 4)));
        run(() -> service.deadLetterBatch(deliveryCommand(batchDead, 5, "dead-batch"),
                headers("dead-batch", 0)));
        run(() -> service.replayBatch(deliveryCommand(batchReplay, 6, "replay-batch"),
                headers("replay-batch", 0)));
        run(() -> service.reconcile(deliveryCommand(reconcile, 7, "reconcile"),
                headers("reconcile", 0)));

        assertDelivery(batchRetry, "PENDING", 3, 1);
        assertDelivery(singleDead, "DEAD", 4, 0);
        assertDelivery(singleReplay, "PENDING", 5, 1);
        assertDelivery(batchDead, "DEAD", 6, 0);
        assertDelivery(batchReplay, "PENDING", 7, 1);
        assertDelivery(reconcile, "FAILED", 8, 0);
        assertThat(count("apr_operation_batches")).isEqualTo(6);
        assertThat(count("apr_operation_items")).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM apr_operation_batches batch
                  JOIN sys_audit_outbox audit
                    ON audit.tenant_id=batch.tenant_id
                   AND audit.event_id=batch.audit_event_id
                 WHERE audit.payload->>'targetType'='APPROVAL_OPERATION_BATCH'
                   AND audit.payload->>'targetId'=batch.operation_id::text
                """, Long.class)).isEqualTo(6);
    }

    @Test
    void batchRetryIsAtomicAndAnUnknownResponseReplayReturnsTheStoredReceipt() {
        UUID first = outbox("FAILED", 2, null);
        UUID second = outbox("DEAD", 4, null);
        UUID operationId = UUID.randomUUID();
        var input = new ApprovalOperationsDtos.DeliveryBatchCommand(
                operationId,
                List.of(
                        new ApprovalOperationsDtos.DeliveryTarget(first, 2),
                        new ApprovalOperationsDtos.DeliveryTarget(second, 4)),
                "Recover selected deliveries");

        var saved = run(() -> service.retryBatch(input, headers("idempotent-batch", 0)));
        var replayed = run(() -> service.retryBatch(input, headers("idempotent-batch", 0)));

        assertThat(replayed).isEqualTo(saved);
        assertThat(saved.items()).hasSize(2);
        assertDelivery(first, "PENDING", 3, 1);
        assertDelivery(second, "PENDING", 5, 1);
        assertThat(count("apr_operation_batches")).isEqualTo(1);
        assertThat(count("apr_operation_items")).isEqualTo(2);
    }

    @Test
    void fiftyItemDeliveryBatchCommitsAtTheExactSupportedMaximum() {
        List<ApprovalOperationsDtos.DeliveryTarget> targets = new ArrayList<>();
        for (int index = 0; index < ApprovalOperationsProtocol.MAXIMUM_BATCH_SIZE; index++) {
            targets.add(new ApprovalOperationsDtos.DeliveryTarget(
                    outbox("FAILED", 2, null), 2));
        }
        var input = new ApprovalOperationsDtos.DeliveryBatchCommand(
                UUID.randomUUID(), List.copyOf(targets), "Recover maximum supported batch");

        var receipt = run(() -> service.retryBatch(input, headers("maximum-batch", 0)));

        assertThat(receipt.itemCount()).isEqualTo(50);
        assertThat(receipt.items()).hasSize(50);
        targets.forEach(target -> assertDelivery(target.targetId(), "PENDING", 3, 1));
        assertThat(count("apr_operation_batches")).isEqualTo(1);
        assertThat(count("apr_operation_items")).isEqualTo(50);
    }

    @Test
    void mixedInvalidAndStaleBatchesRollBackWithoutPartialMutationOrLedger() {
        UUID eligible = outbox("FAILED", 2, null);
        UUID published = outbox("PUBLISHED", 8, null);
        var mixed = new ApprovalOperationsDtos.DeliveryBatchCommand(
                UUID.randomUUID(),
                List.of(
                        new ApprovalOperationsDtos.DeliveryTarget(eligible, 2),
                        new ApprovalOperationsDtos.DeliveryTarget(published, 8)),
                "Recover mixed selection");

        assertThatThrownBy(() -> run(() -> service.retryBatch(mixed, headers("mixed", 0))))
                .isInstanceOf(ApprovalOperationsProtocol.ApprovalOperationsRejected.class);
        assertDelivery(eligible, "FAILED", 2, 0);
        assertDelivery(published, "PUBLISHED", 8, 0);
        assertThat(count("apr_operation_batches")).isZero();

        var stale = deliveryCommand(eligible, 1, "stale");
        assertError(
                () -> run(() -> service.retryBatch(stale, headers("stale", 0))),
                ErrorCode.OBJECT_VERSION_CONFLICT);
        assertDelivery(eligible, "FAILED", 2, 0);
        assertThat(count("apr_operation_batches")).isZero();
    }

    @Test
    void crossTenantScopeAndActorCannotReuseOrMutateAnOperation() {
        UUID delivery = outbox("FAILED", 2, null);
        UUID operationId = UUID.randomUUID();
        var input = new ApprovalOperationsDtos.DeliveryBatchCommand(
                operationId,
                List.of(new ApprovalOperationsDtos.DeliveryTarget(delivery, 2)),
                "Scoped recovery");

        current.set(current(43, 17, ACTOR_PERSON, "RS_APPROVALS"));
        assertError(
                () -> run(() -> service.retryBatch(input, headers("tenant-denied", 0))),
                ErrorCode.FORBIDDEN);
        assertDelivery(delivery, "FAILED", 2, 0);

        current.set(current(17, ACTOR_PERSON, "RS_OTHER"));
        assertError(
                () -> run(() -> service.retryBatch(input, headers("scope-denied", 0))),
                ErrorCode.FORBIDDEN);
        assertDelivery(delivery, "FAILED", 2, 0);

        current.set(current(17, ACTOR_PERSON, "RS_APPROVALS"));
        run(() -> service.retryBatch(input, headers("actor-owned", 0)));
        current.set(current(18, UUID.randomUUID(), "RS_APPROVALS"));
        assertError(
                () -> run(() -> service.retryBatch(input, headers("actor-owned", 0))),
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH);
        assertThat(count("apr_operation_batches")).isEqualTo(1);
    }

    @Test
    void singleAndBatchTaskReassignmentUseTheCurrentCanonicalCandidate() {
        List<UUID> tasks = taskIds();
        UUID first = tasks.get(0);
        UUID second = tasks.get(1);
        UUID secondPerson = UUID.randomUUID();

        var single = run(() -> service.reassignTask(
                first, 0L,
                new ApprovalOperationsDtos.TaskReassignment(
                        301, CANDIDATE_PERSON, "Move breached task"),
                headers("task-single", 0)));
        var batchInput = new ApprovalOperationsDtos.TaskReassignmentBatchCommand(
                UUID.randomUUID(),
                List.of(new ApprovalOperationsDtos.TaskReassignmentTarget(
                        second, 0, 302, secondPerson)),
                "Balance breached queue");
        var batch = run(() -> service.reassignTasks(batchInput, headers("task-batch", 0)));

        assertTask(first, 301, CANDIDATE_PERSON, 1);
        assertTask(second, 302, secondPerson, 1);
        assertThat(single.operation()).isEqualTo("TASK_REASSIGN");
        assertThat(batch.commandMode()).isEqualTo("BATCH");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM apr_request_events WHERE event_type = 'TASK_REASSIGNED_BY_OPERATOR'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentCommandsAgainstOneVersionYieldOneCommitAndOneConflict() throws Exception {
        UUID delivery = outbox("FAILED", 2, null);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentRetry(delivery, "concurrent-a"));
            var second = executor.submit(() -> concurrentRetry(delivery, "concurrent-b"));
            List<Object> outcomes = List.of(first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS));

            assertThat(outcomes.stream().filter(ApprovalOperationsDtos.OperationReceipt.class::isInstance))
                    .hasSize(1);
            assertThat(outcomes.stream().filter(BaseException.class::isInstance)
                    .map(BaseException.class::cast)
                    .map(BaseException::getErrorCode))
                    .containsExactly(ErrorCode.OBJECT_VERSION_CONFLICT);
            assertDelivery(delivery, "PENDING", 3, 1);
            assertThat(count("apr_operation_batches")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void requestStateWriteCannotInterleaveBetweenObservationAndTaskCommit() throws Exception {
        UUID task = taskIds().getFirst();
        UUID request = taskRequestId(task);
        taskObservationReached = new CountDownLatch(1);
        releaseTaskObservation = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var command = executor.submit(() -> concurrentTaskReassign(task, "task-request-drift"));
            assertThat(taskObservationReached.await(10, TimeUnit.SECONDS)).isTrue();
            var requestWrite = executor.submit(() -> jdbc.update("""
                    UPDATE apr_requests
                       SET status = 'APPROVED', version = version + 1
                     WHERE tenant_id = 42 AND request_id = ?
                    """, request));
            Thread.sleep(200);
            assertThat(requestWrite.isDone()).isFalse();
            releaseTaskObservation.countDown();

            Object outcome = command.get(15, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(ApprovalOperationsDtos.OperationReceipt.class);
            assertThat(requestWrite.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT version FROM apr_tasks WHERE task_id = ?", Long.class, task)).isOne();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM apr_requests WHERE request_id = ?", String.class,
                    request)).isEqualTo("APPROVED");
            assertThat(count("apr_operation_batches")).isOne();
            assertThat(count("apr_operation_items")).isOne();
        } finally {
            releaseTaskObservation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void taskUpdateExactFenceRejectsScopeRequestAndStepDriftAsZeroRowConflicts() {
        UUID task = taskIds().getFirst();
        ApprovalOperationsAuthority.Current selectedScope = current.get();
        assertTaskFenceConflict(task, row -> jdbc.update("""
                UPDATE apr_requests SET status = 'APPROVED', version = version + 1
                 WHERE tenant_id = 42 AND request_id = ?
                """, row.requestId()), selectedScope);
        assertTaskFenceConflict(task, row -> jdbc.update("""
                UPDATE apr_steps SET status = 'APPROVED', version = version + 1
                 WHERE tenant_id = 42 AND step_id = ?
                """, row.stepId()), selectedScope);
        assertTaskFenceConflict(task, row -> jdbc.update("""
                UPDATE apr_steps SET candidate_role = 'APPROVAL_REVIEWER', version = version + 1
                 WHERE tenant_id = 42 AND step_id = ?
                """, row.stepId()), selectedScope);
        assertTaskFenceConflict(task, row -> { }, current(17, ACTOR_PERSON, "RS_OTHER"));

        assertThat(jdbc.queryForObject(
                "SELECT version FROM apr_tasks WHERE task_id = ?", Long.class, task)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT request.status AS request_status, step.status AS step_status,
                       step.candidate_role
                  FROM apr_tasks task
                  JOIN apr_requests request
                    ON request.tenant_id = task.tenant_id
                   AND request.request_id = task.request_id
                  JOIN apr_steps step
                    ON step.tenant_id = task.tenant_id
                   AND step.step_id = task.step_id
                 WHERE task.task_id = ?
                """, task)).containsEntry("request_status", "IN_REVIEW")
                .containsEntry("step_status", "IN_PROGRESS")
                .containsEntry("candidate_role", "APPROVAL_OPERATOR");
    }

    @Test
    void v37IsFreshSafeBoundedAndAppendOnly() {
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                 WHERE tablename IN ('apr_operation_batches', 'apr_operation_items')
                """, String.class)).contains(
                "idx_apr_operation_actor_timeline",
                "idx_apr_operation_target_timeline",
                "idx_apr_operation_request_timeline");
        UUID delivery = outbox("FAILED", 2, null);
        run(() -> service.retryBatch(deliveryCommand(delivery, 2, "schema"),
                headers("schema", 0)));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE apr_operation_batches SET reason = 'tampered'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM apr_operation_items"))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("""
                SELECT item_count FROM apr_operation_batches
                """, Integer.class)).isEqualTo(1);
    }

    private Object concurrentRetry(UUID target, String key) {
        try {
            return run(() -> service.retryBatch(deliveryCommand(target, 2, key), headers(key, 0)));
        } catch (BaseException exception) {
            return exception;
        }
    }

    private Object concurrentTaskReassign(UUID target, String key) {
        try {
            return run(() -> service.reassignTask(
                    target, 0L,
                    new ApprovalOperationsDtos.TaskReassignment(
                            301, CANDIDATE_PERSON, "Reassign after authority observation"),
                    headers(key, 0)));
        } catch (BaseException exception) {
            return exception;
        }
    }

    private void assertTaskFenceConflict(
            UUID task,
            Consumer<ApprovalOperationsRepository.TaskRow> drift,
            ApprovalOperationsAuthority.Current updateCurrent) {
        assertError(() -> run(() -> {
            ApprovalOperationsRepository.TaskRow row = repository.tasks(
                    current.get(), List.of(task), true).getFirst();
            drift.accept(row);
            var observation = new ApprovalOperationsAuthority.TaskObservation(
                    301, CANDIDATE_PERSON, row.stepCandidateRole(), "role-observation-1", NOW);
            repository.updateTasks(updateCurrent, List.of(
                    new ApprovalOperationsRepository.TaskCommit(row, observation, "target-fingerprint")));
            return null;
        }), ErrorCode.OBJECT_VERSION_CONFLICT);
    }

    private UUID outbox(String status, long version, Instant lockedUntil) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_integration_outbox (
                    outbox_id, event_id, tenant_id, request_id, event_type, payload,
                    payload_sha256, status, version, event_originator_user_id,
                    assigned_auditor_user_id, recovery_auditor_assignment_state,
                    recovery_auditor_resource_set_key,
                    recovery_auditor_assignment_revision,
                    recovery_auditor_assigned_at, locked_by, locked_until,
                    management_resource_set_key)
                VALUES (?, ?, 42, ?, 'approval.request.changed', '{}'::jsonb,
                        ?, ?, ?, 100001, 300, 'ASSIGNED', 'RS_APPROVALS',
                        'assignment-1', CURRENT_TIMESTAMP, ?, ?, 'RS_APPROVALS')
                """, id, UUID.randomUUID(), requestId(), "a".repeat(64), status, version,
                "SENDING".equals(status) ? "relay-1" : null,
                lockedUntil == null ? null : java.sql.Timestamp.from(lockedUntil));
        return id;
    }

    private void seedTaskReferenceData() {
        UUID workflowVersion = jdbc.queryForObject("""
                SELECT version.workflow_version_id
                  FROM apr_workflow_versions version
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id = version.tenant_id
                   AND workflow.workflow_id = version.workflow_id
                 WHERE workflow.tenant_id = 42
                   AND workflow.workflow_key = 'CAPEX_PURCHASE'
                   AND version.version_number = workflow.current_version
                """, UUID.class);
        UUID formVersion = jdbc.queryForObject("""
                SELECT version.form_version_id
                  FROM apr_form_versions version
                  JOIN apr_forms form
                    ON form.tenant_id = version.tenant_id
                   AND form.form_id = version.form_id
                 WHERE form.tenant_id = 42
                   AND form.form_key = 'CAPEX_PURCHASE_FORM'
                   AND version.version_number = form.current_version
                """, UUID.class);
        for (int ordinal = 1; ordinal <= 2; ordinal++) {
            UUID request = UUID.randomUUID();
            UUID step = UUID.randomUUID();
            UUID task = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO apr_requests (
                        request_id, tenant_id, request_number, workflow_version_id,
                        form_version_id, title, summary, requester_user_id,
                        status, reference_seed_key, management_resource_set_key)
                    VALUES (?, 42, ?, ?, ?, 'Operations fixture', 'Operations fixture',
                            ?, 'IN_REVIEW', ?, 'RS_APPROVALS')
                    """, request, "OPS-" + request, workflowVersion, formVersion,
                    100_000L + ordinal, "task:100:" + ordinal);
            jdbc.update("""
                    INSERT INTO apr_steps (
                        step_id, tenant_id, request_id, step_key, step_name,
                        sequence_number, approval_mode, status, candidate_role)
                    VALUES (?, 42, ?, 'PRIMARY_REVIEW', 'Primary review', 1,
                            'ANY', 'IN_PROGRESS', 'APPROVAL_OPERATOR')
                    """, step, request);
            jdbc.update("""
                    INSERT INTO apr_tasks (
                        task_id, tenant_id, request_id, step_id, assignee_user_id,
                        assignee_person_public_id, candidate_role, status, risk_score)
                    VALUES (?, 42, ?, ?, 100, ?, 'APPROVAL_OPERATOR', 'PENDING', 50)
                    """, task, request, step, CANDIDATE_PERSON);
        }
    }

    private UUID requestId() {
        return jdbc.queryForObject("""
                SELECT request_id FROM apr_requests
                 WHERE tenant_id = 42 AND reference_seed_key = 'task:100:1'
                """, UUID.class);
    }

    private UUID taskRequestId(UUID taskId) {
        return jdbc.queryForObject(
                "SELECT request_id FROM apr_tasks WHERE tenant_id = 42 AND task_id = ?",
                UUID.class,
                taskId);
    }

    private List<UUID> taskIds() {
        return jdbc.queryForList("""
                SELECT task.task_id
                  FROM apr_tasks task
                  JOIN apr_requests request
                    ON request.tenant_id = task.tenant_id
                   AND request.request_id = task.request_id
                 WHERE task.tenant_id = 42
                   AND request.reference_seed_key LIKE 'task:100:%'
                 ORDER BY task.task_id
                 LIMIT 2
                """, UUID.class);
    }

    private ApprovalOperationsDtos.DeliveryBatchCommand deliveryCommand(
            UUID target,
            long version,
            String key) {
        return new ApprovalOperationsDtos.DeliveryBatchCommand(
                UUID.nameUUIDFromBytes(("operation-" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                List.of(new ApprovalOperationsDtos.DeliveryTarget(target, version)),
                "Operate delivery " + key);
    }

    private ApprovalStepUpHeaders headers(String key, long version) {
        return ApprovalStepUpHeaders.of("signed", key, "decision-1", version);
    }

    private ApprovalOperationsAuthority.Current current(long actorId, UUID person, String scope) {
        return current(42, actorId, person, scope);
    }

    private ApprovalOperationsAuthority.Current current(
            long tenantId,
            long actorId,
            UUID person,
            String scope) {
        var actor = new ApprovalRequestContext.Actor(
                actorId, tenantId, person, "Operations actor", Set.of("APPROVAL_OPERATOR"),
                Set.of("APP.APPROVALS:VIEW", "ADMIN.APPROVAL_OPERATIONS:EXECUTE"));
        return new ApprovalOperationsAuthority.Current(
                actor,
                new ApprovalManagementScopeContext.Evidence("opaque-" + scope, scope),
                new ApprovalDecisionRevisionContext.Evidence(
                        "decision-1", OffsetDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC),
                        "approval-management", "opaque-" + scope, "route", "111"),
                "STEPUP-MGMT-HIGH-V1");
    }

    private <T> T run(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    private void assertDelivery(UUID id, String status, long version, int retries) {
        var row = jdbc.queryForMap("""
                SELECT status, version, manual_retry_count, locked_by, locked_until
                  FROM apr_integration_outbox WHERE outbox_id = ?
                """, id);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(version);
        assertThat(((Number) row.get("manual_retry_count")).intValue()).isEqualTo(retries);
        assertThat(row.get("locked_by")).isNull();
        assertThat(row.get("locked_until")).isNull();
    }

    private void assertTask(UUID id, long userId, UUID personId, long version) {
        var row = jdbc.queryForMap("""
                SELECT assignee_user_id, assignee_person_public_id, status, version,
                       delegated_from_user_id, delegated_authority_role_code
                  FROM apr_tasks WHERE task_id = ?
                """, id);
        assertThat(((Number) row.get("assignee_user_id")).longValue()).isEqualTo(userId);
        assertThat(row.get("assignee_person_public_id")).isEqualTo(personId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(version);
        assertThat(row.get("delegated_from_user_id")).isNull();
        assertThat(row.get("delegated_authority_role_code")).isNull();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private void awaitObservationBarrier() {
        CountDownLatch barrier = observationBarrier;
        if (barrier == null) return;
        barrier.countDown();
        try {
            if (!barrier.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent observation timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void awaitTaskObservationRelease() {
        CountDownLatch reached = taskObservationReached;
        CountDownLatch release = releaseTaskObservation;
        if (reached == null || release == null) return;
        reached.countDown();
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Task observation release timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
