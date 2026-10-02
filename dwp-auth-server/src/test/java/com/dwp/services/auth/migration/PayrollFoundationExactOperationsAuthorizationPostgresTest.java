package com.dwp.services.auth.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PayrollFoundationExactOperationsAuthorizationPostgresTest {

    private static final List<String> EXACT_OPERATIONS =
            List.of("PUBLISH", "RECONCILE", "REVERSE", "SIMULATE");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void materializesExactOperationsForExistingRolesAndFutureTenantTemplates() {
        PGSimpleDataSource dataSource = dataSource();
        migrate(dataSource, "233");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM com_roles WHERE code='HR_ADMIN' "
                        + "ORDER BY tenant_id LIMIT 1",
                Long.class);
        Long hrAdminRole = roleId(jdbc, tenantId, "HR_ADMIN");
        Long payrollAdminRole = roleId(jdbc, tenantId, "PAYROLL_ADMIN");
        Long resourceId = jdbc.queryForObject(
                "SELECT resource_id FROM com_resources "
                        + "WHERE tenant_id=? AND key='DATA.HR_PAY'",
                Long.class, tenantId);
        jdbc.update("""
                INSERT INTO com_permissions (code, name, created_by, updated_by)
                VALUES ('SIMULATE', 'Legacy simulation deny', 1, 1)
                ON CONFLICT (code) DO NOTHING
                """);
        Long simulatePermission = jdbc.queryForObject(
                "SELECT permission_id FROM com_permissions WHERE code='SIMULATE'",
                Long.class);
        jdbc.update("""
                INSERT INTO com_role_permissions (
                    tenant_id, role_id, resource_id, permission_id, effect)
                VALUES (?, ?, ?, ?, 'DENY')
                """, tenantId, hrAdminRole, resourceId, simulatePermission);
        Long hrUser = userWithRole(jdbc, tenantId, hrAdminRole, "hr-pay@test.invalid");
        Long payrollUser = userWithRole(
                jdbc, tenantId, payrollAdminRole, "payroll-pay@test.invalid");
        Long hrRevision = accessRevision(jdbc, hrUser);
        Long payrollRevision = accessRevision(jdbc, payrollUser);

        migrate(dataSource, "234");

        assertThat(templateOperations(jdbc, "HR_ADMIN"))
                .containsExactlyElementsOf(EXACT_OPERATIONS);
        assertThat(templateOperations(jdbc, "PAYROLL_ADMIN"))
                .containsExactlyElementsOf(EXACT_OPERATIONS);
        assertThat(roleOperations(jdbc, tenantId, hrAdminRole))
                .containsExactlyElementsOf(EXACT_OPERATIONS);
        assertThat(roleOperations(jdbc, tenantId, payrollAdminRole))
                .containsExactlyElementsOf(EXACT_OPERATIONS);
        assertThat(jdbc.queryForObject("""
                SELECT effect
                  FROM com_role_permissions
                 WHERE tenant_id=? AND role_id=? AND resource_id=? AND permission_id=?
                """, String.class, tenantId, hrAdminRole, resourceId, simulatePermission))
                .isEqualTo("ALLOW");
        assertThat(accessRevision(jdbc, hrUser)).isEqualTo(hrRevision + 1);
        assertThat(accessRevision(jdbc, payrollUser)).isEqualTo(payrollRevision + 1);
    }

    private List<String> templateOperations(JdbcTemplate jdbc, String roleCode) {
        return jdbc.queryForList("""
                SELECT permission_code
                  FROM sys_tenant_role_permission_templates
                 WHERE role_code=? AND resource_key='DATA.HR_PAY'
                   AND permission_code IN ('SIMULATE','PUBLISH','REVERSE','RECONCILE')
                   AND lifecycle_state='ACTIVE'
                 ORDER BY permission_code
                """, String.class, roleCode);
    }

    private List<String> roleOperations(JdbcTemplate jdbc, Long tenantId, Long roleId) {
        return jdbc.queryForList("""
                SELECT permission.code
                  FROM com_role_permissions grant_record
                  JOIN com_resources resource
                    ON resource.tenant_id=grant_record.tenant_id
                   AND resource.resource_id=grant_record.resource_id
                  JOIN com_permissions permission
                    ON permission.permission_id=grant_record.permission_id
                 WHERE grant_record.tenant_id=? AND grant_record.role_id=?
                   AND grant_record.effect='ALLOW' AND resource.key='DATA.HR_PAY'
                   AND permission.code IN ('SIMULATE','PUBLISH','REVERSE','RECONCILE')
                 ORDER BY permission.code
                """, String.class, tenantId, roleId);
    }

    private Long roleId(JdbcTemplate jdbc, Long tenantId, String roleCode) {
        return jdbc.queryForObject(
                "SELECT role_id FROM com_roles WHERE tenant_id=? AND code=?",
                Long.class, tenantId, roleCode);
    }

    private Long userWithRole(
            JdbcTemplate jdbc,
            Long tenantId,
            Long roleId,
            String email) {
        Long userId = jdbc.queryForObject("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                VALUES (?, 'Payroll operation migration actor', ?, 'ACTIVE')
                RETURNING user_id
                """, Long.class, tenantId, email);
        jdbc.update("""
                INSERT INTO com_role_members (tenant_id, role_id, user_id)
                VALUES (?, ?, ?)
                """, tenantId, roleId, userId);
        return userId;
    }

    private Long accessRevision(JdbcTemplate jdbc, Long userId) {
        return jdbc.queryForObject(
                "SELECT access_revision FROM com_users WHERE user_id=?",
                Long.class, userId);
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
}
