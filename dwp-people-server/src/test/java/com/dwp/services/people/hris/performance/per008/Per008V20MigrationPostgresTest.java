package com.dwp.services.people.hris.performance.per008;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class Per008V20MigrationPostgresTest {

    private static final String SCHEMA = "hris_performance";
    private static final String HISTORY = "flyway_performance_schema_history";
    private static final List<String> CREATED_TABLES = List.of(
            "prf_feedback_requests",
            "prf_feedback_assignments",
            "prf_feedback_responses",
            "prf_feedback_release_snapshots");
    private static final List<String> HARDENED_TABLES = List.of(
            "prf_cycles",
            "prf_cycle_versions",
            "prf_command_receipts",
            "prf_outbox_events");
    private static final List<String> PROTECTED_TABLES = List.of(
            "prf_feedback_requests",
            "prf_feedback_assignments",
            "prf_feedback_responses",
            "prf_feedback_release_snapshots",
            "prf_cycles",
            "prf_cycle_versions",
            "prf_command_receipts",
            "prf_outbox_events");
    private static final long TENANT_A = 8_081L;
    private static final long TENANT_B = 8_082L;
    private static final String HEX_A = "a".repeat(64);
    private static final String HEX_B = "b".repeat(64);
    private static final String HEX_C = "c".repeat(64);
    private static final Instant OPENED = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant CLOSED = Instant.parse("2026-11-01T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private final List<String> fixtureRoles = new ArrayList<>();

    @AfterEach
    void dropFixtureRoles() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        for (String role : fixtureRoles) {
            jdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA hris_performance "
                    + "REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM " + role);
            jdbc.execute("DROP OWNED BY " + role);
            jdbc.execute("DROP ROLE IF EXISTS " + role);
        }
        fixtureRoles.clear();
    }

    @Test
    void cleanV1ToV20CreatesOnlyTheSealedOwnerTablesAndForcesAllEightTenantPolicies() {
        DataSource source = dataSource();
        reset(source);
        prepareSharedRlsRuntimeRole(new JdbcTemplate(source));

        assertThat(flyway(source, null).migrate().migrationsExecuted).isPositive();
        JdbcTemplate jdbc = new JdbcTemplate(source);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.flyway_performance_schema_history
                 WHERE version = '20' AND success
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT tablename
                  FROM pg_catalog.pg_tables
                 WHERE schemaname = 'hris_performance'
                   AND tablename LIKE 'prf_feedback_%'
                 ORDER BY tablename
                """, String.class)).containsExactlyElementsOf(CREATED_TABLES.stream().sorted().toList());
        for (String table : CREATED_TABLES) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hris_performance." + table,
                    Integer.class)).isZero();
        }
        assertThat(jdbc.queryForList("""
                SELECT relname
                  FROM pg_catalog.pg_class
                 WHERE relnamespace = 'hris_performance'::regnamespace
                   AND relkind = 'r'
                   AND relname IN (%s)
                   AND relrowsecurity
                   AND relforcerowsecurity
                 ORDER BY relname
                """.formatted(sqlNames(PROTECTED_TABLES)), String.class))
                .containsExactlyElementsOf(PROTECTED_TABLES.stream().sorted().toList());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_constraint constraint_record
                  JOIN pg_catalog.pg_class relation_record
                    ON relation_record.oid = constraint_record.conrelid
                 WHERE relation_record.relnamespace = 'hris_performance'::regnamespace
                   AND relation_record.relname IN (%s)
                   AND NOT constraint_record.convalidated
                """.formatted(sqlNames(PROTECTED_TABLES)), Integer.class)).isZero();

        List<Map<String, Object>> policies = jdbc.queryForList("""
                SELECT tablename, qual, with_check
                  FROM pg_catalog.pg_policies
                 WHERE schemaname = 'hris_performance'
                   AND tablename IN (%s)
                 ORDER BY tablename
                """.formatted(sqlNames(PROTECTED_TABLES)));
        assertThat(policies).hasSize(8);
        for (Map<String, Object> policy : policies) {
            assertThat(policy.get("qual")).isEqualTo(policy.get("with_check"));
            assertThat(String.valueOf(policy.get("qual")))
                    .contains("current_setting")
                    .contains("dwp.tenant_id");
        }
        for (String table : PROTECTED_TABLES) {
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM information_schema.table_privileges
                     WHERE grantee = 'PUBLIC'
                       AND table_schema = 'hris_performance'
                       AND table_name = ?
                       AND privilege_type IN ('SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE')
                    """, Integer.class, table)).isZero();
        }
    }

    @Test
    void v19ToV20PreservesEveryPriorChecksumAndEveryReusedV1RowWithoutSeedOrBackfill() {
        DataSource source = dataSource();
        reset(source);
        prepareSharedRlsRuntimeRole(new JdbcTemplate(source));
        flyway(source, "19").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Map<String, Integer> checksums = historyChecksums(jdbc);
        BaseRows rows = baseRows(TENANT_A, "upgrade");
        insertBaseRows(jdbc, rows);

        assertThat(flyway(source, null).migrate().migrationsExecuted).isEqualTo(1);

        assertThat(historyChecksums(jdbc)).containsAllEntriesOf(checksums);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_cycles
                 WHERE tenant_id = ? AND cycle_id = ?
                """, Integer.class, rows.tenantId(), rows.cycleId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_cycle_versions
                 WHERE tenant_id = ? AND cycle_version_id = ?
                """, Integer.class, rows.tenantId(), rows.cycleVersionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, Integer.class, rows.tenantId(), rows.receiptId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = ? AND event_id = ?
                """, Integer.class, rows.tenantId(), rows.eventId())).isEqualTo(1);
        for (String table : CREATED_TABLES) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hris_performance." + table,
                    Integer.class)).isZero();
        }
    }

    @Test
    void nonBypassRoleAndDatabaseGuardsDenyMissingCrossTenantAndForgedEvidenceAndRollbackLifecycle()
            throws Exception {
        DataSource source = dataSource();
        reset(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        String role = prepareSharedRlsRuntimeRole(jdbc);
        flyway(source, null).migrate();
        seedProtectedRowsForBothTenants(source);

        assertSafeRuntimeRoleAndAcl(jdbc, role);
        for (String table : PROTECTED_TABLES) {
            assertThat(countAs(source, role, table, null)).isZero();
            assertThat(countAs(source, role, table, TENANT_A)).isEqualTo(1);
            assertThat(countAs(source, role, table, TENANT_B)).isEqualTo(1);
        }
        assertThat(updateAs(source, role, TENANT_A, """
                UPDATE hris_performance.prf_feedback_requests
                   SET updated_at = updated_at
                 WHERE tenant_id = 8082
                """)).isZero();

        verifyInitialRequestInsertGuard(source, jdbc);
        verifyCreateCommitAtomicity(source, jdbc);
        verifyResponseReceiptAndWindowGuards(source, jdbc);
        verifyThresholdAndLifecycleRollback(source, jdbc);
        verifyAssignmentTerminalAndCancellationBinding(source, jdbc);
    }

    private static void verifyInitialRequestInsertGuard(
            DataSource source, JdbcTemplate jdbc) throws Exception {
        RequestInsertFixture directOpen = requestInsertFixture(8_092L, "direct-open");
        RequestInsertFixture nonInitialVersion = requestInsertFixture(8_093L, "version-two");
        RequestInsertFixture priorReceipt = requestInsertFixture(8_094L, "prior-receipt");
        RequestInsertFixture mismatchedDigest = requestInsertFixture(
                8_095L, "mismatched-receipt-digest");
        withReplica(source, scoped -> {
            seedRequestAuthority(scoped, directOpen);
            seedRequestAuthority(scoped, nonInitialVersion);
            seedRequestAuthority(scoped, priorReceipt);
            seedRequestAuthority(scoped, mismatchedDigest, HEX_C);
        });

        UUID directOpenReceipt = id("direct-open-lifecycle-receipt");
        UUID priorLifecycleReceipt = id("prior-lifecycle-receipt");
        insertSucceededReceipt(jdbc, directOpen.tenantId(), directOpenReceipt,
                "owner.people.performance-feedback.lifecycle.v1:open",
                id(directOpen.requestId() + "-principal"), directOpen.requestId(),
                1, 2, directOpen.requestId(), OPENED);
        insertSucceededReceipt(jdbc, priorReceipt.tenantId(), priorLifecycleReceipt,
                "owner.people.performance-feedback.lifecycle.v1:open",
                id(priorReceipt.requestId() + "-principal"), priorReceipt.requestId(),
                1, 2, priorReceipt.requestId(), OPENED);

        assertThatThrownBy(() -> insertRequest(
                jdbc, directOpen.tenantId(), directOpen.requestId(), 1, "OPEN",
                directOpen.createReceiptId(), directOpenReceipt, null, null,
                1, OPENED, CLOSED))
                .hasMessageContaining("PER008_FROZEN_AUTHORITY_INVALID");
        assertThat(requestCount(jdbc, directOpen)).isZero();

        assertThatThrownBy(() -> insertRequest(
                jdbc, nonInitialVersion.tenantId(), nonInitialVersion.requestId(),
                2, "DRAFT", nonInitialVersion.createReceiptId(), null, null, null,
                1, OPENED, CLOSED))
                .hasMessageContaining("PER008_FROZEN_AUTHORITY_INVALID");
        assertThat(requestCount(jdbc, nonInitialVersion)).isZero();

        assertThatThrownBy(() -> insertRequest(
                jdbc, priorReceipt.tenantId(), priorReceipt.requestId(), 1, "DRAFT",
                priorReceipt.createReceiptId(), priorLifecycleReceipt, null, null,
                1, OPENED, CLOSED))
                .hasMessageContaining("PER008_FROZEN_AUTHORITY_INVALID");
        assertThat(requestCount(jdbc, priorReceipt)).isZero();

        assertThatThrownBy(() -> insertRequest(
                jdbc, mismatchedDigest.tenantId(), mismatchedDigest.requestId(),
                1, "DRAFT", mismatchedDigest.createReceiptId(), null, null, null,
                1, OPENED, CLOSED))
                .hasMessageContaining("PER008_FROZEN_AUTHORITY_INVALID");
        assertThat(requestCount(jdbc, mismatchedDigest)).isZero();
    }

    private static void verifyCreateCommitAtomicity(
            DataSource source, JdbcTemplate jdbc) throws Exception {
        RequestInsertFixture empty = requestInsertFixture(8_101L, "empty-audience");
        RequestInsertFixture belowThreshold = requestInsertFixture(8_102L, "below-threshold");
        RequestInsertFixture timestampMismatch = requestInsertFixture(8_103L, "timestamp-mismatch");
        RequestInsertFixture valid = requestInsertFixture(8_104L, "valid-create-chain");
        UUID lateWorker = id(valid.requestId() + "-late-worker");
        UUID lateAssignment = id(valid.requestId() + "-late-provider-assignment");
        withReplica(source, scoped -> {
            seedRequestAuthority(scoped, empty);
            seedRequestAuthority(scoped, belowThreshold);
            seedRequestAuthority(scoped, timestampMismatch);
            seedRequestAuthority(scoped, valid);
            seedProjectionMember(scoped, valid, lateWorker, lateAssignment);
        });

        assertThatThrownBy(() -> inTransaction(source, transaction ->
                insertRequest(transaction, empty.tenantId(), empty.requestId(), 1, "DRAFT",
                        empty.createReceiptId(), null, null, null, 1, OPENED, CLOSED)))
                .hasMessageContaining("PER008_CREATE_COMMIT_INCOMPLETE");
        assertThat(requestCount(jdbc, empty)).isZero();

        assertThatThrownBy(() -> inTransaction(source, transaction -> {
            insertRequest(transaction, belowThreshold.tenantId(), belowThreshold.requestId(),
                    1, "DRAFT", belowThreshold.createReceiptId(), null, null, null,
                    2, OPENED, CLOSED);
            insertInitialAssignment(transaction, belowThreshold,
                    id(belowThreshold.requestId() + "-subject-worker"),
                    id(belowThreshold.requestId() + "-subject-assignment"),
                    id("below-threshold-assignment"), null);
            completeCreateReceipt(transaction, belowThreshold);
        })).hasMessageContaining("PER008_RECEIPT_MUTATION_FORBIDDEN");
        assertThat(requestCount(jdbc, belowThreshold)).isZero();

        assertThatThrownBy(() -> inTransaction(source, transaction -> {
            insertRequest(transaction, timestampMismatch.tenantId(),
                    timestampMismatch.requestId(), 1, "DRAFT",
                    timestampMismatch.createReceiptId(), null, null, null,
                    1, OPENED, CLOSED);
            insertInitialAssignment(transaction, timestampMismatch,
                    id(timestampMismatch.requestId() + "-subject-worker"),
                    id(timestampMismatch.requestId() + "-subject-assignment"),
                    id("timestamp-mismatch-assignment"), OPENED);
            completeCreateReceipt(transaction, timestampMismatch);
        })).hasMessageContaining("PER008_CREATE_COMMIT_INCOMPLETE");
        assertThat(requestCount(jdbc, timestampMismatch)).isZero();

        UUID validAssignment = id("valid-create-assignment");
        inTransaction(source, transaction -> {
            insertRequest(transaction, valid.tenantId(), valid.requestId(), 1, "DRAFT",
                    valid.createReceiptId(), null, null, null, 1, OPENED, CLOSED);
            insertInitialAssignment(transaction, valid,
                    id(valid.requestId() + "-subject-worker"),
                    id(valid.requestId() + "-subject-assignment"), validAssignment, null);
            completeCreateReceipt(transaction, valid);
        });
        assertThat(requestCount(jdbc, valid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_feedback_assignments
                 WHERE tenant_id = ? AND feedback_request_public_id = ?
                """, Integer.class, valid.tenantId(), valid.requestId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT receipt_state FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, String.class, valid.tenantId(), valid.createReceiptId()))
                .isEqualTo("SUCCEEDED");

        assertThatThrownBy(() -> insertInitialAssignment(jdbc, valid, lateWorker,
                lateAssignment, id("late-create-assignment"), null))
                .hasMessageContaining("PER008_ASSIGNMENT_CREATE_BINDING_INVALID");
    }

    private static void seedRequestAuthority(
            JdbcTemplate jdbc, RequestInsertFixture fixture) {
        seedRequestAuthority(jdbc, fixture, HEX_B);
    }

    private static void seedRequestAuthority(
            JdbcTemplate jdbc,
            RequestInsertFixture fixture,
            String receiptPopulationScopeDigest) {
        UUID requestId = fixture.requestId();
        long tenantId = fixture.tenantId();
        UUID cycleId = id(requestId + "-cycle");
        UUID cycleVersionId = id(requestId + "-cycle-version");
        UUID projectionHeadId = id(requestId + "-projection");
        UUID artifactId = id(requestId + "-artifact");
        UUID configurationId = id(requestId + "-configuration");
        UUID preparationReceipt = id(requestId + "-preparation-receipt");
        UUID finalizationReceipt = id(requestId + "-finalization-receipt");
        UUID platformReceipt = id(requestId + "-platform-receipt");
        Instant snapshotAsOf = OPENED.minusSeconds(60);

        jdbc.update("""
                INSERT INTO hris_performance.prf_cycles (
                    cycle_id, tenant_id, cycle_key, display_name, lifecycle_state,
                    aggregate_version, retention_policy_id, created_by, updated_by)
                VALUES (?, ?, ?, 'PER-008 request authority', 'DRAFT', 0, ?, 1, 1)
                """, cycleId, tenantId, "request-authority-" + tenantId,
                id(requestId + "-retention"));
        jdbc.update("""
                INSERT INTO hris_performance.prf_cycle_versions (
                    cycle_version_id, tenant_id, cycle_id, version_no, version_state,
                    aggregate_version, effective_from, timezone_id, policy_version_id,
                    content_hash, authored_by)
                VALUES (?, ?, ?, 1, 'DRAFT', 0, ?, 'UTC', ?, ?, 1)
                """, cycleVersionId, tenantId, cycleId, Timestamp.from(OPENED),
                id(requestId + "-cycle-policy"), HEX_A);
        jdbc.update("""
                INSERT INTO hris_performance.prf_workforce_projection_heads (
                    projection_head_id, tenant_id, snapshot_as_of, purpose_code,
                    projection_code, projection_state, current_local_revision,
                    current_owner_revision, current_content_hash)
                VALUES (?, ?, ?, 'HRIS_WORKFORCE_SNAPSHOT_BOOTSTRAP',
                        'PERFORMANCE_V1', 'READY', 1, 1, ?)
                """, projectionHeadId, tenantId, Timestamp.from(snapshotAsOf), HEX_B);
        jdbc.update("""
                INSERT INTO hris_performance.prf_workforce_projection_revisions (
                    projection_revision_id, tenant_id, projection_head_id,
                    local_revision, owner_revision, snapshot_as_of, purpose_code,
                    projection_code, owner_page_digest, content_hash, member_count)
                VALUES (?, ?, ?, 1, 1, ?, 'HRIS_WORKFORCE_SNAPSHOT_BOOTSTRAP',
                        'PERFORMANCE_V1', ?, ?, 1)
                """, id(requestId + "-projection-revision"), tenantId,
                projectionHeadId, Timestamp.from(snapshotAsOf), HEX_A, HEX_B);
        jdbc.update("""
                INSERT INTO hris_performance.prf_workforce_projection_members (
                    projection_member_id, tenant_id, projection_head_id, local_revision,
                    worker_public_id, assignment_public_id, workforce_status,
                    organization_public_id)
                VALUES (?, ?, ?, 1, ?, ?, 'ACTIVE', ?)
                """, id(requestId + "-projection-member"), tenantId, projectionHeadId,
                id(requestId + "-subject-worker"),
                id(requestId + "-subject-assignment"),
                id(requestId + "-organization"));
        jdbc.update("""
                INSERT INTO hris_performance.prf_per_artifact_versions (
                    tenant_id, public_id, artifact_type, schema_version,
                    artifact_revision, owner_aggregate_type,
                    owner_aggregate_public_id, canonical_content_digest,
                    encrypted_content, encryption_key_version_public_id,
                    effective_from, effective_to, created_at, created_by)
                VALUES (?, ?, 'PERFORMANCE_REVIEW_FORM', 1, 1,
                        'PERFORMANCE_CONFIGURATION_VERSION', ?, ?, ?, ?,
                        NULL, NULL, CURRENT_TIMESTAMP, ?)
                """, tenantId, artifactId, configurationId, HEX_A, new byte[29],
                id(requestId + "-artifact-key"), id(requestId + "-artifact-creator"));
        String payloadReference = "ref_per_artifact_" + artifactId;
        jdbc.update("""
                INSERT INTO hris_performance.prf_per004_artifact_preparations (
                    tenant_id, configuration_id, artifact_public_id, artifact_revision,
                    create_subject_principal_public_id, originating_action,
                    create_idempotency_key, create_request_digest, schema_key,
                    schema_version, payload_reference, payload_digest, scope_digest,
                    preparation_receipt_id, status, finalization_receipt_id,
                    platform_create_receipt_id, platform_version, resolved_at)
                VALUES (?, ?, ?, 1, ?, 'PER008_TEST', ?, ?,
                        'dwp.hris.performance.review-form.v1', 1, ?, ?, ?, ?,
                        'FINALIZED', ?, ?, 1, CURRENT_TIMESTAMP)
                """, tenantId, configurationId, artifactId,
                id(requestId + "-principal"), "prepare-" + requestId, HEX_C,
                payloadReference, HEX_A, HEX_B, preparationReceipt,
                finalizationReceipt, platformReceipt);
        jdbc.update("""
                INSERT INTO hris_performance.prf_per004_configuration_version_bindings (
                    tenant_id, configuration_id, platform_version, artifact_public_id,
                    schema_key, schema_version, payload_reference, payload_digest,
                    scope_digest, preparation_receipt_id, finalization_receipt_id,
                    platform_create_receipt_id)
                VALUES (?, ?, 1, ?, 'dwp.hris.performance.review-form.v1', 1,
                        ?, ?, ?, ?, ?, ?)
                """, tenantId, configurationId, artifactId, payloadReference,
                HEX_A, HEX_B, preparationReceipt, finalizationReceipt, platformReceipt);
        jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state)
                VALUES (?, ?, ?, 'FEEDBACK_REQUEST_CREATE', 'PEP-PER-015', 1, ?, ?, 0,
                        ?, ?, 1, 'PERFORMANCE_FEEDBACK', 1, 'ACCEPTED')
                """, fixture.createReceiptId(), tenantId, "create-" + requestId,
                id(requestId + "-principal"), requestId, HEX_A,
                receiptPopulationScopeDigest);
    }

    private static void seedProjectionMember(
            JdbcTemplate jdbc,
            RequestInsertFixture fixture,
            UUID workerId,
            UUID assignmentId) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_workforce_projection_members (
                    projection_member_id, tenant_id, projection_head_id, local_revision,
                    worker_public_id, assignment_public_id, workforce_status,
                    organization_public_id)
                VALUES (?, ?, ?, 1, ?, ?, 'ACTIVE', ?)
                """, id(fixture.requestId() + "-member-" + assignmentId),
                fixture.tenantId(), id(fixture.requestId() + "-projection"),
                workerId, assignmentId, id(fixture.requestId() + "-organization"));
    }

    private static void insertInitialAssignment(
            JdbcTemplate jdbc,
            RequestInsertFixture fixture,
            UUID providerWorkerId,
            UUID providerAssignmentId,
            UUID assignmentId,
            Instant createdAt) {
        Timestamp timestamp = createdAt == null ? null : Timestamp.from(createdAt);
        jdbc.update("""
                INSERT INTO hris_performance.prf_feedback_assignments (
                    tenant_id, public_id, feedback_request_public_id,
                    provider_worker_public_id, provider_assignment_public_id,
                    workforce_projection_head_id, workforce_local_revision,
                    relationship_type, assignment_state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, 'PEER', 'ASSIGNED',
                        COALESCE(CAST(? AS TIMESTAMPTZ), CURRENT_TIMESTAMP),
                        COALESCE(CAST(? AS TIMESTAMPTZ), CURRENT_TIMESTAMP))
                """, fixture.tenantId(), assignmentId, fixture.requestId(),
                providerWorkerId, providerAssignmentId,
                id(fixture.requestId() + "-projection"), timestamp, timestamp);
    }

    private static void completeCreateReceipt(
            JdbcTemplate jdbc, RequestInsertFixture fixture) {
        assertThat(jdbc.update("""
                UPDATE hris_performance.prf_command_receipts receipt
                   SET applied_aggregate_version = 1,
                       result_ref = ?,
                       receipt_state = 'SUCCEEDED',
                       completed_at = (
                           SELECT request.created_at
                             FROM hris_performance.prf_feedback_requests request
                            WHERE request.tenant_id = receipt.tenant_id
                              AND request.public_id = receipt.aggregate_id)
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, fixture.requestId(), fixture.tenantId(), fixture.createReceiptId()))
                .isEqualTo(1);
    }

    private static int requestCount(JdbcTemplate jdbc, RequestInsertFixture fixture) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_feedback_requests
                 WHERE tenant_id = ? AND public_id = ?
                """, Integer.class, fixture.tenantId(), fixture.requestId());
    }

    private static RequestInsertFixture requestInsertFixture(long tenantId, String key) {
        return new RequestInsertFixture(
                tenantId, id("request-insert-" + key), id("request-create-" + key));
    }

    private static void verifyResponseReceiptAndWindowGuards(
            DataSource source, JdbcTemplate jdbc) throws Exception {
        UUID requestId = id("response-request");
        UUID assignmentId = id("response-assignment");
        UUID createReceipt = id("response-create-receipt");
        UUID openReceipt = id("response-open-receipt");
        UUID forgedReceipt = id("response-forged-receipt");
        UUID forgedPrincipalReceipt = id("response-forged-principal-receipt");
        UUID lateReceipt = id("response-late-receipt");
        UUID validReceipt = id("response-valid-receipt");
        UUID providerWorker = id(assignmentId + "-provider-worker");
        Instant submittedAt = Instant.parse("2026-10-15T12:00:00Z");
        Instant lateAt = Instant.parse("2026-11-01T00:00:00Z");

        withReplica(source, scoped -> {
            insertRequest(scoped, TENANT_A, requestId, 2, "OPEN",
                    createReceipt, openReceipt, null, null, 1, OPENED, CLOSED);
            insertAssignment(scoped, TENANT_A, requestId, assignmentId,
                    "ASSIGNED", null);
        });
        withReplica(source, scoped -> {
            insertSucceededReceipt(scoped, TENANT_A, forgedReceipt,
                    "PEP-PER-015", providerWorker,
                    requestId, 2, 2, assignmentId, submittedAt);
            insertSucceededReceipt(scoped, TENANT_A, forgedPrincipalReceipt,
                    "PEP-PER-022", id("not-the-assignment-provider"),
                    requestId, 2, 2, assignmentId, submittedAt);
            insertSucceededReceipt(scoped, TENANT_A, lateReceipt,
                    "PEP-PER-022", providerWorker,
                    requestId, 2, 2, assignmentId, lateAt);
        });

        assertThatThrownBy(() -> insertResponse(jdbc, TENANT_A, requestId,
                assignmentId, forgedReceipt, submittedAt, id("forged-response")))
                .hasMessageContaining("PER008_RESPONSE_BINDING_INVALID");
        assertThatThrownBy(() -> insertResponse(jdbc, TENANT_A, requestId,
                assignmentId, forgedPrincipalReceipt, submittedAt,
                id("forged-principal-response")))
                .hasMessageContaining("PER008_RESPONSE_BINDING_INVALID");
        assertThatThrownBy(() -> insertResponse(jdbc, TENANT_A, requestId,
                assignmentId, lateReceipt, lateAt, id("late-response")))
                .hasMessageContaining("PER008_RESPONSE_BINDING_INVALID");

        withReplica(source, scoped -> scoped.update("""
                DELETE FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND command_receipt_id IN (?, ?, ?)
                """, TENANT_A, forgedReceipt, forgedPrincipalReceipt, lateReceipt));

        UUID partialReceipt = id("partial-submit-receipt");
        assertThatThrownBy(() -> inTransaction(source, transaction -> {
            insertSucceededReceipt(transaction, TENANT_A, partialReceipt,
                    "PEP-PER-022", providerWorker,
                    requestId, 2, 2, assignmentId, submittedAt);
            insertResponse(transaction, TENANT_A, requestId, assignmentId,
                    partialReceipt, submittedAt, id("partial-response"));
            submitAssignment(transaction, TENANT_A, assignmentId, submittedAt);
        })).hasMessageContaining("PER008_SUBMISSION_COMMIT_INCOMPLETE");

        UUID extraPayloadReceipt = id("extra-payload-submit-receipt");
        assertThatThrownBy(() -> inTransaction(source, transaction -> {
            insertSucceededReceipt(transaction, TENANT_A, extraPayloadReceipt,
                    "PEP-PER-022", providerWorker,
                    requestId, 2, 2, assignmentId, submittedAt);
            insertResponse(transaction, TENANT_A, requestId, assignmentId,
                    extraPayloadReceipt, submittedAt, id("extra-payload-response"));
            submitAssignment(transaction, TENANT_A, assignmentId, submittedAt);
            insertSubmissionEvent(transaction, TENANT_A,
                    id("extra-payload-submission-event"), extraPayloadReceipt,
                    requestId, assignmentId, 2, submittedAt, true);
        })).hasMessageContaining("PER008_SUBMISSION_COMMIT_INCOMPLETE");

        UUID validResponse = id("valid-response");
        UUID validEvent = id("valid-submission-event");
        inTransaction(source, transaction -> {
            insertSucceededReceipt(transaction, TENANT_A, validReceipt,
                    "PEP-PER-022", providerWorker,
                    requestId, 2, 2, assignmentId, submittedAt);
            insertResponse(transaction, TENANT_A, requestId, assignmentId,
                    validReceipt, submittedAt, validResponse);
            submitAssignment(transaction, TENANT_A, assignmentId, submittedAt);
            insertSubmissionEvent(transaction, TENANT_A, validEvent, validReceipt,
                    requestId, assignmentId, 2, submittedAt, false);
        });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_feedback_responses
                 WHERE tenant_id = ? AND feedback_assignment_public_id = ?
                """, Integer.class, TENANT_A, assignmentId)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE hris_performance.prf_command_receipts
                   SET request_hash = ?
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, HEX_C, TENANT_A, validReceipt))
                .hasMessageContaining("PER008_RECEIPT_IDENTITY_OR_INTENT_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET payload = payload || '{"extra":true}'::jsonb
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_A, validEvent))
                .hasMessageContaining("PER008_OUTBOX_IDENTITY_OR_PAYLOAD_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, TENANT_A, validReceipt))
                .hasMessageContaining("PER008_RECEIPT_EVIDENCE_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_A, validEvent))
                .hasMessageContaining("PER008_OUTBOX_EVIDENCE_IMMUTABLE");
        assertThat(jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET delivery_state = 'DELIVERED', delivery_attempts = 1
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_A, validEvent)).isEqualTo(1);
    }

    private static void verifyThresholdAndLifecycleRollback(
            DataSource source, JdbcTemplate jdbc) throws Exception {
        UUID requestId = id("aggregate-request");
        UUID firstAssignment = id("aggregate-assignment-1");
        UUID secondAssignment = id("aggregate-assignment-2");
        UUID createReceipt = id("aggregate-create-receipt");
        UUID openReceipt = id("aggregate-open-receipt");
        UUID closeReceipt = id("aggregate-close-receipt");
        UUID aggregateReceipt = id("aggregate-receipt");
        Instant aggregatedAt = Instant.parse("2026-11-02T00:00:00Z");

        withReplica(source, scoped -> {
            insertRequest(scoped, TENANT_A, requestId, 3, "CLOSED",
                    createReceipt, openReceipt, closeReceipt, null, 2, OPENED, CLOSED);
            insertAssignment(scoped, TENANT_A, requestId, firstAssignment,
                    "SUBMITTED", Instant.parse("2026-10-10T00:00:00Z"));
            insertAssignment(scoped, TENANT_A, requestId, secondAssignment,
                    "SUBMITTED", Instant.parse("2026-10-11T00:00:00Z"));
            insertResponse(scoped, TENANT_A, requestId, firstAssignment,
                    id("aggregate-submit-receipt-1"),
                    Instant.parse("2026-10-10T00:00:00Z"), id("aggregate-response-1"));
        });
        insertSucceededReceipt(jdbc, TENANT_A, aggregateReceipt,
                "owner.people.performance-feedback.lifecycle.v1:aggregate",
                requestId, 3, 4, requestId, aggregatedAt);

        String oneResponseDigest = sourceSetDigest(jdbc, TENANT_A, requestId);
        assertThatThrownBy(() -> insertSnapshot(jdbc, TENANT_A, requestId,
                aggregateReceipt, 1, oneResponseDigest,
                "PerformanceFeedbackAggregate.v1/mean-text-v1",
                aggregatedAt, id("below-threshold-snapshot")))
                .hasMessageContaining("PER008_SNAPSHOT_BINDING_INVALID");

        withReplica(source, scoped -> insertResponse(scoped, TENANT_A, requestId,
                secondAssignment, id("aggregate-submit-receipt-2"),
                Instant.parse("2026-10-11T00:00:00Z"), id("aggregate-response-2")));
        String exactSourceDigest = sourceSetDigest(jdbc, TENANT_A, requestId);
        assertThatThrownBy(() -> insertSnapshot(jdbc, TENANT_A, requestId,
                aggregateReceipt, 1, exactSourceDigest,
                "PerformanceFeedbackAggregate.v1/mean-text-v1",
                aggregatedAt, id("forged-count-snapshot")))
                .hasMessageContaining("PER008_SNAPSHOT_BINDING_INVALID");

        assertThatThrownBy(() -> insertSnapshot(jdbc, TENANT_A, requestId,
                aggregateReceipt, 2, oneResponseDigest,
                "PerformanceFeedbackAggregate.v1/mean-text-v1",
                aggregatedAt, id("row-substitution-snapshot")))
                .hasMessageContaining("PER008_SNAPSHOT_BINDING_INVALID");
        assertThatThrownBy(() -> insertSnapshot(jdbc, TENANT_A, requestId,
                aggregateReceipt, 2, exactSourceDigest,
                "PerformanceFeedbackAggregate.v1/forged",
                aggregatedAt, id("forged-algorithm-snapshot")))
                .hasMessageContaining("PER008_SNAPSHOT_BINDING_INVALID");

        UUID wrongPurposeReceipt = id("aggregate-wrong-purpose-receipt");
        withReplica(source, scoped -> {
            insertSucceededReceipt(scoped, TENANT_A, wrongPurposeReceipt,
                    "owner.people.performance-feedback.lifecycle.v1:aggregate",
                    requestId, 3, 4, requestId, aggregatedAt);
            scoped.update("""
                    UPDATE hris_performance.prf_command_receipts
                       SET command_type = 'PERFORMANCE_FEEDBACK',
                           purpose_code = 'PERFORMANCE_FEEDBACK'
                     WHERE tenant_id = ? AND command_receipt_id = ?
                    """, TENANT_A, wrongPurposeReceipt);
        });
        assertThatThrownBy(() -> insertSnapshot(jdbc, TENANT_A, requestId,
                wrongPurposeReceipt, 2, exactSourceDigest,
                "PerformanceFeedbackAggregate.v1/mean-text-v1",
                aggregatedAt, id("wrong-purpose-snapshot")))
                .hasMessageContaining("PER008_SNAPSHOT_BINDING_INVALID");

        assertThatThrownBy(() -> insertSnapshot(jdbc, TENANT_A, requestId,
                aggregateReceipt, 2, exactSourceDigest,
                "PerformanceFeedbackAggregate.v1/mean-text-v1",
                aggregatedAt, id("orphan-closed-snapshot")))
                .hasMessageContaining("PER008_ORPHAN_SNAPSHOT_COMMIT_FORBIDDEN");

        UUID validSnapshot = id("valid-aggregate-snapshot");
        inTransaction(source, transaction -> {
            insertSnapshot(transaction, TENANT_A, requestId, aggregateReceipt,
                    2, exactSourceDigest,
                    "PerformanceFeedbackAggregate.v1/mean-text-v1",
                    aggregatedAt, validSnapshot);
            assertThat(transaction.update("""
                    UPDATE hris_performance.prf_feedback_requests
                       SET request_state = 'AGGREGATED', aggregate_version = 4,
                           aggregation_command_receipt_id = ?, aggregated_at = ?, updated_at = ?
                     WHERE tenant_id = ? AND public_id = ?
                    """, aggregateReceipt, Timestamp.from(aggregatedAt),
                    Timestamp.from(aggregatedAt), TENANT_A, requestId)).isEqualTo(1);
        });

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_feedback_release_snapshots
                 WHERE tenant_id = ? AND feedback_request_public_id = ?
                """, Integer.class, TENANT_A, requestId)).isEqualTo(1);
        Map<String, Object> state = jdbc.queryForMap("""
                SELECT request_state, aggregate_version
                  FROM hris_performance.prf_feedback_requests
                 WHERE tenant_id = ? AND public_id = ?
                """, TENANT_A, requestId);
        assertThat(state.get("request_state")).isEqualTo("AGGREGATED");
        assertThat(((Number) state.get("aggregate_version")).longValue()).isEqualTo(4L);
    }

    private static void verifyAssignmentTerminalAndCancellationBinding(
            DataSource source, JdbcTemplate jdbc) throws Exception {
        long tenantId = 8_111L;
        UUID requestId = id("cancel-request");
        UUID assignmentId = id("cancel-assignment");
        Instant cancelledAt = Instant.parse("2026-10-20T00:00:00Z");
        withReplica(source, scoped -> {
            insertRequest(scoped, tenantId, requestId, 1, "DRAFT",
                    id("cancel-create-receipt"), null, null, null,
                    1, OPENED, CLOSED);
            insertAssignment(scoped, tenantId, requestId, assignmentId,
                    "ASSIGNED", null);
        });

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE hris_performance.prf_feedback_assignments
                   SET assignment_state = 'DECLINED', declined_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND public_id = ?
                """, Timestamp.from(cancelledAt), Timestamp.from(cancelledAt),
                tenantId, assignmentId))
                .hasMessageContaining("PER008_ASSIGNMENT_IMMUTABLE_OR_ILLEGAL_TRANSITION");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE hris_performance.prf_feedback_assignments
                   SET assignment_state = 'CANCELLED', cancelled_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND public_id = ?
                """, Timestamp.from(cancelledAt), Timestamp.from(cancelledAt),
                tenantId, assignmentId))
                .hasMessageContaining("PER008_ASSIGNMENT_IMMUTABLE_OR_ILLEGAL_TRANSITION");

        UUID wrongPurposeReceipt = id("cancel-wrong-purpose-receipt");
        withReplica(source, scoped -> {
            insertSucceededReceipt(scoped, tenantId, wrongPurposeReceipt,
                    "owner.people.performance-feedback.lifecycle.v1:cancel",
                    requestId, 1, 2, requestId, cancelledAt);
            scoped.update("""
                    UPDATE hris_performance.prf_command_receipts
                       SET command_type = 'PERFORMANCE_FEEDBACK',
                           purpose_code = 'PERFORMANCE_FEEDBACK'
                     WHERE tenant_id = ? AND command_receipt_id = ?
                    """, tenantId, wrongPurposeReceipt);
        });
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE hris_performance.prf_feedback_assignments
                   SET assignment_state = 'CANCELLED', cancelled_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND public_id = ?
                """, Timestamp.from(cancelledAt), Timestamp.from(cancelledAt),
                tenantId, assignmentId))
                .hasMessageContaining("PER008_ASSIGNMENT_IMMUTABLE_OR_ILLEGAL_TRANSITION");

        UUID cancelReceipt = id("cancel-valid-receipt");
        insertSucceededReceipt(jdbc, tenantId, cancelReceipt,
                "owner.people.performance-feedback.lifecycle.v1:cancel",
                requestId, 1, 2, requestId, cancelledAt);
        inTransaction(source, transaction -> {
            assertThat(transaction.update("""
                    UPDATE hris_performance.prf_feedback_assignments
                       SET assignment_state = 'CANCELLED', cancelled_at = ?, updated_at = ?
                     WHERE tenant_id = ? AND public_id = ?
                    """, Timestamp.from(cancelledAt), Timestamp.from(cancelledAt),
                    tenantId, assignmentId)).isEqualTo(1);
            assertThat(transaction.update("""
                    UPDATE hris_performance.prf_feedback_requests
                       SET request_state = 'CANCELLED', aggregate_version = 2,
                           cancel_command_receipt_id = ?, cancelled_at = ?, updated_at = ?
                     WHERE tenant_id = ? AND public_id = ?
                    """, cancelReceipt, Timestamp.from(cancelledAt),
                    Timestamp.from(cancelledAt), tenantId, requestId)).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("""
                SELECT assignment_state
                  FROM hris_performance.prf_feedback_assignments
                 WHERE tenant_id = ? AND public_id = ?
                """, String.class, tenantId, assignmentId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("""
                SELECT request_state FROM hris_performance.prf_feedback_requests
                 WHERE tenant_id = ? AND public_id = ?
                """, String.class, tenantId, requestId)).isEqualTo("CANCELLED");
    }

    private static void seedProtectedRowsForBothTenants(DataSource source) throws Exception {
        withReplica(source, jdbc -> {
            for (long tenantId : List.of(TENANT_A, TENANT_B)) {
                BaseRows base = baseRows(tenantId, "rls");
                insertBaseRows(jdbc, base);
                UUID request = id("rls-request-" + tenantId);
                UUID assignment = id("rls-assignment-" + tenantId);
                UUID response = id("rls-response-" + tenantId);
                UUID snapshot = id("rls-snapshot-" + tenantId);
                insertRequest(jdbc, tenantId, request, 1, "DRAFT",
                        base.receiptId(), null, null, null, 1, OPENED, CLOSED);
                insertAssignment(jdbc, tenantId, request, assignment, "ASSIGNED", null);
                insertResponse(jdbc, tenantId, request, assignment,
                        base.receiptId(), OPENED.plusSeconds(1), response);
                insertSnapshot(jdbc, tenantId, request, base.receiptId(),
                        1, CLOSED.plusSeconds(1), snapshot);
            }
        });
    }

    private static void insertBaseRows(JdbcTemplate jdbc, BaseRows rows) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_cycles (
                    cycle_id, tenant_id, cycle_key, display_name, lifecycle_state,
                    aggregate_version, retention_policy_id, created_by, updated_by)
                VALUES (?, ?, ?, 'PER-008 migration fixture', 'DRAFT', 0, ?, 1, 1)
                """, rows.cycleId(), rows.tenantId(), rows.key(), id(rows.key() + "-retention"));
        jdbc.update("""
                INSERT INTO hris_performance.prf_cycle_versions (
                    cycle_version_id, tenant_id, cycle_id, version_no, version_state,
                    aggregate_version, effective_from, timezone_id, policy_version_id,
                    content_hash, authored_by)
                VALUES (?, ?, ?, 1, 'DRAFT', 0, ?, 'UTC', ?, ?, 1)
                """, rows.cycleVersionId(), rows.tenantId(), rows.cycleId(),
                Timestamp.from(OPENED), id(rows.key() + "-policy"), HEX_A);
        jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state)
                VALUES (?, ?, ?, 'PER008_TEST', 'PER008_TEST', 1, ?, ?, 0,
                        ?, ?, 1, 'PERFORMANCE_FEEDBACK', 1, 'ACCEPTED')
                """, rows.receiptId(), rows.tenantId(), rows.key() + "-receipt",
                id(rows.key() + "-principal"), rows.cycleId(), HEX_A, HEX_B);
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type,
                    aggregate_id, aggregate_version, event_type, payload)
                VALUES (?, ?, ?, 'PER008_TEST', ?, 1, 'PER008.Test.v1', '{}'::jsonb)
                """, rows.eventId(), rows.tenantId(), rows.receiptId(), rows.cycleId());
    }

    private static void insertRequest(
            JdbcTemplate jdbc,
            long tenantId,
            UUID requestId,
            long aggregateVersion,
            String state,
            UUID createReceipt,
            UUID openReceipt,
            UUID closeReceipt,
            UUID aggregationReceipt,
            int effectiveThreshold,
            Instant opensAt,
            Instant closesAt) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_feedback_requests (
                    tenant_id, public_id, aggregate_version, cycle_version_id,
                    subject_worker_public_id, subject_assignment_public_id,
                    workforce_projection_head_id, workforce_local_revision,
                    workforce_owner_revision, workforce_snapshot_as_of,
                    workforce_owner_page_digest, workforce_content_hash,
                    template_artifact_public_id, template_configuration_id,
                    template_platform_version, template_schema_key,
                    template_payload_digest, template_scope_digest, template_content_hash,
                    request_title, authorization_principal_public_id,
                    create_command_receipt_id, open_command_receipt_id,
                    close_command_receipt_id, aggregation_command_receipt_id,
                    request_state, anonymity_mode, requested_minimum_release_count,
                    privacy_minimum_release_count, effective_minimum_release_count,
                    privacy_policy_public_id, threshold_policy_revision,
                    threshold_policy_digest, audience_policy_public_id,
                    audience_policy_revision, audience_policy_digest, opens_at, closes_at,
                    audience_scope_digest, execution_binding_digest,
                    scope_decision_revision, authorization_decision_revision, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?, ?, ?, ?, 1,
                        'dwp.hris.performance.review-form.v1', ?, ?, ?,
                        'PER-008 request', ?, ?, ?, ?, ?, ?, 'IDENTIFIED', ?, ?, ?,
                        ?, 'threshold-v1', ?, ?, 'audience-v1', ?, ?, ?, ?, ?,
                        'scope-v1', 'authorization-v1', ?)
                """,
                tenantId, requestId, aggregateVersion, id(requestId + "-cycle-version"),
                id(requestId + "-subject-worker"), id(requestId + "-subject-assignment"),
                id(requestId + "-projection"), Timestamp.from(opensAt.minusSeconds(60)),
                HEX_A, HEX_B, id(requestId + "-artifact"), id(requestId + "-configuration"),
                HEX_A, HEX_B, HEX_A, id(requestId + "-principal"), createReceipt,
                openReceipt, closeReceipt, aggregationReceipt,
                state, effectiveThreshold, effectiveThreshold, effectiveThreshold,
                id(requestId + "-privacy-policy"), HEX_C,
                id(requestId + "-audience-policy"), HEX_B,
                Timestamp.from(opensAt), Timestamp.from(closesAt), HEX_A, HEX_B,
                id(requestId + "-creator"));
    }

    private static void insertAssignment(
            JdbcTemplate jdbc,
            long tenantId,
            UUID requestId,
            UUID assignmentId,
            String state,
            Instant submittedAt) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_feedback_assignments (
                    tenant_id, public_id, feedback_request_public_id,
                    provider_worker_public_id, provider_assignment_public_id,
                    workforce_projection_head_id, workforce_local_revision,
                    relationship_type, assignment_state, submitted_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, 'PEER', ?, ?)
                """, tenantId, assignmentId, requestId,
                id(assignmentId + "-provider-worker"),
                id(assignmentId + "-provider-assignment"),
                id(requestId + "-projection"), state,
                submittedAt == null ? null : Timestamp.from(submittedAt));
    }

    private static void insertResponse(
            JdbcTemplate jdbc,
            long tenantId,
            UUID requestId,
            UUID assignmentId,
            UUID receiptId,
            Instant submittedAt,
            UUID responseId) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_feedback_responses (
                    tenant_id, public_id, feedback_request_public_id,
                    feedback_assignment_public_id, response_schema_key,
                    template_content_hash, submit_command_receipt_id,
                    encryption_envelope_version, encryption_algorithm, encrypted_content,
                    encryption_nonce, authentication_tag, encryption_key_version_public_id,
                    canonical_content_hmac, digest_key_version_public_id, submitted_at)
                VALUES (?, ?, ?, ?, 'PerformanceFeedbackResponse.v1', ?, ?, 1,
                        'AES_256_GCM', ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, responseId, requestId, assignmentId, HEX_A, receiptId,
                new byte[29], new byte[12], new byte[16], id(responseId + "-key"),
                HEX_B, id(responseId + "-digest-key"), Timestamp.from(submittedAt));
    }

    private static void submitAssignment(
            JdbcTemplate jdbc, long tenantId, UUID assignmentId, Instant submittedAt) {
        assertThat(jdbc.update("""
                UPDATE hris_performance.prf_feedback_assignments
                   SET assignment_state = 'SUBMITTED', submitted_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND public_id = ?
                """, Timestamp.from(submittedAt), Timestamp.from(submittedAt),
                tenantId, assignmentId)).isEqualTo(1);
    }

    private static void insertSubmissionEvent(
            JdbcTemplate jdbc,
            long tenantId,
            UUID eventId,
            UUID receiptId,
            UUID requestId,
            UUID assignmentId,
            long aggregateVersion,
            Instant submittedAt,
            boolean addForbiddenField) {
        String payload = addForbiddenField
                ? "jsonb_build_object('feedbackRequestId', ?::text, "
                + "'feedbackAssignmentId', ?::text, "
                + "'submittedAt', ?::timestamptz, 'extra', true)"
                : "jsonb_build_object('feedbackRequestId', ?::text, "
                + "'feedbackAssignmentId', ?::text, 'submittedAt', ?::timestamptz)";
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type,
                    aggregate_id, aggregate_version, event_type, payload)
                VALUES (?, ?, ?, 'PERFORMANCE_FEEDBACK_ASSIGNMENT', ?, ?,
                        'PerformanceFeedbackSubmitted.v1', %s)
                """.formatted(payload), eventId, tenantId, receiptId, assignmentId,
                aggregateVersion, requestId.toString(), assignmentId.toString(),
                Timestamp.from(submittedAt));
    }

    private static void insertSnapshot(
            JdbcTemplate jdbc,
            long tenantId,
            UUID requestId,
            UUID receiptId,
            int submittedCount,
            Instant aggregatedAt,
            UUID snapshotId) {
        insertSnapshot(jdbc, tenantId, requestId, receiptId, submittedCount,
                HEX_A, "PerformanceFeedbackAggregate.v1/mean-text-v1",
                aggregatedAt, snapshotId);
    }

    private static void insertSnapshot(
            JdbcTemplate jdbc,
            long tenantId,
            UUID requestId,
            UUID receiptId,
            int submittedCount,
            String sourceSetDigest,
            String algorithm,
            Instant aggregatedAt,
            UUID snapshotId) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_feedback_release_snapshots (
                    tenant_id, public_id, feedback_request_public_id,
                    aggregate_schema_key, threshold_policy_revision,
                    privacy_policy_public_id, threshold_policy_digest,
                    distinct_submitted_count, source_set_digest,
                    aggregation_algorithm_version, aggregation_command_receipt_id,
                    encryption_envelope_version, encryption_algorithm, encrypted_content,
                    encryption_nonce, authentication_tag, encryption_key_version_public_id,
                    canonical_content_hmac, digest_key_version_public_id, aggregated_at)
                VALUES (?, ?, ?, 'PerformanceFeedbackAggregate.v1', 'threshold-v1',
                        ?, ?, ?, ?, ?, ?, 1, 'AES_256_GCM', ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, snapshotId, requestId, id(requestId + "-privacy-policy"),
                HEX_C, submittedCount, sourceSetDigest, algorithm, receiptId,
                new byte[29], new byte[12],
                new byte[16], id(snapshotId + "-key"), HEX_B,
                id(snapshotId + "-digest-key"), Timestamp.from(aggregatedAt));
    }

    private static String sourceSetDigest(
            JdbcTemplate jdbc, long tenantId, UUID requestId) {
        return jdbc.queryForObject("""
                SELECT encode(sha256(convert_to(COALESCE(string_agg(
                           response.public_id::TEXT || ':'
                           || response.feedback_assignment_public_id::TEXT || ':'
                           || response.template_content_hash || ':'
                           || response.canonical_content_hmac || ':'
                           || response.digest_key_version_public_id::TEXT,
                           '|' ORDER BY response.public_id::TEXT), ''), 'UTF8')), 'hex')
                  FROM hris_performance.prf_feedback_assignments assignment
                  JOIN hris_performance.prf_feedback_responses response
                    ON response.tenant_id = assignment.tenant_id
                   AND response.feedback_request_public_id
                       = assignment.feedback_request_public_id
                   AND response.feedback_assignment_public_id = assignment.public_id
                 WHERE assignment.tenant_id = ?
                   AND assignment.feedback_request_public_id = ?
                   AND assignment.assignment_state = 'SUBMITTED'
                """, String.class, tenantId, requestId);
    }

    private static void insertSucceededReceipt(
            JdbcTemplate jdbc,
            long tenantId,
            UUID receiptId,
            String action,
            UUID subjectPrincipal,
            UUID aggregateId,
            long expectedVersion,
            long appliedVersion,
            UUID resultRef,
            Instant completedAt) {
        boolean lifecycle = action.startsWith(
                "owner.people.performance-feedback.lifecycle.v1:");
        String commandType = lifecycle ? "FEEDBACK_REQUEST_LIFECYCLE"
                : "PEP-PER-015".equals(action) ? "FEEDBACK_REQUEST_CREATE"
                : "PEP-PER-022".equals(action) ? "FEEDBACK_RESPONSE_SUBMIT"
                : "PERFORMANCE_FEEDBACK";
        String purpose = lifecycle
                ? "PERFORMANCE_FEEDBACK_LIFECYCLE" : "PERFORMANCE_FEEDBACK";
        jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, applied_aggregate_version,
                    request_hash, population_scope_digest, field_policy_revision,
                    purpose_code, authorization_revision, receipt_state, result_ref,
                    completed_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 1,
                        ?, 1, 'SUCCEEDED', ?, ?)
                """, receiptId, tenantId, "per008-" + receiptId, commandType, action,
                subjectPrincipal, aggregateId, expectedVersion, appliedVersion,
                HEX_A, HEX_B, purpose, resultRef, Timestamp.from(completedAt));
    }

    private static void insertSucceededReceipt(
            JdbcTemplate jdbc,
            long tenantId,
            UUID receiptId,
            String action,
            UUID aggregateId,
            long expectedVersion,
            long appliedVersion,
            UUID resultRef,
            Instant completedAt) {
        insertSucceededReceipt(jdbc, tenantId, receiptId, action,
                id(receiptId + "-principal"), aggregateId, expectedVersion,
                appliedVersion, resultRef, completedAt);
    }

    private String prepareSharedRlsRuntimeRole(JdbcTemplate jdbc) {
        String role = "per008_runtime_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12);
        jdbc.execute("CREATE ROLE " + role
                + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA hris_performance TO " + role);
        jdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA hris_performance "
                + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + role);
        fixtureRoles.add(role);
        return role;
    }

    private static void assertSafeRuntimeRoleAndAcl(JdbcTemplate jdbc, String role) {
        Map<String, Object> attributes = jdbc.queryForMap("""
                SELECT rolsuper, rolcreatedb, rolcreaterole, rolinherit, rolbypassrls
                  FROM pg_catalog.pg_roles WHERE rolname = ?
                """, role);
        assertThat(attributes.values()).containsOnly(false);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_class relation_record
                  JOIN pg_catalog.pg_namespace namespace_record
                    ON namespace_record.oid = relation_record.relnamespace
                  JOIN pg_catalog.pg_roles owner_role
                    ON owner_role.oid = relation_record.relowner
                 WHERE namespace_record.nspname = 'hris_performance'
                   AND relation_record.relname IN (%s)
                   AND owner_role.rolname = ?
                """.formatted(sqlNames(PROTECTED_TABLES)), Integer.class, role))
                .isZero();
        for (String table : PROTECTED_TABLES) {
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'SELECT')",
                    Boolean.class, role, qualified(table))).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'TRUNCATE')",
                    Boolean.class, role, qualified(table))).isFalse();
        }
        for (String table : CREATED_TABLES) {
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'INSERT')",
                    Boolean.class, role, qualified(table))).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'DELETE') "
                            + "OR has_table_privilege(?, ?, 'TRUNCATE')",
                    Boolean.class, role, qualified(table), role, qualified(table))).isFalse();
        }
        for (String table : List.of(
                "prf_feedback_responses", "prf_feedback_release_snapshots")) {
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'UPDATE')",
                    Boolean.class, role, qualified(table))).isFalse();
        }
        for (String table : List.of(
                "prf_feedback_requests", "prf_feedback_assignments")) {
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'UPDATE')",
                    Boolean.class, role, qualified(table))).isTrue();
        }
        for (String table : HARDENED_TABLES) {
            assertThat(jdbc.queryForObject("""
                    SELECT has_table_privilege(?, ?, 'INSERT')
                       AND has_table_privilege(?, ?, 'UPDATE')
                       AND has_table_privilege(?, ?, 'DELETE')
                    """, Boolean.class,
                    role, qualified(table), role, qualified(table),
                    role, qualified(table))).isTrue();
        }
    }

    private static int countAs(
            DataSource source, String role, String table, Long tenantId) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE " + role);
            }
            if (tenantId != null) {
                tenant(connection, tenantId);
            }
            try (Statement query = connection.createStatement();
                 var rows = query.executeQuery(
                         "SELECT COUNT(*) FROM hris_performance." + table)) {
                rows.next();
                return rows.getInt(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static int updateAs(
            DataSource source, String role, long tenantId, String sql) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE " + role);
            }
            tenant(connection, tenantId);
            try (Statement update = connection.createStatement()) {
                return update.executeUpdate(sql);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void tenant(Connection connection, long tenantId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('dwp.tenant_id', ?, TRUE)")) {
            statement.setString(1, Long.toString(tenantId));
            statement.execute();
        }
    }

    private static void withReplica(DataSource source, Consumer<JdbcTemplate> work)
            throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = 'replica'");
            }
            JdbcTemplate scoped = new JdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            try {
                work.accept(scoped);
                connection.commit();
            } catch (RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static void inTransaction(DataSource source, Consumer<JdbcTemplate> work)
            throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate transaction = new JdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            try {
                work.accept(transaction);
                connection.commit();
            } catch (RuntimeException | java.sql.SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static Map<String, Integer> historyChecksums(JdbcTemplate jdbc) {
        Map<String, Integer> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT version, checksum
                  FROM hris_performance.flyway_performance_schema_history
                 WHERE success AND version IS NOT NULL
                 ORDER BY installed_rank
                """).forEach(row -> result.put(
                String.valueOf(row.get("version")),
                ((Number) row.get("checksum")).intValue()));
        return Map.copyOf(result);
    }

    private static String sqlNames(List<String> values) {
        return values.stream()
                .map(value -> "'" + value + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static BaseRows baseRows(long tenantId, String prefix) {
        String key = prefix + "-" + tenantId;
        return new BaseRows(
                tenantId,
                key,
                id(key + "-cycle"),
                id(key + "-cycle-version"),
                id(key + "-receipt"),
                id(key + "-event"));
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String qualified(String table) {
        return "hris_performance." + table;
    }

    private static void reset(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS hris_performance CASCADE");
        jdbc.execute("CREATE SCHEMA hris_performance");
    }

    private static Flyway flyway(DataSource source, String target) {
        var configuration = Flyway.configure(Per008V20MigrationPostgresTest.class.getClassLoader())
                .dataSource(source)
                .locations("classpath:db/performance-migration")
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .table(HISTORY)
                .validateOnMigrate(true)
                .outOfOrder(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private record BaseRows(
            long tenantId,
            String key,
            UUID cycleId,
            UUID cycleVersionId,
            UUID receiptId,
            UUID eventId) {
    }

    private record RequestInsertFixture(
            long tenantId,
            UUID requestId,
            UUID createReceiptId) {
    }
}
