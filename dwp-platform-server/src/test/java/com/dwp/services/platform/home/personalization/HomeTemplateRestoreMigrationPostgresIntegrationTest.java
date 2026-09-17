package com.dwp.services.platform.home.personalization;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class HomeTemplateRestoreMigrationPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void addsOnlyTheRestoreRevisionContractAndKeepsRegistryAuthorityInShadow() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway throughWaveFour = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("299")
                .cleanDisabled(false)
                .load();
        throughWaveFour.clean();
        throughWaveFour.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID templateId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO adm_home_templates (
                    template_id, tenant_id, template_key, name,
                    audience_payload, lifecycle_state, schema_version, layout_payload,
                    published_at, published_by)
                VALUES (?, 1, 'wave-five-restore', 'Wave Five Restore',
                        '{"type":"ALL","values":[]}'::jsonb, 'PUBLISHED', 5, '{}'::jsonb,
                        CURRENT_TIMESTAMP, 11)
                """, templateId);

        assertThatThrownBy(() -> insertRevision(jdbc, templateId, "RESTORE"))
                .isInstanceOf(DataAccessException.class);

        Flyway restoreContract = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("300")
                .load();
        assertThat(restoreContract.migrate().migrationsExecuted).isEqualTo(1);
        insertRevision(jdbc, templateId, "RESTORE");

        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM sys_code_values
                 WHERE code_set_key = 'PLATFORM.ADM_HOME_TEMPLATE_REVISIONS.SOURCE'
                   AND code = 'RESTORE'
                """, String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForMap("""
                SELECT migration_mode, runtime_activation_ready
                  FROM plt_widget_registry_state WHERE environment = 'GLOBAL'
                """)).containsEntry("migration_mode", "SHADOW")
                .containsEntry("runtime_activation_ready", false);
        assertThatThrownBy(() -> insertRevision(jdbc, templateId, "UNKNOWN"))
                .isInstanceOf(DataAccessException.class);
        assertThat(restoreContract.migrate().migrationsExecuted).isZero();
    }

    private static void insertRevision(
            JdbcTemplate jdbc, UUID templateId, String source) {
        jdbc.update("""
                INSERT INTO adm_home_template_revisions (
                    template_revision_id, template_id, tenant_id, revision_number,
                    snapshot, source, created_by)
                VALUES (?, ?, 1,
                        COALESCE((SELECT max(revision_number) + 1
                                    FROM adm_home_template_revisions
                                   WHERE template_id = ?), 1),
                        '{}'::jsonb, ?, 11)
                """, UUID.randomUUID(), templateId, templateId, source);
    }
}
