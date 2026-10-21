package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.LeaseGrant;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.PreparedOriginal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.RuntimeRun;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.RunRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.ScheduleRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Profile;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.RetryMode;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.RunState;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.SelectionMode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Required real-PostgreSQL release evidence for BASE-TFR-SYS-006.
 *
 * <p>The two migration paths use independent schemas. Runtime evidence is then
 * collected only against the schema produced by the clean Platform migration.
 */
@Testcontainers(disabledWithoutDocker = true)
class AutomationRequiredDatabaseEvidenceTest {

    private static final String CLEAN_DATABASE = "sys006_clean_evidence";
    private static final String UPGRADE_DATABASE = "sys006_upgrade_evidence";
    private static final String CLEAN_SCHEMA = "public";
    private static final String UPGRADE_SCHEMA = "public";
    private static final String APP_ROLE = "sys006_evidence_app";
    private static final String APP_PASSWORD = "sys006-evidence-password";
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);
    private static final String DIGEST_D = "d".repeat(64);
    private static final String DIGEST_E = "e".repeat(64);
    private static final String DIGEST_F = "f".repeat(64);
    private static final List<String> AUTOMATION_TABLES = List.of(
            "sys_automation_definitions",
            "sys_automation_versions",
            "sys_automation_schedules",
            "sys_automation_runs",
            "sys_automation_run_attempts",
            "sys_automation_run_items",
            "sys_automation_item_attempts",
            "sys_automation_run_checkpoints");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate cleanJdbc;
    private static JdbcTemplate upgradeJdbc;
    private static TransactionTemplate cleanTransactions;
    private static UUID legacyReceiptPublicId;

    @BeforeAll
    static void migrateBothRequiredPaths() {
        JdbcTemplate admin = new JdbcTemplate(dataSource(
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        admin.execute("CREATE DATABASE " + CLEAN_DATABASE);
        admin.execute("CREATE DATABASE " + UPGRADE_DATABASE);

        PGSimpleDataSource cleanSource = dataSource(
                CLEAN_DATABASE, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway clean = flyway(cleanSource, CLEAN_SCHEMA, "333");
        clean.clean();
        clean.migrate();
        cleanSource.setCurrentSchema(CLEAN_SCHEMA);
        cleanJdbc = new JdbcTemplate(cleanSource);
        cleanTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(cleanSource));

        PGSimpleDataSource upgradeSource = dataSource(
                UPGRADE_DATABASE, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway throughV332 = flyway(upgradeSource, UPGRADE_SCHEMA, "332");
        throughV332.clean();
        throughV332.migrate();
        upgradeSource.setCurrentSchema(UPGRADE_SCHEMA);
        upgradeJdbc = new JdbcTemplate(upgradeSource);
        legacyReceiptPublicId = insertLegacyReceipt(upgradeJdbc);
        flyway(upgradeSource, UPGRADE_SCHEMA, "333").migrate();
    }

    @Test
    void cleanPlatformMigrationReachesV333AndInstallsTheEightTables() {
        assertThat(currentVersion(cleanJdbc)).isEqualTo("333");
        assertThat(cleanJdbc.queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = ?
                   AND table_name LIKE 'sys_automation_%'
                 ORDER BY table_name
                """, String.class, CLEAN_SCHEMA))
                .containsExactlyInAnyOrderElementsOf(AUTOMATION_TABLES);
    }

    @Test
    void realV332ToV333UpgradePreservesExistingSharedReceiptEvidence() {
        assertThat(currentVersion(upgradeJdbc)).isEqualTo("333");
        assertThat(upgradeJdbc.queryForObject("""
                SELECT public_id
                  FROM sys_hris_command_receipts
                 WHERE tenant_id = 73001 AND public_id = ?
                """, UUID.class, legacyReceiptPublicId))
                .isEqualTo(legacyReceiptPublicId);
        assertThat(upgradeJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = ? AND table_name LIKE 'sys_automation_%'
                """, Integer.class, UPGRADE_SCHEMA)).isEqualTo(8);
        assertThat(upgradeJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_trigger
                 WHERE tgrelid = 'sys_hris_command_receipts'::regclass
                   AND tgname = 'trg_sys_automation_shared_receipt'
                   AND NOT tgisinternal
                """, Integer.class)).isOne();
    }

    @Test
    void allEightTablesDenyMissingContextAndHideCrossTenantRows() {
        seedCompleteTenantGraph(cleanJdbc, 73101, "one");
        seedCompleteTenantGraph(cleanJdbc, 73102, "two");
        provisionRlsApplicationRole();

        PGSimpleDataSource appSource = dataSource(
                CLEAN_DATABASE, APP_ROLE, APP_PASSWORD);
        appSource.setCurrentSchema(CLEAN_SCHEMA);
        JdbcTemplate appJdbc = new JdbcTemplate(appSource);
        TransactionTemplate appTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(appSource));

        AUTOMATION_TABLES.forEach(table -> assertThat(appJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class))
                .as("missing tenant context must hide %s", table)
                .isZero());

        appTransactions.executeWithoutResult(ignored -> {
            appJdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', '73101', true)",
                    String.class);
            AUTOMATION_TABLES.forEach(table -> {
                assertThat(appJdbc.queryForList(
                        "SELECT tenant_id FROM " + table, Long.class))
                        .as("only the bound tenant may read %s", table)
                        .containsExactly(73101L);
                assertThat(appJdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table
                                + " WHERE tenant_id = 73102", Integer.class))
                        .as("cross-tenant rows must be hidden in %s", table)
                        .isZero();
            });
        });

        assertThatThrownBy(() -> appTransactions.executeWithoutResult(ignored -> {
            appJdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', '73101', true)",
                    String.class);
            appJdbc.update("""
                    INSERT INTO sys_automation_definitions (
                        tenant_id, automation_key, display_name,
                        owner_domain, author_actor_ref)
                    VALUES (73102, 'cross.tenant.write', 'denied', 'HRM', ?)
                    """, uuid("cross-tenant-author"));
        })).isInstanceOf(DataAccessException.class);
    }

    @Test
    void runCasReceiptSysEvt004AndAuditOutboxRollbackAtomically() {
        AtomicFixture fixture = seedAtomicFixture(cleanJdbc, 73201);
        JdbcAutomationRepository repository = new JdbcAutomationRepository(
                cleanJdbc, new ObjectMapper());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID correlationId = uuid("atomic-correlation");
        CommandSeal seal = new CommandSeal(
                fixture.run().tenantId(), fixture.subjectPrincipalPublicId(),
                "CANCEL_AUTOMATION_RUN", AutomationService.CANCEL_RUN,
                "route:sys006:cancel", "context:sys006:cancel",
                "scope:tenant:73201", "decision:sys006:1", DIGEST_A,
                1L, 1L, "HR_OPERATIONS", "atomic-cancel-key-0001",
                DIGEST_B, correlationId);

        assertThatThrownBy(() -> cleanTransactions.executeWithoutResult(ignored -> {
            repository.bindTenantPrincipal(
                    fixture.run().tenantId(), fixture.subjectPrincipalPublicId());
            var accepted = repository.beginCommand(seal, now);
            RunRecord cancelled = repository.cancelRun(
                    fixture.run(), "required atomic rollback evidence", now);

            assertThat(cancelled.state()).isEqualTo(RunState.CANCELLED);
            assertThat(cancelled.objectRevision()).isEqualTo(2L);
            assertThatThrownBy(() -> repository.cancelRun(
                    fixture.run(), "stale revision must lose", now))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("AUTOMATION_RUN_CAS_CONFLICT");

            repository.appendRunEventAndAudit(
                    seal, cancelled, "CANCEL_ACCEPTED", now);
            repository.completeCommand(
                    seal, accepted.receiptId(), cancelled.publicId(),
                    cancelled.objectRevision(), "automation-run:"
                            + cancelled.publicId(), now);

            assertSysEvt004AndAuditEvidence(
                    cleanJdbc, fixture, correlationId, cancelled);
            throw new InjectedRollback();
        })).isInstanceOf(InjectedRollback.class);

        assertThat(cleanJdbc.queryForMap("""
                SELECT lifecycle_state, object_revision
                  FROM sys_automation_runs
                 WHERE tenant_id = ? AND run_id = ?
                """, fixture.run().tenantId(), fixture.run().runId()))
                .containsEntry("lifecycle_state", "QUEUED")
                .containsEntry("object_revision", 1L);
        assertThat(cleanJdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND correlation_id = ?
                """, Integer.class, fixture.run().tenantId(), correlationId))
                .isZero();
        assertThat(cleanJdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_domain_event_outbox
                 WHERE tenant_id = ? AND correlation_id = ?
                """, Integer.class, fixture.run().tenantId(),
                correlationId.toString())).isZero();
        assertThat(cleanJdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE tenant_id = ? AND payload ->> 'correlationId' = ?
                """, Integer.class, fixture.run().tenantId(),
                correlationId.toString())).isZero();
    }

    @Test
    void scheduleActivationInitializesDeterministicFirstFutureUtcWithoutMovingCursor() {
        long tenantId = 73301;
        UUID definitionId = uuid("activate-definition-id");
        UUID definitionPublicId = uuid("activate-definition-public");
        UUID versionId = uuid("activate-version-id");
        UUID scheduleId = uuid("activate-schedule-id");
        UUID schedulePublicId = uuid("activate-schedule-public");
        UUID author = uuid("activate-author");
        UUID approver = uuid("activate-approver");
        OffsetDateTime now = OffsetDateTime.parse("2026-10-21T00:00:00Z");
        String tzdb = ZoneRulesProvider.getVersions("Asia/Seoul").lastKey();
        cleanJdbc.update("""
                INSERT INTO sys_automation_definitions (
                    definition_id, public_id, tenant_id, automation_key,
                    display_name, owner_domain, lifecycle_state,
                    author_actor_ref, created_at, updated_at)
                VALUES (?, ?, ?, 'activation.first-fire', 'Activation first fire',
                        'HRM', 'DRAFT', ?, ?, ?)
                """, definitionId, definitionPublicId, tenantId, author, now, now);
        insertVersion(cleanJdbc, tenantId, definitionId, versionId, author, now);
        cleanJdbc.update("""
                UPDATE sys_automation_versions SET lifecycle_state = 'ACTIVE'
                 WHERE tenant_id = ? AND version_id = ?
                """, tenantId, versionId);
        cleanJdbc.update("""
                UPDATE sys_automation_definitions
                   SET lifecycle_state = 'ACTIVE', active_version_number = 1
                 WHERE tenant_id = ? AND definition_id = ?
                """, tenantId, definitionId);
        cleanJdbc.update("""
                INSERT INTO sys_automation_schedules (
                    schedule_id, public_id, tenant_id, definition_id, version_id,
                    pinned_version_number, lifecycle_state, schedule_expression,
                    timezone, timezone_database_revision, input_template_ref,
                    input_template_digest, concurrency_key_template,
                    schedule_policy_digest, submitted_by_actor_ref, submitted_at,
                    submitted_subject_revision, submission_receipt_id,
                    submission_evidence_digest, approved_by_actor_ref, approved_at,
                    approved_subject_revision, approval_receipt_id,
                    approval_evidence_digest, approval_step_up_evidence_digest,
                    author_actor_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, 'APPROVED', '0 0 9 * * MON-FRI',
                        'Asia/Seoul', ?, 'template:first-fire', ?,
                        'tenant:{tenantId}', ?, ?, ?, 1, ?, ?, ?, ?, 1, ?, ?, ?,
                        ?, ?, ?)
                """, scheduleId, schedulePublicId, tenantId, definitionId, versionId,
                tzdb, DIGEST_A, DIGEST_B, author, now,
                uuid("activate-submit-receipt"), DIGEST_C, approver, now,
                uuid("activate-approve-receipt"), DIGEST_D, DIGEST_E,
                author, now, now);
        JdbcAutomationRepository repository = new JdbcAutomationRepository(
                cleanJdbc, new ObjectMapper());

        ScheduleRecord changed = cleanTransactions.execute(ignored -> {
            repository.bindTenantPrincipal(tenantId, approver);
            ScheduleRecord locked = repository.lockSchedule(
                    tenantId, schedulePublicId);
            return repository.transitionSchedule(
                    locked, AutomationDtos.ScheduleState.ACTIVE, approver,
                    uuid("activate-command-receipt"), DIGEST_F, DIGEST_E, now);
        });

        assertThat(changed).isNotNull();
        assertThat(changed.state()).isEqualTo(AutomationDtos.ScheduleState.ACTIVE);
        assertThat(changed.nextFireAt()).isEqualTo(
                AutomationDomainPolicy.nextScheduleFire(
                        "0 0 9 * * MON-FRI", "Asia/Seoul", tzdb, now));
        assertThat(changed.nextFireAt()).isAfter(now);
        assertThat(changed.fireCursorRevision()).isZero();
        assertThat(changed.subjectRevision()).isOne();
        assertThat(changed.objectRevision()).isEqualTo(2L);
    }

    @Test
    void terminalReceiptCapturesTheSameLockedRunRevisionAsCancellationCas() {
        long tenantId = 73302;
        seedCompleteTenantGraph(cleanJdbc, tenantId, "snapshot");
        JdbcAutomationRepository repository = new JdbcAutomationRepository(
                cleanJdbc, new ObjectMapper());
        UUID runPublicId = uuid("snapshot-run-public");
        UUID subject = uuid("snapshot-subject");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        RunRecord cancelled = cleanTransactions.execute(ignored -> {
            repository.bindTenantPrincipal(tenantId, subject);
            RunRecord locked = repository.lockRun(tenantId, runPublicId);
            RunRecord changed = repository.cancelRun(
                    locked, "snapshot evidence", now);
            assertThat(changed.objectRevision())
                    .isEqualTo(locked.objectRevision() + 1);
            return changed;
        });

        assertThat(cancelled).isNotNull();
        assertThat(cancelled.objectRevision()).isEqualTo(2L);
        assertThat(cleanJdbc.queryForMap("""
                SELECT receipt_state, run_revision_snapshot
                  FROM sys_automation_item_attempts
                 WHERE tenant_id = ? AND run_id = ?
                """, tenantId, cancelled.runId()))
                .containsEntry("receipt_state", "CANCELLED")
                .containsEntry("run_revision_snapshot", 2L);
    }

    @Test
    void v333EnforcesExactPdx004BoundsAndRejectsFutureReceiptRevision() {
        long tenantId = 73305;
        String discriminator = "pdx004-bounds";
        seedCompleteTenantGraph(cleanJdbc, tenantId, discriminator);
        UUID versionId = uuid(discriminator + "-version-id");
        UUID runId = uuid(discriminator + "-run-id");
        String maximumReference = "r".repeat(512);

        cleanJdbc.update("""
                UPDATE sys_automation_versions
                   SET input_schema_ref = ?, receipt_schema_ref = ?
                 WHERE tenant_id = ? AND version_id = ?
                """, maximumReference, maximumReference, tenantId, versionId);
        cleanJdbc.update("""
                UPDATE sys_automation_runs
                   SET requested_by_actor_ref = ?, input_ref = ?
                 WHERE tenant_id = ? AND run_id = ?
                """, maximumReference, maximumReference, tenantId, runId);

        assertThat(cleanJdbc.queryForMap("""
                SELECT length(input_schema_ref) AS input_schema_length,
                       length(receipt_schema_ref) AS receipt_schema_length
                  FROM sys_automation_versions
                 WHERE tenant_id = ? AND version_id = ?
                """, tenantId, versionId))
                .containsEntry("input_schema_length", 512)
                .containsEntry("receipt_schema_length", 512);
        assertThat(cleanJdbc.queryForMap("""
                SELECT length(requested_by_actor_ref) AS actor_ref_length,
                       length(input_ref) AS input_ref_length
                  FROM sys_automation_runs
                 WHERE tenant_id = ? AND run_id = ?
                """, tenantId, runId))
                .containsEntry("actor_ref_length", 512)
                .containsEntry("input_ref_length", 512);
        assertConstraintRejected("""
                UPDATE sys_automation_versions
                   SET input_schema_ref = ?
                 WHERE tenant_id = ? AND version_id = ?
                """, "r".repeat(513), tenantId, versionId);

        assertConstraintRejected("""
                UPDATE sys_automation_versions
                   SET handler_contract_version = 'AutomationInput1'
                 WHERE tenant_id = ? AND version_id = ?
                """, tenantId, versionId);
        assertConstraintRejected("""
                UPDATE sys_automation_versions
                   SET allowed_scope_types = ARRAY['TENANT', 'TENANT']::TEXT[]
                 WHERE tenant_id = ? AND version_id = ?
                """, tenantId, versionId);
        assertConstraintRejected("""
                UPDATE sys_automation_versions
                   SET allowed_scope_types = ARRAY['tenant']::TEXT[]
                 WHERE tenant_id = ? AND version_id = ?
                """, tenantId, versionId);
        assertConstraintRejected("""
                UPDATE sys_automation_versions
                   SET allowed_scope_types = ARRAY[
                       'GLOBAL', 'TENANT', 'LEGAL_ENTITY', 'ORGANIZATION',
                       'POPULATION', 'SUBJECT', 'DEPARTMENT']::TEXT[]
                 WHERE tenant_id = ? AND version_id = ?
                """, tenantId, versionId);

        assertThatThrownBy(() -> cleanJdbc.update("""
                UPDATE sys_automation_item_attempts
                   SET run_revision_snapshot = 2
                 WHERE tenant_id = ? AND run_id = ?
                """, tenantId, runId))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("SYS006_RECEIPT_RUN_REVISION_FUTURE");
    }

    @Test
    void resultUnknownDeadLetterReconcilesWithoutMutatingTerminalReceipt() {
        long tenantId = 73306;
        String discriminator = "result-unknown";
        UUID runId = uuid(discriminator + "-run-id");
        UUID runPublicId = uuid(discriminator + "-run-public");
        UUID itemPublicId = uuid(discriminator + "-run-item-public");
        UUID workerToken = uuid(discriminator + "-lease-token");
        UUID reconcilerToken = uuid(discriminator + "-reconciler-token");
        seedCompleteTenantGraph(cleanJdbc, tenantId, discriminator);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1)
                .withNano(0);
        cleanJdbc.update("""
                UPDATE sys_automation_item_attempts
                   SET correlation_id = ?
                 WHERE tenant_id = ? AND run_id = ?
                """, uuid(discriminator + "-run-correlation"), tenantId, runId);
        cleanJdbc.update("""
                UPDATE sys_automation_run_items
                   SET compensation_policy_ref = 'compensation:result-unknown',
                       compensation_policy_digest = ?
                 WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                """, DIGEST_F, tenantId, runId, itemPublicId);
        JdbcAutomationRepository repository = new JdbcAutomationRepository(
                cleanJdbc, new ObjectMapper());
        AutomationCanonicalJson canonical = new AutomationCanonicalJson(
                new ObjectMapper());

        cleanTransactions.executeWithoutResult(ignored -> {
            RuntimeRun run = repository.lockRuntimeRun(tenantId, runPublicId);
            LeaseGrant worker = new LeaseGrant(
                    AutomationDomainPolicy.AuthorityKind.WORKER,
                    tenantId, runPublicId, run.attemptNumber(), workerToken,
                    run.workerFenceEpoch(), run.controlFenceEpoch(),
                    run.workerLeasedUntil());
            PreparedOriginal prepared = repository.prepareOriginal(
                    run, worker, DIGEST_A, now);
            var item = prepared.items().getFirst();
            AutomationRunItemReceiptV1 deadLetter =
                    new AutomationRunItemReceiptV1(
                            runPublicId, run.run().objectRevision(),
                            item.itemPublicId(), item.itemSequence(),
                            item.subjectRef(), "DEAD_LETTERED",
                            run.attemptNumber(), item.acceptedAt(),
                            item.acceptedAt().plusSeconds(1),
                            null, null, "PERMANENT_FAILURE", false, null,
                            run.correlationId());
            repository.finalizeOriginal(
                    run, worker, prepared, List.of(deadLetter), now.plusSeconds(1));

            Map<String, Object> terminalBefore = cleanJdbc.queryForMap("""
                    SELECT i.lifecycle_state, i.error_code, i.effect_state,
                           i.compensation_state, a.receipt_state,
                           a.run_revision_snapshot, a.completed_at,
                           a.effect_receipt_digest
                      FROM sys_automation_run_items i
                      JOIN sys_automation_item_attempts a
                        ON a.tenant_id = i.tenant_id
                       AND a.run_id = i.run_id
                       AND a.run_item_id = i.run_item_id
                     WHERE i.tenant_id = ? AND i.run_id = ? AND i.public_id = ?
                    """, tenantId, runId, itemPublicId);
            assertThat(terminalBefore)
                    .containsEntry("lifecycle_state", "DEAD_LETTER")
                    .containsEntry("receipt_state", "DEAD_LETTER")
                    .containsEntry("error_code", "PERMANENT_FAILURE")
                    .containsEntry("effect_state", "RESULT_UNKNOWN")
                    .containsEntry("compensation_state", "NOT_REQUIRED")
                    .containsEntry("run_revision_snapshot",
                            run.run().objectRevision());
            String terminalDigest = terminalBefore.get(
                    "effect_receipt_digest").toString();

            OffsetDateTime reconcileAt = now.plusSeconds(2);
            OffsetDateTime reconcileUntil = reconcileAt.plusMinutes(10);
            cleanJdbc.update("""
                    UPDATE sys_automation_runs
                       SET cancellation_requested_at = ?,
                           cancellation_reason = 'reconcile committed effect',
                           worker_fence_epoch = worker_fence_epoch + 1,
                           worker_revoked_at = ?, worker_lease_token = NULL,
                           worker_leased_until = NULL,
                           worker_lease_fence_snapshot = NULL,
                           worker_control_fence_snapshot = NULL,
                           reconciliation_fence_epoch = 2,
                           reconciliation_lease_token = ?,
                           reconciliation_leased_until = ?,
                           reconciliation_lease_fence_snapshot = 2,
                           reconciliation_control_fence_snapshot = control_fence_epoch
                     WHERE tenant_id = ? AND run_id = ?
                    """, reconcileAt, reconcileAt, reconcilerToken,
                    reconcileUntil, tenantId, runId);
            cleanJdbc.update("""
                    UPDATE sys_automation_run_attempts
                       SET worker_fence_epoch = worker_fence_epoch + 1,
                           worker_lease_revoked_at = ?, worker_lease_token = NULL,
                           worker_leased_until = NULL,
                           worker_lease_fence_epoch_snapshot = NULL,
                           worker_control_fence_epoch_snapshot = NULL,
                           reconciliation_fence_epoch = 2,
                           reconciliation_lease_token = ?,
                           reconciliation_leased_until = ?,
                           reconciliation_lease_fence_epoch_snapshot = 2,
                           reconciliation_control_fence_epoch_snapshot = 1
                     WHERE tenant_id = ? AND run_id = ? AND attempt_number = 1
                    """, reconcileAt, reconcilerToken, reconcileUntil,
                    tenantId, runId);

            RuntimeRun reconciling = repository.lockRuntimeRun(
                    tenantId, runPublicId);
            LeaseGrant reconciler = new LeaseGrant(
                    AutomationDomainPolicy.AuthorityKind.RECONCILER,
                    tenantId, runPublicId, reconciling.attemptNumber(),
                    reconcilerToken, reconciling.reconcilerFenceEpoch(),
                    reconciling.controlFenceEpoch(),
                    reconciling.reconcilerLeasedUntil());
            RuntimeRun reconciled = repository.reconcileOriginal(
                    reconciling, reconciler, itemPublicId,
                    item.effectIdempotencyKey(), item.effectRequestDigest(),
                    new AutomationRuntimePorts.ReconciliationEvidence(
                            "COMMITTED", terminalDigest, DIGEST_E,
                            reconcileAt, reconcileUntil),
                    reconcileAt);

            Map<String, Object> terminalAfter = cleanJdbc.queryForMap("""
                    SELECT i.lifecycle_state, i.error_code, i.effect_state,
                           i.compensation_state, a.receipt_state,
                           a.run_revision_snapshot, a.completed_at,
                           a.effect_receipt_digest,
                           a.effect_trusted_evidence_digest
                      FROM sys_automation_run_items i
                      JOIN sys_automation_item_attempts a
                        ON a.tenant_id = i.tenant_id
                       AND a.run_id = i.run_id
                       AND a.run_item_id = i.run_item_id
                     WHERE i.tenant_id = ? AND i.run_id = ? AND i.public_id = ?
                    """, tenantId, runId, itemPublicId);
            assertThat(terminalAfter)
                    .containsEntry("lifecycle_state", "DEAD_LETTER")
                    .containsEntry("receipt_state", "DEAD_LETTER")
                    .containsEntry("error_code", "PERMANENT_FAILURE")
                    .containsEntry("effect_state", "COMMITTED")
                    .containsEntry("compensation_state", "PENDING")
                    .containsEntry("run_revision_snapshot",
                            terminalBefore.get("run_revision_snapshot"))
                    .containsEntry("completed_at", terminalBefore.get("completed_at"))
                    .containsEntry("effect_receipt_digest", terminalDigest)
                    .containsEntry("effect_trusted_evidence_digest", DIGEST_E);

            AutomationRunItemReceiptV1 reloaded =
                    repository.loadCommittedOriginalReceipt(
                            reconciled, itemPublicId);
            assertThat(reloaded).isEqualTo(deadLetter);
            assertThat(canonical.digest(reloaded)).isEqualTo(terminalDigest);
        });
    }

    @Test
    void committedEvidenceWithoutATerminalReceiptCannotEnterCompensation() {
        long tenantId = 73307;
        String discriminator = "crash-before-terminal";
        UUID runId = uuid(discriminator + "-run-id");
        UUID runPublicId = uuid(discriminator + "-run-public");
        UUID itemPublicId = uuid(discriminator + "-run-item-public");
        UUID workerToken = uuid(discriminator + "-lease-token");
        UUID reconcilerToken = uuid(discriminator + "-reconciler-token");
        seedCompleteTenantGraph(cleanJdbc, tenantId, discriminator);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1)
                .withNano(0);
        cleanJdbc.update("""
                UPDATE sys_automation_item_attempts
                   SET correlation_id = ?
                 WHERE tenant_id = ? AND run_id = ?
                """, uuid(discriminator + "-run-correlation"), tenantId, runId);
        cleanJdbc.update("""
                UPDATE sys_automation_run_items
                   SET compensation_policy_ref = 'compensation:crash-recovery',
                       compensation_policy_digest = ?
                 WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                """, DIGEST_F, tenantId, runId, itemPublicId);
        JdbcAutomationRepository repository = new JdbcAutomationRepository(
                cleanJdbc, new ObjectMapper());

        cleanTransactions.executeWithoutResult(ignored -> {
            RuntimeRun run = repository.lockRuntimeRun(tenantId, runPublicId);
            LeaseGrant worker = new LeaseGrant(
                    AutomationDomainPolicy.AuthorityKind.WORKER,
                    tenantId, runPublicId, run.attemptNumber(), workerToken,
                    run.workerFenceEpoch(), run.controlFenceEpoch(),
                    run.workerLeasedUntil());
            PreparedOriginal prepared = repository.prepareOriginal(
                    run, worker, DIGEST_A, now);
            var item = prepared.items().getFirst();

            OffsetDateTime reconcileAt = now.plusSeconds(2);
            OffsetDateTime reconcileUntil = reconcileAt.plusMinutes(10);
            cleanJdbc.update("""
                    UPDATE sys_automation_runs
                       SET cancellation_requested_at = ?,
                           cancellation_reason = 'crash before terminal receipt',
                           worker_fence_epoch = worker_fence_epoch + 1,
                           worker_revoked_at = ?, worker_lease_token = NULL,
                           worker_leased_until = NULL,
                           worker_lease_fence_snapshot = NULL,
                           worker_control_fence_snapshot = NULL,
                           reconciliation_fence_epoch = 2,
                           reconciliation_lease_token = ?,
                           reconciliation_leased_until = ?,
                           reconciliation_lease_fence_snapshot = 2,
                           reconciliation_control_fence_snapshot = control_fence_epoch
                     WHERE tenant_id = ? AND run_id = ?
                    """, reconcileAt, reconcileAt, reconcilerToken,
                    reconcileUntil, tenantId, runId);
            cleanJdbc.update("""
                    UPDATE sys_automation_run_attempts
                       SET worker_fence_epoch = worker_fence_epoch + 1,
                           worker_lease_revoked_at = ?, worker_lease_token = NULL,
                           worker_leased_until = NULL,
                           worker_lease_fence_epoch_snapshot = NULL,
                           worker_control_fence_epoch_snapshot = NULL,
                           reconciliation_fence_epoch = 2,
                           reconciliation_lease_token = ?,
                           reconciliation_leased_until = ?,
                           reconciliation_lease_fence_epoch_snapshot = 2,
                           reconciliation_control_fence_epoch_snapshot = 1
                     WHERE tenant_id = ? AND run_id = ? AND attempt_number = 1
                    """, reconcileAt, reconcilerToken, reconcileUntil,
                    tenantId, runId);

            RuntimeRun reconciling = repository.lockRuntimeRun(
                    tenantId, runPublicId);
            LeaseGrant reconciler = new LeaseGrant(
                    AutomationDomainPolicy.AuthorityKind.RECONCILER,
                    tenantId, runPublicId, reconciling.attemptNumber(),
                    reconcilerToken, reconciling.reconcilerFenceEpoch(),
                    reconciling.controlFenceEpoch(),
                    reconciling.reconcilerLeasedUntil());
            RuntimeRun reconciled = repository.reconcileOriginal(
                    reconciling, reconciler, itemPublicId,
                    item.effectIdempotencyKey(), item.effectRequestDigest(),
                    new AutomationRuntimePorts.ReconciliationEvidence(
                            "COMMITTED", DIGEST_F, DIGEST_E,
                            reconcileAt, reconcileUntil),
                    reconcileAt);

            assertThat(cleanJdbc.queryForMap("""
                    SELECT i.effect_state, i.compensation_state,
                           i.compensation_effect_state, a.receipt_state,
                           a.completed_at
                      FROM sys_automation_run_items i
                      JOIN sys_automation_item_attempts a
                        ON a.tenant_id = i.tenant_id
                       AND a.run_id = i.run_id
                       AND a.run_item_id = i.run_item_id
                     WHERE i.tenant_id = ? AND i.run_id = ? AND i.public_id = ?
                    """, tenantId, runId, itemPublicId))
                    .containsEntry("effect_state", "COMMITTED")
                    .containsEntry("compensation_state", "NOT_REQUIRED")
                    .containsEntry("compensation_effect_state", "NOT_STARTED")
                    .containsEntry("completed_at", null);
            assertThatThrownBy(() -> repository.loadCommittedOriginalReceipt(
                    reconciled, itemPublicId))
                    .hasMessageContaining(
                            "AUTOMATION_COMPENSATION_ORIGINAL_RECEIPT_MISSING");
            assertThatThrownBy(() -> repository.prepareCompensation(
                    reconciled, reconciler, itemPublicId,
                    "compensation-crash-key", DIGEST_D, reconcileAt))
                    .hasMessageContaining(
                            "AUTOMATION_COMPENSATION_ORIGINAL_RECEIPT_MISSING");
            assertThat(cleanJdbc.queryForMap("""
                    SELECT compensation_state, compensation_effect_state
                      FROM sys_automation_run_items
                     WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                    """, tenantId, runId, itemPublicId))
                    .containsEntry("compensation_state", "NOT_REQUIRED")
                    .containsEntry("compensation_effect_state", "NOT_STARTED");
        });
    }

    @Test
    void catalogActionsAreThePersistedAuthorizationVisibilityAndCurrentManifestIntersection() {
        long tenantId = 73303;
        UUID definitionId = uuid("catalog-definition-id");
        UUID definitionPublicId = uuid("catalog-definition-public");
        UUID versionId = uuid("catalog-version-id");
        UUID subject = uuid("catalog-subject");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        cleanJdbc.update("""
                INSERT INTO sys_automation_definitions (
                    definition_id, public_id, tenant_id, automation_key,
                    display_name, owner_domain, lifecycle_state,
                    author_actor_ref, created_at, updated_at)
                VALUES (?, ?, ?, 'catalog.intersection', 'Catalog intersection',
                        'HRM', 'DRAFT', ?, ?, ?)
                """, definitionId, definitionPublicId, tenantId, subject, now, now);
        insertVersion(cleanJdbc, tenantId, definitionId, versionId, subject, now);
        cleanJdbc.update("""
                UPDATE sys_automation_versions SET lifecycle_state = 'ACTIVE'
                 WHERE tenant_id = ? AND version_id = ?
                """, tenantId, versionId);
        cleanJdbc.update("""
                UPDATE sys_automation_definitions
                   SET lifecycle_state = 'ACTIVE', active_version_number = 1
                 WHERE tenant_id = ? AND definition_id = ?
                """, tenantId, definitionId);
        JdbcAutomationRepository repository = new JdbcAutomationRepository(
                cleanJdbc, new ObjectMapper());
        SignedManifest signed = activeManifest();
        AutomationRuntimePorts.SignedArtifactVerifierPort verifier =
                new AutomationRuntimePorts.SignedArtifactVerifierPort() {
                    @Override
                    public VerifiedArtifact verifyDraft(
                            AutomationDtos.CreateVersionCommand command) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public VerifiedArtifact revalidate(
                            SignedManifest approvedEvidence,
                            String definitionDigest,
                            String executionPolicyDigest,
                            String compatibilityBaselineDigest) {
                        return verified(approvedEvidence, now.plusMinutes(5));
                    }
                };
        Authority authority = catalogAuthority(tenantId, subject, now);
        List<String> fields = List.of(
                "definitionPublicId", "automationKey", "ownerDomain",
                "derivedLifecycleState", "activeVersionNumber", "objectRevision");
        AutomationService current = service(
                repository, identity -> Optional.of(signed), verifier, now);

        AutomationDtos.DefinitionItem visible = (AutomationDtos.DefinitionItem)
                current.catalog(authority, now.plusSeconds(1), fields,
                        "FRESH_ONLY", 20, null, "catalog.intersection",
                        List.of("AUTOMATION_DEFINITION")).items().getFirst();
        assertThat(visible.availableActions()).extracting(
                AutomationDtos.ActionDescriptor::actionKey)
                .containsExactlyInAnyOrder(
                        AutomationService.CREATE_VERSION,
                        AutomationService.CREATE_SCHEDULE,
                        AutomationService.QUEUE_RUN);

        AutomationService stale = service(
                repository, identity -> Optional.empty(), verifier, now);
        AutomationDtos.DefinitionItem staleProjection =
                (AutomationDtos.DefinitionItem) stale.catalog(
                        authority, now.plusSeconds(1), fields, "FRESH_ONLY", 20,
                        null, "catalog.intersection",
                        List.of("AUTOMATION_DEFINITION")).items().getFirst();
        assertThat(staleProjection.availableActions()).extracting(
                AutomationDtos.ActionDescriptor::actionKey)
                .containsExactly(AutomationService.CREATE_VERSION);

        Authority hiddenAuthority = new Authority(
                authority.tenantId(), authority.actorId(),
                authority.subjectPrincipalPublicId(), authority.profiles(),
                authority.routeContractKey(), authority.decisionRevision(),
                authority.revalidateAt(), authority.populationScopeDigest(),
                authority.fieldPolicyRevision(), authority.authorizationRevision(),
                authority.contextKey(), authority.contextScopeKey(),
                authority.correlationId(), authority.purposeCode(),
                authority.idempotencyKey(), authority.expectedObjectRevision(),
                authority.stepUpEvidenceDigest(), Set.of(), Set.of("automationKey"));
        AutomationDtos.DefinitionItem hidden = (AutomationDtos.DefinitionItem)
                current.catalog(hiddenAuthority, now.plusSeconds(1), fields,
                        "FRESH_ONLY", 20, null, "catalog.intersection",
                        List.of("AUTOMATION_DEFINITION")).items().getFirst();
        assertThat(hidden.availableActions()).isEmpty();
    }

    @Test
    void retryActionRequiresExactRetryableSelectionDedupeEvidenceAndCurrentManifest() {
        long tenantId = 73304;
        UUID definitionId = uuid("retry-definition-id");
        UUID definitionPublicId = uuid("retry-definition-public");
        UUID versionId = uuid("retry-version-id");
        UUID runId = uuid("retry-run-id");
        UUID runPublicId = uuid("retry-run-public");
        UUID runAttemptId = uuid("retry-run-attempt-id");
        UUID runItemId = uuid("retry-run-item-id");
        UUID itemPublicId = uuid("retry-run-item-public");
        UUID subject = uuid("retry-subject");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        String selectionDigest = AutomationDomainPolicy.sha256(
                ("[\"" + itemPublicId + "\"]").getBytes(StandardCharsets.UTF_8));
        cleanTransactions.executeWithoutResult(ignored -> {
            cleanJdbc.execute("SET session_replication_role = replica");
            try {
            cleanJdbc.update("""
                    INSERT INTO sys_automation_definitions (
                        definition_id, public_id, tenant_id, automation_key,
                        display_name, owner_domain, lifecycle_state,
                        active_version_number, author_actor_ref, created_at, updated_at)
                    VALUES (?, ?, ?, 'retry.intersection', 'Retry intersection',
                            'HRM', 'ACTIVE', 1, ?, ?, ?)
                    """, definitionId, definitionPublicId, tenantId, subject, now, now);
            insertVersion(cleanJdbc, tenantId, definitionId, versionId, subject, now);
            cleanJdbc.update("""
                    UPDATE sys_automation_versions SET lifecycle_state = 'ACTIVE'
                     WHERE tenant_id = ? AND version_id = ?
                    """, tenantId, versionId);
            cleanJdbc.update("""
                    INSERT INTO sys_automation_runs (
                        run_id, public_id, tenant_id, definition_id, version_id,
                        version_number, lifecycle_state, selection_mode, scope_type,
                        requested_at, requested_by_actor_ref, idempotency_key,
                        correlation_id, input_ref, input_digest, expected_item_count,
                        concurrency_key_digest, retry_mode, max_attempts,
                        current_attempt, item_total, item_succeeded, item_failed,
                        command_receipt_id, created_by_actor_ref, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 1, 'FAILED', 'ALL_ITEMS', 'TENANT', ?, ?,
                            'retry-run-key-0000001', ?, 'input:retry', ?, 1, ?,
                            'FAILED_ITEMS_ONLY', 3, 1, 1, 0, 1, ?, ?, ?, ?)
                    """, runId, runPublicId, tenantId, definitionId, versionId,
                    now, "principal:" + subject, uuid("retry-correlation"),
                    DIGEST_A, DIGEST_B, uuid("retry-command-receipt"),
                    subject, now, now);
            cleanJdbc.update("""
                    INSERT INTO sys_automation_run_attempts (
                        run_attempt_id, tenant_id, run_id, attempt_number,
                        selection_mode, selected_item_count, selected_item_digest,
                        lifecycle_state, started_at, completed_at, created_at)
                    VALUES (?, ?, ?, 1, 'ALL_ITEMS', 1, ?, 'FAILED', ?, ?, ?)
                    """, runAttemptId, tenantId, runId, selectionDigest,
                    now, now, now);
            cleanJdbc.update("""
                    INSERT INTO sys_automation_run_items (
                        run_item_id, public_id, tenant_id, run_id, item_sequence,
                        subject_ref, subject_reference_digest, lifecycle_state,
                        attempt_count, error_code, effect_state,
                        effect_idempotency_key, effect_request_digest, updated_at)
                    VALUES (?, ?, ?, ?, 1, 'subject:retry', ?, 'FAILED_RETRYABLE',
                            1, 'RETRYABLE_FAILURE', 'NO_EFFECT_CONFIRMED',
                            'effect-retry-0001', ?, ?)
                    """, runItemId, itemPublicId, tenantId, runId,
                    DIGEST_C, DIGEST_D, now);
            cleanJdbc.update("""
                    INSERT INTO sys_automation_item_attempts (
                        tenant_id, run_id, run_item_id, attempt_ordinal,
                        run_revision_snapshot, receipt_state, accepted_at,
                        error_code, retryable_snapshot, correlation_id,
                        effect_state, effect_authority_kind,
                        effect_idempotency_key, effect_request_digest,
                        effect_dispatch_authority_fence,
                        effect_dispatch_control_fence, started_at, completed_at,
                        created_at)
                    VALUES (?, ?, ?, 1, 1, 'FAILED_RETRYABLE', ?,
                            'RETRYABLE_FAILURE', TRUE, ?, 'NO_EFFECT_CONFIRMED',
                            'WORKER', 'effect-retry-0001', ?, 1, 1, ?, ?, ?)
                    """, tenantId, runId, runItemId, now,
                    uuid("retry-item-correlation"), DIGEST_D, now, now, now);
            } finally {
                cleanJdbc.execute("SET session_replication_role = origin");
            }
        });
        JdbcAutomationRepository repository = new JdbcAutomationRepository(
                cleanJdbc, new ObjectMapper());
        SignedManifest signed = activeManifest();
        AutomationRuntimePorts.SignedArtifactVerifierPort verifier =
                new AutomationRuntimePorts.SignedArtifactVerifierPort() {
                    @Override
                    public VerifiedArtifact verifyDraft(
                            AutomationDtos.CreateVersionCommand command) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public VerifiedArtifact revalidate(
                            SignedManifest approvedEvidence,
                            String definitionDigest,
                            String executionPolicyDigest,
                            String compatibilityBaselineDigest) {
                        return verified(approvedEvidence, now.plusMinutes(5));
                    }
                };
        Authority authority = catalogAuthority(tenantId, subject, now);
        AutomationService current = service(
                repository, identity -> Optional.of(signed), verifier, now);
        AutomationDtos.RunDetailResponse executable = current.runDetail(
                authority, runPublicId, now.plusSeconds(1), "FRESH_ONLY", 20,
                null, null, null);
        assertThat(executable.retryEligibleItemCount()).isOne();
        assertThat(executable.availableActions()).extracting(
                AutomationDtos.ActionDescriptor::actionKey)
                .containsExactly(AutomationService.RETRY_RUN);

        AutomationService stale = service(
                repository, identity -> Optional.empty(), verifier, now);
        AutomationDtos.RunDetailResponse staleProjection = stale.runDetail(
                authority, runPublicId, now.plusSeconds(1), "FRESH_ONLY", 20,
                null, null, null);
        assertThat(staleProjection.retryEligibleItemCount()).isZero();
        assertThat(staleProjection.availableActions()).isEmpty();
    }

    private static void assertSysEvt004AndAuditEvidence(
            JdbcTemplate jdbc,
            AtomicFixture fixture,
            UUID correlationId,
            RunRecord cancelled) {
        Map<String, Object> event = jdbc.queryForMap("""
                SELECT event_source, event_type, schema_version,
                       aggregate_type, aggregate_id, aggregate_sequence,
                       correlation_id, status,
                       payload ->> 'runPublicId' AS run_public_id,
                       payload ->> 'state' AS run_state,
                       payload ->> 'itemTotal' AS item_total,
                       payload_sha256
                  FROM sys_domain_event_outbox
                 WHERE tenant_id = ? AND correlation_id = ?
                """, fixture.run().tenantId(), correlationId.toString());
        assertThat(event)
                .containsEntry("event_source",
                        "/dwp-platform-server/hris/automation")
                .containsEntry("event_type",
                        "dwp.hris.automation.run.state-changed.v1")
                .containsEntry("schema_version", 1)
                .containsEntry("aggregate_type", "HRIS_AUTOMATION_RUN")
                .containsEntry("aggregate_id", cancelled.publicId().toString())
                .containsEntry("aggregate_sequence", 2L)
                .containsEntry("correlation_id", correlationId.toString())
                .containsEntry("status", "PENDING")
                .containsEntry("run_public_id", cancelled.publicId().toString())
                .containsEntry("run_state", "CANCELLED")
                .containsEntry("item_total", "0")
                .satisfies(row -> assertThat(row.get("payload_sha256").toString())
                        .matches("^[a-f0-9]{64}$"));
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT status, payload ->> 'slice' AS slice,
                       payload ->> 'transition' AS transition,
                       payload ->> 'runPublicId' AS run_public_id,
                       payload ->> 'runRevision' AS run_revision,
                       payload ->> 'state' AS run_state,
                       payload ->> 'correlationId' AS correlation_id,
                       payload ->> 'eventPayloadDigest' AS event_payload_digest
                  FROM sys_audit_outbox
                 WHERE tenant_id = ? AND payload ->> 'correlationId' = ?
                """, fixture.run().tenantId(), correlationId.toString());
        assertThat(audit)
                .containsEntry("status", "PENDING")
                .containsEntry("slice", "SYS-006")
                .containsEntry("transition", "CANCEL_ACCEPTED")
                .containsEntry("run_public_id", cancelled.publicId().toString())
                .containsEntry("run_revision", "2")
                .containsEntry("run_state", "CANCELLED")
                .containsEntry("correlation_id", correlationId.toString())
                .satisfies(row -> assertThat(row.get("event_payload_digest").toString())
                        .matches("^[a-f0-9]{64}$"));
        assertThat(audit.get("event_payload_digest"))
                .isEqualTo(event.get("payload_sha256"));
        assertThat(jdbc.queryForObject("""
                SELECT status FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND correlation_id = ?
                """, String.class, fixture.run().tenantId(), correlationId))
                .isEqualTo("SUCCEEDED");
    }

    private static void assertConstraintRejected(String sql, Object... arguments) {
        assertThatThrownBy(() -> cleanJdbc.update(sql, arguments))
                .isInstanceOf(DataAccessException.class);
    }

    private static AutomationService service(
            AutomationRepository repository,
            AutomationHandlerRegistryPort registry,
            AutomationRuntimePorts.SignedArtifactVerifierPort verifier,
            OffsetDateTime now) {
        ObjectMapper json = new ObjectMapper();
        return new AutomationService(
                repository, registry, verifier,
                new AutomationCursorCodec(
                        json, "sys006-final-evidence-cursor-secret-32"),
                json, Clock.fixed(now.toInstant(), ZoneOffset.UTC));
    }

    private static Authority catalogAuthority(
            long tenantId, UUID subject, OffsetDateTime now) {
        return new Authority(
                tenantId, 73303, subject,
                Set.of(
                        new Profile("CONFIG.HRIS_POLICY:UPDATE",
                                "HRIS_CONFIG_AUTHOR",
                                "HRIS_DUTY_POLICY_AUTHOR"),
                        new Profile("ACTION.WORKFORCE_DATA_OPERATIONS:EXECUTE",
                                "HRIS_INTEGRATION_EXECUTOR",
                                "HRIS_DUTY_INTEGRATION_EXECUTOR")),
                "route.hcm.configuration.automation-catalog.data",
                "psr-" + DIGEST_A, now.plusMinutes(5), DIGEST_B,
                1, 1, "psc-" + DIGEST_C, "tenant:" + tenantId,
                uuid("catalog-correlation"), "HRIS_SYSTEM_ADMIN",
                null, null, null, Set.of(), Set.of());
    }

    private static SignedManifest activeManifest() {
        AutomationHandlerManifestV1 manifest = new AutomationHandlerManifestV1(
                "hris.hrm.test", "dwp-hrm-server", "AutomationInput.v1",
                "schema:input:v1", "schema:receipt:v1", DIGEST_E,
                List.of("TENANT"), "FAILED_ITEMS_ONLY", 3, "ACTIVE");
        return new SignedManifest(
                manifest, "test-signing-key", DIGEST_A, DIGEST_B, DIGEST_C, 1);
    }

    private static VerifiedArtifact verified(
            SignedManifest signed, OffsetDateTime freshUntil) {
        return new VerifiedArtifact(
                signed, "registry-r1", DIGEST_D, "compatibility-v1",
                DIGEST_E, DIGEST_F, freshUntil);
    }

    private static UUID insertLegacyReceipt(JdbcTemplate jdbc) {
        UUID publicId = uuid("v332-preserved-receipt");
        jdbc.update("""
                INSERT INTO sys_hris_command_receipts (
                    public_id, tenant_id, command_type, originating_action,
                    subject_principal_public_id, population_scope_digest,
                    authority_population_scope_digest, field_policy_revision,
                    authority_field_policy_revision, purpose_code,
                    authorization_revision, authority_authorization_revision,
                    origin_route_contract_key, origin_context_key,
                    origin_context_scope_key, origin_decision_revision,
                    idempotency_key, request_digest, status, correlation_id)
                VALUES (?, 73001, 'CREATE_AUTOMATION_DEFINITION', ?, ?, ?, ?,
                        1, 1, 'HR_OPERATIONS', 1, 1, 'route:v332',
                        'context:v332', 'scope:tenant:73001', 'decision:v332',
                        'v332-preserved-key-0001', ?, 'ACCEPTED', ?)
                """, publicId, AutomationService.CREATE_DEFINITION,
                uuid("v332-subject"), DIGEST_A, DIGEST_A, DIGEST_B,
                uuid("v332-correlation"));
        return publicId;
    }

    private static void seedCompleteTenantGraph(
            JdbcTemplate jdbc, long tenantId, String discriminator) {
        UUID definitionId = uuid(discriminator + "-definition-id");
        UUID definitionPublicId = uuid(discriminator + "-definition-public");
        UUID versionId = uuid(discriminator + "-version-id");
        UUID scheduleId = uuid(discriminator + "-schedule-id");
        UUID runId = uuid(discriminator + "-run-id");
        UUID runAttemptId = uuid(discriminator + "-run-attempt-id");
        UUID runItemId = uuid(discriminator + "-run-item-id");
        UUID itemAttemptId = uuid(discriminator + "-item-attempt-id");
        UUID leaseToken = uuid(discriminator + "-lease-token");
        UUID actor = uuid(discriminator + "-actor");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime leasedUntil = now.plusHours(1);

        jdbc.update("""
                INSERT INTO sys_automation_definitions (
                    definition_id, public_id, tenant_id, automation_key,
                    display_name, owner_domain, author_actor_ref,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'HRM', ?, ?, ?)
                """, definitionId, definitionPublicId, tenantId,
                "rls." + discriminator, "RLS " + discriminator,
                actor, now, now);
        insertVersion(jdbc, tenantId, definitionId, versionId, actor, now);
        jdbc.update("""
                INSERT INTO sys_automation_schedules (
                    schedule_id, public_id, tenant_id, definition_id,
                    version_id, pinned_version_number, schedule_expression,
                    timezone, timezone_database_revision, input_template_ref,
                    input_template_digest, concurrency_key_template,
                    schedule_policy_digest, author_actor_ref,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, '0 0 * * *', 'UTC', '2026a',
                        'template:rls', ?, 'tenant:{tenantId}', ?, ?, ?, ?)
                """, scheduleId, uuid(discriminator + "-schedule-public"),
                tenantId, definitionId, versionId, DIGEST_A, DIGEST_B,
                actor, now, now);
        jdbc.update("""
                INSERT INTO sys_automation_runs (
                    run_id, public_id, tenant_id, definition_id, version_id,
                    version_number, lifecycle_state, scope_type, requested_at,
                    requested_by_actor_ref, idempotency_key, correlation_id,
                    input_ref, input_digest, expected_item_count,
                    concurrency_key_digest, retry_mode, max_attempts,
                    current_attempt, item_total, command_receipt_id,
                    worker_fence_epoch, worker_lease_token, worker_leased_until,
                    worker_lease_fence_snapshot, worker_control_fence_snapshot,
                    control_fence_epoch, created_by_actor_ref,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, 'LEASED', 'TENANT', ?, ?, ?, ?,
                        'input:rls', ?, 1, ?, 'FAILED_ITEMS_ONLY', 3, 1, 1, ?,
                        1, ?, ?, 1, 1, 1, ?, ?, ?)
                """, runId, uuid(discriminator + "-run-public"), tenantId,
                definitionId, versionId, now, "principal:" + actor,
                "rls-idempotency-" + discriminator + "-0001",
                uuid(discriminator + "-run-correlation"), DIGEST_A, DIGEST_C,
                uuid(discriminator + "-command-receipt"), leaseToken,
                leasedUntil, actor, now, now);
        jdbc.update("""
                INSERT INTO sys_automation_run_attempts (
                    run_attempt_id, tenant_id, run_id, attempt_number,
                    selection_mode, selected_item_count, selected_item_digest,
                    lifecycle_state, worker_fence_epoch, worker_lease_token,
                    worker_leased_until, worker_lease_fence_epoch_snapshot,
                    worker_control_fence_epoch_snapshot, created_at)
                VALUES (?, ?, ?, 1, 'ALL_ITEMS', 1, ?, 'LEASED',
                        1, ?, ?, 1, 1, ?)
                """, runAttemptId, tenantId, runId, DIGEST_D,
                leaseToken, leasedUntil, now);
        jdbc.update("""
                INSERT INTO sys_automation_run_items (
                    run_item_id, public_id, tenant_id, run_id, item_sequence,
                    subject_ref, subject_reference_digest, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?)
                """, runItemId, uuid(discriminator + "-run-item-public"),
                tenantId, runId, "subject:" + discriminator, DIGEST_E, now);
        jdbc.update("""
                INSERT INTO sys_automation_item_attempts (
                    item_attempt_id, tenant_id, run_id, run_item_id,
                    attempt_ordinal, run_revision_snapshot, correlation_id,
                    accepted_at, created_at)
                VALUES (?, ?, ?, ?, 1, 1, ?, ?, ?)
                """, itemAttemptId, tenantId, runId, runItemId,
                uuid(discriminator + "-item-correlation"), now, now);
        jdbc.update("""
                INSERT INTO sys_automation_run_checkpoints (
                    tenant_id, run_id, run_attempt_id, attempt_number,
                    checkpoint_ordinal, processed_item_count,
                    processed_item_digest, output_digest, writer_authority_kind,
                    authority_fence_epoch, control_fence_epoch, committed_at)
                VALUES (?, ?, ?, 1, 1, 0, ?, ?, 'WORKER', 1, 1, ?)
                """, tenantId, runId, runAttemptId, DIGEST_E, DIGEST_F, now);
    }

    private static AtomicFixture seedAtomicFixture(JdbcTemplate jdbc, long tenantId) {
        String discriminator = "atomic";
        UUID definitionId = uuid(discriminator + "-definition-id");
        UUID definitionPublicId = uuid(discriminator + "-definition-public");
        UUID versionId = uuid(discriminator + "-version-id");
        UUID runId = uuid(discriminator + "-run-id");
        UUID runPublicId = uuid(discriminator + "-run-public");
        UUID actor = uuid(discriminator + "-actor");
        UUID subject = uuid(discriminator + "-subject");
        String concurrencyDigest = DIGEST_F;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        jdbc.update("""
                INSERT INTO sys_automation_definitions (
                    definition_id, public_id, tenant_id, automation_key,
                    display_name, owner_domain, author_actor_ref,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'atomic.rollback', 'Atomic rollback',
                        'HRM', ?, ?, ?)
                """, definitionId, definitionPublicId, tenantId,
                actor, now, now);
        insertVersion(jdbc, tenantId, definitionId, versionId, actor, now);
        jdbc.update("""
                INSERT INTO sys_automation_runs (
                    run_id, public_id, tenant_id, definition_id, version_id,
                    version_number, scope_type, requested_at,
                    requested_by_actor_ref, idempotency_key, correlation_id,
                    input_ref, input_digest, expected_item_count,
                    concurrency_key_digest, retry_mode, max_attempts,
                    current_attempt, item_total, command_receipt_id,
                    created_by_actor_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, 'TENANT', ?, ?,
                        'seed-run-key-0000001', ?, 'input:atomic', ?, 0, ?,
                        'FAILED_ITEMS_ONLY', 3, 1, 0, ?, ?, ?, ?)
                """, runId, runPublicId, tenantId, definitionId, versionId,
                now, "principal:" + actor, uuid("atomic-seed-correlation"),
                DIGEST_A, concurrencyDigest, uuid("atomic-seed-receipt"),
                actor, now, now);
        jdbc.update("""
                INSERT INTO sys_automation_run_attempts (
                    tenant_id, run_id, attempt_number, selection_mode,
                    selected_item_count, selected_item_digest, started_at,
                    created_at)
                VALUES (?, ?, 1, 'ALL_ITEMS', 0, ?, ?, ?)
                """, tenantId, runId, DIGEST_B, now, now);

        RunRecord run = new RunRecord(
                runId, runPublicId, tenantId, definitionId, definitionPublicId,
                versionId, 1L, RunState.QUEUED, SelectionMode.ALL_ITEMS,
                concurrencyDigest, 1, 3, RetryMode.FAILED_ITEMS_ONLY,
                0L, 0L, 0L, null, 1L, null);
        return new AtomicFixture(run, subject);
    }

    private static void insertVersion(
            JdbcTemplate jdbc,
            long tenantId,
            UUID definitionId,
            UUID versionId,
            UUID actor,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO sys_automation_versions (
                    version_id, tenant_id, definition_id, version_number,
                    definition_payload, definition_digest, execution_policy,
                    execution_policy_digest, manual_concurrency_key_template,
                    compensation_policy_digest, manifest_retry_mode,
                    manifest_max_attempts, handler_owner_service, handler_key,
                    handler_contract_version, allowed_scope_types,
                    handler_manifest_state, handler_manifest_revision,
                    handler_manifest_digest, handler_signature_key_id,
                    handler_signature, handler_signature_digest,
                    input_schema_ref, input_schema_digest, receipt_schema_ref,
                    receipt_schema_digest, author_actor_ref,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, '{}'::jsonb, ?, '{}'::jsonb, ?,
                        'tenant:{tenantId}', ?, 'FAILED_ITEMS_ONLY', 3,
                        'dwp-hrm-server', 'hris.hrm.test', 'AutomationInput.v1',
                        ARRAY['TENANT']::TEXT[], 'ACTIVE', 1, ?,
                        'test-signing-key', 'test-signature', ?,
                        'schema:input:v1', ?, 'schema:receipt:v1', ?, ?, ?, ?)
                """, versionId, tenantId, definitionId, DIGEST_A, DIGEST_B,
                DIGEST_C, DIGEST_D, DIGEST_E, DIGEST_A, DIGEST_B,
                actor, now, now);
    }

    private static void provisionRlsApplicationRole() {
        cleanJdbc.execute("""
                DO $role$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles WHERE rolname = 'sys006_evidence_app'
                    ) THEN
                        CREATE ROLE sys006_evidence_app LOGIN PASSWORD
                            'sys006-evidence-password' NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END
                $role$
                """);
        cleanJdbc.execute("GRANT USAGE ON SCHEMA " + CLEAN_SCHEMA
                + " TO " + APP_ROLE);
        cleanJdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                + CLEAN_SCHEMA + " TO " + APP_ROLE);
    }

    private static String currentVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT version
                  FROM flyway_schema_history
                 WHERE success AND version IS NOT NULL
                 ORDER BY installed_rank DESC
                 LIMIT 1
                """, String.class);
    }

    private static Flyway flyway(
            PGSimpleDataSource source, String schema, String target) {
        return Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
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

    private static UUID uuid(String material) {
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private record AtomicFixture(
            RunRecord run,
            UUID subjectPrincipalPublicId) {
    }

    private static final class InjectedRollback extends RuntimeException {
    }
}
