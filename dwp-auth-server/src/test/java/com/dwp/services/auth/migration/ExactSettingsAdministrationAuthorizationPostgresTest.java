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
class ExactSettingsAdministrationAuthorizationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void seedsCurrentAndFutureExactSettingsAuthorities() {
        PGSimpleDataSource dataSource = dataSource();
        migrate(dataSource, "229");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM com_roles WHERE code='TENANT_ADMIN' "
                        + "ORDER BY tenant_id LIMIT 1",
                Long.class);
        Long tenantAdminRole = roleId(jdbc, tenantId, "TENANT_ADMIN");
        Long identityAdminRole = roleId(jdbc, tenantId, "IDENTITY_ADMIN");
        Long foundationAdminRole = roleId(jdbc, tenantId, "ADMIN");
        Long catalogResource = jdbc.queryForObject(
                "SELECT resource_id FROM com_resources WHERE tenant_id=? "
                        + "AND key='ADMIN.PLATFORM_CATALOG'",
                Long.class, tenantId);
        Long viewPermission = jdbc.queryForObject(
                "SELECT permission_id FROM com_permissions WHERE code='VIEW'",
                Long.class);
        jdbc.update("""
                INSERT INTO com_role_permissions (
                    tenant_id, role_id, resource_id, permission_id, effect)
                VALUES (?, ?, ?, ?, 'DENY')
                """, tenantId, foundationAdminRole, catalogResource, viewPermission);
        Long userId = jdbc.queryForObject("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                VALUES (?, 'Exact settings administrator',
                        'exact-settings-admin@test.invalid', 'ACTIVE')
                RETURNING user_id
                """, Long.class, tenantId);
        jdbc.update("""
                INSERT INTO com_role_members (tenant_id, role_id, user_id)
                VALUES (?, ?, ?)
                """, tenantId, tenantAdminRole, userId);
        Long revisionBefore = jdbc.queryForObject(
                "SELECT access_revision FROM com_users WHERE user_id=?",
                Long.class, userId);

        migrate(dataSource, "230");

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM sys_tenant_resource_templates
                 WHERE resource_key IN (
                    'ADMIN.ACCESS_GOVERNANCE', 'ADMIN.ACCESS_REVIEWS',
                    'ADMIN.PRIVILEGED_ACCESS', 'ADMIN.TENANT_BRANDING',
                    'ADMIN.MANAGED_PREFERENCES', 'ADMIN.LOCALIZATION',
                    'ADMIN.REFERENCE_DATA')
                   AND lifecycle_state='ACTIVE'
                """, Integer.class)).isEqualTo(7);
        assertThat(permissions(jdbc, tenantId, tenantAdminRole, "ADMIN.ACCESS_REVIEWS"))
                .containsExactly("APPROVE", "MANAGE", "VIEW");
        assertThat(permissions(jdbc, tenantId, tenantAdminRole, "ADMIN.PRIVILEGED_ACCESS"))
                .containsExactly("APPROVE", "MANAGE", "VIEW");
        assertThat(permissions(jdbc, tenantId, identityAdminRole, "ADMIN.ACCESS_GOVERNANCE"))
                .containsExactly("MANAGE", "VIEW");
        assertThat(permissions(jdbc, tenantId, identityAdminRole, "ADMIN.PRIVILEGED_ACCESS"))
                .isEmpty();
        assertThat(permissions(jdbc, tenantId, foundationAdminRole, "ADMIN.PLATFORM_CATALOG"))
                .containsExactly("MANAGE");
        assertThat(jdbc.queryForObject("""
                SELECT grant_record.effect
                  FROM com_role_permissions grant_record
                  JOIN com_resources resource
                    ON resource.tenant_id=grant_record.tenant_id
                   AND resource.resource_id=grant_record.resource_id
                  JOIN com_permissions permission
                    ON permission.permission_id=grant_record.permission_id
                 WHERE grant_record.tenant_id=?
                   AND grant_record.role_id=?
                   AND resource.key='ADMIN.PLATFORM_CATALOG'
                   AND permission.code='VIEW'
                """, String.class, tenantId, foundationAdminRole)).isEqualTo("DENY");
        assertThat(jdbc.queryForObject(
                "SELECT access_revision FROM com_users WHERE user_id=?",
                Long.class, userId)).isEqualTo(revisionBefore + 1);
    }

    private List<String> permissions(
            JdbcTemplate jdbc,
            Long tenantId,
            Long roleId,
            String resourceKey) {
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
                   AND resource.key=?
                 ORDER BY permission.code
                """, String.class, tenantId, roleId, resourceKey);
    }

    private Long roleId(
            JdbcTemplate jdbc,
            Long tenantId,
            String roleCode) {
        return jdbc.queryForObject(
                "SELECT role_id FROM com_roles WHERE tenant_id=? AND code=?",
                Long.class, tenantId, roleCode);
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
