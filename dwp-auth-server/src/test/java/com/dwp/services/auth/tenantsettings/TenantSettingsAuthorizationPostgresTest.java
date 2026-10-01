package com.dwp.services.auth.tenantsettings;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TenantSettingsAuthorizationPostgresTest {

    private static final long TENANT = 7L;
    private static final long ACTOR = 101L;
    private static final long ROLE = 201L;
    private static final long GROUP = 301L;

    @Test
    void acceptsOnlyCurrentTenantScopedGroupAuthorityAndAppliesDenyPrecedence() {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(source);
            createSchema(jdbc);
            seedPermission(jdbc);
            var authorization = new TenantSettingsAuthorization(jdbc);

            jdbc.update("INSERT INTO com_role_members VALUES(?,?,?)", TENANT, ROLE, ACTOR);
            assertThat(canView(authorization)).isTrue();

            jdbc.update("UPDATE com_users SET identity_plane='PROVIDER' WHERE tenant_id=? AND user_id=?",
                    TENANT, ACTOR);
            assertThat(canView(authorization)).isFalse();
            jdbc.update("UPDATE com_users SET identity_plane='TENANT' WHERE tenant_id=? AND user_id=?",
                    TENANT, ACTOR);

            jdbc.update("UPDATE com_users SET status='INACTIVE' WHERE tenant_id=? AND user_id=?",
                    TENANT, ACTOR);
            assertThat(canView(authorization)).isFalse();
            jdbc.update("UPDATE com_users SET status='ACTIVE' WHERE tenant_id=? AND user_id=?",
                    TENANT, ACTOR);

            jdbc.update("UPDATE com_roles SET status = 'INACTIVE' WHERE tenant_id=? AND role_id=?",
                    TENANT, ROLE);
            assertThat(canView(authorization)).isFalse();
            jdbc.update("UPDATE com_roles SET status = 'ACTIVE' WHERE tenant_id=? AND role_id=?",
                    TENANT, ROLE);

            jdbc.update("UPDATE com_resources SET enabled = false WHERE tenant_id=?", TENANT);
            assertThat(canView(authorization)).isFalse();
            jdbc.update("UPDATE com_resources SET enabled = true WHERE tenant_id=?", TENANT);

            jdbc.update("DELETE FROM com_role_members");

            insertGroupAssignment(jdbc, TENANT, "ACTIVE", "ACTIVE", "TENANT",
                    OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(1));
            assertThat(canView(authorization)).isTrue();

            jdbc.update("UPDATE com_group_role_assignments SET valid_to = CURRENT_TIMESTAMP - INTERVAL '1 second'");
            assertThat(canView(authorization)).isFalse();

            jdbc.update("UPDATE com_group_role_assignments SET valid_to = CURRENT_TIMESTAMP + INTERVAL '1 hour', lifecycle_state = 'INACTIVE'");
            assertThat(canView(authorization)).isFalse();

            jdbc.update("UPDATE com_group_role_assignments SET lifecycle_state = 'ACTIVE', assignment_type = 'ELIGIBLE'");
            assertThat(canView(authorization)).isFalse();

            jdbc.update("UPDATE com_group_role_assignments SET assignment_type = 'ACTIVE', scope_type = 'RESOURCE'");
            assertThat(canView(authorization)).isFalse();

            jdbc.update("DELETE FROM com_group_role_assignments");
            insertGroupAssignment(jdbc, TENANT + 1, "ACTIVE", "ACTIVE", "TENANT",
                    OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(1));
            assertThat(canView(authorization)).isFalse();

            jdbc.update("DELETE FROM com_group_role_assignments");
            insertGroupAssignment(jdbc, TENANT, "ACTIVE", "ACTIVE", "TENANT",
                    OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(1));
            jdbc.update("INSERT INTO com_role_permissions VALUES(?,?,?,?,?)",
                    TENANT, ROLE, 401L, 501L, "DENY");
            assertThat(canView(authorization)).isFalse();
        }
    }

    private boolean canView(TenantSettingsAuthorization authorization) {
        return authorization.can(TENANT, ACTOR,
                TenantSettingsAuthorization.DIRECTORY_RESOURCE, "VIEW");
    }

    private void insertGroupAssignment(
            JdbcTemplate jdbc,
            long tenantId,
            String lifecycle,
            String type,
            String scope,
            OffsetDateTime validFrom,
            OffsetDateTime validTo) {
        jdbc.update("INSERT INTO com_group_role_assignments VALUES(?,?,?,?,?,?,?,?)",
                tenantId, GROUP, ROLE, lifecycle, type, scope, validFrom, validTo);
    }

    private void seedPermission(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO com_users VALUES(?,?,?,?)",
                TENANT, ACTOR, "ACTIVE", "TENANT");
        jdbc.update("INSERT INTO com_roles VALUES(?,?,?)", TENANT, ROLE, "ACTIVE");
        jdbc.update("INSERT INTO com_groups VALUES(?,?,?)", TENANT, GROUP, "ACTIVE");
        jdbc.update("INSERT INTO com_group_members VALUES(?,?,?)", TENANT, GROUP, ACTOR);
        jdbc.update("INSERT INTO com_resources VALUES(?,?,?,?)",
                TENANT, 401L, TenantSettingsAuthorization.DIRECTORY_RESOURCE, true);
        jdbc.update("INSERT INTO com_permissions VALUES(?,?)", 501L, "VIEW");
        jdbc.update("INSERT INTO com_role_permissions VALUES(?,?,?,?,?)",
                TENANT, ROLE, 401L, 501L, "ALLOW");
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE com_users(
                    tenant_id bigint,user_id bigint,status text,identity_plane text)
                """);
        jdbc.execute("CREATE TABLE com_roles(tenant_id bigint,role_id bigint,status text)");
        jdbc.execute("CREATE TABLE com_role_members(tenant_id bigint,role_id bigint,user_id bigint)");
        jdbc.execute("CREATE TABLE com_groups(tenant_id bigint,group_id bigint,status text)");
        jdbc.execute("CREATE TABLE com_group_members(tenant_id bigint,group_id bigint,user_id bigint)");
        jdbc.execute("""
                CREATE TABLE com_group_role_assignments(
                    tenant_id bigint,group_id bigint,role_id bigint,lifecycle_state text,
                    assignment_type text,scope_type text,valid_from timestamptz,valid_to timestamptz)
                """);
        jdbc.execute("""
                CREATE TABLE com_role_permissions(
                    tenant_id bigint,role_id bigint,resource_id bigint,permission_id bigint,effect text)
                """);
        jdbc.execute("CREATE TABLE com_resources(tenant_id bigint,resource_id bigint,key text,enabled boolean)");
        jdbc.execute("CREATE TABLE com_permissions(permission_id bigint,code text)");
    }
}
