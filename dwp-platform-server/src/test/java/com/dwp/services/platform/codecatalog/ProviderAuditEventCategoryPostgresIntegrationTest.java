package com.dwp.services.platform.codecatalog;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ProviderAuditEventCategoryPostgresIntegrationTest {

    private static final String CODE_SET = "PROVIDER.AUDIT_EVENT_CATEGORY";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAcrossTheRegistryAlignmentBoundary() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        Flyway baseline = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("186")
                .cleanDisabled(false)
                .load();
        baseline.clean();
        baseline.migrate();
        jdbc = new JdbcTemplate(dataSource);

        jdbc.update("""
                UPDATE sys_code_sets
                   SET lifecycle_state = 'RETIRED'
                 WHERE code_set_key = ?
                """, CODE_SET);
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    sort_order, behavior_metadata, predefined, lifecycle_state)
                VALUES
                    (?, 'FEATURE_ROLLOUT', 'Stale feature label', '{}',
                     900, '{"stale":true}', FALSE, 'RETIRED'),
                    (?, 'COMMERCIAL_GOVERNANCE', 'Stale commercial label', '{}',
                     910, '{"stale":true}', FALSE, 'RETIRED'),
                    (?, 'LEGACY_PROVIDER_CATEGORY', 'Legacy category', '{}',
                     920, '{}', TRUE, 'ACTIVE')
                """, CODE_SET, CODE_SET, CODE_SET);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();
    }

    @Test
    void exposesExactlyTheEightCategoriesEnforcedByTheProviderDatabase() {
        List<String> activeCodes = jdbc.queryForList("""
                SELECT code
                  FROM sys_code_values
                 WHERE code_set_key = ?
                   AND lifecycle_state = 'ACTIVE'
                 ORDER BY sort_order, code
                """, String.class, CODE_SET);

        assertThat(activeCodes).containsExactly(
                "ADMINISTRATION",
                "PRIVILEGED_ACCESS",
                "SERVICE_HEALTH",
                "CHANGE_MANAGEMENT",
                "TENANT_LIFECYCLE",
                "DATA_GOVERNANCE",
                "FEATURE_ROLLOUT",
                "COMMERCIAL_GOVERNANCE");
    }

    @Test
    void registersTheTwoForwardCategoriesAsActivePredefinedValues() {
        List<ForwardCategory> categories = jdbc.query("""
                SELECT code, display_name,
                       label_i18n ->> 'ko' AS korean_label,
                       label_i18n ->> 'en' AS english_label,
                       sort_order, predefined, lifecycle_state
                  FROM sys_code_values
                 WHERE code_set_key = ?
                   AND code IN ('FEATURE_ROLLOUT', 'COMMERCIAL_GOVERNANCE')
                 ORDER BY sort_order
                """, (row, ignored) -> new ForwardCategory(
                        row.getString("code"),
                        row.getString("display_name"),
                        row.getString("korean_label"),
                        row.getString("english_label"),
                        row.getInt("sort_order"),
                        row.getBoolean("predefined"),
                        row.getString("lifecycle_state")), CODE_SET);

        assertThat(categories).containsExactly(
                new ForwardCategory(
                        "FEATURE_ROLLOUT", "Feature rollout", "기능 롤아웃",
                        "Feature rollout", 70, true, "ACTIVE"),
                new ForwardCategory(
                        "COMMERCIAL_GOVERNANCE", "Commercial governance", "상업 거버넌스",
                        "Commercial governance", 80, true, "ACTIVE"));
    }

    private record ForwardCategory(
            String code,
            String displayName,
            String koreanLabel,
            String englishLabel,
            int sortOrder,
            boolean predefined,
            String lifecycleState) {
    }
}
