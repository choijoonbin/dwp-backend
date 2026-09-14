package com.dwp.services.approval.security;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalDecisionEvidenceMigrationPostgresTest {

    private static final UUID WORKFLOW_ID = UUID.fromString(
            "20000000-0000-0000-0000-00000000000a");
    private static final UUID FORM_ID = UUID.fromString(
            "30000000-0000-0000-0000-00000000000a");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUpAtV14() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway beforeDecisionBinding = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("14"))
                .cleanDisabled(false)
                .load();
        beforeDecisionBinding.clean();
        beforeDecisionBinding.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void preservesImmutableDecisionAndDelegationAuthorityEvidence() {
        RuntimeRows rows = seedRuntime("MIGRATION");
        jdbc.update("UPDATE apr_request_payload_versions "
                + "SET created_at = CURRENT_TIMESTAMP - INTERVAL '3 days' "
                + "WHERE request_id = ?", rows.requestId());
        jdbc.update("""
                UPDATE apr_request_payloads
                   SET schema_version = 2, payload = '{"detail":"latest"}'::jsonb,
                       payload_sha256 = ?,
                       updated_at = CURRENT_TIMESTAMP - INTERVAL '2 days'
                 WHERE request_id = ?
                """, "b".repeat(64), rows.requestId());
        jdbc.update("""
                INSERT INTO apr_request_payload_versions (
                    payload_version_id, tenant_id, request_id, revision_number,
                    payload, payload_sha256, change_type, changed_by, created_at)
                VALUES (gen_random_uuid(), 42, ?, 2, '{"detail":"latest"}'::jsonb,
                        ?, 'INFORMATION_RESPONDED', 99,
                        CURRENT_TIMESTAMP - INTERVAL '2 days')
                """, rows.requestId(), "b".repeat(64));
        jdbc.update("""
                UPDATE apr_tasks
                   SET status = 'APPROVED', completed_at = CURRENT_TIMESTAMP - INTERVAL '1 day',
                       delegated_from_user_id = 100, candidate_role = 'APPROVER'
                 WHERE task_id = ?
                """, rows.taskId());

        migrateAll();

        assertThat(jdbc.queryForObject(
                "SELECT decision_payload_revision FROM apr_tasks WHERE task_id = ?",
                Integer.class, rows.taskId())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT decision_payload_sha256 FROM apr_tasks WHERE task_id = ?",
                String.class, rows.taskId())).isEqualTo("b".repeat(64));
        assertThat(jdbc.queryForObject(
                "SELECT delegated_authority_role_code FROM apr_tasks WHERE task_id = ?",
                String.class, rows.taskId())).isEqualTo("APPROVER");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE apr_tasks SET status = 'SUPERSEDED' WHERE task_id = ?", rows.taskId()))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.update("""
                UPDATE apr_tasks
                   SET status = 'SUPERSEDED',
                       decision_invalidated_at = CURRENT_TIMESTAMP,
                       decision_invalidation_reason = 'MATERIAL_INFORMATION_RESPONSE'
                 WHERE task_id = ?
                """, rows.taskId())).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_tasks
                   SET decision_invalidated_at = NULL,
                       decision_invalidation_reason = NULL
                 WHERE task_id = ?
                """, rows.taskId())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void recoversOnlyProvablyPreDecisionLegacyPayloads() {
        RuntimeRows rows = seedRuntime("LEGACY");
        jdbc.update("DELETE FROM apr_request_payload_versions WHERE request_id = ?",
                rows.requestId());
        jdbc.update("""
                UPDATE apr_request_payloads
                   SET updated_at = CURRENT_TIMESTAMP - INTERVAL '2 days'
                 WHERE request_id = ?
                """, rows.requestId());
        jdbc.update("""
                UPDATE apr_tasks
                   SET status = 'APPROVED', completed_at = CURRENT_TIMESTAMP - INTERVAL '1 day'
                 WHERE task_id = ?
                """, rows.taskId());

        migrateAll();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM apr_request_payload_versions
                 WHERE tenant_id = 42 AND request_id = ?
                   AND change_type = 'BASELINE'
                   AND change_reason =
                       'Legacy payload baseline recovered during decision evidence upgrade'
                """, Integer.class, rows.requestId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT decision_payload_revision FROM apr_tasks WHERE task_id = ?",
                Integer.class, rows.taskId())).isEqualTo(1);
    }

    @Test
    void rejectsLegacyPayloadModifiedAfterItsDecision() {
        RuntimeRows rows = seedRuntime("TAMPERED");
        jdbc.update("DELETE FROM apr_request_payload_versions WHERE request_id = ?",
                rows.requestId());
        jdbc.update("""
                UPDATE apr_tasks
                   SET status = 'APPROVED', completed_at = CURRENT_TIMESTAMP - INTERVAL '1 day'
                 WHERE task_id = ?
                """, rows.taskId());
        jdbc.update("UPDATE apr_request_payloads SET updated_at = CURRENT_TIMESTAMP "
                + "WHERE request_id = ?", rows.requestId());

        assertThatThrownBy(this::migrateAll)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining(
                        "Cannot bind existing approval decisions to immutable payload history");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version = '15' AND success
                """, Integer.class)).isZero();
    }

    @Test
    void rejectsImmutablePayloadCapturedOnlyAfterItsDecision() {
        RuntimeRows rows = seedRuntime("LATE_HISTORY");
        jdbc.update("""
                UPDATE apr_tasks
                   SET status = 'APPROVED', completed_at = CURRENT_TIMESTAMP - INTERVAL '1 day'
                 WHERE task_id = ?
                """, rows.taskId());

        assertThatThrownBy(this::migrateAll)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining(
                        "Cannot bind existing approval decisions to immutable payload history");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version = '15' AND success
                """, Integer.class)).isZero();
    }

    private RuntimeRows seedRuntime(String suffix) {
        jdbc.update("INSERT INTO apr_tenants (tenant_id) VALUES (42)");
        jdbc.update("""
                INSERT INTO apr_workflow_definitions (
                    workflow_id, tenant_id, workflow_key, name_ko, name_en,
                    description_ko, description_en, category,
                    management_resource_set_key, created_by, updated_by)
                VALUES (?, 42, 'FLOW_A', 'Flow', 'Flow', 'description', 'description',
                        'GENERAL', 'RS_TEAM_A', 99, 99)
                """, WORKFLOW_ID);
        UUID workflowVersionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_workflow_versions (
                    workflow_version_id, tenant_id, workflow_id, version_number,
                    definition, definition_sha256, lifecycle_state,
                    effective_from, published_at, published_by, created_by)
                VALUES (?, 42, ?, 1, '{"steps":[]}'::jsonb, ?, 'PUBLISHED',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP, 99, 99)
                """, workflowVersionId, WORKFLOW_ID, "a".repeat(64));
        UUID categoryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_form_categories (
                    category_id, tenant_id, category_key, name_ko, name_en,
                    management_resource_set_key, created_by, updated_by)
                VALUES (?, 42, 'GENERAL', 'General', 'General', 'RS_TEAM_A', 99, 99)
                """, categoryId);
        jdbc.update("""
                INSERT INTO apr_forms (
                    form_id, tenant_id, form_key, name_ko, name_en,
                    lifecycle_state, category_id, management_resource_set_key,
                    created_by, updated_by)
                VALUES (?, 42, 'FORM_A', 'Form', 'Form', 'PUBLISHED', ?,
                        'RS_TEAM_A', 99, 99)
                """, FORM_ID, categoryId);
        UUID formVersionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_form_versions (
                    form_version_id, tenant_id, form_id, version_number,
                    schema_payload, schema_sha256, lifecycle_state,
                    published_at, published_by, created_by)
                VALUES (?, 42, ?, 1, '{"fields":[]}'::jsonb, ?, 'PUBLISHED',
                        CURRENT_TIMESTAMP, 99, 99)
                """, formVersionId, FORM_ID, "b".repeat(64));
        jdbc.update("""
                INSERT INTO apr_form_workflow_bindings (
                    binding_id, tenant_id, form_id, workflow_id,
                    binding_type, lifecycle_state, effective_from,
                    created_by, updated_by)
                VALUES (?, 42, ?, ?, 'DEFAULT', 'ACTIVE',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute', 99, 99)
                """, UUID.randomUUID(), FORM_ID, WORKFLOW_ID);
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_requests (
                    request_id, tenant_id, request_number,
                    workflow_version_id, form_version_id,
                    title, requester_user_id, status,
                    management_resource_set_key, created_by, updated_by)
                VALUES (?, 42, ?, ?, ?, 'Request', 99, 'IN_REVIEW',
                        'RS_TEAM_A', 99, 99)
                """, requestId, "REQ-" + suffix + '-' + requestId,
                workflowVersionId, formVersionId);
        jdbc.update("""
                INSERT INTO apr_request_payloads (
                    tenant_id, request_id, payload, payload_sha256, schema_version)
                VALUES (42, ?, '{"detail":"original"}'::jsonb,
                        encode(sha256(convert_to(
                            '{"detail":"original"}'::jsonb::text, 'UTF8')), 'hex'), 1)
                """, requestId);
        jdbc.update("""
                INSERT INTO apr_request_payload_versions (
                    payload_version_id, tenant_id, request_id, revision_number,
                    payload, payload_sha256, change_type, changed_by)
                SELECT gen_random_uuid(), tenant_id, request_id, schema_version,
                       payload, payload_sha256, 'BASELINE', 99
                  FROM apr_request_payloads
                 WHERE tenant_id = 42 AND request_id = ?
                """, requestId);
        UUID stepId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_steps (
                    step_id, tenant_id, request_id, step_key, step_name,
                    sequence_number, status)
                VALUES (?, 42, ?, 'REVIEW', 'Review', 1, 'IN_PROGRESS')
                """, stepId, requestId);
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_tasks (
                    task_id, tenant_id, request_id, step_id,
                    assignee_user_id, status)
                VALUES (?, 42, ?, ?, 17, 'PENDING')
                """, taskId, requestId, stepId);
        return new RuntimeRows(requestId, taskId);
    }

    private void migrateAll() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
    }

    private record RuntimeRows(UUID requestId, UUID taskId) {
    }
}
