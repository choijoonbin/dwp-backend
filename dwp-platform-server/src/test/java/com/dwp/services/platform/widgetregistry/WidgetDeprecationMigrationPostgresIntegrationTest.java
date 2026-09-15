package com.dwp.services.platform.widgetregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class WidgetDeprecationMigrationPostgresIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void expandMigrationPreservesLegacyRowsAndProtectsDeadlineAcrossRollbackAndSafetyTransitions() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration")
                .target("258").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        UUID legacy = UUID.fromString("31000000-0000-0000-0000-000000000001");
        UUID version = UUID.fromString("31000000-0000-0000-0000-000000000003");
        jdbc.update("UPDATE plt_widget_definition_versions SET release_state = 'DEPRECATED' WHERE version_id = ?",
                legacy);
        Flyway latest = Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plt_widget_definition_versions", Integer.class))
                .isEqualTo(7);
        assertThat(deadline(jdbc, legacy)).isNull();

        for (String interval : new String[]{"-1 second", "366 days"}) {
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE plt_widget_definition_versions SET release_state = 'DEPRECATED',
                           deprecation_ends_at = CURRENT_TIMESTAMP + ?::interval WHERE version_id = ?
                    """, interval, version)).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE plt_widget_definition_versions SET release_state = 'DEPRECATED' WHERE version_id = ?
                """, version)).isInstanceOf(DataAccessException.class);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            deprecate(jdbc, version);
            assertThat(deadline(jdbc, version)).isNotNull();
            status.setRollbackOnly();
        });
        assertThat(deadline(jdbc, version)).isNull();
        assertThat(jdbc.queryForObject("SELECT release_state FROM plt_widget_definition_versions WHERE version_id = ?",
                String.class, version)).isEqualTo("PUBLISHED");

        deprecate(jdbc, version);
        OffsetDateTime approved = deadline(jdbc, version);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE plt_widget_definition_versions SET deprecation_ends_at = deprecation_ends_at + INTERVAL '1 day'
                 WHERE version_id = ?
                """, version)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE plt_widget_definition_versions SET release_state = 'BLOCKED', deprecation_ends_at = NULL
                 WHERE version_id = ?
                """, version)).isInstanceOf(DataAccessException.class);
        jdbc.update("""
                UPDATE plt_widget_definition_versions SET release_state = 'BLOCKED', safety_state = 'REVOKED'
                 WHERE version_id = ?
                """, version);
        assertThat(deadline(jdbc, version)).isEqualTo(approved);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plt_widget_definition_versions", Integer.class))
                .isEqualTo(7);
    }

    private static void deprecate(JdbcTemplate jdbc, UUID version) {
        jdbc.update("""
                UPDATE plt_widget_definition_versions SET release_state = 'DEPRECATED',
                       deprecation_ends_at = CURRENT_TIMESTAMP + INTERVAL '30 days'
                 WHERE version_id = ?
                """, version);
    }

    private static OffsetDateTime deadline(JdbcTemplate jdbc, UUID version) {
        return jdbc.queryForObject("SELECT deprecation_ends_at FROM plt_widget_definition_versions WHERE version_id = ?",
                OffsetDateTime.class, version);
    }
}
