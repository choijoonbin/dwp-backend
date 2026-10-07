package com.dwp.services.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AppGovernanceAuthorizationPostgresTest {

    private static final long TENANT = 7L;
    private static final long OTHER_TENANT = 8L;
    private static final long ACTOR = 101L;
    private static final long ROLE = 201L;
    private static final long OTHER_ROLE = 202L;
    private static final long GROUP = 501L;
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
    void catalogControlRequiresTheExactRoleAndPersistedPermissionTogether() {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(source);
            createSchema(jdbc);
            jdbc.update("INSERT INTO com_roles VALUES(?,?,?,?)",
                    TENANT, ROLE, "APP_CATALOG_ADMIN", "ACTIVE");
            jdbc.update("INSERT INTO com_role_members VALUES(?,?,?)", TENANT, ROLE, ACTOR);
            seedPermission(jdbc, TENANT, "ALLOW");
            jdbc.update("UPDATE com_permissions SET code='MANAGE' WHERE permission_id=?",
                    PERMISSION);
            var authorization = new AppGovernanceAuthorization(jdbc);

            assertThatCode(() -> authorization.requireCatalogAdmin(
                    TENANT, ACTOR, "exact", "TEST", "1")).doesNotThrowAnyException();

            jdbc.update("UPDATE com_roles SET code='TENANT_ADMIN' WHERE role_id=?", ROLE);
            assertThatThrownBy(() -> authorization.requireCatalogAdmin(
                    TENANT, ACTOR, "wrong-role", "TEST", "1"));

            jdbc.update("UPDATE com_roles SET code='APP_CATALOG_ADMIN' WHERE role_id=?", ROLE);
            jdbc.update("DELETE FROM com_role_permissions");
            assertThatThrownBy(() -> authorization.requireCatalogAdmin(
                    TENANT, ACTOR, "missing-permission", "TEST", "1"));
        }
    }

    @Test
    void catalogControlRejectsDirectAndGroupCrossRolePermissionBorrowing() {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(source);
            createSchema(jdbc);
            insertRole(jdbc, ROLE, "APP_CATALOG_ADMIN");
            insertRole(jdbc, OTHER_ROLE, "OTHER_ADMIN");
            jdbc.update("INSERT INTO com_role_members VALUES(?,?,?)", TENANT, ROLE, ACTOR);
            seedPermission(jdbc, TENANT, OTHER_ROLE, "MANAGE", "ALLOW");
            var authorization = new AppGovernanceAuthorization(jdbc);

            assertCatalogDenied(authorization, "direct-cross-role");

            jdbc.update("DELETE FROM com_role_members");
            assignGroupRole(jdbc, ROLE);
            assertCatalogDenied(authorization, "group-catalog-direct-permission");

            jdbc.update("DELETE FROM com_role_permissions");
            jdbc.update("DELETE FROM com_group_role_assignments");
            jdbc.update("INSERT INTO com_role_members VALUES(?,?,?)", TENANT, ROLE, ACTOR);
            assignGroupRole(jdbc, OTHER_ROLE);
            seedPermission(jdbc, TENANT, OTHER_ROLE, "MANAGE", "ALLOW");
            assertCatalogDenied(authorization, "direct-catalog-group-permission");

            jdbc.update("DELETE FROM com_role_members");
            jdbc.update("DELETE FROM com_group_role_assignments");
            assignGroupRole(jdbc, ROLE);
            jdbc.update("DELETE FROM com_role_permissions");
            seedPermission(jdbc, TENANT, ROLE, "MANAGE", "ALLOW");
            assertThatCode(() -> authorization.requireCatalogAdmin(
                    TENANT, ACTOR, "same-group-role", "TEST", "1"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void actorWideDenyWinsOverTheExactCatalogRoleAllow() {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(source);
            createSchema(jdbc);
            insertRole(jdbc, ROLE, "APP_CATALOG_ADMIN");
            insertRole(jdbc, OTHER_ROLE, "OTHER_ADMIN");
            jdbc.update("INSERT INTO com_role_members VALUES(?,?,?)", TENANT, ROLE, ACTOR);
            assignGroupRole(jdbc, OTHER_ROLE);
            seedPermission(jdbc, TENANT, ROLE, "MANAGE", "ALLOW");
            seedPermission(jdbc, TENANT, OTHER_ROLE, "MANAGE", "DENY");

            assertCatalogDenied(new AppGovernanceAuthorization(jdbc), "actor-wide-deny");
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
        seedPermission(jdbc, tenantId, ROLE, "VIEW", effect);
    }

    private void seedPermission(
            JdbcTemplate jdbc, long tenantId, long roleId, String code, String effect) {
        jdbc.update("INSERT INTO com_resources VALUES(?,?,?,?)",
                tenantId, RESOURCE, AppGovernanceAuthorization.GOVERNANCE_RESOURCE, true);
        jdbc.update("INSERT INTO com_permissions VALUES(?,?)", PERMISSION, code);
        jdbc.update("INSERT INTO com_role_permissions VALUES(?,?,?,?,?)",
                tenantId, roleId, RESOURCE, PERMISSION, effect);
    }

    private void insertRole(JdbcTemplate jdbc, long roleId, String code) {
        jdbc.update("INSERT INTO com_roles VALUES(?,?,?,?)", TENANT, roleId, code, "ACTIVE");
    }

    private void assignGroupRole(JdbcTemplate jdbc, long roleId) {
        jdbc.update("INSERT INTO com_groups VALUES(?,?,?)", TENANT, GROUP, "ACTIVE");
        jdbc.update("INSERT INTO com_group_members VALUES(?,?,?)", TENANT, GROUP, ACTOR);
        jdbc.update("""
                INSERT INTO com_group_role_assignments VALUES(
                    ?,?,?, 'ACTIVE','ACTIVE','TENANT',NULL,NULL)
                """, TENANT, GROUP, roleId);
    }

    private void assertCatalogDenied(
            AppGovernanceAuthorization authorization, String correlationId) {
        assertThatThrownBy(() -> authorization.requireCatalogAdmin(
                TENANT, ACTOR, correlationId, "TEST", "1"));
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
