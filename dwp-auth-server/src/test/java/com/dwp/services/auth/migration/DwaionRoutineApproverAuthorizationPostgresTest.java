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
class DwaionRoutineApproverAuthorizationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void onlyDedicatedRoleReceivesCrossUserRoutineApproval() {
        PGSimpleDataSource dataSource = dataSource();
        migrate(dataSource, "222");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM com_roles WHERE code = 'WORKSPACE_MEMBER' ORDER BY tenant_id LIMIT 1",
                Long.class);

        assertThat(authorities(jdbc, tenantId, "DWAION_ROUTINE_APPROVER"))
                .containsExactly("APP.ASK:VIEW", "APP.DWAION_ROUTINES:APPROVE");
        assertThat(authorities(jdbc, tenantId, "WORKSPACE_MEMBER"))
                .contains("APP.DWAION_ROUTINES:MANAGE", "APP.DWAION_ROUTINES:VIEW")
                .doesNotContain("APP.DWAION_ROUTINES:APPROVE");
        assertThat(jdbc.queryForObject("""
                SELECT privileged FROM com_roles
                 WHERE tenant_id = ? AND code = 'DWAION_ROUTINE_APPROVER'
                """, Boolean.class, tenantId)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT assignment_mode FROM sys_role_assignment_policies
                 WHERE grantor_role_code = 'TENANT_ADMIN'
                   AND target_role_code = 'DWAION_ROUTINE_APPROVER'
                   AND lifecycle_state = 'ACTIVE'
                """, String.class)).isEqualTo("APPROVAL");
    }

    private java.util.List<String> authorities(
            JdbcTemplate jdbc, Long tenantId, String roleCode) {
        return jdbc.queryForList("""
                SELECT resource.key || ':' || permission.code
                  FROM com_role_permissions grant_record
                  JOIN com_roles role
                    ON role.tenant_id = grant_record.tenant_id
                   AND role.role_id = grant_record.role_id
                  JOIN com_resources resource
                    ON resource.tenant_id = grant_record.tenant_id
                   AND resource.resource_id = grant_record.resource_id
                  JOIN com_permissions permission
                    ON permission.permission_id = grant_record.permission_id
                 WHERE grant_record.tenant_id = ?
                   AND role.code = ?
                   AND grant_record.effect = 'ALLOW'
                   AND resource.key IN ('APP.ASK', 'APP.DWAION_ROUTINES')
                 ORDER BY resource.key, permission.code
                """, String.class, tenantId, roleCode);
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
