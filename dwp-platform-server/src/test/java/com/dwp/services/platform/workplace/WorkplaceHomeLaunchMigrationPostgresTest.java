package com.dwp.services.platform.workplace;

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
class WorkplaceHomeLaunchMigrationPostgresTest {
    @Container
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void upgradesOnlyLegacyNativeWorkplaceLaunchTargetsAndIsRepeatable() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE adm_workspace_apps (
                    tenant_id bigint, app_key text, launch_mode text, launch_target text,
                    version bigint DEFAULT 1, updated_at timestamptz, updated_by bigint)
                """);
        jdbc.update("""
                INSERT INTO adm_workspace_apps (tenant_id, app_key, launch_mode, launch_target) VALUES
                    (1, 'dwp-rooms', 'NATIVE', '/workplace/explore'),
                    (2, 'dwp-rooms', 'NATIVE', '/rooms/find'),
                    (3, 'dwp-rooms', 'NATIVE', '/workplace/my-bookings'),
                    (4, 'dwp-rooms', 'SSO', '/workplace/explore'),
                    (5, 'custom-app', 'NATIVE', '/workplace/explore')
                """);
        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V225__launch_workplace_at_home.sql"));
        migration.execute(dataSource);
        migration.execute(dataSource);

        assertThat(jdbc.queryForList(
                "SELECT launch_target FROM adm_workspace_apps ORDER BY tenant_id", String.class))
                .containsExactly("/workplace/home", "/workplace/home", "/workplace/my-bookings",
                        "/workplace/explore", "/workplace/explore");
        assertThat(jdbc.queryForList(
                "SELECT version FROM adm_workspace_apps ORDER BY tenant_id", Long.class))
                .containsExactly(2L, 2L, 1L, 1L, 1L);
    }
}
