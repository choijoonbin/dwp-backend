package com.dwp.services.auth.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class HomeExperienceAuthorizationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void grantsExactViewAndManageAuthoritiesToExistingTenantAdministrators() {
        PGSimpleDataSource dataSource = dataSource();
        migrate(dataSource, "218");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM com_roles WHERE code = 'TENANT_ADMIN' "
                        + "ORDER BY tenant_id LIMIT 1",
                Long.class);
        Long roleId = jdbc.queryForObject(
                "SELECT role_id FROM com_roles WHERE tenant_id = ? AND code = 'TENANT_ADMIN'",
                Long.class,
                tenantId);
        Long userId = jdbc.queryForObject("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                VALUES (?, 'Home experience administrator',
                        'home-experience-admin@test.invalid', 'ACTIVE')
                RETURNING user_id
                """, Long.class, tenantId);
        jdbc.update("""
                INSERT INTO com_role_members (tenant_id, role_id, user_id)
                VALUES (?, ?, ?)
                """, tenantId, roleId, userId);
        Long revisionBefore = jdbc.queryForObject(
                "SELECT access_revision FROM com_users WHERE user_id = ?",
                Long.class,
                userId);

        migrate(dataSource, "219");

        assertThat(jdbc.queryForList("""
                SELECT permission.code
                  FROM com_role_permissions grant_record
                  JOIN com_resources resource
                    ON resource.tenant_id = grant_record.tenant_id
                   AND resource.resource_id = grant_record.resource_id
                  JOIN com_permissions permission
                    ON permission.permission_id = grant_record.permission_id
                 WHERE grant_record.tenant_id = ?
                   AND grant_record.role_id = ?
                   AND grant_record.effect = 'ALLOW'
                   AND resource.key = 'ADMIN.HOME_EXPERIENCE'
                 ORDER BY permission.code
                """, String.class, tenantId, roleId))
                .containsExactly("MANAGE", "VIEW");
        assertThat(jdbc.queryForObject("""
                SELECT required_entitlement
                  FROM sys_tenant_resource_templates
                 WHERE resource_key = 'ADMIN.HOME_EXPERIENCE'
                """, String.class)).isEqualTo("core.workspace");
        assertThat(jdbc.queryForObject(
                "SELECT access_revision FROM com_users WHERE user_id = ?",
                Long.class,
                userId)).isEqualTo(revisionBefore + 1);
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
