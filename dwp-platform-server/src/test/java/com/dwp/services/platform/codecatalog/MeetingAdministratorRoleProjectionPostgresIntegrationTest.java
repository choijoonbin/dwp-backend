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
class MeetingAdministratorRoleProjectionPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V195__project_meeting_administrator_role.sql");

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
        cleanAndMigrateThroughV194();

        executeForwardMigration();
        assertExactProjection();
        String fingerprint = roleProjectionFingerprint();

        executeForwardMigration();

        assertExactProjection();
        assertThat(roleProjectionFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void upgradeReplacesStaleMetadataAndReactivatesTheRole() throws Exception {
        cleanAndMigrateThroughV194();
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    sort_order, behavior_metadata, predefined, lifecycle_state)
                VALUES (
                    'AUTH.BUILT_IN_ROLE', 'MEETING_ADMIN', 'Stale', '{}',
                    999, '{"privileged":false}', FALSE, 'RETIRED')
                """);

        executeForwardMigration();

        assertExactProjection();
    }

    private static void cleanAndMigrateThroughV194() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("194")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void assertExactProjection() {
        MeetingRole role = jdbc.queryForObject("""
                SELECT display_name,
                       label_i18n ->> 'ko' AS korean_label,
                       label_i18n ->> 'en' AS english_label,
                       sort_order,
                       behavior_metadata ->> 'roleFamily' AS role_family,
                       behavior_metadata ->> 'assignmentClass' AS assignment_class,
                       (behavior_metadata ->> 'privileged')::BOOLEAN AS privileged,
                       (behavior_metadata ->> 'assignableToGroups')::BOOLEAN
                           AS assignable_to_groups,
                       predefined,
                       lifecycle_state
                  FROM sys_code_values
                 WHERE code_set_key = 'AUTH.BUILT_IN_ROLE'
                   AND code = 'MEETING_ADMIN'
                """, (row, ignored) -> new MeetingRole(
                row.getString("display_name"),
                row.getString("korean_label"),
                row.getString("english_label"),
                row.getInt("sort_order"),
                row.getString("role_family"),
                row.getString("assignment_class"),
                row.getBoolean("privileged"),
                row.getBoolean("assignable_to_groups"),
                row.getBoolean("predefined"),
                row.getString("lifecycle_state")));

        assertThat(role).isEqualTo(new MeetingRole(
                "Meeting administrator",
                "화상회의 관리자",
                "Meeting administrator",
                59,
                "WORKSPACE",
                "DELEGATED",
                true,
                true,
                true,
                "ACTIVE"));
    }

    private static String roleProjectionFingerprint() {
        return jdbc.queryForObject("""
                SELECT schema_version || '|' || code_value.updated_at::TEXT || '|'
                           || code_value.display_name || '|' || code_value.label_i18n::TEXT
                           || '|' || code_value.sort_order || '|'
                           || code_value.behavior_metadata::TEXT || '|'
                           || code_value.predefined || '|' || code_value.lifecycle_state
                  FROM sys_code_sets code_set
                  JOIN sys_code_values code_value
                    ON code_value.code_set_key = code_set.code_set_key
                 WHERE code_set.code_set_key = 'AUTH.BUILT_IN_ROLE'
                   AND code_value.code = 'MEETING_ADMIN'
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

    private record MeetingRole(
            String displayName,
            String koreanLabel,
            String englishLabel,
            int sortOrder,
            String roleFamily,
            String assignmentClass,
            boolean privileged,
            boolean assignableToGroups,
            boolean predefined,
            String lifecycleState) {
    }
}
