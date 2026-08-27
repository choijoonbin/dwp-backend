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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class LatestDatabaseCheckContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V193__register_latest_database_check_contracts.sql");

    private static final String EXPECTED_CONTRACTS = """
            dwp-auth-server\tsys_provider_tenant_command_receipts.command_type\tENTITLEMENTS,LIFECYCLE
            dwp-people-server\tsys_provider_tenant_command_receipts.command_type\tENTITLEMENTS,LIFECYCLE
            dwp-platform-server\tadm_experience_revisions.change_type\tASSET_PUBLISHED,ASSET_RESET,BASELINE,EXPERIENCE_PUBLISHED,ROLLBACK,SETTINGS_PUBLISHED
            dwp-platform-server\tadm_home_experiences.content_alignment\tCENTER,LEFT,RIGHT
            dwp-platform-server\tsys_provider_tenant_command_receipts.command_type\tENTITLEMENTS,LIFECYCLE
            dwp-platform-server\tusr_saved_view_lifecycle_commands.action\tARCHIVE_NOW,EXTEND_RETENTION,REASSIGN
            dwp-platform-server\tusr_saved_view_lifecycle_commands.new_lifecycle_state\tACTIVE,ARCHIVED,ORPHANED
            dwp-platform-server\tusr_saved_view_lifecycle_commands.previous_lifecycle_state\tORPHANED
            dwp-platform-server\tusr_saved_view_lifecycle_commands.reason_code\tOFFBOARDING,OWNER_CORRECTION,TEAM_REORGANIZATION
            dwp-platform-server\twp_floor_plan_media_assets.asset_status\tDELETED,DELETING,PENDING_DELETE,REFERENCED,STAGED
            dwp-provider-server\tprv_support_activation_control.control_key\tSTANDARD_JIT
            dwp-provider-server\tprv_tenant_command_outbox.command_type\tENTITLEMENTS,LIFECYCLE
            dwp-provider-server\tprv_tenant_command_outbox.lifecycle_state\tAPPLIED,COMPENSATED,COMPENSATION_PENDING,LEASED,PENDING,RECONCILIATION_REQUIRED,RETRY_WAIT
            dwp-provider-server\tprv_tenant_command_outbox.target_service\tAUTH,PEOPLE,PLATFORM
            dwp-provider-server\tprv_tenant_mutations.lifecycle_state\tCOMPENSATED,COMPENSATING,EXECUTING,PENDING,RECONCILIATION_REQUIRED,RETRY_WAIT,SUCCEEDED
            dwp-provider-server\tprv_tenant_mutations.mutation_type\tENTITLEMENTS,LIFECYCLE
            """;

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
    void freshRegistryThroughDurableReceiptMigrationMatchesTheExactManifest()
            throws Exception {
        cleanAndMigrateThrough("191");

        executeForwardMigration();
        assertExactManifest();
        String fingerprint = registryRevisionFingerprint();

        executeForwardMigration();

        assertExactManifest();
        assertThat(registryRevisionFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void upgradeFromAppliedV190ReactivatesRealValuesAndRetiresRegistryOnlyDrift()
            throws Exception {
        cleanAndMigrateThrough("190");
        seedUpgradeDrift();

        executeForwardMigration();

        assertExactManifest();
        assertThat(jdbc.queryForList("""
                SELECT code_set_key || ':' || code
                  FROM sys_code_values
                 WHERE code = 'LEGACY_REGISTRY_ONLY'
                   AND lifecycle_state = 'RETIRED'
                 ORDER BY code_set_key
                """, String.class)).containsExactly(
                "PLATFORM.EXPERIENCE_REVISION.CHANGE_TYPE:LEGACY_REGISTRY_ONLY",
                "PLATFORM.WP_FLOOR_PLAN_MEDIA_ASSETS.ASSET_STATUS:LEGACY_REGISTRY_ONLY");
    }

    private static void cleanAndMigrateThrough(String target) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void seedUpgradeDrift() {
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    behavior_metadata, lifecycle_state)
                VALUES
                    ('PLATFORM.EXPERIENCE_REVISION.CHANGE_TYPE',
                     'EXPERIENCE_PUBLISHED', 'Experience published', '{}', '{}', 'RETIRED'),
                    ('PLATFORM.EXPERIENCE_REVISION.CHANGE_TYPE',
                     'LEGACY_REGISTRY_ONLY', 'Legacy', '{}', '{}', 'ACTIVE'),
                    ('PLATFORM.WP_FLOOR_PLAN_MEDIA_ASSETS.ASSET_STATUS',
                     'DELETED', 'Deleted', '{}', '{}', 'RETIRED'),
                    ('PLATFORM.WP_FLOOR_PLAN_MEDIA_ASSETS.ASSET_STATUS',
                     'DELETING', 'Deleting', '{}', '{}', 'RETIRED'),
                    ('PLATFORM.WP_FLOOR_PLAN_MEDIA_ASSETS.ASSET_STATUS',
                     'LEGACY_REGISTRY_ONLY', 'Legacy', '{}', '{}', 'ACTIVE')
                ON CONFLICT (code_set_key, code) DO UPDATE
                   SET lifecycle_state = EXCLUDED.lifecycle_state
                """);
    }

    private static void assertExactManifest() {
        Map<String, String> expected = Arrays.stream(EXPECTED_CONTRACTS.strip().split("\\R"))
                .map(line -> line.split("\\t", 3))
                .collect(Collectors.toMap(
                        parts -> parts[0] + "\t" + parts[1],
                        parts -> parts[2]));

        List<ContractSnapshot> snapshots = jdbc.query("""
                SELECT code_set.code_set_key,
                       binding.consumer_service,
                       binding.source_reference,
                       code_set.runtime_visibility,
                       string_agg(code_value.code, ',' ORDER BY code_value.code) AS codes
                  FROM sys_code_sets code_set
                  JOIN sys_code_bindings binding
                    ON binding.code_set_key = code_set.code_set_key
                   AND binding.lifecycle_state = 'ACTIVE'
                   AND binding.usage_type = 'DATABASE_COLUMN'
                   AND binding.enforcement_type = 'CHECK'
                  JOIN sys_code_values code_value
                    ON code_value.code_set_key = code_set.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE'
                 WHERE code_set.lifecycle_state = 'ACTIVE'
                 GROUP BY code_set.code_set_key, binding.consumer_service,
                          binding.source_reference, code_set.runtime_visibility
                """, (row, ignored) -> new ContractSnapshot(
                row.getString("consumer_service"),
                row.getString("source_reference"),
                row.getString("runtime_visibility"),
                row.getString("codes")));

        for (Map.Entry<String, String> contract : expected.entrySet()) {
            List<ContractSnapshot> matches = snapshots.stream()
                    .filter(snapshot -> snapshot.contractReference().equals(contract.getKey()))
                    .toList();
            assertThat(matches)
                    .as("one canonical registration for %s", contract.getKey())
                    .hasSize(1);
            assertThat(matches.getFirst().codes())
                    .as("active values for %s", contract.getKey())
                    .isEqualTo(contract.getValue());
            assertThat(matches.getFirst().runtimeVisibility())
                    .as("runtime visibility for %s", contract.getKey())
                    .isEqualTo("ADMIN_ONLY");
        }
        assertThat(expected).hasSize(16);
    }

    private static String registryRevisionFingerprint() {
        return jdbc.queryForObject("""
                WITH registry_rows AS (
                    SELECT 'SET|' || code_set_key || '|' || schema_version || '|'
                               || updated_at::TEXT AS row_value
                      FROM sys_code_sets
                    UNION ALL
                    SELECT 'VALUE|' || code_set_key || '|' || code || '|'
                               || lifecycle_state || '|' || updated_at::TEXT
                      FROM sys_code_values
                    UNION ALL
                    SELECT 'BINDING|' || code_binding_id || '|' || code_set_key || '|'
                               || lifecycle_state || '|' || updated_at::TEXT
                      FROM sys_code_bindings
                )
                SELECT string_agg(row_value, E'\n' ORDER BY row_value)
                  FROM registry_rows
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

    private record ContractSnapshot(
            String consumerService,
            String sourceReference,
            String runtimeVisibility,
            String codes) {

        private String contractReference() {
            return consumerService + "\t" + sourceReference;
        }
    }
}
