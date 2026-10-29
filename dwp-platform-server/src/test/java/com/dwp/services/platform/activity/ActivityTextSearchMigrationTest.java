package com.dwp.services.platform.activity;

import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityTextSearchMigrationTest {

    @Test
    void repositoryAndTrigramIndexUseTheSameBoundedSearchDocument() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V342__index_activity_text_search.sql"));
        String migrationConfiguration = Files.readString(Path.of(
                "src/main/resources/db/migration/V342__index_activity_text_search.sql.conf"));
        String applicationConfiguration = Files.readString(Path.of(
                "src/main/resources/application.yml"));

        assertThat(migration)
                .contains("CREATE EXTENSION IF NOT EXISTS pg_trgm")
                .contains("SET lock_timeout = '5s'")
                .contains("DROP INDEX CONCURRENTLY IF EXISTS idx_activity_search_text_trgm")
                .contains("CREATE INDEX CONCURRENTLY idx_activity_search_text_trgm")
                .contains("RESET lock_timeout;")
                .contains("RESET statement_timeout;")
                .contains("gin_trgm_ops");
        assertThat(migration.indexOf("SET lock_timeout"))
                .isLessThan(migration.indexOf("CREATE EXTENSION"));
        assertThat(migration.indexOf("SET statement_timeout"))
                .isLessThan(migration.indexOf("CREATE EXTENSION"));
        assertThat(migration.indexOf("RESET lock_timeout;"))
                .isGreaterThan(migration.indexOf("CREATE INDEX CONCURRENTLY"));
        assertThat(migration.indexOf("RESET statement_timeout;"))
                .isGreaterThan(migration.indexOf("CREATE INDEX CONCURRENTLY"));
        assertThat(migrationConfiguration.trim()).isEqualTo("executeInTransaction=false");
        assertThat(applicationConfiguration).containsSubsequence(
                "flyway:", "postgresql:", "transactional-lock: false");
        assertThat(Flyway.configure().load().getConfiguration()
                .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
                .isTransactionalLock()).isFalse();
        var indexedExpression = Pattern.compile(
                        "USING\\s+GIN\\s*\\(\\((.*?)\\)\\s+gin_trgm_ops\\s*\\)",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(migration);
        assertThat(indexedExpression.find()).isTrue();
        assertThat(normalize(indexedExpression.group(1))).isEqualTo(normalize(
                ActivityRepository.SEARCH_DOCUMENT.replace("e.", "")));
        assertThat(ActivityRepository.SEARCH_DOCUMENT)
                .doesNotContain("actor_name")
                .doesNotContain("correlation_id")
                .doesNotContain("source_event_id");
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
