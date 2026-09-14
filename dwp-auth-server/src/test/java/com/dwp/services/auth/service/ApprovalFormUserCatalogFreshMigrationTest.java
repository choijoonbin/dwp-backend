package com.dwp.services.auth.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

class ApprovalFormUserCatalogFreshMigrationTest {
    private static final String KEY = "ACTION.APPROVAL_FORM_USER_DIRECTORY";

    @Test
    void everyActualAuthMigrationRunsOnAFreshDatabaseWithAnExplicitUngrantableSourceCatalog() {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var flyway = Flyway.configure().dataSource(source).locations("filesystem:" + migrations(), "filesystem:" + coreMigrations()).load();
            flyway.migrate(); flyway.validate();
            var jdbc = new JdbcTemplate(source);
            assertCatalog(jdbc);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM com_resources WHERE key=?", Long.class, KEY))
                    .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM com_tenants", Long.class));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM com_resources WHERE key=? AND NOT enabled", Long.class, KEY)).isZero();
        }
    }

    @Test
    void fullPreviousSchemaUpgradeAndRepeatedRegistrationPreserveDisabledResourcesWithoutAnyGrants() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("filesystem:" + migrations(), "filesystem:" + coreMigrations()).target("210").load().migrate();
            var jdbc = new JdbcTemplate(source);
            long tenant = jdbc.queryForObject("SELECT min(tenant_id) FROM com_tenants", Long.class);
            jdbc.update("INSERT INTO com_resources(tenant_id,type,key,name,enabled,created_by,updated_by) VALUES(?,'ACTION',?,'Previously disabled',false,1,1)", tenant, KEY);
            var flyway = Flyway.configure().dataSource(source).locations("filesystem:" + migrations(), "filesystem:" + coreMigrations()).load();
            flyway.migrate(); flyway.validate();
            try (var connection = source.getConnection()) {
                var migration = new FileSystemResource(migrations().resolve("V211__register_approval_form_user_directory_resource.sql"));
                ScriptUtils.executeSqlScript(connection, migration); ScriptUtils.executeSqlScript(connection, migration);
            }
            assertCatalog(jdbc);
            assertThat(jdbc.queryForObject("SELECT enabled FROM com_resources WHERE tenant_id=? AND key=?", Boolean.class, tenant, KEY)).isFalse();
            assertThat(jdbc.queryForObject("SELECT name FROM com_resources WHERE tenant_id=? AND key=?", String.class, tenant, KEY)).isEqualTo("Previously disabled");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM com_resources WHERE key=?", Long.class, KEY))
                    .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM com_tenants", Long.class));
        }
    }

    private static Path migrations() {
        Path path = Path.of("dwp-auth-server/src/main/resources/db/migration");
        return Files.isDirectory(path) ? path : Path.of("src/main/resources/db/migration");
    }

    private static Path coreMigrations() {
        Path path = Path.of("dwp-core/src/main/resources/db/migration");
        return Files.isDirectory(path) ? path : Path.of("../dwp-core/src/main/resources/db/migration");
    }

    private static void assertCatalog(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT resource_type FROM sys_tenant_resource_templates WHERE resource_key=?", String.class, KEY)).isEqualTo("ACTION");
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM sys_tenant_resource_templates WHERE resource_key=?", String.class, KEY)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT required_entitlement FROM sys_tenant_resource_templates WHERE resource_key=?", String.class, KEY)).isEqualTo("core.approvals");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_tenant_role_permission_templates WHERE resource_key=?", Long.class, KEY)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_tenant_resource_templates WHERE resource_key='DWP_APPROVAL_FORM_USER_DIRECTORY'", Long.class)).isZero();
        for (String table : List.of("com_role_permissions", "com_principal_resource_grants")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " permission JOIN com_resources resource ON resource.resource_id=permission.resource_id WHERE resource.key=?", Long.class, KEY)).isZero();
        }
        assertThat(ApprovalFormUserCurrentAuthorityAdapter.SOURCE_PERMISSION).isEqualTo(KEY + ":VIEW");
    }
}
