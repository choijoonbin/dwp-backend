package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.RunRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Profile;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.RetryMode;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.RunState;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.SelectionMode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared setup and evidence builders for AutomationRequiredDatabaseEvidenceTest. */
abstract class AutomationDatabaseEvidenceFixture {
    protected static final String CLEAN_DATABASE = "sys006_clean_evidence";
    protected static final String UPGRADE_DATABASE = "sys006_upgrade_evidence";
    protected static final String CLEAN_SCHEMA = "public";
    protected static final String UPGRADE_SCHEMA = "public";
    protected static final String APP_ROLE = "sys006_evidence_app";
    protected static final String APP_PASSWORD = "sys006-evidence-password";
    protected static final String DIGEST_A = "a".repeat(64);
    protected static final String DIGEST_B = "b".repeat(64);
    protected static final String DIGEST_C = "c".repeat(64);
    protected static final String DIGEST_D = "d".repeat(64);
    protected static final String DIGEST_E = "e".repeat(64);
    protected static final String DIGEST_F = "f".repeat(64);
    protected static final List<String> AUTOMATION_TABLES = List.of(
            "sys_automation_definitions",
            "sys_automation_versions",
            "sys_automation_schedules",
            "sys_automation_runs",
            "sys_automation_run_attempts",
            "sys_automation_run_items",
            "sys_automation_item_attempts",
            "sys_automation_run_checkpoints");

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    protected static JdbcTemplate cleanJdbc;
    protected static JdbcTemplate upgradeJdbc;
    protected static TransactionTemplate cleanTransactions;
    protected static UUID legacyReceiptPublicId;

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

    protected static void assertSysEvt004AndAuditEvidence(
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

    protected static void assertConstraintRejected(String sql, Object... arguments) {
        assertThatThrownBy(() -> cleanJdbc.update(sql, arguments))
                .isInstanceOf(DataAccessException.class);
    }

    protected static AutomationService service(
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

    protected static Authority catalogAuthority(
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

    protected static SignedManifest activeManifest() {
        AutomationHandlerManifestV1 manifest = new AutomationHandlerManifestV1(
                "hris.hrm.test", "dwp-hrm-server", "AutomationInput.v1",
                "schema:input:v1", "schema:receipt:v1", DIGEST_E,
                List.of("TENANT"), "FAILED_ITEMS_ONLY", 3, "ACTIVE");
        return new SignedManifest(
                manifest, "test-signing-key", DIGEST_A, DIGEST_B, DIGEST_C, 1);
    }

    protected static VerifiedArtifact verified(
            SignedManifest signed, OffsetDateTime freshUntil) {
        return new VerifiedArtifact(
                signed, "registry-r1", DIGEST_D, "compatibility-v1",
                DIGEST_E, DIGEST_F, freshUntil);
    }

    protected static UUID insertLegacyReceipt(JdbcTemplate jdbc) {
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

    protected static void seedCompleteTenantGraph(
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

    protected static AtomicFixture seedAtomicFixture(JdbcTemplate jdbc, long tenantId) {
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

    protected static void insertVersion(
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

    protected static void provisionRlsApplicationRole() {
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

    protected static String currentVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT version
                  FROM flyway_schema_history
                 WHERE success AND version IS NOT NULL
                 ORDER BY installed_rank DESC
                 LIMIT 1
                """, String.class);
    }

    protected static Flyway flyway(
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

    protected static PGSimpleDataSource dataSource(
            String database, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(new String[]{POSTGRES.getHost()});
        source.setPortNumbers(new int[]{POSTGRES.getFirstMappedPort()});
        source.setDatabaseName(database);
        source.setUser(username);
        source.setPassword(password);
        return source;
    }

    protected static UUID uuid(String material) {
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    protected record AtomicFixture(
            RunRecord run,
            UUID subjectPrincipalPublicId) {
    }

    protected static final class InjectedRollback extends RuntimeException {
    }
}
