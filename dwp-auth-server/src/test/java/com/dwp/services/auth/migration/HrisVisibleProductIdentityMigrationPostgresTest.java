package com.dwp.services.auth.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class HrisVisibleProductIdentityMigrationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void keepsAuthorizationKeysWhilePublishingOneVisibleHrisName() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("239")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("""
                SELECT display_name FROM sys_tenant_resource_templates
                 WHERE resource_key = 'APP.HCM'
                """, String.class)).isEqualTo("HRIS");
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT name FROM com_resources
                 WHERE type = 'APP' AND key = 'APP.HCM'
                """, String.class)).containsExactly("HRIS");
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT name FROM com_resources
                 WHERE type = 'APP' AND key = 'APP.HRIS'
                """, String.class)).containsExactly("HRIS compatibility alias");
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT name FROM com_admin_resource_sets
                 WHERE resource_set_key = 'RS_HCM_CONFIG'
                """, String.class)).containsExactly("HRIS");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                 WHERE version = '239' AND success
                """, Integer.class)).isOne();

        jdbc.update("""
                UPDATE sys_tenant_resource_templates SET updated_at = TIMESTAMPTZ '2000-01-01 00:00:00Z'
                 WHERE resource_key = 'APP.HCM'
                """);
        jdbc.update("""
                UPDATE com_resources SET updated_at = TIMESTAMPTZ '2000-01-01 00:00:00Z'
                 WHERE type = 'APP' AND key = 'APP.HCM'
                """);
        jdbc.update("""
                UPDATE com_admin_resource_sets SET updated_at = TIMESTAMPTZ '2000-01-01 00:00:00Z'
                 WHERE resource_set_key IN ('APP_HRIS', 'RS_HCM_CONFIG')
                """);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V239__rename_visible_hcm_product_to_hris.sql"))
                .execute(dataSource);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM (
                    SELECT updated_at FROM sys_tenant_resource_templates
                     WHERE resource_key = 'APP.HCM'
                    UNION ALL
                    SELECT updated_at FROM com_resources
                     WHERE type = 'APP' AND key = 'APP.HCM'
                    UNION ALL
                    SELECT updated_at FROM com_admin_resource_sets
                     WHERE resource_set_key IN ('APP_HRIS', 'RS_HCM_CONFIG')
                  ) visible_identity
                 WHERE updated_at <> TIMESTAMPTZ '2000-01-01 00:00:00Z'
                """, Integer.class)).isZero();
    }
}
