package com.dwp.services.provider;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ProviderSupportLedgerPermissionMigrationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        flyway(null).clean();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void upgradeFromV49AddsTheDedicatedLedgerPermissionToOnlyAuthorizedRoles() {
        flyway("49").migrate();
        assertThat(permissionCount()).isZero();

        flyway(null).migrate();

        assertPermissionBoundary();
        flyway(null).validate();
    }

    @Test
    void cleanLatestBuildsTheSameLeastPrivilegeBoundary() {
        flyway(null).migrate();

        assertPermissionBoundary();
    }

    private void assertPermissionBoundary() {
        assertThat(jdbc.queryForMap("""
                SELECT risk_tier, lifecycle_state
                  FROM prv_operator_permission_catalog
                 WHERE permission_code = 'SUPPORT_ACCESS_READ'
                """))
                .containsEntry("risk_tier", "L2")
                .containsEntry("lifecycle_state", "ACTIVE");
        assertThat(jdbc.queryForList("""
                SELECT role_code
                  FROM prv_operator_role_permissions
                 WHERE permission_code = 'SUPPORT_ACCESS_READ'
                 ORDER BY role_code
                """, String.class)).containsExactlyElementsOf(List.of(
                "PROVIDER_ADMIN", "PROVIDER_AUDITOR", "PROVIDER_SUPPORT"));
    }

    private int permissionCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_operator_permission_catalog
                 WHERE permission_code = 'SUPPORT_ACCESS_READ'
                """, Integer.class);
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
