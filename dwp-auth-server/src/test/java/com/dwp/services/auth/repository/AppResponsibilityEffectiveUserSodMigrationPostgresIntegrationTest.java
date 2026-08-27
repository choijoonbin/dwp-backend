package com.dwp.services.auth.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AppResponsibilityEffectiveUserSodMigrationPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void v208RepairsCrossPrincipalDriftAndRejectsLaterMembershipBypass() {
        PGSimpleDataSource dataSource = dataSource();
        migrate(dataSource, "100");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Long tenantId = jdbc.queryForObject("""
                INSERT INTO com_tenants (code, name, status)
                VALUES ('effective-sod-upgrade', 'Effective SoD upgrade', 'ACTIVE')
                RETURNING tenant_id
                """, Long.class);
        Long approver = user(jdbc, tenantId, "migration-approver");
        Long conflictUser = user(jdbc, tenantId, "migration-conflict");
        Long inheritedOnlyUser = user(jdbc, tenantId, "migration-inherited-only");
        Long groupId = group(jdbc, tenantId, "migration-approver-group");
        membership(jdbc, tenantId, groupId, conflictUser);
        membership(jdbc, tenantId, groupId, inheritedOnlyUser);
        UUID managerSet = resourceSet(jdbc, tenantId, "RS_SOD_MANAGER");
        UUID approverSet = resourceSet(jdbc, tenantId, "RS_SOD_APPROVER");

        UUID retainedManager = assignment(
                jdbc, tenantId, "USER", conflictUser.toString(),
                "APP_ACCESS_MANAGER", managerSet, "ACTIVE", approver,
                "2 days");
        UUID repairedApprover = assignment(
                jdbc, tenantId, "GROUP", groupId.toString(),
                "APP_ACCESS_APPROVER", approverSet, "PENDING_APPROVAL", approver,
                "1 day");

        Long presetConflictUser = user(jdbc, tenantId, "migration-preset-conflict");
        UUID presetSet = resourceSet(
                jdbc, tenantId, "RS_SOD_PRESET_REVIEWER", "APP.APPROVALS");
        assignment(
                jdbc, tenantId, "USER", presetConflictUser.toString(),
                "APP_ACCESS_MANAGER", presetSet, "ACTIVE", approver, "2 days");
        PresetBundle repairedPreset = pendingAuditorPreset(
                dataSource, jdbc, tenantId, presetConflictUser,
                presetSet, approver);

        Long orphanUserId = jdbc.queryForObject(
                "SELECT max(user_id) + 100000 FROM com_users", Long.class);
        assignment(
                jdbc, tenantId, "USER", orphanUserId.toString(),
                "APP_ACCESS_MANAGER", managerSet, "ACTIVE", approver,
                "2 hours");
        assignment(
                jdbc, tenantId, "USER", orphanUserId.toString(),
                "APP_ACCESS_APPROVER", approverSet, "ACTIVE", approver,
                "1 hour");

        migrate(dataSource, "208");

        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM com_admin_role_assignments
                 WHERE admin_role_assignment_id = ?
                """, String.class, retainedManager)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM com_admin_role_assignments
                 WHERE admin_role_assignment_id = ?
                """, String.class, repairedApprover)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("""
                SELECT reason_code FROM sys_app_responsibility_sod_repairs
                 WHERE tenant_id = ? AND revoked_assignment_id = ?
                """, String.class, tenantId, repairedApprover))
                .isEqualTo("EFFECTIVE_USER_CROSS_PRINCIPAL_SOD");
        assertThat(jdbc.queryForObject("""
                SELECT revocation_reason FROM com_admin_role_assignments
                 WHERE admin_role_assignment_id = ?
                """, String.class, repairedApprover))
                .isEqualTo("EFFECTIVE_USER_SOD_REPAIR_V208");
        UUID repairId = jdbc.queryForObject("""
                SELECT repair_id FROM sys_app_responsibility_sod_repairs
                 WHERE tenant_id = ? AND revoked_assignment_id = ?
                """, UUID.class, tenantId, repairedApprover);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE sys_app_responsibility_sod_repairs
                   SET reason_code = 'MUTATED'
                 WHERE repair_id = ?
                """, repairId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("repair evidence is immutable");
        assertThat(jdbc.queryForObject("""
                SELECT access_revision FROM com_users
                 WHERE tenant_id = ? AND user_id = ?
                """, Long.class, tenantId, conflictUser)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT access_revision FROM com_users
                 WHERE tenant_id = ? AND user_id = ?
                """, Long.class, tenantId, inheritedOnlyUser)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM com_admin_role_assignments
                 WHERE admin_role_assignment_id = ?
                """, String.class, repairedPreset.responsibilityId()))
                .isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM com_admin_app_preset_assignments
                 WHERE app_preset_assignment_id = ?
                """, String.class, repairedPreset.aggregateId()))
                .isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM com_admin_scoped_duty_assignments
                 WHERE scoped_duty_assignment_id = ?
                """, String.class, repairedPreset.dutyId()))
                .isEqualTo("REVOKED");

        Long emptyGroup = group(jdbc, tenantId, "post-migration-reviewer-group");
        UUID reviewerAssignment = assignment(
                jdbc, tenantId, "GROUP", emptyGroup.toString(),
                "APP_ACCESS_REVIEWER", approverSet, "ACTIVE", approver,
                "1 hour");
        assertThat(reviewerAssignment).isNotNull();

        assertThatThrownBy(() -> membership(
                jdbc, tenantId, emptyGroup, conflictUser))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "effective-user separation-of-duties conflict");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_group_members
                 WHERE tenant_id = ? AND group_id = ? AND user_id = ?
                """, Integer.class, tenantId, emptyGroup, conflictUser)).isZero();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO com_users (
                    user_id, tenant_id, display_name, email, status)
                VALUES (?, ?, 'Materialized orphan', ?, 'ACTIVE')
                """, orphanUserId, tenantId,
                "materialized-orphan-" + orphanUserId + "@effective-sod.test"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "effective-user separation-of-duties conflict");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_users
                 WHERE tenant_id = ? AND user_id = ?
                """, Integer.class, tenantId, orphanUserId)).isZero();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private void migrate(PGSimpleDataSource dataSource, String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private Long user(JdbcTemplate jdbc, Long tenantId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                VALUES (?, ?, ?, 'ACTIVE') RETURNING user_id
                """, Long.class, tenantId, name, name + "@effective-sod.test");
    }

    private Long group(JdbcTemplate jdbc, Long tenantId, String key) {
        return jdbc.queryForObject("""
                INSERT INTO com_groups (
                    tenant_id, group_key, display_name, source_type, status)
                VALUES (?, ?, ?, 'LOCAL', 'ACTIVE') RETURNING group_id
                """, Long.class, tenantId, key, key);
    }

    private void membership(
            JdbcTemplate jdbc, Long tenantId, Long groupId, Long userId) {
        jdbc.update("""
                INSERT INTO com_group_members (
                    tenant_id, group_id, user_id, source_type)
                VALUES (?, ?, ?, 'LOCAL')
                """, tenantId, groupId, userId);
    }

    private UUID resourceSet(JdbcTemplate jdbc, Long tenantId, String key) {
        return resourceSet(jdbc, tenantId, key, "APP.SOD.TEST");
    }

    private UUID resourceSet(
            JdbcTemplate jdbc, Long tenantId, String key, String resourceKey) {
        jdbc.update("""
                INSERT INTO com_resources (tenant_id, type, key, name, enabled)
                VALUES (?, 'APP', ?, 'SoD test app', TRUE)
                ON CONFLICT (tenant_id, type, key) DO NOTHING
                """, tenantId, resourceKey);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_resource_sets (
                    resource_set_id, tenant_id, resource_set_key, name,
                    resource_type, lifecycle_state)
                VALUES (?, ?, ?, ?, 'APP', 'ACTIVE')
                """, id, tenantId, key, key);
        jdbc.update("""
                INSERT INTO com_admin_resource_set_members (
                    tenant_id, resource_set_id, resource_type, resource_key,
                    lifecycle_state)
                VALUES (?, ?, 'APP', ?, 'ACTIVE')
                """, tenantId, id, resourceKey);
        return id;
    }

    private PresetBundle pendingAuditorPreset(
            PGSimpleDataSource dataSource,
            JdbcTemplate jdbc,
            Long tenantId,
            Long subjectId,
            UUID resourceSetId,
            Long requesterId) {
        UUID responsibilityId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        UUID dutyId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(ignored -> {
            jdbc.update("""
                    INSERT INTO com_admin_role_assignments (
                        admin_role_assignment_id, tenant_id,
                        principal_type, principal_ref, responsibility_code,
                        resource_set_id, assignment_source, lifecycle_state,
                        valid_to, review_due_at, justification,
                        created_at, created_by, updated_by)
                    VALUES (?, ?, 'USER', ?, 'APP_ACCESS_REVIEWER', ?,
                            'MANUAL', 'PENDING_APPROVAL',
                            CURRENT_TIMESTAMP + INTERVAL '30 days',
                            CURRENT_TIMESTAMP + INTERVAL '20 days',
                            'Pending auditor preset migration repair evidence.',
                            CURRENT_TIMESTAMP - INTERVAL '1 day', ?, ?)
                    """, responsibilityId, tenantId, subjectId.toString(),
                    resourceSetId, requesterId, requesterId);
            jdbc.update("""
                    INSERT INTO com_admin_app_preset_assignments (
                        app_preset_assignment_id, tenant_id, preset_code,
                        preset_catalog_version, principal_type, principal_ref,
                        resource_set_id, responsibility_assignment_id,
                        assignment_source, request_channel, lifecycle_state,
                        valid_to, review_due_at, justification, requested_by,
                        created_at, created_by, updated_by)
                    SELECT ?, ?, preset.preset_code, preset.version,
                           'USER', ?, ?, ?, 'MIGRATION', 'GOVERNANCE',
                           'PENDING_APPROVAL',
                           CURRENT_TIMESTAMP + INTERVAL '30 days',
                           CURRENT_TIMESTAMP + INTERVAL '20 days',
                           'Pending auditor preset migration repair evidence.', ?,
                           CURRENT_TIMESTAMP - INTERVAL '1 day', ?, ?
                      FROM sys_admin_app_preset_catalog preset
                     WHERE preset.preset_code = 'APPROVAL_AUDITOR'
                    """, aggregateId, tenantId, subjectId.toString(),
                    resourceSetId, responsibilityId, requesterId,
                    requesterId, requesterId);
            jdbc.update("""
                    INSERT INTO com_admin_scoped_duty_assignments (
                        scoped_duty_assignment_id, tenant_id,
                        principal_type, principal_ref, duty_code,
                        resource_set_id, responsibility_assignment_id,
                        app_preset_assignment_id, assignment_source,
                        lifecycle_state, valid_to, review_due_at,
                        justification, requested_by, created_at,
                        created_by, updated_by)
                    VALUES (?, ?, 'USER', ?, 'APPROVAL_OPERATIONS_AUDIT',
                            ?, ?, ?, 'MIGRATION', 'PENDING_APPROVAL',
                            CURRENT_TIMESTAMP + INTERVAL '30 days',
                            CURRENT_TIMESTAMP + INTERVAL '20 days',
                            'Pending auditor preset migration repair evidence.', ?,
                            CURRENT_TIMESTAMP - INTERVAL '1 day', ?, ?)
                    """, dutyId, tenantId, subjectId.toString(), resourceSetId,
                    responsibilityId, aggregateId, requesterId,
                    requesterId, requesterId);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
        return new PresetBundle(responsibilityId, aggregateId, dutyId);
    }

    private UUID assignment(
            JdbcTemplate jdbc,
            Long tenantId,
            String principalType,
            String principalRef,
            String responsibility,
            UUID resourceSetId,
            String state,
            Long actorId,
            String age) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_role_assignments (
                    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
                    responsibility_code, resource_set_id, assignment_source,
                    lifecycle_state, valid_from, valid_to, review_due_at,
                    justification, approved_by, approved_at, decision_reason,
                    created_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'MANUAL', ?,
                        CASE WHEN ? = 'ACTIVE'
                             THEN statement_timestamp() - INTERVAL '3 days' END,
                        statement_timestamp() + INTERVAL '30 days',
                        statement_timestamp() + INTERVAL '20 days',
                        'Effective-user SoD migration integration evidence.',
                        CASE WHEN ? = 'ACTIVE' THEN ? END,
                        CASE WHEN ? = 'ACTIVE' THEN statement_timestamp() END,
                        CASE WHEN ? = 'ACTIVE'
                             THEN 'Independent migration integration approval.' END,
                        statement_timestamp() - CAST(? AS INTERVAL), ?, ?)
                """, id, tenantId, principalType, principalRef,
                responsibility, resourceSetId, state,
                state, state, actorId, state, state, age, actorId, actorId);
        return id;
    }

    private record PresetBundle(
            UUID responsibilityId, UUID aggregateId, UUID dutyId) {
    }
}
