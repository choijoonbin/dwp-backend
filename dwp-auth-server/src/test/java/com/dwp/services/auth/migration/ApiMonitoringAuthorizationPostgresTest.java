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
class ApiMonitoringAuthorizationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void upgradesExistingTenantsWithoutOverwritingExplicitDeny() {
        PGSimpleDataSource dataSource = dataSource();
        migrate(dataSource, "235");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM com_roles WHERE code='TENANT_ADMIN' "
                        + "ORDER BY tenant_id LIMIT 1",
                Long.class);
        Long tenantAdminRole = roleId(jdbc, tenantId, "TENANT_ADMIN");
        Long foundationAdminRole = roleId(jdbc, tenantId, "ADMIN");
        Long resourceId = jdbc.queryForObject(
                "SELECT resource_id FROM com_resources WHERE tenant_id=? "
                        + "AND key='ADMIN.API_MONITORING'",
                Long.class,
                tenantId);
        Long viewPermissionId = jdbc.queryForObject(
                "SELECT permission_id FROM com_permissions WHERE code='VIEW'",
                Long.class);
        jdbc.update("""
                INSERT INTO com_role_permissions (
                    tenant_id, role_id, resource_id, permission_id, effect)
                VALUES (?, ?, ?, ?, 'DENY')
                ON CONFLICT (tenant_id, role_id, resource_id, permission_id)
                DO UPDATE SET effect='DENY'
                """, tenantId, foundationAdminRole, resourceId, viewPermissionId);
        Long userId = jdbc.queryForObject("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                VALUES (?, 'API monitoring administrator',
                        'api-monitoring-admin@test.invalid', 'ACTIVE')
                RETURNING user_id
                """, Long.class, tenantId);
        jdbc.update("""
                INSERT INTO com_role_members (tenant_id, role_id, user_id)
                VALUES (?, ?, ?)
                """, tenantId, tenantAdminRole, userId);
        Long revisionBefore = jdbc.queryForObject(
                "SELECT access_revision FROM com_users WHERE user_id=?",
                Long.class,
                userId);

        migrate(dataSource, "236");

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM sys_tenant_role_permission_templates
                 WHERE resource_key='ADMIN.API_MONITORING'
                   AND role_code IN ('ADMIN', 'PLATFORM_ADMIN', 'TENANT_ADMIN')
                   AND permission_code IN ('VIEW', 'MANAGE')
                   AND lifecycle_state='ACTIVE'
                """, Integer.class)).isEqualTo(6);
        assertThat(permissions(jdbc, tenantId, tenantAdminRole))
                .containsExactly("MANAGE", "VIEW");
        assertThat(permissions(jdbc, tenantId, foundationAdminRole))
                .containsExactly("MANAGE");
        assertThat(jdbc.queryForObject("""
                SELECT grant_record.effect
                  FROM com_role_permissions grant_record
                  JOIN com_permissions permission
                    ON permission.permission_id=grant_record.permission_id
                 WHERE grant_record.tenant_id=?
                   AND grant_record.role_id=?
                   AND grant_record.resource_id=?
                   AND permission.code='VIEW'
                """, String.class, tenantId, foundationAdminRole, resourceId))
                .isEqualTo("DENY");
        assertThat(jdbc.queryForObject(
                "SELECT access_revision FROM com_users WHERE user_id=?",
                Long.class,
                userId)).isEqualTo(revisionBefore + 1);
    }

    private List<String> permissions(
            JdbcTemplate jdbc,
            Long tenantId,
            Long roleId) {
        return jdbc.queryForList("""
                SELECT permission.code
                  FROM com_role_permissions grant_record
                  JOIN com_resources resource
                    ON resource.tenant_id=grant_record.tenant_id
                   AND resource.resource_id=grant_record.resource_id
                  JOIN com_permissions permission
                    ON permission.permission_id=grant_record.permission_id
                 WHERE grant_record.tenant_id=?
                   AND grant_record.role_id=?
                   AND grant_record.effect='ALLOW'
                   AND resource.key='ADMIN.API_MONITORING'
                 ORDER BY permission.code
                """, String.class, tenantId, roleId);
    }

    private Long roleId(
            JdbcTemplate jdbc,
            Long tenantId,
            String roleCode) {
        return jdbc.queryForObject(
                "SELECT role_id FROM com_roles WHERE tenant_id=? AND code=?",
                Long.class,
                tenantId,
                roleCode);
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
