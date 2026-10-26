package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.ExecutionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.ExecutionState;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.Operation;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.ReceiptStatus;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationService.APPROVE_DEFINITION;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationService.CANCEL_EXECUTION;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationService.CREATE_EXECUTION;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.DEFAULT_SORT;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.Projection.PRIMARY_DWP_CONTROL;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.QueryState.COMPLETE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Production JDBC adapter proof against an actual PostgreSQL 16 database. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcIntegrationRepositoryPostgresTest {

    private static final String DATABASE = "sys008_repository_evidence";
    private static final String APP_ROLE = "sys008_repository_app";
    private static final String APP_PASSWORD = "sys008-repository-password";
    private static final String SCOPE_DIGEST = "a".repeat(64);
    private static final String REQUEST_DIGEST = "b".repeat(64);
    private static final String PAYLOAD_DIGEST = "c".repeat(64);
    private static final String PROVIDER_DIGEST = "d".repeat(64);
    private static final UUID SUBJECT = uuid("sys008-repository-subject");
    private static final UUID SECOND_SUBJECT = uuid(
            "sys008-repository-second-subject");
    private static final long ACTOR_ID = 83001;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate admin;
    private static JdbcTemplate app;
    private static DataSourceTransactionManager transactionManager;
    private static TransactionTemplate transactions;
    private static JdbcIntegrationRepository repository;

    @BeforeAll
    static void migrateAndProvisionRuntimeRole() {
        JdbcTemplate root = new JdbcTemplate(dataSource(
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        root.execute("CREATE DATABASE " + DATABASE);

        PGSimpleDataSource adminSource = dataSource(
                DATABASE, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(adminSource)
                .schemas("public")
                .defaultSchema("public")
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("334")
                .cleanDisabled(false)
                .load()
                .migrate();
        adminSource.setCurrentSchema("public");
        admin = new JdbcTemplate(adminSource);
        provisionApplicationRole();

        PGSimpleDataSource appSource = dataSource(
                DATABASE, APP_ROLE, APP_PASSWORD);
        appSource.setCurrentSchema("public");
        app = new JdbcTemplate(appSource);
        transactionManager = new DataSourceTransactionManager(appSource);
        transactions = new TransactionTemplate(transactionManager);
        repository = new JdbcIntegrationRepository(
                app, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void receiptReplayConflictAndAcceptanceCreateInitialItemAttemptLedger() {
        Seed seed = seedActiveConnector(83101, "acceptance-ledger");
        UUID executionPublicId = uuid("acceptance-ledger-execution");
        CommandSeal seal = executionSeal(
                seed.tenantId(), "acceptance-ledger-key-0001", REQUEST_DIGEST);

        Created created = createAcceptedExecution(
                seed, seal, executionPublicId, "acceptance-ledger-provider-0001");
        assertThat(created.receipt().status()).isEqualTo(ReceiptStatus.RUNNING);
        assertThat(created.execution().state()).isEqualTo(ExecutionState.ACCEPTED);
        assertThat(created.execution().providerDispatchOccurred()).isFalse();
        assertThat(inTransaction(seed.tenantId(), () ->
                repository.lockExecutionAuthorized(
                        seed.tenantId(), executionPublicId, SCOPE_DIGEST,
                        "TENANT", null))).isEqualTo(created.execution());
        assertThat(inTransaction(seed.tenantId(), () ->
                repository.executionResponseAuthorized(
                        seed.tenantId(), executionPublicId, SCOPE_DIGEST,
                        "TENANT", null)).executionId()).isEqualTo(executionPublicId);
        assertThatThrownBy(() -> inTransaction(seed.tenantId(), () ->
                repository.lockExecutionAuthorized(
                        seed.tenantId(), executionPublicId, "9".repeat(64),
                        "TENANT", null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SYS008_EXECUTION_NOT_FOUND");
        assertThatThrownBy(() -> inTransaction(seed.tenantId(), () ->
                repository.executionResponseAuthorized(
                        seed.tenantId(), executionPublicId, SCOPE_DIGEST,
                        "LEGAL_ENTITY", UUID.randomUUID())))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SYS008_EXECUTION_NOT_FOUND");
        assertThat(admin.queryForList("""
                SELECT lifecycle_state FROM sys_connector_execution_attempts
                 WHERE tenant_id = ? AND execution_id = ?
                """, String.class, seed.tenantId(),
                created.execution().internalId())).containsExactly("READY");

        ExecutionRecord dispatched = inTransaction(seed.tenantId(), () -> {
            ExecutionRecord current = repository.lockExecution(
                    seed.tenantId(), executionPublicId);
            ExecutionRecord changed = repository.beginDispatch(
                    current, current.attemptFence(), repository.databaseNow());
            assertThat(repository.currentAttempt(changed).attemptFence()).isEqualTo(1);
            return changed;
        });

        assertThat(dispatched.state()).isEqualTo(ExecutionState.DISPATCHED);
        assertThat(dispatched.attemptFence()).isEqualTo(1);
        assertThat(dispatched.providerDispatchOccurred()).isTrue();
        assertThat(admin.queryForMap("""
                SELECT lifecycle_state, source_count, object_revision,
                       current_attempt_fence
                  FROM sys_connector_executions
                 WHERE tenant_id = ? AND public_id = ?
                """, seed.tenantId(), executionPublicId))
                .containsEntry("lifecycle_state", "DISPATCHED")
                .containsEntry("source_count", 1)
                .containsEntry("object_revision", 3L)
                .containsEntry("current_attempt_fence", 1L);
        assertThat(admin.queryForList("""
                SELECT lifecycle_state FROM sys_connector_execution_items
                 WHERE tenant_id = ? AND execution_id = ?
                """, String.class, seed.tenantId(), dispatched.internalId()))
                .containsExactly("DISPATCHED");
        assertThat(admin.queryForList("""
                SELECT lifecycle_state FROM sys_connector_execution_attempts
                 WHERE tenant_id = ? AND execution_id = ?
                """, String.class, seed.tenantId(), dispatched.internalId()))
                .containsExactly("DISPATCHED");

        var replay = inTransaction(seed.tenantId(), () ->
                repository.beginCommand(seal, repository.databaseNow()));
        assertThat(replay.owner()).isFalse();
        assertThat(replay.receipt().receiptId())
                .isEqualTo(created.receipt().receiptId());
        assertThat(replay.receipt().status()).isEqualTo(ReceiptStatus.RUNNING);

        CommandSeal conflicting = executionSeal(
                seed.tenantId(), seal.idempotencyKey(), "e".repeat(64));
        assertThatThrownBy(() -> inTransaction(seed.tenantId(), () ->
                repository.beginCommand(conflicting, repository.databaseNow())))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SYS008_IDEMPOTENCY_DIGEST_CONFLICT");
    }

    @Test
    void workerMaterializesAcceptedExecutionUnderOriginalSubjectRls() {
        Seed seed = seedActiveConnector(83109, "worker-subject-rls");
        UUID executionPublicId = uuid("worker-subject-rls-execution");
        CommandSeal original = executionSeal(
                seed.tenantId(), "worker-subject-command-0001", REQUEST_DIGEST);
        Created created = createAcceptedExecution(
                seed, original, executionPublicId, "worker-subject-provider-0001");
        inTransaction(seed.tenantId(), () -> {
            repository.appendExecutionEventAndAudit(
                    original, created.execution(), "EXECUTION_ACCEPTED",
                    repository.databaseNow());
            return true;
        });
        String eventSubject = admin.queryForObject("""
                SELECT subject
                  FROM sys_domain_event_outbox
                 WHERE tenant_id = ? AND aggregate_id = ?
                   AND aggregate_sequence = 1
                """, String.class, seed.tenantId(), executionPublicId.toString());
        assertThat(eventSubject).isEqualTo(SUBJECT.toString());

        IntegrationRuntimeService runtime = new IntegrationRuntimeService(
                repository, repository, unavailableProvider(),
                unavailableProvider(), unavailableProvider(), ignored -> {},
                transactionManager,
                new ObjectMapper().findAndRegisterModules(), 3);
        var result = runtime.dispatchAccepted(
                seed.tenantId(), executionPublicId,
                UUID.fromString(eventSubject));

        assertThat(result.state()).isEqualTo(ExecutionState.RESULT_UNKNOWN);
        assertThat(admin.queryForObject("""
                SELECT status FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND public_id = ?
                """, String.class, seed.tenantId(),
                created.execution().originalCommandReceiptId()))
                .isEqualTo("RESULT_UNKNOWN");
    }

    @Test
    void receiptAuthorizationPrecedesDigestAndTerminalRevisionAdvances() {
        Seed seed = seedActiveConnector(83105, "receipt-finalization");
        UUID aggregateId = seed.connectorPublicId();
        String resultRef = "/v1/admin/hris/connectors/" + aggregateId;
        CommandSeal runningSeal = executionSeal(
                seed.tenantId(), "receipt-finalization-running-0001",
                REQUEST_DIGEST);
        var running = inTransaction(seed.tenantId(), () -> {
            OffsetDateTime now = repository.databaseNow();
            var accepted = repository.beginCommand(runningSeal, now).receipt();
            return repository.progressCommand(
                    runningSeal, accepted.receiptId(), aggregateId, 2,
                    resultRef, now);
        });
        var succeeded = inTransaction(seed.tenantId(), () ->
                repository.finishCommand(
                        runningSeal, running.receiptId(), ReceiptStatus.SUCCEEDED,
                        aggregateId, 4, resultRef, repository.databaseNow()));
        assertThat(succeeded.aggregateVersion()).isEqualTo(4);
        assertThat(admin.queryForMap("""
                SELECT status, aggregate_public_id, aggregate_revision, result_ref
                  FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND public_id = ?
                """, seed.tenantId(), running.receiptId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("aggregate_public_id", aggregateId)
                .containsEntry("aggregate_revision", 4L)
                .containsEntry("result_ref", resultRef);

        CommandSeal changedAuthorityAndDigest = new CommandSeal(
                runningSeal.tenantId(), runningSeal.subjectPrincipalPublicId(),
                runningSeal.commandType(), runningSeal.originatingAction(),
                runningSeal.routeContractKey(), runningSeal.contextKey(),
                runningSeal.contextScopeKey(), runningSeal.decisionRevision(),
                "9".repeat(64), runningSeal.fieldPolicyRevision(),
                runningSeal.authorizationRevision(), runningSeal.purposeCode(),
                runningSeal.idempotencyKey(), "e".repeat(64), UUID.randomUUID());
        assertThatThrownBy(() -> inTransaction(seed.tenantId(), () ->
                repository.findReplay(changedAuthorityAndDigest)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SYS008_COMMAND_RECEIPT_NOT_AVAILABLE");

        CommandSeal rejectedSeal = executionSeal(
                seed.tenantId(), "receipt-finalization-rejected-0001",
                "f".repeat(64));
        var rejected = inTransaction(seed.tenantId(), () -> {
            OffsetDateTime now = repository.databaseNow();
            var accepted = repository.beginCommand(rejectedSeal, now).receipt();
            return repository.finishCommand(
                    rejectedSeal, accepted.receiptId(), ReceiptStatus.REJECTED,
                    aggregateId, 9, resultRef, now);
        });
        assertThat(rejected.aggregateVersion()).isNull();
        assertThat(rejected.resultRef()).isNull();
        Map<String, Object> rejectedRow = admin.queryForMap("""
                SELECT status, aggregate_public_id, aggregate_revision, result_ref
                  FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND public_id = ?
                """, seed.tenantId(), rejected.receiptId());
        assertThat(rejectedRow.get("status")).isEqualTo("REJECTED");
        assertThat(rejectedRow.get("aggregate_public_id")).isNull();
        assertThat(rejectedRow.get("aggregate_revision")).isNull();
        assertThat(rejectedRow.get("result_ref")).isNull();
    }

    @Test
    void cancelAndOwnerPageUseProductionQueriesAndActionIntersection() {
        Seed seed = seedActiveConnector(83102, "cancel-owner-page");
        UUID executionPublicId = uuid("cancel-owner-page-execution");
        CommandSeal original = executionSeal(
                seed.tenantId(), "cancel-owner-original-0001", REQUEST_DIGEST);
        Created created = createAcceptedExecution(
                seed, original, executionPublicId, "cancel-owner-provider-0001");

        CommandSeal cancelSeal = new CommandSeal(
                seed.tenantId(), SUBJECT, "CANCEL_CONNECTOR_EXECUTION",
                CANCEL_EXECUTION, "route.hcm.configuration.settings-integrations.page",
                "ctx-sys008", "scope-sys008", "psr-" + "f".repeat(64),
                SCOPE_DIGEST, 7, 11, "HRIS_INTEGRATION_ADMIN",
                "cancel-owner-command-0001", "0".repeat(64), UUID.randomUUID());
        var cancelReceipt = inTransaction(seed.tenantId(), () -> {
            OffsetDateTime now = repository.databaseNow();
            var accepted = repository.beginCommand(cancelSeal, now).receipt();
            ExecutionRecord current = repository.lockExecution(
                    seed.tenantId(), executionPublicId);
            ExecutionRecord cancelled = repository.cancel(
                    current, null, null, null, null, ACTOR_ID, now);
            repository.markOriginalReceiptOutcome(
                    cancelled, ReceiptStatus.FAILED, now);
            repository.markOriginalReceiptOutcome(
                    cancelled, ReceiptStatus.FAILED, now);
            return repository.finishCommand(
                    cancelSeal, accepted.receiptId(), ReceiptStatus.SUCCEEDED,
                    executionPublicId, cancelled.aggregateRevision(),
                    "/v1/admin/hris/connector-executions/" + executionPublicId,
                    now);
        });

        assertThat(cancelReceipt.status()).isEqualTo(ReceiptStatus.SUCCEEDED);
        assertThat(admin.queryForObject("""
                SELECT status FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND public_id = ?
                """, String.class, seed.tenantId(),
                created.execution().originalCommandReceiptId()))
                .isEqualTo("FAILED");
        assertThat(admin.queryForObject("""
                SELECT lifecycle_state FROM sys_connector_executions
                 WHERE tenant_id = ? AND public_id = ?
                """, String.class, seed.tenantId(), executionPublicId))
                .isEqualTo("CANCELLED");
        assertThat(admin.queryForList("""
                SELECT lifecycle_state FROM sys_connector_execution_items
                 WHERE tenant_id = ? AND execution_id = ?
                """, String.class, seed.tenantId(), created.execution().internalId()))
                .containsExactly("CANCELLED");
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM sys_connector_execution_attempts
                 WHERE tenant_id = ? AND execution_id = ?
                """, Integer.class, seed.tenantId(), created.execution().internalId()))
                .isEqualTo(1);

        var query = new Sys008ProjectionContract.TrustedOwnerQuery(
                seed.tenantId(), ACTOR_ID, PRIMARY_DWP_CONTROL, Map.of(),
                DEFAULT_SORT, Instant.now().plusSeconds(5), "ko-KR",
                "Asia/Seoul", 3, 7, 11, 20, null);
        var page = inTransaction(seed.tenantId(), () -> repository.ownerPage(
                query, null, List.of(CREATE_EXECUTION, APPROVE_DEFINITION)));
        assertThat(page.state()).isEqualTo(COMPLETE);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().itemPublicId())
                .isEqualTo(seed.connectorPublicId());
        assertThat(page.items().getFirst().availableActions())
                .containsExactly(CREATE_EXECUTION);
        assertThat(page.ownerReceipt()).matches("^[a-f0-9]{64}$");
    }

    @Test
    void compensationClaimProvidesDurableCasFenceAndUnknownMarker() {
        Seed seed = seedActiveConnector(83106, "compensation-claim");
        UUID executionPublicId = uuid("compensation-claim-execution");
        CommandSeal seal = executionSeal(
                seed.tenantId(), "compensation-claim-command-0001",
                REQUEST_DIGEST);
        Created created = createAcceptedExecution(
                seed, seal, executionPublicId, "compensation-claim-provider-0001");
        ExecutionRecord dispatched = inTransaction(seed.tenantId(), () ->
                repository.beginDispatch(
                        repository.lockExecution(seed.tenantId(), executionPublicId),
                        created.execution().attemptFence(), repository.databaseNow()));
        var claimed = inTransaction(seed.tenantId(), SUBJECT, () ->
                repository.claimCompensation(
                        repository.lockExecution(seed.tenantId(), executionPublicId),
                        "7".repeat(64), dispatched.aggregateRevision(),
                        repository.databaseNow()));
        assertThat(claimed.owner()).isTrue();
        assertThat(claimed.execution().aggregateRevision())
                .isEqualTo(dispatched.aggregateRevision() + 1);
        var observed = inTransaction(seed.tenantId(), SECOND_SUBJECT, () ->
                repository.claimCompensation(
                        claimed.execution(),
                        "7".repeat(64), dispatched.aggregateRevision(),
                        repository.databaseNow()));
        assertThat(observed.owner()).isFalse();
        assertThat(observed.execution().aggregateRevision())
                .isEqualTo(claimed.execution().aggregateRevision());
        assertThat(observed.claimDigest()).isEqualTo("7".repeat(64));
        assertThat(admin.queryForMap("""
                SELECT subject_principal_public_id, request_digest, status,
                       aggregate_public_id, aggregate_revision
                  FROM sys_hris_command_receipts
                 WHERE tenant_id = ?
                   AND command_type = 'COMPENSATION_EFFECT_CLAIM'
                   AND aggregate_public_id = ?
                """, seed.tenantId(), executionPublicId))
                .containsEntry("subject_principal_public_id", UUID.fromString(
                        "00000000-0000-4000-8000-000000000009"))
                .containsEntry("request_digest", "7".repeat(64))
                .containsEntry("status", "RUNNING")
                .containsEntry("aggregate_public_id", executionPublicId)
                .containsEntry("aggregate_revision",
                        claimed.execution().aggregateRevision());
        assertThatThrownBy(() -> inTransaction(
                seed.tenantId(), SECOND_SUBJECT, () ->
                repository.claimCompensation(
                        claimed.execution(),
                        "8".repeat(64), dispatched.aggregateRevision(),
                        repository.databaseNow())))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining(
                        "SYS008_COMPENSATION_CLAIM_DIGEST_CONFLICT");

        String operationsRef = "hris-operations/sys008/" + executionPublicId;
        ExecutionRecord unknown = inTransaction(seed.tenantId(), () ->
        {
            OffsetDateTime now = repository.databaseNow();
            ExecutionRecord marked = repository.markCompensationResultUnknown(
                    repository.lockExecution(seed.tenantId(), executionPublicId),
                    operationsRef, now);
            repository.markOriginalReceiptOutcome(
                    marked, ReceiptStatus.RESULT_UNKNOWN, now);
            return marked;
        });
        assertThat(unknown.aggregateRevision())
                .isEqualTo(claimed.execution().aggregateRevision() + 1);
        assertThat(unknown.providerDispatchOccurred()).isTrue();
        assertThat(admin.queryForMap("""
                SELECT unresolved_external_effect, operations_exception_ref,
                       object_revision
                  FROM sys_connector_executions
                 WHERE tenant_id = ? AND public_id = ?
                """, seed.tenantId(), executionPublicId))
                .containsEntry("unresolved_external_effect", true)
                .containsEntry("operations_exception_ref", operationsRef)
                .containsEntry("object_revision", unknown.aggregateRevision());
        assertThat(admin.queryForObject("""
                SELECT status FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND public_id = ?
                """, String.class, seed.tenantId(),
                created.execution().originalCommandReceiptId()))
                .isEqualTo("RESULT_UNKNOWN");
    }

    @Test
    void receiptExecutionItemAndAttemptRollbackAsOneTransaction() {
        Seed seed = seedActiveConnector(83103, "atomic-rollback");
        UUID executionPublicId = uuid("atomic-rollback-execution");
        CommandSeal seal = executionSeal(
                seed.tenantId(), "atomic-rollback-command-0001", REQUEST_DIGEST);

        assertThatThrownBy(() -> inTransaction(seed.tenantId(), () -> {
            OffsetDateTime now = repository.databaseNow();
            var definition = repository.lockDefinition(
                    seed.tenantId(), seed.connectorPublicId());
            var mapping = repository.lockMapping(
                    seed.tenantId(), seed.connectorPublicId(), 1);
            var accepted = repository.beginCommand(seal, now).receipt();
            ExecutionRecord execution = repository.insertExecution(
                    definition, mapping, executionPublicId, ACTOR_ID,
                    accepted.receiptId(), executionCommand(now),
                    "atomic-rollback-provider-0001", PROVIDER_DIGEST,
                    seal.authorizationRevision(), SCOPE_DIGEST,
                    "TENANT", null, manifest(), now);
            repository.progressCommand(
                    seal, accepted.receiptId(), executionPublicId,
                    execution.aggregateRevision(),
                    "/v1/admin/hris/connector-executions/" + executionPublicId,
                    now);
            repository.beginDispatch(
                    execution, execution.attemptFence(), now);
            throw new InjectedRollback();
        })).isInstanceOf(InjectedRollback.class);

        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND idempotency_key = ?
                """, Integer.class, seed.tenantId(), seal.idempotencyKey()))
                .isZero();
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM sys_connector_executions
                 WHERE tenant_id = ? AND public_id = ?
                """, Integer.class, seed.tenantId(), executionPublicId))
                .isZero();
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM sys_connector_execution_items i
                  JOIN sys_connector_executions e
                    ON e.tenant_id = i.tenant_id
                   AND e.execution_id = i.execution_id
                 WHERE e.tenant_id = ? AND e.public_id = ?
                """, Integer.class, seed.tenantId(), executionPublicId))
                .isZero();
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM sys_connector_execution_attempts a
                  JOIN sys_connector_executions e
                    ON e.tenant_id = a.tenant_id
                   AND e.execution_id = a.execution_id
                 WHERE e.tenant_id = ? AND e.public_id = ?
                """, Integer.class, seed.tenantId(), executionPublicId))
                .isZero();
    }

    @Test
    void retryBudgetDeadLetterAtomicallyFinalizesRetryableChildren() {
        Seed seed = seedActiveConnector(83104, "dead-letter-cascade");
        UUID executionPublicId = uuid("dead-letter-cascade-execution");
        CommandSeal seal = executionSeal(
                seed.tenantId(), "dead-letter-command-0001", REQUEST_DIGEST);
        Created created = createAcceptedExecution(
                seed, seal, executionPublicId, "dead-letter-provider-0001");

        ExecutionRecord failed = inTransaction(seed.tenantId(), () -> {
            OffsetDateTime now = repository.databaseNow();
            ExecutionRecord dispatched = repository.beginDispatch(
                    repository.lockExecution(seed.tenantId(), executionPublicId),
                    created.execution().attemptFence(), now);
            var attempt = repository.currentAttempt(dispatched);
            var item = new IntegrationRuntimePorts.ProviderItemEvidence(
                    attempt.itemPublicId(),
                    IntegrationRuntimePorts.ProviderEvidence.Outcome.FAILED,
                    attempt.providerIdempotencyKey(), attempt.requestDigest(),
                    "ref_providerreceipt0001", "7".repeat(64), null,
                    false, "TEMPORARY_PROVIDER_FAILURE", true);
            var evidence = new IntegrationRuntimePorts.ProviderEvidence(
                    IntegrationRuntimePorts.ProviderEvidence.Outcome.FAILED,
                    dispatched.providerIdempotencyKey(),
                    dispatched.providerRequestDigest(),
                    "ref_providerreceipt0001", "7".repeat(64),
                    1, 0, 1, 0, null, now, List.of(item));
            return repository.applyProviderEvidence(
                    dispatched, evidence, ExecutionState.FAILED, now);
        });
        assertThat(failed.state()).isEqualTo(ExecutionState.FAILED);
        assertThat(inTransaction(seed.tenantId(), () ->
                repository.retryableItemIds(failed))).hasSize(1);

        ExecutionRecord terminal = inTransaction(seed.tenantId(), () ->
                repository.deadLetter(
                        repository.lockExecution(
                                seed.tenantId(), executionPublicId),
                        ACTOR_ID, repository.databaseNow()));

        assertThat(terminal.state()).isEqualTo(ExecutionState.DEAD_LETTER);
        assertThat(admin.queryForList("""
                SELECT lifecycle_state FROM sys_connector_execution_items
                 WHERE tenant_id = ? AND execution_id = ?
                """, String.class, seed.tenantId(), terminal.internalId()))
                .containsExactly("REJECTED_FINAL");
        assertThat(admin.queryForList("""
                SELECT lifecycle_state FROM sys_connector_execution_attempts
                 WHERE tenant_id = ? AND execution_id = ?
                """, String.class, seed.tenantId(), terminal.internalId()))
                .containsExactly("FAILED");
    }

    @Test
    void retryRecomputesCountsBeforeAmbiguousProviderOutcome() {
        Seed seed = seedActiveConnector(83107, "retry-counts");
        UUID executionPublicId = uuid("retry-counts-execution");
        CommandSeal seal = executionSeal(
                seed.tenantId(), "retry-counts-command-0001", REQUEST_DIGEST);
        Created created = createAcceptedExecution(
                seed, seal, executionPublicId, "retry-counts-provider-0001");

        ExecutionRecord failed = inTransaction(seed.tenantId(), () -> {
            OffsetDateTime now = repository.databaseNow();
            ExecutionRecord dispatched = repository.beginDispatch(
                    repository.lockExecution(seed.tenantId(), executionPublicId),
                    created.execution().attemptFence(), now);
            var attempt = repository.currentAttempt(dispatched);
            var item = new IntegrationRuntimePorts.ProviderItemEvidence(
                    attempt.itemPublicId(),
                    IntegrationRuntimePorts.ProviderEvidence.Outcome.FAILED,
                    attempt.providerIdempotencyKey(), attempt.requestDigest(),
                    "ref_retrycounts_fail01", "7".repeat(64), null,
                    false, "TEMPORARY_PROVIDER_FAILURE", true);
            var evidence = new IntegrationRuntimePorts.ProviderEvidence(
                    IntegrationRuntimePorts.ProviderEvidence.Outcome.FAILED,
                    dispatched.providerIdempotencyKey(),
                    dispatched.providerRequestDigest(),
                    "ref_retrycounts_fail01", "7".repeat(64),
                    1, 0, 1, 0, null, now, List.of(item));
            return repository.applyProviderEvidence(
                    dispatched, evidence, ExecutionState.FAILED, now);
        });
        List<UUID> retryable = inTransaction(seed.tenantId(), () ->
                repository.retryableItemIds(failed));
        String failedSetDigest = IntegrationDomainPolicy.sha256(
                retryable.stream().sorted().toList().toString());
        ExecutionRecord retried = inTransaction(seed.tenantId(), () ->
                repository.createRetryAttempts(
                        repository.lockExecution(seed.tenantId(), executionPublicId),
                        retryable, failedSetDigest, ACTOR_ID,
                        repository.databaseNow()));
        assertThat(retried.state()).isEqualTo(ExecutionState.ACCEPTED);
        assertThat(retried.acceptedCount()).isZero();
        assertThat(retried.rejectedCount()).isZero();
        assertThat(retried.quarantinedCount()).isZero();
        assertThat(retried.providerDispatchOccurred()).isTrue();

        ExecutionRecord unknown = inTransaction(seed.tenantId(), () -> {
            OffsetDateTime now = repository.databaseNow();
            ExecutionRecord dispatched = repository.beginDispatch(
                    repository.lockExecution(seed.tenantId(), executionPublicId),
                    retried.attemptFence(), now);
            var attempt = repository.currentAttempt(dispatched);
            var item = new IntegrationRuntimePorts.ProviderItemEvidence(
                    attempt.itemPublicId(),
                    IntegrationRuntimePorts.ProviderEvidence.Outcome.RESULT_UNKNOWN,
                    attempt.providerIdempotencyKey(), attempt.requestDigest(),
                    null, null, null, false, null, false);
            var evidence = new IntegrationRuntimePorts.ProviderEvidence(
                    IntegrationRuntimePorts.ProviderEvidence.Outcome.RESULT_UNKNOWN,
                    dispatched.providerIdempotencyKey(),
                    dispatched.providerRequestDigest(), null, null,
                    1, 0, 0, 0, null, now, List.of(item));
            return repository.applyProviderEvidence(
                    dispatched, evidence, ExecutionState.RESULT_UNKNOWN, now);
        });
        assertThat(unknown.state()).isEqualTo(ExecutionState.RESULT_UNKNOWN);
        assertThat(unknown.acceptedCount()).isZero();
        assertThat(unknown.rejectedCount()).isZero();
    }

    private static Created createAcceptedExecution(
            Seed seed,
            CommandSeal seal,
            UUID executionPublicId,
            String providerKey) {
        return inTransaction(seed.tenantId(), () -> {
            OffsetDateTime now = repository.databaseNow();
            var definition = repository.lockDefinition(
                    seed.tenantId(), seed.connectorPublicId());
            var mapping = repository.lockMapping(
                    seed.tenantId(), seed.connectorPublicId(), 1);
            var accepted = repository.beginCommand(seal, now).receipt();
            ExecutionRecord execution = repository.insertExecution(
                    definition, mapping, executionPublicId, ACTOR_ID,
                    accepted.receiptId(), executionCommand(now), providerKey,
                    PROVIDER_DIGEST, seal.authorizationRevision(),
                    SCOPE_DIGEST, "TENANT", null, manifest(), now);
            var running = repository.progressCommand(
                    seal, accepted.receiptId(), executionPublicId,
                    execution.aggregateRevision(),
                    "/v1/admin/hris/connector-executions/" + executionPublicId,
                    now);
            return new Created(execution, running);
        });
    }

    private static IntegrationDtos.ExecutionCommand executionCommand(
            OffsetDateTime now) {
        return new IntegrationDtos.ExecutionCommand(
                Operation.EXPORT, "payload/sys008/repository",
                PAYLOAD_DIGEST, now);
    }

    private static List<IntegrationRuntimePorts.SourceManifestItem> manifest() {
        return List.of(new IntegrationRuntimePorts.SourceManifestItem(
                "1".repeat(64), PAYLOAD_DIGEST));
    }

    private static CommandSeal executionSeal(
            long tenantId, String idempotencyKey, String requestDigest) {
        return new CommandSeal(
                tenantId, SUBJECT, "CREATE_CONNECTOR_EXECUTION",
                CREATE_EXECUTION,
                "route.hcm.configuration.settings-integrations.page",
                "ctx-sys008", "scope-sys008", "psr-" + "f".repeat(64),
                SCOPE_DIGEST, 7, 11, "HRIS_INTEGRATION_ADMIN",
                idempotencyKey, requestDigest, UUID.randomUUID());
    }

    private static <T> T inTransaction(long tenantId, Supplier<T> work) {
        return inTransaction(tenantId, SUBJECT, work);
    }

    private static <T> T inTransaction(
            long tenantId,
            UUID subject,
            Supplier<T> work) {
        return transactions.execute(ignored -> {
            repository.bindTenantPrincipal(tenantId, subject);
            return work.get();
        });
    }

    private static Seed seedActiveConnector(long tenantId, String key) {
        UUID definitionId = uuid(key + "-definition");
        UUID connectorPublicId = uuid(key + "-connector");
        UUID definitionVersionId = uuid(key + "-definition-version");
        UUID mappingId = uuid(key + "-mapping");
        long author = tenantId + 10;
        long checker = tenantId + 20;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);

        admin.update("""
                INSERT INTO sys_connector_definitions (
                    tenant_id, definition_id, public_id, connector_key,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenantId, definitionId, connectorPublicId,
                "connector." + key, now, now);
        admin.update("""
                INSERT INTO sys_connector_definition_versions (
                    tenant_id, definition_version_id, definition_id,
                    version_number, connector_type, endpoint_uri,
                    endpoint_policy_key, endpoint_policy_revision, secret_ref,
                    secret_revision, definition_digest, author_actor_id,
                    author_scope_digest, created_at, updated_at)
                VALUES (?, ?, ?, 1, 'HTTPS', 'https://api.example.com/hris',
                        'egress.sys008', 1, 'secret_abcdefghijklmnop', 1,
                        repeat('1', 64), ?, repeat('2', 64), ?, ?)
                """, tenantId, definitionVersionId, definitionId,
                author, now, now);
        admin.update("""
                UPDATE sys_connector_definition_versions
                   SET lifecycle_state = 'VALIDATED',
                       validation_receipt_id = gen_random_uuid(),
                       validation_evidence_digest = repeat('3', 64),
                       validated_by_actor_id = ?, validated_at = ?,
                       object_revision = 2, updated_at = ?
                 WHERE tenant_id = ? AND definition_version_id = ?
                """, author, now, now, tenantId, definitionVersionId);
        admin.update("""
                UPDATE sys_connector_definition_versions
                   SET lifecycle_state = 'TESTED',
                       connection_test_receipt_id = gen_random_uuid(),
                       connection_test_result_ref = 'result/test/repository',
                       connection_test_result_digest = repeat('4', 64),
                       connection_test_evidence_digest = repeat('5', 64),
                       tested_by_actor_id = ?, tested_at = ?,
                       object_revision = 3, updated_at = ?
                 WHERE tenant_id = ? AND definition_version_id = ?
                """, author, now, now, tenantId, definitionVersionId);
        admin.update("""
                UPDATE sys_connector_definition_versions
                   SET lifecycle_state = 'PENDING_APPROVAL',
                       submission_receipt_id = gen_random_uuid(),
                       submission_evidence_digest = repeat('6', 64),
                       submitted_by_actor_id = ?, submitted_at = ?,
                       object_revision = 4, updated_at = ?
                 WHERE tenant_id = ? AND definition_version_id = ?
                """, author, now, now, tenantId, definitionVersionId);
        admin.update("""
                UPDATE sys_connector_definition_versions
                   SET lifecycle_state = 'APPROVED',
                       approval_receipt_id = gen_random_uuid(),
                       approval_evidence_digest = repeat('7', 64),
                       approval_step_up_evidence_digest = repeat('8', 64),
                       approved_by_actor_id = ?, approved_at = ?,
                       object_revision = 5, updated_at = ?
                 WHERE tenant_id = ? AND definition_version_id = ?
                """, checker, now, now, tenantId, definitionVersionId);
        admin.update("""
                UPDATE sys_connector_definition_versions
                   SET lifecycle_state = 'ACTIVE',
                       activation_receipt_id = gen_random_uuid(),
                       activation_evidence_digest = repeat('9', 64),
                       activation_step_up_evidence_digest = repeat('a', 64),
                       activated_by_actor_id = ?, activated_at = ?,
                       object_revision = 6, updated_at = ?
                 WHERE tenant_id = ? AND definition_version_id = ?
                """, checker, now, now, tenantId, definitionVersionId);
        admin.update("""
                UPDATE sys_connector_definitions
                   SET lifecycle_state = 'ACTIVE', active_version_number = 1,
                       aggregate_revision = 2, updated_at = ?
                 WHERE tenant_id = ? AND definition_id = ?
                """, now, tenantId, definitionId);

        admin.update("""
                INSERT INTO sys_connector_mapping_versions (
                    tenant_id, mapping_version_id, definition_id, mapping_key,
                    version_number, direction, source_schema_version,
                    source_schema_digest, target_schema_version,
                    target_schema_digest, mapping_ref, mapping_digest,
                    compatibility_mode, effective_from, author_actor_id,
                    author_scope_digest, created_at, updated_at)
                VALUES (?, ?, ?, 'default', 1, 'OUTBOUND', 1, repeat('b', 64),
                        1, repeat('c', 64), 'ref_abcdefghijklmnop',
                        repeat('d', 64), 'FULL', ?, ?, repeat('e', 64), ?, ?)
                """, tenantId, mappingId, definitionId, now, author, now, now);
        admin.update("""
                UPDATE sys_connector_mapping_versions
                   SET lifecycle_state = 'VALIDATED',
                       validation_receipt_id = gen_random_uuid(),
                       validation_evidence_digest = repeat('f', 64),
                       validated_by_actor_id = ?, validated_at = ?,
                       object_revision = 2, updated_at = ?
                 WHERE tenant_id = ? AND mapping_version_id = ?
                """, author, now, now, tenantId, mappingId);
        admin.update("""
                UPDATE sys_connector_mapping_versions
                   SET dry_run_receipt_id = gen_random_uuid(),
                       dry_run_result_ref = 'result/dry-run/repository',
                       dry_run_result_digest = repeat('0', 64),
                       dry_run_evidence_digest = repeat('1', 64),
                       dry_run_source_count = 1, dry_run_rejected_count = 0,
                       dry_run_by_actor_id = ?, dry_run_at = ?,
                       object_revision = 3, updated_at = ?
                 WHERE tenant_id = ? AND mapping_version_id = ?
                """, author, now, now, tenantId, mappingId);
        admin.update("""
                UPDATE sys_connector_mapping_versions
                   SET lifecycle_state = 'PENDING_APPROVAL',
                       submission_receipt_id = gen_random_uuid(),
                       submission_evidence_digest = repeat('2', 64),
                       submitted_by_actor_id = ?, submitted_at = ?,
                       object_revision = 4, updated_at = ?
                 WHERE tenant_id = ? AND mapping_version_id = ?
                """, author, now, now, tenantId, mappingId);
        admin.update("""
                UPDATE sys_connector_mapping_versions
                   SET lifecycle_state = 'APPROVED',
                       approval_receipt_id = gen_random_uuid(),
                       approval_evidence_digest = repeat('3', 64),
                       approval_step_up_evidence_digest = repeat('4', 64),
                       approved_by_actor_id = ?, approved_at = ?,
                       object_revision = 5, updated_at = ?
                 WHERE tenant_id = ? AND mapping_version_id = ?
                """, checker, now, now, tenantId, mappingId);
        admin.update("""
                UPDATE sys_connector_mapping_versions
                   SET lifecycle_state = 'ACTIVE',
                       activation_receipt_id = gen_random_uuid(),
                       activation_evidence_digest = repeat('5', 64),
                       activation_step_up_evidence_digest = repeat('6', 64),
                       activated_by_actor_id = ?, activated_at = ?,
                       object_revision = 6, updated_at = ?
                 WHERE tenant_id = ? AND mapping_version_id = ?
                """, checker, now, now, tenantId, mappingId);
        return new Seed(tenantId, connectorPublicId);
    }

    private static void provisionApplicationRole() {
        admin.execute("""
                CREATE ROLE sys008_repository_app LOGIN PASSWORD
                    'sys008-repository-password' NOSUPERUSER NOINHERIT NOBYPASSRLS
                """);
        admin.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
        for (String table : List.of(
                "sys_connector_definitions",
                "sys_connector_definition_versions",
                "sys_connector_mapping_versions",
                "sys_connector_executions",
                "sys_connector_execution_items",
                "sys_connector_execution_attempts")) {
            admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE "
                    + table + " TO " + APP_ROLE);
        }
        admin.execute("GRANT SELECT, INSERT, UPDATE ON TABLE "
                + "sys_hris_command_receipts TO " + APP_ROLE);
        admin.execute("GRANT SELECT, INSERT ON TABLE "
                + "sys_domain_event_outbox TO " + APP_ROLE);
        admin.execute("GRANT SELECT, INSERT ON TABLE "
                + "sys_audit_outbox TO " + APP_ROLE);
    }

    private static PGSimpleDataSource dataSource(
            String database, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(new String[]{POSTGRES.getHost()});
        source.setPortNumbers(new int[]{POSTGRES.getFirstMappedPort()});
        source.setDatabaseName(database);
        source.setUser(username);
        source.setPassword(password);
        return source;
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> unavailableProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private record Seed(long tenantId, UUID connectorPublicId) {}

    private record Created(
            ExecutionRecord execution,
            IntegrationDtos.CommandReceipt receipt) {}

    private static final class InjectedRollback extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
