package com.dwp.services.auth.identity;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WorkforceIdentityWorkerNumberMigrationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void forwardMigrationBackfillsOnlyGovernedReferenceIdentitiesAndEnforcesTenantUniqueness() {
        PGSimpleDataSource source = dataSource();
        migrate(source, "214");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Long tenantId = jdbc.queryForObject("SELECT min(tenant_id) FROM com_tenants", Long.class);
        Long unrelatedUserId = jdbc.queryForObject("""
                INSERT INTO com_users (
                    tenant_id, display_name, email, status, source_type, external_id)
                VALUES (?, 'Unrelated HRIS user', 'unrelated-worker@test.invalid',
                        'ACTIVE', 'HRIS', 'opaque-external-id')
                RETURNING user_id
                """, Long.class, tenantId);

        migrate(source, "215");

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_name = 'com_users' AND column_name = 'worker_number'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT worker_number
                  FROM com_users
                 WHERE tenant_id = 1 AND external_id LIKE 'SKAX-HRIS-%'
                 ORDER BY user_id LIMIT 1
                """, String.class)).startsWith("SK");
        assertThat(jdbc.queryForObject(
                "SELECT worker_number FROM com_users WHERE user_id = ?",
                String.class,
                unrelatedUserId)).isNull();

        String workerNumber = jdbc.queryForObject("""
                SELECT worker_number
                  FROM com_users
                 WHERE tenant_id = 1 AND worker_number IS NOT NULL
                 ORDER BY user_id LIMIT 1
                """, String.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO com_users (
                    tenant_id, display_name, email, status, source_type, worker_number)
                VALUES (1, 'Duplicate worker', 'duplicate-worker@test.invalid',
                        'ACTIVE', 'HRIS', ?)
                """, workerNumber)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private void migrate(PGSimpleDataSource source, String target) {
        Flyway.configure()
                .dataSource(source)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target(target)
                .load()
                .migrate();
    }
}
