package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.AutomationInvocationV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.LeaseGrant;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.RuntimeRun;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.HandlerIdentity;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.AutomationEffectRequest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.AutomationItemPlan;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.ExternalEffectEvidencePort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.SignedArtifactVerifierPort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.TypedAutomationHandlerPort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Shared setup and evidence builders for AutomationGoldenExecutionEvidenceTest. */
abstract class AutomationGoldenExecutionFixture {
    protected static final long TENANT = 73506L;
    protected static final String DIGEST_A = "a".repeat(64);
    protected static final String DIGEST_B = "b".repeat(64);
    protected static final String DIGEST_C = "c".repeat(64);
    protected static final String DIGEST_D = "d".repeat(64);
    protected static final String DIGEST_E = "e".repeat(64);
    protected static final String DIGEST_F = "f".repeat(64);
    protected static final ObjectMapper JSON = new ObjectMapper();

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    protected static JdbcTemplate jdbc;
    protected static TransactionTemplate transactions;
    protected JdbcAutomationRepository repository;

    @BeforeAll
    static void migrateRealPlatformSchema() {
        PGSimpleDataSource source = dataSource();
        Flyway flyway = Flyway.configure()
                .dataSource(source)
                .schemas("public")
                .defaultSchema("public")
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("333")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        source.setCurrentSchema("public");
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(source));
    }

    @BeforeEach
    void resetGoldenEvidence() {
        jdbc.execute("""
                TRUNCATE TABLE
                    sys_automation_run_checkpoints,
                    sys_automation_item_attempts,
                    sys_automation_run_items,
                    sys_automation_run_attempts,
                    sys_automation_runs,
                    sys_automation_schedules,
                    sys_automation_versions,
                    sys_automation_definitions,
                    sys_hris_command_receipts
                CASCADE
                """);
        repository = new JdbcAutomationRepository(jdbc, JSON);
    }

    protected AutomationRuntimeService runtime(
            AutomationHandlerRegistryPort registry,
            SignedArtifactVerifierPort verifier,
            List<TypedAutomationHandlerPort> handlers) {
        return runtime(registry, verifier, handlers, List.of());
    }

    protected AutomationRuntimeService runtime(
            AutomationHandlerRegistryPort registry,
            SignedArtifactVerifierPort verifier,
            List<TypedAutomationHandlerPort> handlers,
            List<ExternalEffectEvidencePort> evidenceProviders) {
        return new AutomationRuntimeService(
                repository, repository, registry, verifier, handlers,
                evidenceProviders, JSON);
    }

    protected static void assertClosedInvocationRejects(
            AutomationInvocationV1 invocation, String property, String payload) {
        ObjectNode document = JSON.valueToTree(invocation);
        document.put(property, payload);
        Throwable rejected = catchThrowable(() -> JSON.treeToValue(
                document, AutomationInvocationV1.class));
        assertThat(rejected).hasMessageContaining("unknown property " + property);
    }

    protected static void assertResourceConflict(
            Throwable throwable, String message) {
        assertThat(throwable)
                .isInstanceOf(BaseException.class)
                .hasMessageContaining(message);
        assertThat(((BaseException) throwable).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    protected static SignedArtifactVerifierPort verifier(
            OffsetDateTime freshUntil) {
        return new SignedArtifactVerifierPort() {
            @Override
            public VerifiedArtifact verifyDraft(
                    AutomationDtos.CreateVersionCommand command) {
                throw new UnsupportedOperationException(
                        "draft verification is outside execution evidence");
            }

            @Override
            public VerifiedArtifact revalidate(
                    SignedManifest approvedEvidence,
                    String definitionDigest,
                    String executionPolicyDigest,
                    String compatibilityBaselineDigest) {
                return new VerifiedArtifact(
                        approvedEvidence, "golden-registry-r1", DIGEST_A,
                        "golden-compatibility-v1", DIGEST_B, DIGEST_C,
                        freshUntil);
            }
        };
    }

    protected static CommandSeal commandSeal(String requestDigest) {
        return new CommandSeal(
                TENANT, uuid("gold016-subject"),
                "CREATE_AUTOMATION_DEFINITION",
                AutomationService.CREATE_DEFINITION,
                "route:sys006:gold016", "context:sys006:gold016",
                "scope:tenant:" + TENANT, "decision:gold016:1",
                DIGEST_C, 1, 1, "HRIS_SYSTEM_ADMIN",
                "gold016-command-key-0001", requestDigest,
                uuid("gold016-correlation"));
    }

    protected GoldenFixture seedQueued(String discriminator, int expectedItemCount) {
        UUID definitionId = uuid(discriminator + "-definition-id");
        UUID definitionPublicId = uuid(discriminator + "-definition-public");
        UUID versionId = uuid(discriminator + "-version-id");
        UUID runId = uuid(discriminator + "-run-id");
        UUID runPublicId = uuid(discriminator + "-run-public");
        UUID actor = uuid(discriminator + "-actor");
        UUID subject = uuid(discriminator + "-subject");
        OffsetDateTime now = databaseNow();
        insertDefinitionAndVersion(
                discriminator, definitionId, definitionPublicId,
                versionId, actor, now);
        jdbc.update("""
                INSERT INTO sys_automation_runs (
                    run_id, public_id, tenant_id, definition_id, version_id,
                    version_number, lifecycle_state, selection_mode, scope_type,
                    requested_at, requested_by_actor_ref, idempotency_key,
                    correlation_id, input_ref, input_digest, expected_item_count,
                    concurrency_key_digest, retry_mode, max_attempts,
                    current_attempt, item_total, command_receipt_id,
                    created_by_actor_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, 'QUEUED', 'ALL_ITEMS', 'TENANT', ?, ?, ?,
                        ?, 'input:golden', ?, ?, ?, 'FAILED_ITEMS_ONLY', 3,
                        1, 0, ?, ?, ?, ?)
                """, runId, runPublicId, TENANT, definitionId, versionId,
                now, "principal:" + actor,
                "golden-" + discriminator + "-0001",
                uuid(discriminator + "-correlation"), DIGEST_A,
                expectedItemCount, digest(discriminator + "-concurrency"),
                uuid(discriminator + "-command-receipt"), actor, now, now);
        jdbc.update("""
                INSERT INTO sys_automation_run_attempts (
                    tenant_id, run_id, attempt_number, selection_mode,
                    selected_item_count, selected_item_digest, created_at)
                VALUES (?, ?, 1, 'ALL_ITEMS', 0, ?, ?)
                """, TENANT, runId, selectionDigest(List.of()), now);
        return new GoldenFixture(
                TENANT, definitionId, definitionPublicId, versionId,
                runId, runPublicId, actor, subject);
    }

    protected PartialFixture seedPartial(
            String discriminator, boolean withCheckpoint) {
        UUID definitionId = uuid(discriminator + "-definition-id");
        UUID definitionPublicId = uuid(discriminator + "-definition-public");
        UUID versionId = uuid(discriminator + "-version-id");
        UUID runId = uuid(discriminator + "-run-id");
        UUID runPublicId = uuid(discriminator + "-run-public");
        UUID runAttemptId = uuid(discriminator + "-attempt-1");
        UUID actor = uuid(discriminator + "-actor");
        UUID subject = uuid(discriminator + "-subject");
        UUID successful = uuid(discriminator + "-success");
        UUID retryable = uuid(discriminator + "-retryable");
        UUID finalFailed = uuid(discriminator + "-final");
        OffsetDateTime now = databaseNow();

        transactions.executeWithoutResult(ignored -> {
            jdbc.execute("SET session_replication_role = replica");
            try {
                insertDefinitionAndVersion(
                        discriminator, definitionId, definitionPublicId,
                        versionId, actor, now);
                jdbc.update("""
                        INSERT INTO sys_automation_runs (
                            run_id, public_id, tenant_id, definition_id, version_id,
                            version_number, lifecycle_state, selection_mode, scope_type,
                            requested_at, requested_by_actor_ref, idempotency_key,
                            correlation_id, input_ref, input_digest, expected_item_count,
                            concurrency_key_digest, retry_mode, max_attempts,
                            current_attempt, item_total, item_succeeded, item_failed,
                            command_receipt_id, worker_fence_epoch,
                            control_fence_epoch, created_by_actor_ref,
                            created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 1, 'PARTIAL', 'ALL_ITEMS', 'TENANT',
                                ?, ?, ?, ?, 'input:golden', ?, 3, ?,
                                'FAILED_ITEMS_ONLY', 3, 1, 3, 1, 2, ?, 1, 0, ?, ?, ?)
                        """, runId, runPublicId, TENANT, definitionId, versionId,
                        now, "principal:" + actor,
                        "golden-" + discriminator + "-0001",
                        uuid(discriminator + "-correlation"), DIGEST_A,
                        digest(discriminator + "-concurrency"),
                        uuid(discriminator + "-command-receipt"), actor, now, now);
                jdbc.update("""
                        INSERT INTO sys_automation_run_attempts (
                            run_attempt_id, tenant_id, run_id, attempt_number,
                            selection_mode, selected_item_count, selected_item_digest,
                            lifecycle_state, worker_fence_epoch,
                            started_at, completed_at, created_at)
                        VALUES (?, ?, ?, 1, 'ALL_ITEMS', 3, ?, 'PARTIAL', 1, ?, ?, ?)
                        """, runAttemptId, TENANT, runId,
                        selectionDigest(List.of(successful, retryable, finalFailed)),
                        now, now, now);
                insertTerminalItem(runId, successful, 1, "subject:successful",
                        "SUCCEEDED", "COMMITTED", DIGEST_B, null,
                        "SUCCEEDED", false, now, discriminator);
                insertTerminalItem(runId, retryable, 2, "subject:retryable",
                        "FAILED_RETRYABLE", "NO_EFFECT_CONFIRMED", null,
                        "RETRYABLE_FAILURE", "FAILED_RETRYABLE", true,
                        now, discriminator);
                insertTerminalItem(runId, finalFailed, 3, "subject:final",
                        "FAILED_FINAL", "COMMITTED", DIGEST_C,
                        "FINAL_FAILURE", "FAILED_FINAL", false,
                        now, discriminator);
                if (withCheckpoint) {
                    jdbc.update("""
                            INSERT INTO sys_automation_run_checkpoints (
                                tenant_id, run_id, run_attempt_id, attempt_number,
                                checkpoint_ordinal, processed_item_count,
                                processed_item_digest, output_digest,
                                writer_authority_kind, authority_fence_epoch,
                                control_fence_epoch, committed_at)
                            VALUES (?, ?, ?, 1, 1, 3, ?, ?, 'WORKER', 1, 0, ?)
                            """, TENANT, runId, runAttemptId, DIGEST_D, DIGEST_E, now);
                }
            } finally {
                jdbc.execute("SET session_replication_role = origin");
            }
        });
        return new PartialFixture(
                TENANT, runId, runPublicId, subject,
                successful, retryable, finalFailed);
    }

    protected void insertDefinitionAndVersion(
            String discriminator,
            UUID definitionId,
            UUID definitionPublicId,
            UUID versionId,
            UUID actor,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO sys_automation_definitions (
                    definition_id, public_id, tenant_id, automation_key,
                    display_name, owner_domain, author_actor_ref,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'HRM', ?, ?, ?)
                """, definitionId, definitionPublicId, TENANT,
                "gold." + discriminator, "Golden " + discriminator,
                actor, now, now);
        jdbc.update("""
                INSERT INTO sys_automation_versions (
                    version_id, tenant_id, definition_id, version_number,
                    lifecycle_state, definition_payload, definition_digest,
                    execution_policy, execution_policy_digest,
                    manual_concurrency_key_template, compensation_policy_digest,
                    manifest_retry_mode, manifest_max_attempts,
                    handler_owner_service, handler_key, handler_contract_version,
                    allowed_scope_types, handler_manifest_state,
                    handler_manifest_revision, handler_manifest_digest,
                    handler_signature_key_id, handler_signature,
                    handler_signature_digest, input_schema_ref,
                    input_schema_digest, receipt_schema_ref,
                    receipt_schema_digest, author_actor_ref,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, 'ACTIVE', '{}'::jsonb, ?, '{}'::jsonb, ?,
                        'tenant:{tenantId}', ?, 'FAILED_ITEMS_ONLY', 3,
                        'dwp-hrm-server', 'hris.hrm.test', 'AutomationInput.v1',
                        ARRAY['TENANT']::TEXT[], 'ACTIVE', 1, ?,
                        'golden-signing-key', 'golden-signature', ?,
                        'schema:input:v1', ?, 'schema:receipt:v1', ?, ?, ?, ?)
                """, versionId, TENANT, definitionId, DIGEST_A, DIGEST_B,
                DIGEST_C, DIGEST_D, DIGEST_E, DIGEST_A, DIGEST_B,
                actor, now, now);
        jdbc.update("""
                UPDATE sys_automation_definitions
                   SET lifecycle_state = 'ACTIVE', active_version_number = 1
                 WHERE tenant_id = ? AND definition_id = ?
                """, TENANT, definitionId);
    }

    protected void insertTerminalItem(
            UUID runId,
            UUID itemPublicId,
            long sequence,
            String subjectRef,
            String itemState,
            String effectState,
            String resultDigest,
            String errorCode,
            String receiptState,
            boolean retryable,
            OffsetDateTime now,
            String discriminator) {
        UUID itemId = uuid(discriminator + "-item-" + sequence);
        UUID attemptId = uuid(discriminator + "-item-attempt-" + sequence);
        String effectKey = "effect-" + discriminator + "-" + sequence + "-0001";
        String receiptDigest = "COMMITTED".equals(effectState)
                ? digest(discriminator + "-receipt-" + sequence) : null;
        jdbc.update("""
                INSERT INTO sys_automation_run_items (
                    run_item_id, public_id, tenant_id, run_id, item_sequence,
                    subject_ref, subject_reference_digest, lifecycle_state,
                    attempt_count, result_digest, error_code, effect_state,
                    effect_idempotency_key, effect_request_digest,
                    effect_receipt_digest, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                """, itemId, itemPublicId, TENANT, runId, sequence,
                subjectRef, digest(subjectRef), itemState, resultDigest,
                errorCode, effectState, effectKey, DIGEST_D,
                receiptDigest, now);
        jdbc.update("""
                INSERT INTO sys_automation_item_attempts (
                    item_attempt_id, tenant_id, run_id, run_item_id,
                    attempt_ordinal, run_revision_snapshot,
                    receipt_state, accepted_at, next_attempt_at,
                    result_ref, result_digest, error_code,
                    retryable_snapshot, correlation_id,
                    effect_state, effect_authority_kind,
                    effect_idempotency_key, effect_request_digest,
                    effect_receipt_id, effect_receipt_digest,
                    effect_dispatch_authority_fence,
                    effect_dispatch_control_fence,
                    effect_resolution_authority_fence,
                    effect_resolution_control_fence,
                    effect_resolution_mode, effect_committed_at,
                    started_at, completed_at, created_at)
                VALUES (?, ?, ?, ?, 1, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WORKER',
                        ?, ?, ?, ?, 1, 0, 1, 0, 'DISPATCH_FINALIZE', ?, ?, ?, ?)
                """, attemptId, TENANT, runId, itemId, receiptState, now,
                retryable ? now.plusMinutes(1) : null,
                "SUCCEEDED".equals(receiptState)
                        ? "result:" + itemPublicId : null,
                resultDigest, errorCode, retryable,
                uuid(discriminator + "-item-correlation-" + sequence),
                effectState, effectKey, DIGEST_D,
                receiptDigest == null ? null
                        : uuid(discriminator + "-effect-receipt-" + sequence),
                receiptDigest,
                "COMMITTED".equals(effectState) ? now : null,
                now, now, now);
    }

    protected static AutomationRunItemReceiptV1 successfulReceipt(
            AutomationInvocationV1 invocation,
            AutomationEffectRequest request) {
        return new AutomationRunItemReceiptV1(
                invocation.invocationId(), request.runRevision(),
                request.itemId(), request.itemSequence(), request.subjectRef(),
                "SUCCEEDED", request.attemptNumber(), request.acceptedAt(),
                request.acceptedAt().plusSeconds(1),
                "result:" + request.itemId(), DIGEST_D, null, false, null,
                invocation.correlationId());
    }

    protected static AutomationRunItemReceiptV1 retryableFailureReceipt(
            AutomationInvocationV1 invocation,
            AutomationEffectRequest request) {
        return new AutomationRunItemReceiptV1(
                invocation.invocationId(), request.runRevision(),
                request.itemId(), request.itemSequence(), request.subjectRef(),
                "FAILED", request.attemptNumber(), request.acceptedAt(),
                request.acceptedAt().plusSeconds(1), null, null,
                "RETRYABLE_FAILURE", true,
                request.acceptedAt().plusSeconds(30),
                invocation.correlationId());
    }

    protected static AutomationEffectRequest effectRequest(
            RuntimeRun run,
            AutomationExecutionRepository.PreparedItem item) {
        return new AutomationEffectRequest(
                item.itemPublicId(), item.itemSequence(), item.subjectRef(),
                run.run().objectRevision(), run.attemptNumber(), item.acceptedAt(),
                item.effectIdempotencyKey(), item.effectRequestDigest());
    }

    protected void expireWorkerLease(
            GoldenFixture fixture, OffsetDateTime expiredAt) {
        expireWorkerLease(fixture, 1, expiredAt);
    }

    protected void expireWorkerLease(
            GoldenFixture fixture, int attemptNumber,
            OffsetDateTime expiredAt) {
        jdbc.update("""
                UPDATE sys_automation_runs
                   SET worker_leased_until = ?
                 WHERE tenant_id = ? AND run_id = ?
                """, expiredAt, fixture.tenantId(), fixture.runId());
        jdbc.update("""
                UPDATE sys_automation_run_attempts
                   SET worker_leased_until = ?
                 WHERE tenant_id = ? AND run_id = ? AND attempt_number = ?
                """, expiredAt, fixture.tenantId(), fixture.runId(),
                attemptNumber);
    }

    protected List<UUID> attemptedItems(PartialFixture fixture, int attempt) {
        return attemptedItems(fixture.tenantId(), fixture.runId(), attempt);
    }

    protected List<UUID> attemptedItems(
            long tenantId, UUID runId, int attempt) {
        return jdbc.query("""
                SELECT i.public_id
                  FROM sys_automation_item_attempts a
                  JOIN sys_automation_run_items i
                    ON i.tenant_id = a.tenant_id
                   AND i.run_id = a.run_id
                   AND i.run_item_id = a.run_item_id
                 WHERE a.tenant_id = ? AND a.run_id = ?
                   AND a.attempt_ordinal = ?
                 ORDER BY i.public_id
                """, (rs, row) -> rs.getObject("public_id", UUID.class),
                tenantId, runId, attempt);
    }

    protected Map<String, Object> itemEvidence(
            PartialFixture fixture, UUID itemPublicId) {
        return jdbc.queryForMap("""
                SELECT lifecycle_state, effect_state, attempt_count
                  FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                """, fixture.tenantId(), fixture.runId(), itemPublicId);
    }

    protected int checkpointCount(PartialFixture fixture) {
        return checkpointCount(fixture.tenantId(), fixture.runId());
    }

    protected int checkpointCount(long tenantId, UUID runId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_run_checkpoints
                 WHERE tenant_id = ? AND run_id = ?
                """, Integer.class, tenantId, runId);
    }

    protected int itemAttemptCount(
            GoldenFixture fixture, UUID itemPublicId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_item_attempts a
                  JOIN sys_automation_run_items i
                    ON i.tenant_id = a.tenant_id
                   AND i.run_id = a.run_id
                   AND i.run_item_id = a.run_item_id
                 WHERE a.tenant_id = ? AND a.run_id = ? AND i.public_id = ?
                """, Integer.class, fixture.tenantId(), fixture.runId(),
                itemPublicId);
    }

    protected int runAttemptCount(PartialFixture fixture) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_run_attempts
                 WHERE tenant_id = ? AND run_id = ?
                """, Integer.class, fixture.tenantId(), fixture.runId());
    }

    protected Throwable rollbackFailure(Runnable operation) {
        return catchThrowable(() -> transactions.executeWithoutResult(status -> {
            try {
                operation.run();
            } finally {
                status.setRollbackOnly();
            }
        }));
    }

    protected static String selectionDigest(List<UUID> itemIds) {
        String material = itemIds.stream().map(UUID::toString).sorted()
                .collect(java.util.stream.Collectors.joining("\",\"", "[\"", "\"]"));
        return digest(material);
    }

    protected static String digest(String material) {
        return AutomationDomainPolicy.sha256(
                material.getBytes(StandardCharsets.UTF_8));
    }

    protected static OffsetDateTime databaseNow() {
        return jdbc.queryForObject(
                "SELECT statement_timestamp()", OffsetDateTime.class);
    }

    protected static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    protected static PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    protected static final class RecordingHandler
            implements TypedAutomationHandlerPort {
        private final HandlerIdentity identity;
        private final AtomicInteger invocations = new AtomicInteger();

        RecordingHandler(HandlerIdentity identity) {
            this.identity = identity;
        }

        @Override
        public HandlerIdentity identity() {
            return identity;
        }

        @Override
        public List<AutomationItemPlan> materialize(
                AutomationInvocationV1 invocation) {
            throw new UnsupportedOperationException(
                    "materialization is persisted before this evidence boundary");
        }

        @Override
        public List<AutomationRunItemReceiptV1> invoke(
                AutomationInvocationV1 invocation,
                List<AutomationEffectRequest> selectedItems) {
            invocations.incrementAndGet();
            return selectedItems.stream()
                    .map(item -> successfulReceipt(invocation, item))
                    .toList();
        }

        int invocations() {
            return invocations.get();
        }
    }

    protected record GoldenFixture(
            long tenantId,
            UUID definitionId,
            UUID definitionPublicId,
            UUID versionId,
            UUID runId,
            UUID runPublicId,
            UUID actor,
            UUID subject) {
    }

    protected record PartialFixture(
            long tenantId,
            UUID runId,
            UUID runPublicId,
            UUID subject,
            UUID successfulItem,
            UUID retryableItem,
            UUID finalFailedItem) {
    }

    protected record Golden051Checkpoint(
            GoldenFixture fixture,
            UUID successfulItem,
            UUID retryableItem) {
    }

    protected record Golden051Crash(
            Golden051Checkpoint checkpointed,
            LeaseGrant worker) {
    }
}
