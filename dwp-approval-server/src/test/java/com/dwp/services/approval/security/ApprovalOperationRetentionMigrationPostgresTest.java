package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalOperationRetentionMigrationPostgresTest {
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-01T09:30:00Z");
    private static final Instant ISO_OCCURRED_AT =
            Instant.parse("2026-08-01T09:30:00.123456Z");
    private static final Instant FRACTIONAL_OCCURRED_AT =
            Instant.parse("2026-08-01T09:30:00.654321Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private UUID requestId;

    @BeforeEach
    void migrateToV40() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JdbcTemplate cleanup = new JdbcTemplate(dataSource);
        cleanup.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        cleanup.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        Flyway flyway = flyway(MigrationVersion.fromVersion("40"));
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper().findAndRegisterModules();
        jdbc.execute("SELECT seed_approval_tenant(42)");
        requestId = seedRequest();
    }

    @Test
    void exactLegacySingleAndBatchEvidenceBackfillsAndRemainsImmutable() {
        SeededOperation single = seedOperation(false, "RS_APPROVALS");
        SeededOperation batch = seedOperation(true, "RS_APPROVALS");

        migrateLatest();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_operation_batches batch
                 WHERE batch.audit_event_id IS NOT NULL
                   AND batch.audit_occurred_at=?
                   AND apr_retention_internal.operation_batch_is_exact(batch.operation_id)
                   AND apr_retention_internal.operation_audit_is_exact(batch.operation_id)
                """, Long.class, Timestamp.from(OCCURRED_AT))).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT audit_event_id FROM apr_operation_batches WHERE operation_id=?",
                UUID.class, single.operationId())).isEqualTo(single.auditEventId());
        assertThat(jdbc.queryForObject(
                "SELECT audit_event_id FROM apr_operation_batches WHERE operation_id=?",
                UUID.class, batch.operationId())).isEqualTo(batch.auditEventId());
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE apr_operation_batches SET reason='mutated' WHERE operation_id=?",
                single.operationId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM apr_operation_batches WHERE operation_id=?", single.operationId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void isoOffsetAndFractionalEpochAuditTimesBackfillExactly() {
        SeededOperation iso = seedOperation(false, "RS_APPROVALS");
        SeededOperation fractional = seedOperation(false, "RS_APPROVALS");
        rewriteAuditOccurredAt(
                iso.auditEventId(), "\"2026-08-01T18:30:00.123456+09:00\"");
        rewriteAuditOccurredAt(fractional.auditEventId(), "1785576600.654321");

        migrateLatest();

        assertAuditOccurredAt(iso, ISO_OCCURRED_AT);
        assertAuditOccurredAt(fractional, FRACTIONAL_OCCURRED_AT);
    }

    @Test
    void exactLegacyTaskReassignmentBackfills() {
        SeededOperation operation = seedTaskOperation(requestId, requestId);

        migrateLatest();

        assertThat(jdbc.queryForObject("""
                SELECT apr_retention_internal.operation_batch_is_exact(?)
                   AND apr_retention_internal.operation_audit_is_exact(?)
                """, Boolean.class, operation.operationId(), operation.operationId())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT audit_event_id FROM apr_operation_batches WHERE operation_id=?",
                UUID.class, operation.operationId())).isEqualTo(operation.auditEventId());
    }

    @Test
    void missingStrictAuditCandidateRollsBackTheWholeUpgrade() {
        seedOperation(false, "RS_APPROVALS", false);

        assertMigrationRejected("audit identity is unavailable or ambiguous");
    }

    @Test
    void ambiguousStrictAuditCandidatesRollBackTheWholeUpgrade() {
        SeededOperation operation = seedOperation(false, "RS_APPROVALS");
        recordAudit(
                operation.operationId(),
                "DELIVERY_RETRY",
                "SINGLE",
                operation.targetIds());

        assertMigrationRejected("audit identity is unavailable or ambiguous");
    }

    @Test
    void mismatchedTargetScopeRollsBackTheWholeUpgrade() {
        seedOperation(false, "RS_OTHER");

        assertMigrationRejected("not exactly request-bound");
    }

    @Test
    void crossRequestOutboxTargetRollsBackTheWholeUpgrade() {
        UUID otherRequest = seedRequest();
        seedDeliveryOperation(otherRequest, requestId);

        assertMigrationRejected("not exactly request-bound");
    }

    @Test
    void crossRequestTaskTargetRollsBackTheWholeUpgrade() {
        UUID otherRequest = seedRequest();
        seedTaskOperation(otherRequest, requestId);

        assertMigrationRejected("not exactly request-bound");
    }

    @Test
    void alreadyPreparedRequestRollsBackTheWholeUpgrade() {
        seedOperation(false, "RS_APPROVALS");
        jdbc.update("""
                INSERT INTO apr_record_retention_heads(tenant_id,request_id,state)
                VALUES(42,?,'PREPARED')
                """, requestId);

        assertMigrationRejected("not exactly request-bound");
    }

    private void assertMigrationRejected(String message) {
        assertThatThrownBy(this::migrateLatest)
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining(message);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema='public' AND table_name='apr_operation_batches'
                   AND column_name IN('audit_event_id','audit_occurred_at')
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                 WHERE version='41' AND success
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger
                 WHERE tgrelid='apr_operation_batches'::regclass
                   AND tgname='trg_apr_operation_batches_append_only'
                   AND NOT tgisinternal
                """, Long.class)).isOne();
    }

    private SeededOperation seedOperation(boolean batch, String targetScope) {
        return seedOperation(batch, targetScope, true);
    }

    private SeededOperation seedOperation(
            boolean batch,
            String targetScope,
            boolean includeAudit) {
        List<UUID> targetIds = batch
                ? List.of(operationTarget(targetScope), operationTarget(targetScope))
                : List.of(operationTarget(targetScope));
        return seedOperation(
                "DELIVERY_RETRY",
                "OUTBOX_EVENT",
                targetIds,
                java.util.Collections.nCopies(targetIds.size(), requestId),
                includeAudit);
    }

    private SeededOperation seedDeliveryOperation(UUID targetRequest, UUID itemRequest) {
        return seedOperation(
                "DELIVERY_RETRY",
                "OUTBOX_EVENT",
                List.of(operationTarget(targetRequest, "RS_APPROVALS")),
                List.of(itemRequest),
                true);
    }

    private SeededOperation seedTaskOperation(UUID targetRequest, UUID itemRequest) {
        return seedOperation(
                "TASK_REASSIGN",
                "APPROVAL_TASK",
                List.of(taskTarget(targetRequest)),
                List.of(itemRequest),
                true);
    }

    private SeededOperation seedOperation(
            String operationType,
            String targetType,
            List<UUID> targetIds,
            List<UUID> itemRequestIds,
            boolean includeAudit) {
        UUID operationId = UUID.randomUUID();
        boolean batch = targetIds.size() > 1;
        UUID auditEventId = includeAudit
                ? recordAudit(
                        operationId,
                        operationType,
                        batch ? "BATCH" : "SINGLE",
                        targetIds)
                : null;
        jdbc.update("""
                INSERT INTO apr_operation_batches(
                    operation_id,tenant_id,management_resource_set_key,actor_user_id,
                    actor_person_public_id,route_contract_key,operation_type,command_mode,
                    idempotency_key,request_fingerprint,decision_revision,reason,item_count,
                    result_receipt,broker_observed_at,committed_at,retention_until)
                VALUES(?,42,'RS_APPROVALS',99,?,?,
                    ?,?,?,repeat('a',64),'migration-revision',
                    'Migrate durable operation evidence',?,'{}'::jsonb,?,?,?)
                """, operationId, UUID.randomUUID(),
                "TASK_REASSIGN".equals(operationType)
                        ? "POST /internal/approval/tasks/reassign"
                        : "POST /internal/approval/deliveries/retry",
                operationType, batch ? "BATCH" : "SINGLE",
                "migration-" + operationId, targetIds.size(),
                Timestamp.from(OCCURRED_AT.minusSeconds(1)), Timestamp.from(OCCURRED_AT),
                Timestamp.from(OCCURRED_AT.plusSeconds(2_555L * 86_400)));
        for (int index = 0; index < targetIds.size(); index++) {
            jdbc.update("""
                    INSERT INTO apr_operation_items(
                        operation_id,item_sequence,tenant_id,management_resource_set_key,
                        actor_user_id,target_type,target_id,request_id,expected_version,
                        committed_version,status_before,status_after,authority_subject_user_id,
                        authority_subject_person_public_id,authority_role_code,broker_revision,
                        broker_observed_at,target_fingerprint,committed_at)
                    VALUES(?,?,42,'RS_APPROVALS',99,?,?,?,0,1,?,
                        ?,99,?,'APPROVAL_OPERATOR','migration-revision',?,
                        repeat('b',64),?)
                    """, operationId, index + 1, targetType, targetIds.get(index),
                    itemRequestIds.get(index),
                    "APPROVAL_TASK".equals(targetType) ? "CLAIMED" : "FAILED",
                    "APPROVAL_TASK".equals(targetType) ? "REASSIGNED" : "PENDING",
                    UUID.randomUUID(), Timestamp.from(OCCURRED_AT.minusSeconds(1)),
                    Timestamp.from(OCCURRED_AT));
        }
        return new SeededOperation(operationId, auditEventId, targetIds);
    }

    private UUID recordAudit(
            UUID operationId,
            String operationType,
            String mode,
            List<UUID> targets) {
        return new AuditOutboxRecorder(
                new NamedParameterJdbcTemplate(jdbc), mapper,
                "dwp-approval-server", "migration-test", "test")
                .record(AuditEvent.builder()
                        .tenantId(42L)
                        .occurredAt(OCCURRED_AT)
                        .category("ADMIN_CHANGE")
                        .action("approval.operations."
                                + operationType.toLowerCase(java.util.Locale.ROOT))
                        .outcome("SUCCESS")
                        .severity("HIGH")
                        .actorType("USER")
                        .actorId("99")
                        .actorRoles(List.of("APPROVAL_OPERATOR"))
                        .sourceService("dwp-approval-server")
                        .sourceModule("approval-native-operations")
                        .targetType("APPROVAL_OPERATION_BATCH")
                        .targetId(operationId.toString())
                        .correlationId("migration-" + operationId)
                        .afterState(Map.of(
                                "operation", operationType,
                                "commandMode", mode,
                                "managementResourceSetKey", "RS_APPROVALS",
                                "itemCount", targets.size(),
                                "targetIds", targets.stream().map(UUID::toString).toList()))
                        .retentionClass("EXTENDED")
                        .build());
    }

    private UUID operationTarget(String scope) {
        return operationTarget(requestId, scope);
    }

    private UUID operationTarget(UUID targetRequest, String scope) {
        UUID outboxId = UUID.randomUUID();
        boolean injectLegacyDrift = !"RS_APPROVALS".equals(scope);
        if (injectLegacyDrift) {
            jdbc.execute("ALTER TABLE apr_integration_outbox "
                    + "DISABLE TRIGGER trg_apr_integration_management_scope");
        }
        try {
            jdbc.update("""
                    INSERT INTO apr_integration_outbox(
                        outbox_id,event_id,tenant_id,request_id,event_type,payload,payload_sha256,
                        status,version,event_originator_user_id,assigned_auditor_user_id,
                        recovery_auditor_assignment_state,recovery_auditor_resource_set_key,
                        recovery_auditor_assignment_revision,recovery_auditor_assigned_at,
                        management_resource_set_key)
                    VALUES(?,?,42,?,'approval.request.changed','{}'::jsonb,repeat('c',64),
                        'FAILED',0,100001,300,'ASSIGNED',?,'migration-assignment',
                        CURRENT_TIMESTAMP,?)
                    """, outboxId, UUID.randomUUID(), targetRequest, scope, scope);
        } finally {
            if (injectLegacyDrift) {
                jdbc.execute("ALTER TABLE apr_integration_outbox "
                        + "ENABLE TRIGGER trg_apr_integration_management_scope");
            }
        }
        return outboxId;
    }

    private UUID taskTarget(UUID targetRequest) {
        UUID stepId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_steps(
                    step_id,tenant_id,request_id,step_key,step_name,sequence_number,
                    approval_mode,status)
                VALUES(?,42,?,'MIGRATION_STEP','Migration step',1,'ANY','IN_PROGRESS')
                """, stepId, targetRequest);
        jdbc.update("""
                INSERT INTO apr_tasks(
                    task_id,tenant_id,request_id,step_id,assignee_user_id,status,version)
                VALUES(?,42,?,?,99,'CLAIMED',0)
                """, taskId, targetRequest, stepId);
        return taskId;
    }

    private void rewriteAuditOccurredAt(UUID eventId, String jsonValue) {
        assertThat(jdbc.update("""
                UPDATE sys_audit_outbox
                   SET payload=jsonb_set(payload,'{occurredAt}',CAST(? AS jsonb),false)
                 WHERE event_id=?
                """, jsonValue, eventId)).isOne();
    }

    private void assertAuditOccurredAt(SeededOperation operation, Instant expected) {
        assertThat(jdbc.queryForObject("""
                SELECT audit_occurred_at FROM apr_operation_batches WHERE operation_id=?
                """, Timestamp.class, operation.operationId()).toInstant()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("""
                SELECT apr_retention_internal.operation_audit_is_exact(?)
                """, Boolean.class, operation.operationId())).isTrue();
    }

    private UUID seedRequest() {
        UUID workflowVersion = jdbc.queryForObject("""
                SELECT version.workflow_version_id
                  FROM apr_workflow_versions version
                  JOIN apr_workflow_definitions workflow USING(tenant_id,workflow_id)
                 WHERE workflow.tenant_id=42 AND workflow.workflow_key='CAPEX_PURCHASE'
                   AND version.version_number=workflow.current_version
                """, UUID.class);
        UUID formVersion = jdbc.queryForObject("""
                SELECT version.form_version_id
                  FROM apr_form_versions version
                  JOIN apr_forms form USING(tenant_id,form_id)
                 WHERE form.tenant_id=42 AND form.form_key='CAPEX_PURCHASE_FORM'
                   AND version.version_number=form.current_version
                """, UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_requests(
                    request_id,tenant_id,request_number,workflow_version_id,form_version_id,
                    title,summary,requester_user_id,status,management_resource_set_key)
                VALUES(?,42,?,?,?,'Migration fixture','Migration fixture',99,'IN_REVIEW',
                    'RS_APPROVALS')
                """, id, "MIG-" + id, workflowVersion, formVersion);
        return id;
    }

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private void migrateLatest() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
    }

    private record SeededOperation(
            UUID operationId,
            UUID auditEventId,
            List<UUID> targetIds) {
    }
}
