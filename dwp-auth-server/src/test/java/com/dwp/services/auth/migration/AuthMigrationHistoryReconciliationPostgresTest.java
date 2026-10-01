package com.dwp.services.auth.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AuthMigrationHistoryReconciliationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void convergesTheExactPreReleaseDatabaseVariantToCanonicalHistory() {
        PGSimpleDataSource source = dataSource();
        Flyway beforeReconciliation = Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .target("237")
                .cleanDisabled(false)
                .load();
        beforeReconciliation.clean();
        beforeReconciliation.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                ALTER TABLE com_tenant_app_workforce_assignments
                    DROP CONSTRAINT ck_tenant_app_assignment_review,
                    DROP CONSTRAINT ck_tenant_app_assignment_activation
                """);
        jdbc.execute("""
                ALTER TABLE com_tenant_app_workforce_assignments
                    ADD CONSTRAINT ck_tenant_app_assignment_review CHECK (
                        (lifecycle_state IN ('APPROVED', 'ACTIVE')
                            AND approved_by IS NOT NULL AND approved_at IS NOT NULL
                            AND approved_by <> requested_by)
                        OR lifecycle_state NOT IN ('APPROVED', 'ACTIVE')),
                    ADD CONSTRAINT ck_tenant_app_assignment_activation CHECK (
                        (lifecycle_state = 'ACTIVE'
                            AND activated_by IS NOT NULL AND activated_at IS NOT NULL
                            AND activation_receipt_id IS NOT NULL
                            AND activated_by <> requested_by
                            AND activated_by <> approved_by)
                        OR lifecycle_state <> 'ACTIVE')
                """);
        jdbc.execute("""
                DROP TRIGGER trg_tenant_setting_owner_immutable
                    ON sys_tenant_setting_owner_registry
                """);
        jdbc.update("""
                UPDATE sys_tenant_setting_owner_registry
                   SET override_policy = 'TENANT_ALLOWED',
                       default_value = 'true'::jsonb
                 WHERE owner_key = 'AUTH_POLICY'
                   AND owner_version = 1
                   AND setting_key = 'authentication.requireMfa'
                   AND lifecycle_state = 'RETIRED'
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_tenant_setting_owner_immutable
                    BEFORE UPDATE OR DELETE ON sys_tenant_setting_owner_registry
                    FOR EACH ROW EXECUTE FUNCTION dwp_reject_tenant_setting_owner_mutation()
                """);

        Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();

        assertThat(jdbc.queryForObject("""
                SELECT override_policy = 'OWNER_LOCKED'
                       AND default_value = 'false'::jsonb
                       AND lifecycle_state = 'RETIRED'
                  FROM sys_tenant_setting_owner_registry
                 WHERE owner_key = 'AUTH_POLICY'
                   AND owner_version = 1
                   AND setting_key = 'authentication.requireMfa'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForList("""
                SELECT conname
                  FROM pg_constraint
                 WHERE conrelid = 'com_tenant_app_workforce_assignments'::regclass
                   AND conname IN (
                       'ck_tenant_app_assignment_review',
                       'ck_tenant_app_assignment_activation',
                       'ck_tenant_app_assignment_reviewer_not_principal',
                       'ck_tenant_app_assignment_activator_not_principal')
                 ORDER BY conname
                """, String.class)).containsExactly(
                "ck_tenant_app_assignment_activation",
                "ck_tenant_app_assignment_review");
        assertThat(jdbc.queryForObject("""
                SELECT bool_and(convalidated)
                  FROM pg_constraint
                 WHERE conrelid = 'com_tenant_app_workforce_assignments'::regclass
                   AND conname IN (
                       'ck_tenant_app_assignment_review',
                       'ck_tenant_app_assignment_activation')
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) LIKE '%approved_by%principal_ref%'
                  FROM pg_constraint
                 WHERE conrelid = 'com_tenant_app_workforce_assignments'::regclass
                   AND conname = 'ck_tenant_app_assignment_review'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) LIKE '%activated_by%principal_ref%'
                  FROM pg_constraint
                 WHERE conrelid = 'com_tenant_app_workforce_assignments'::regclass
                   AND conname = 'ck_tenant_app_assignment_activation'
                """, Boolean.class)).isTrue();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
