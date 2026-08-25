package com.dwp.services.auth.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Upgrade proof: V91 authority remains effective but is never silently bundled. */
@Testcontainers(disabledWithoutDocker = true)
class AppAdminPresetMigrationPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void v92KeepsLegacyDirectAndGroupDutiesUnbundledAndQueuesPerUserReview() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        migrate(dataSource, "91");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Long tenantId = jdbc.queryForObject("""
                INSERT INTO com_tenants (code, name, status)
                VALUES ('preset-upgrade', 'Preset upgrade', 'ACTIVE')
                RETURNING tenant_id
                """, Long.class);
        Long requester = user(jdbc, tenantId, "requester");
        Long approver = user(jdbc, tenantId, "approver");
        Long directUser = user(jdbc, tenantId, "direct-user");
        Long groupUser = user(jdbc, tenantId, "group-user");
        Long groupId = jdbc.queryForObject("""
                INSERT INTO com_groups (
                    tenant_id, group_key, display_name, source_type, status)
                VALUES (?, 'legacy-duty-group', 'Legacy duty group', 'LOCAL', 'ACTIVE')
                RETURNING group_id
                """, Long.class, tenantId);
        jdbc.update("""
                INSERT INTO com_group_members (tenant_id, group_id, user_id, source_type)
                VALUES (?, ?, ?, 'LOCAL')
                """, tenantId, groupId, groupUser);
        jdbc.update("""
                INSERT INTO com_resources (tenant_id, type, key, name, enabled)
                VALUES (?, 'APP', 'APP.APPROVALS', 'Approvals', TRUE)
                """, tenantId);
        UUID setId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_resource_sets (
                    resource_set_id, tenant_id, resource_set_key, name,
                    resource_type, lifecycle_state)
                VALUES (?, ?, 'RS_APPROVALS', 'Approvals', 'APP', 'ACTIVE')
                """, setId, tenantId);
        jdbc.update("""
                INSERT INTO com_admin_resource_set_members (
                    tenant_id, resource_set_id, resource_type, resource_key,
                    lifecycle_state)
                VALUES (?, ?, 'APP', 'APP.APPROVALS', 'ACTIVE')
                """, tenantId, setId);

        UUID directResponsibility = responsibility(
                jdbc, tenantId, "USER", directUser.toString(), setId, approver);
        UUID groupResponsibility = responsibility(
                jdbc, tenantId, "GROUP", groupId.toString(), setId, approver);
        UUID directDuty = duty(
                jdbc, tenantId, "USER", directUser.toString(),
                "APPROVAL_DESIGN_DRAFT", setId, directResponsibility,
                requester, approver, "MANUAL");
        UUID groupDuty = duty(
                jdbc, tenantId, "GROUP", groupId.toString(),
                "APPROVAL_SIGNATURE_READ", setId, groupResponsibility,
                requester, approver, "GROUP");

        migrate(dataSource, "92");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_app_preset_assignments
                 WHERE tenant_id = ?
                """, Integer.class, tenantId)).isZero();
        assertThat(jdbc.query("""
                SELECT scoped_duty_assignment_id
                  FROM com_admin_scoped_duty_assignments
                 WHERE scoped_duty_assignment_id IN (?, ?)
                   AND app_preset_assignment_id IS NULL
                 ORDER BY scoped_duty_assignment_id
                """, (result, ignored) ->
                result.getObject(1, UUID.class), directDuty, groupDuty))
                .containsExactlyInAnyOrder(directDuty, groupDuty);
        assertThat(jdbc.query("""
                SELECT user_id, duty_code, evidence->>'principalType' AS principal_type,
                       evidence->>'resourceSetId' AS resource_set_id
                  FROM com_admin_scoped_duty_reviews
                 WHERE tenant_id = ?
                   AND reason_code = 'PRESET_WORKFLOW_REVIEW_REQUIRED'
                 ORDER BY user_id
                """, (result, ignored) -> new Review(
                        result.getLong("user_id"), result.getString("duty_code"),
                        result.getString("principal_type"),
                        result.getString("resource_set_id")), tenantId))
                .containsExactlyInAnyOrder(
                        new Review(directUser, "APPROVAL_DESIGN_DRAFT", "USER",
                                setId.toString()),
                        new Review(groupUser, "APPROVAL_SIGNATURE_READ", "GROUP",
                                setId.toString()));
        assertThat(jdbc.queryForObject("""
                SELECT product_resource_key FROM sys_admin_app_preset_catalog
                 WHERE preset_code = 'SERVICES_PRESET_CATALOG_PENDING'
                """, String.class)).isEqualTo("APP.SERVICES");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_admin_app_preset_catalog
                 WHERE product_key = 'rooms'
                """, Integer.class)).isZero();
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
                """, Long.class, tenantId, name, name + "@preset-upgrade.test");
    }

    private UUID responsibility(
            JdbcTemplate jdbc,
            Long tenantId,
            String principalType,
            String principalRef,
            UUID setId,
            Long approver) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_role_assignments (
                    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
                    responsibility_code, resource_set_id, assignment_source,
                    lifecycle_state, valid_from, valid_to, review_due_at,
                    justification, approved_by, approved_at, decision_reason)
                VALUES (?, ?, ?, ?, 'APP_CONFIG_ADMIN', ?, 'MANUAL', 'ACTIVE',
                        CURRENT_TIMESTAMP - INTERVAL '1 day',
                        CURRENT_TIMESTAMP + INTERVAL '365 days',
                        CURRENT_TIMESTAMP + INTERVAL '180 days',
                        'Legacy scoped responsibility retained for upgrade review.',
                        ?, CURRENT_TIMESTAMP,
                        'Independently approved before the preset aggregate existed.')
                """, id, tenantId, principalType, principalRef, setId, approver);
        return id;
    }

    private UUID duty(
            JdbcTemplate jdbc,
            Long tenantId,
            String principalType,
            String principalRef,
            String dutyCode,
            UUID setId,
            UUID responsibilityId,
            Long requester,
            Long approver,
            String source) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_scoped_duty_assignments (
                    scoped_duty_assignment_id, tenant_id, principal_type,
                    principal_ref, duty_code, resource_set_id,
                    responsibility_assignment_id, assignment_source,
                    lifecycle_state, valid_from, valid_to, review_due_at,
                    justification, requested_by, approved_by, approved_at,
                    decision_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE',
                        CURRENT_TIMESTAMP - INTERVAL '1 day',
                        CURRENT_TIMESTAMP + INTERVAL '365 days',
                        CURRENT_TIMESTAMP + INTERVAL '180 days',
                        'Legacy scoped duty retained for explicit preset review.',
                        ?, ?, CURRENT_TIMESTAMP,
                        'Independently approved before the preset aggregate existed.')
                """, id, tenantId, principalType, principalRef, dutyCode, setId,
                responsibilityId, source, requester, approver);
        return id;
    }

    private record Review(
            Long userId, String dutyCode, String principalType, String resourceSetId) {
    }
}
