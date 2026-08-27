package com.dwp.services.platform.codecatalog;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PublishPermissionActionProjectionPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V197__project_publish_permission_action.sql");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void configureDataSource() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void freshProjectionIsExactAndSemanticallyIdempotent() throws Exception {
        cleanAndMigrateThroughV196();

        executeForwardMigration();
        assertExactProjection();
        String fingerprint = projectionFingerprint();

        executeForwardMigration();

        assertExactProjection();
        assertThat(projectionFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void upgradeReplacesStaleMetadataAndReactivatesThePermission() throws Exception {
        cleanAndMigrateThroughV196();
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    sort_order, behavior_metadata, predefined, lifecycle_state)
                VALUES (
                    'AUTH.PERMISSION_ACTION', 'PUBLISH', 'Stale', '{}',
                    999, '{"legacy":true}', FALSE, 'RETIRED')
                """);

        executeForwardMigration();

        assertExactProjection();
    }

    private static void cleanAndMigrateThroughV196() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("196")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void assertExactProjection() {
        PermissionAction action = jdbc.queryForObject("""
                SELECT display_name,
                       label_i18n ->> 'ko' AS korean_label,
                       label_i18n ->> 'en' AS english_label,
                       sort_order,
                       behavior_metadata,
                       predefined,
                       lifecycle_state
                  FROM sys_code_values
                 WHERE code_set_key = 'AUTH.PERMISSION_ACTION'
                   AND code = 'PUBLISH'
                """, (row, ignored) -> new PermissionAction(
                row.getString("display_name"),
                row.getString("korean_label"),
                row.getString("english_label"),
                row.getInt("sort_order"),
                row.getString("behavior_metadata"),
                row.getBoolean("predefined"),
                row.getString("lifecycle_state")));

        assertThat(action).isEqualTo(new PermissionAction(
                "Publish",
                "게시",
                "Publish",
                90,
                "{}",
                true,
                "ACTIVE"));
    }

    private static String projectionFingerprint() {
        return jdbc.queryForObject("""
                SELECT code_set.schema_version || '|'
                           || code_value.updated_at::TEXT || '|'
                           || code_value.display_name || '|'
                           || code_value.label_i18n::TEXT || '|'
                           || code_value.sort_order || '|'
                           || code_value.behavior_metadata::TEXT || '|'
                           || code_value.predefined || '|'
                           || code_value.lifecycle_state
                  FROM sys_code_sets code_set
                  JOIN sys_code_values code_value
                    ON code_value.code_set_key = code_set.code_set_key
                 WHERE code_set.code_set_key = 'AUTH.PERMISSION_ACTION'
                   AND code_value.code = 'PUBLISH'
                """, String.class);
    }

    private static void executeForwardMigration() throws Exception {
        String migration = Files.readString(MIGRATION);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute(migration);
            connection.commit();
        }
    }

    private record PermissionAction(
            String displayName,
            String koreanLabel,
            String englishLabel,
            int sortOrder,
            String behaviorMetadata,
            boolean predefined,
            String lifecycleState) {
    }
}
