package com.dwp.services.auth.service;

import static org.assertj.core.api.Assertions.*;
import com.dwp.services.auth.repository.ApprovalFormUserDirectoryRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

class ApprovalFormUserDirectoryPostgresIntegrationTest {
    @Test
    void disposableActualQueriesAndCatalogMigrationNeverEnumerateEmailOrCreateGrants() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE com_tenants (tenant_id bigint PRIMARY KEY, status text NOT NULL)");
            jdbc.execute("CREATE TABLE com_users (user_id bigint PRIMARY KEY, tenant_id bigint, person_public_id uuid, display_name text, identity_plane text, status text, email text)");
            jdbc.execute("CREATE TABLE sys_tenant_resource_templates (resource_key text PRIMARY KEY, resource_type text, display_name text, required_entitlement text, lifecycle_state text DEFAULT 'ACTIVE', CONSTRAINT ck_tenant_resource_template_key CHECK(resource_key LIKE resource_type || '.%'))");
            jdbc.execute("CREATE TABLE com_resources (tenant_id bigint, type text, key text, name text, enabled boolean, created_by bigint, updated_by bigint, UNIQUE(tenant_id,type,key))");
            jdbc.execute("CREATE TABLE com_role_permissions (resource_key text)");
            jdbc.execute("CREATE TABLE com_principal_resource_grants (resource_key text)");
            jdbc.execute("CREATE TABLE sys_tenant_role_permission_templates (resource_key text)");
            jdbc.execute("INSERT INTO com_tenants VALUES(10,'ACTIVE'),(11,'ACTIVE'),(12,'INACTIVE')");
            add(jdbc, 1, 10, "Kim Active", "TENANT", "ACTIVE", "none@example.test");
            add(jdbc, 2, 10, "Kim Inactive", "TENANT", "INACTIVE", "none@example.test");
            add(jdbc, 3, 10, "Kim Provider", "PROVIDER", "ACTIVE", "none@example.test");
            add(jdbc, 4, 11, "Kim Other Tenant", "TENANT", "ACTIVE", "none@example.test");
            add(jdbc, 5, 12, "Kim Inactive Tenant", "TENANT", "ACTIVE", "none@example.test");
            add(jdbc, 6, 10, "Unrelated", "TENANT", "ACTIVE", "Kim@example.test");
            add(jdbc, 7, 10, "%Kim literal", "TENANT", "ACTIVE", "none@example.test");
            var directory = new ApprovalFormUserDirectoryRepository(new NamedParameterJdbcTemplate(dataSource));
            assertThat(directory.search(10, "Kim", 30)).extracting(person -> person.subjectId()).containsExactly(7L, 1L);
            assertThat(directory.search(10, "%K", 30)).extracting(person -> person.subjectId()).containsExactly(7L);
            assertThat(directory.search(10, "Kim", 1)).hasSize(1);
            assertThat(directory.resolve(10, List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), new UUID(0, 4))))
                    .extracting(person -> person.subjectId()).containsExactly(1L);
            assertThat(directory.search(12, "Kim", 30)).isEmpty();
            jdbc.execute("INSERT INTO com_resources VALUES(11,'ACTION','ACTION.APPROVAL_FORM_USER_DIRECTORY','Previously disabled',false,1,1)");
            Path migration = Path.of("dwp-auth-server/src/main/resources/db/migration/V211__register_approval_form_user_directory_resource.sql");
            if (!java.nio.file.Files.exists(migration)) migration = Path.of("src/main/resources/db/migration/V211__register_approval_form_user_directory_resource.sql");
            try (var connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration));
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration));
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_tenant_resource_templates", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM com_resources", Long.class)).isEqualTo(3L);
            assertThat(jdbc.queryForObject("SELECT enabled FROM com_resources WHERE tenant_id=11", Boolean.class)).isFalse();
            for (String table : List.of("com_role_permissions", "com_principal_resource_grants", "sys_tenant_role_permission_templates")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
            }
        }
    }

    private void add(JdbcTemplate jdbc, long id, long tenant, String name, String plane, String status, String email) {
        jdbc.update("INSERT INTO com_users VALUES(?,?,?,?,?,?,?)", id, tenant, new UUID(0, id), name, plane, status, email);
    }
}
