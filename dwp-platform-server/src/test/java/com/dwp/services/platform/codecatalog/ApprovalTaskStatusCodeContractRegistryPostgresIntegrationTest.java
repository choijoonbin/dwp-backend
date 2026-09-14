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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalTaskStatusCodeContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V231__register_approval_superseded_task_status.sql");
    private static final String CODE_SET = "APPROVAL.APR_TASKS.STATUS";
    private static final List<String> EXPECTED = List.of(
            "APPROVED", "CANCELLED", "CLAIMED", "INFO_REQUESTED",
            "PENDING", "REASSIGNED", "REJECTED", "SKIPPED", "SUPERSEDED");

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
    void freshRegistryMatchesTheApprovalDatabaseCheckContractExactly() {
        migrateThrough(null);

        assertExactContract();
    }

    @Test
    void upgradeFromV230IsIdempotentAndFailsClosedOnUnexpectedValues()
            throws Exception {
        migrateThrough("230");

        executeForwardMigration();
        assertExactContract();
        executeForwardMigration();
        assertExactContract();

        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    behavior_metadata, lifecycle_state)
                VALUES (?, 'REGISTRY_ONLY', 'Drift', '{}', '{}', 'ACTIVE')
                """, CODE_SET);
        assertThatThrownBy(
                ApprovalTaskStatusCodeContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .hasMessageContaining("values drifted");
    }

    private static void migrateThrough(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        Flyway flyway = configuration.load();
        flyway.clean();
        flyway.migrate();
    }

    private static void assertExactContract() {
        assertThat(jdbc.queryForList("""
                SELECT code
                  FROM sys_code_values
                 WHERE code_set_key = ? AND lifecycle_state = 'ACTIVE'
                 ORDER BY code
                """, String.class, CODE_SET)).containsExactlyElementsOf(EXPECTED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_code_sets code_set
                  JOIN sys_code_bindings binding
                    ON binding.code_set_key = code_set.code_set_key
                 WHERE code_set.code_set_key = ?
                   AND code_set.owner_service = 'dwp-approval-server'
                   AND code_set.source_reference = 'apr_tasks.status'
                   AND code_set.validation_source = 'CHECK'
                   AND code_set.contract_kind = 'STATE_MACHINE'
                   AND code_set.lifecycle_state = 'ACTIVE'
                   AND binding.consumer_service = 'dwp-approval-server'
                   AND binding.usage_type = 'DATABASE_COLUMN'
                   AND binding.source_reference = 'apr_tasks.status'
                   AND binding.enforcement_type = 'CHECK'
                   AND binding.lifecycle_state = 'ACTIVE'
                """, Integer.class, CODE_SET)).isEqualTo(1);
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
}
