package com.dwp.services.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

class ApprovalFormCatalogFreshMigrationTest {
    private static final String KEY = "ACTION.APPROVAL_FORM";
    private static final List<String> GRANT_TABLES = List.of(
            "sys_tenant_role_permission_templates", "com_role_permissions", "com_principal_resource_grants");

    @Test
    void fullFreshAuthMigrationsRegisterFormWithoutGrantsAndKeepActualTaskPermissions() {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = dataSource(postgres);
            var flyway = flyway(source);
            flyway.migrate(); flyway.validate();
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForMap("SELECT resource_type,required_entitlement,lifecycle_state FROM sys_tenant_resource_templates WHERE resource_key=?", KEY))
                    .containsExactlyInAnyOrderEntriesOf(Map.of("resource_type", "ACTION", "required_entitlement", "core.approvals", "lifecycle_state", "ACTIVE"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM com_resources WHERE key=? AND type='ACTION' AND enabled", Long.class, KEY))
                    .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM com_tenants", Long.class));
            assertNoFormGrants(jdbc);
            assertThat(jdbc.queryForList("""
                    SELECT role.code || ':' || permission.code
                      FROM com_role_permissions grant_record
                      JOIN com_roles role ON role.role_id=grant_record.role_id AND role.tenant_id=grant_record.tenant_id
                      JOIN com_resources resource ON resource.resource_id=grant_record.resource_id AND resource.tenant_id=grant_record.tenant_id
                      JOIN com_permissions permission ON permission.permission_id=grant_record.permission_id
                     WHERE resource.key='ACTION.APPROVAL_TASK' AND permission.code IN('VIEW','APPROVE')
                       AND role.status='ACTIVE' AND resource.enabled AND grant_record.effect='ALLOW'
                     ORDER BY role.code,permission.code
                    """, String.class)).containsExactly("APPROVAL_OPERATOR:APPROVE", "APPROVAL_OPERATOR:VIEW",
                            "WORKSPACE_MEMBER:APPROVE", "WORKSPACE_MEMBER:VIEW");
        }
    }

    @Test
    void upgradeFromActualV211ConfirmsAbsenceAndPreservesEveryGrantTable() {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = dataSource(postgres);
            flyway(source, "211").migrate();
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_tenant_resource_templates WHERE resource_key=?", Long.class, KEY)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM com_resources WHERE key=?", Long.class, KEY)).isZero();
            var counts = grantCounts(jdbc);
            var upgrade = flyway(source, "212");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            upgrade.validate();
            assertThat(grantCounts(jdbc)).isEqualTo(counts);
            assertNoFormGrants(jdbc);
        }
    }

    @Test
    void existingDisabledResourceAndRepeatedRegistrationKeepTheExactRecord() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = dataSource(postgres);
            flyway(source, "211").migrate();
            var jdbc = new JdbcTemplate(source);
            long tenant = jdbc.queryForObject("SELECT min(tenant_id) FROM com_tenants", Long.class);
            jdbc.update("INSERT INTO com_resources(tenant_id,type,key,name,enabled,created_by,updated_by) VALUES(?,'ACTION',?,'Disabled before V212',false,1,1)", tenant, KEY);
            var before = jdbc.queryForMap("SELECT * FROM com_resources WHERE tenant_id=? AND key=?", tenant, KEY);
            var counts = grantCounts(jdbc);
            var upgrade = flyway(source, "212");
            upgrade.migrate(); upgrade.validate();
            repeatRegistration(source);
            assertThat(jdbc.queryForMap("SELECT * FROM com_resources WHERE tenant_id=? AND key=?", tenant, KEY)).isEqualTo(before);
            assertThat(grantCounts(jdbc)).isEqualTo(counts);
            assertNoFormGrants(jdbc);
        }
    }

    @Test
    void existingRetiredTemplateIsNotReactivatedAndCreatesNoResources() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = dataSource(postgres);
            flyway(source, "211").migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO sys_tenant_resource_templates(resource_key,resource_type,display_name,required_entitlement,lifecycle_state) VALUES(?,'ACTION','Previously retired','core.approvals','RETIRED')", KEY);
            var before = jdbc.queryForMap("SELECT * FROM sys_tenant_resource_templates WHERE resource_key=?", KEY);
            var counts = grantCounts(jdbc);
            var upgrade = flyway(source, "212");
            upgrade.migrate(); upgrade.validate();
            repeatRegistration(source);
            assertThat(jdbc.queryForMap("SELECT * FROM sys_tenant_resource_templates WHERE resource_key=?", KEY)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM com_resources WHERE key=?", Long.class, KEY)).isZero();
            assertThat(grantCounts(jdbc)).isEqualTo(counts);
            assertNoFormGrants(jdbc);
        }
    }

    private static void assertNoFormGrants(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_tenant_role_permission_templates WHERE resource_key=?", Long.class, KEY)).isZero();
        for (String table : GRANT_TABLES.subList(1, GRANT_TABLES.size())) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " grant_record JOIN com_resources resource ON resource.resource_id=grant_record.resource_id WHERE resource.key=?", Long.class, KEY)).isZero();
        }
    }

    private static List<Long> grantCounts(JdbcTemplate jdbc) {
        return GRANT_TABLES.stream().map(table -> jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).toList();
    }

    private static void repeatRegistration(DriverManagerDataSource source) throws Exception {
        try (var connection = source.getConnection()) {
            var script = new FileSystemResource(migrations().resolve("V212__register_approval_form_resource.sql"));
            ScriptUtils.executeSqlScript(connection, script); ScriptUtils.executeSqlScript(connection, script);
        }
    }

    private static DriverManagerDataSource dataSource(PostgreSQLContainer<?> postgres) {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Flyway flyway(DriverManagerDataSource source, String... target) {
        var configuration = Flyway.configure().dataSource(source).locations("filesystem:" + migrations(), "filesystem:" + coreMigrations());
        if (target.length != 0) configuration.target(target[0]);
        return configuration.load();
    }

    private static Path migrations() {
        Path path = Path.of("dwp-auth-server/src/main/resources/db/migration");
        return Files.isDirectory(path) ? path : Path.of("src/main/resources/db/migration");
    }

    private static Path coreMigrations() {
        Path path = Path.of("dwp-core/src/main/resources/db/migration");
        return Files.isDirectory(path) ? path : Path.of("../dwp-core/src/main/resources/db/migration");
    }
}
