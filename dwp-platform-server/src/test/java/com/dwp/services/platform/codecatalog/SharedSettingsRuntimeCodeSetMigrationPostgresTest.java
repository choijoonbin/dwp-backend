package com.dwp.services.platform.codecatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class SharedSettingsRuntimeCodeSetMigrationPostgresTest {

    private static final List<String> PERSONAL_CODE_SETS = List.of(
            "PLATFORM.PREFERENCE.COLOR_MODE",
            "PLATFORM.PREFERENCE.DENSITY",
            "PLATFORM.PREFERENCE.TIME_ZONE",
            "PLATFORM.PREFERENCE.DATE_FORMAT",
            "PLATFORM.PREFERENCE.TIME_FORMAT",
            "PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK",
            "PLATFORM.PREFERENCE.NUMBER_FORMAT");
    private static final List<String> AUDIT_CODE_SETS = List.of(
            "PLATFORM.AUDIT.WINDOW",
            "PLATFORM.AUDIT.CATEGORY_FILTER",
            "PLATFORM.AUDIT.SEVERITY_FILTER",
            "PLATFORM.AUDIT.OUTCOME_FILTER",
            "PLATFORM.EVENT_ENVELOPE.DOMAIN",
            "PLATFORM.EVENT_ENVELOPE.CLASSIFICATION",
            "PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT");
    private static final List<String> SHARED_SETTINGS_CODE_SETS = List.of(
            "PLATFORM.PREFERENCE.COLOR_MODE",
            "PLATFORM.PREFERENCE.DENSITY",
            "PLATFORM.PREFERENCE.TIME_ZONE",
            "PLATFORM.PREFERENCE.DATE_FORMAT",
            "PLATFORM.PREFERENCE.TIME_FORMAT",
            "PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK",
            "PLATFORM.PREFERENCE.NUMBER_FORMAT",
            "PLATFORM.AUDIT.WINDOW",
            "PLATFORM.AUDIT.CATEGORY_FILTER",
            "PLATFORM.AUDIT.SEVERITY_FILTER",
            "PLATFORM.AUDIT.OUTCOME_FILTER",
            "PLATFORM.EVENT_ENVELOPE.DOMAIN",
            "PLATFORM.EVENT_ENVELOPE.CLASSIFICATION",
            "PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void publishesOnlyTheExactPersonalAndAuditCodeSetsForRuntimeReads() {
        PGSimpleDataSource source = dataSource();
        Flyway beforePublication = Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .target("317")
                .cleanDisabled(false)
                .load();
        beforePublication.clean();
        beforePublication.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(source);
        Map<String, String> before = visibilityByKey(jdbc);
        assertThat(before).hasSize(14);
        assertThat(PERSONAL_CODE_SETS.stream()
                .filter(key -> "RUNTIME".equals(before.get(key)))
                .toList()).containsExactlyInAnyOrder(
                "PLATFORM.PREFERENCE.COLOR_MODE",
                "PLATFORM.PREFERENCE.DENSITY");
        assertThat(AUDIT_CODE_SETS.stream()
                .filter(key -> "RUNTIME".equals(before.get(key)))
                .toList()).containsExactlyInAnyOrder(
                "PLATFORM.AUDIT.WINDOW",
                "PLATFORM.AUDIT.CATEGORY_FILTER",
                "PLATFORM.AUDIT.SEVERITY_FILTER",
                "PLATFORM.AUDIT.OUTCOME_FILTER",
                "PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT");
        assertThat(jdbc.queryForObject("""
                SELECT runtime_visibility
                  FROM sys_code_sets
                 WHERE code_set_key = 'PLATFORM.SYS_CODE_SETS.RUNTIME_VISIBILITY'
                """, String.class)).isEqualTo("ADMIN_ONLY");

        Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();

        assertThat(visibilityByKey(jdbc))
                .hasSize(14)
                .allSatisfy((key, visibility) -> assertThat(visibility)
                        .as(key)
                        .isEqualTo("RUNTIME"));
        assertThat(jdbc.queryForObject("""
                SELECT runtime_visibility
                  FROM sys_code_sets
                 WHERE code_set_key = 'PLATFORM.SYS_CODE_SETS.RUNTIME_VISIBILITY'
                """, String.class)).isEqualTo("ADMIN_ONLY");

        SystemCodeCatalogRepository repository = new SystemCodeCatalogRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        for (String codeSetKey : SHARED_SETTINGS_CODE_SETS) {
            SystemCodeCatalogDtos.RuntimeCodeSet runtime = repository.getRuntime(codeSetKey, "ko-KR");
            assertThat(runtime.codeSetKey()).isEqualTo(codeSetKey);
            assertThat(runtime.values()).as(codeSetKey).isNotEmpty();
        }
    }

    private Map<String, String> visibilityByKey(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT code_set_key, runtime_visibility
                  FROM sys_code_sets
                 WHERE code_set_key = ANY (?::text[])
                 ORDER BY code_set_key
                """, statement -> statement.setArray(
                        1, statement.getConnection().createArrayOf(
                                "text", SHARED_SETTINGS_CODE_SETS.toArray())),
                result -> {
                    Map<String, String> rows = new java.util.LinkedHashMap<>();
                    while (result.next()) {
                        rows.put(result.getString("code_set_key"),
                                result.getString("runtime_visibility"));
                    }
                    return rows;
                });
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
