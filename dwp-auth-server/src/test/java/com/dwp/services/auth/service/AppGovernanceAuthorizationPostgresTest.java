package com.dwp.services.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AppGovernanceAuthorizationPostgresTest {

    private static final long TENANT = 7L;
    private static final long OTHER_TENANT = 8L;
    private static final long ACTOR = 101L;
    private static final long ROLE = 201L;
    private static final long RESOURCE = 301L;
    private static final long PERMISSION = 401L;

    @Test
    void requiresAnExactPersistedPermissionWithDenyPrecedenceAndTenantIsolation() {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(source);
            createSchema(jdbc);
            jdbc.update("INSERT INTO com_roles VALUES(?,?,?,?)",
                    TENANT, ROLE, "APP_CATALOG_ADMIN", "ACTIVE");
            jdbc.update("INSERT INTO com_role_members VALUES(?,?,?)", TENANT, ROLE, ACTOR);
            var authorization = new AppGovernanceAuthorization(jdbc);

            assertThat(canView(authorization)).as("a role name alone grants nothing").isFalse();

            seedPermission(jdbc, TENANT, "ALLOW");
            assertThat(canView(authorization)).isTrue();

            jdbc.update("UPDATE com_role_permissions SET effect='DENY' WHERE tenant_id=?", TENANT);
            assertThat(canView(authorization)).as("revocation/deny wins").isFalse();

            jdbc.update("DELETE FROM com_role_permissions");
            seedPermission(jdbc, OTHER_TENANT, "ALLOW");
            assertThat(canView(authorization)).as("another tenant cannot grant access").isFalse();
        }
    }

    @Test
    void returnsEveryActivePrincipalBeyondTheFormerFiveHundredRowCutoff() {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE com_users(
                        tenant_id bigint,user_id bigint,display_name text,job_title text,status text)
                    """);
            jdbc.execute("""
                    CREATE TABLE com_groups(
                        tenant_id bigint,group_id bigint,display_name text,description text,status text)
                    """);
            jdbc.update("""
                    INSERT INTO com_users(tenant_id,user_id,display_name,job_title,status)
                    SELECT ?, value, 'User ' || value, '', 'ACTIVE'
                      FROM generate_series(1, 501) value
                    """, TENANT);
            jdbc.update("""
                    INSERT INTO com_users(tenant_id,user_id,display_name,job_title,status)
                    VALUES (?, 900, 'Other tenant user', '', 'ACTIVE')
                    """, OTHER_TENANT);

            var service = new AppGovernanceService(jdbc, mock(IdentityAuditService.class));

            assertThat(service.principals(TENANT)).hasSize(501);
        }
    }

    private boolean canView(AppGovernanceAuthorization authorization) {
        return authorization.canPermission(
                TENANT, ACTOR, AppGovernanceAuthorization.GOVERNANCE_RESOURCE, "VIEW");
    }

    private void seedPermission(JdbcTemplate jdbc, long tenantId, String effect) {
        jdbc.update("INSERT INTO com_resources VALUES(?,?,?,?)",
                tenantId, RESOURCE, AppGovernanceAuthorization.GOVERNANCE_RESOURCE, true);
        jdbc.update("INSERT INTO com_permissions VALUES(?,?)", PERMISSION, "VIEW");
        jdbc.update("INSERT INTO com_role_permissions VALUES(?,?,?,?,?)",
                tenantId, ROLE, RESOURCE, PERMISSION, effect);
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE com_roles(tenant_id bigint,role_id bigint,code text,status text)");
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
        jdbc.execute(
                "CREATE TABLE com_resources(tenant_id bigint,resource_id bigint,key text,enabled boolean)");
        jdbc.execute("CREATE TABLE com_permissions(permission_id bigint,code text)");
    }
}
