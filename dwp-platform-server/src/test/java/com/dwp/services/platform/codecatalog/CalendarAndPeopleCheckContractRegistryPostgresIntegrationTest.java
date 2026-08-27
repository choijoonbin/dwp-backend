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
class CalendarAndPeopleCheckContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V194__reconcile_calendar_and_people_receipt_check_contracts.sql");

    private static final String EXPECTED_CONTRACTS = """
            dwp-people-server\tsys_provider_tenant_command_receipts.command_type\tLIFECYCLE
            dwp-platform-server\tcal_calendar_access_grants.access_level\tEDIT,MANAGE,VIEW_DETAILS,VIEW_FREE_BUSY
            dwp-platform-server\tcal_calendar_access_grants.lifecycle_state\tACTIVE,EXPIRED,REVOKED
            dwp-platform-server\tcal_calendar_access_grants.principal_type\tGROUP,PERSON,TENANT
            dwp-platform-server\tcal_calendars.subscription_policy\tDEFAULT_ON,OPTIONAL,REQUIRED
            dwp-platform-server\tcal_event_occurrence_overrides.importance\tHIGH,LOW,NORMAL
            dwp-platform-server\tcal_event_occurrence_overrides.override_kind\tCANCELLED,MODIFIED
            dwp-platform-server\tcal_events.importance\tHIGH,LOW,NORMAL
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
    void freshMigrationRegistersExactContractsAndIsSemanticallyIdempotent()
            throws Exception {
        cleanAndMigrateThroughV193();

        executeForwardMigration();
        assertExactManifest();
        String fingerprint = registryRevisionFingerprint();

        executeForwardMigration();

        assertExactManifest();
        assertThat(registryRevisionFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void upgradeRetiresPeopleEntitlementsAndOtherRegistryOnlyDrift()
            throws Exception {
        cleanAndMigrateThroughV193();
        seedRegistryOnlyDrift();

        executeForwardMigration();

        assertExactManifest();
        assertThat(jdbc.queryForList("""
                SELECT code_set_key || ':' || code
                  FROM sys_code_values
                 WHERE (code_set_key =
                            'PEOPLE.SYS_PROVIDER_TENANT_COMMAND_RECEIPTS.COMMAND_TYPE'
                            AND code IN ('ENTITLEMENTS', 'LEGACY_REGISTRY_ONLY'))
                    OR (code_set_key =
                            'PLATFORM.CAL_CALENDAR_ACCESS_GRANTS.ACCESS_LEVEL'
                            AND code = 'LEGACY_REGISTRY_ONLY')
                 ORDER BY code_set_key, code
                """, String.class)).containsExactly(
                "PEOPLE.SYS_PROVIDER_TENANT_COMMAND_RECEIPTS.COMMAND_TYPE:ENTITLEMENTS",
                "PEOPLE.SYS_PROVIDER_TENANT_COMMAND_RECEIPTS.COMMAND_TYPE:LEGACY_REGISTRY_ONLY",
                "PLATFORM.CAL_CALENDAR_ACCESS_GRANTS.ACCESS_LEVEL:LEGACY_REGISTRY_ONLY");
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM sys_code_values
                 WHERE ((code_set_key =
                            'PEOPLE.SYS_PROVIDER_TENANT_COMMAND_RECEIPTS.COMMAND_TYPE'
                            AND code IN ('ENTITLEMENTS', 'LEGACY_REGISTRY_ONLY'))
                         OR (code_set_key =
                            'PLATFORM.CAL_CALENDAR_ACCESS_GRANTS.ACCESS_LEVEL'
                            AND code = 'LEGACY_REGISTRY_ONLY'))
                   AND lifecycle_state = 'RETIRED'
                """, Integer.class)).isEqualTo(3);
    }

    private static void cleanAndMigrateThroughV193() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("193")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void seedRegistryOnlyDrift() {
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    behavior_metadata, lifecycle_state)
                VALUES (
                    'PEOPLE.SYS_PROVIDER_TENANT_COMMAND_RECEIPTS.COMMAND_TYPE',
                    'LEGACY_REGISTRY_ONLY', 'Legacy', '{}', '{}', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility, lifecycle_state)
                VALUES (
                    'PLATFORM.CAL_CALENDAR_ACCESS_GRANTS.ACCESS_LEVEL',
                    'dwp-platform-server', 'Stale access level', 'Upgrade fixture',
                    'SYSTEM', 'CHECK', 'cal_calendar_access_grants.access_level',
                    'REFERENCE', 'ADMIN_ONLY', 'RETIRED')
                """);
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    behavior_metadata, lifecycle_state)
                VALUES
                    ('PLATFORM.CAL_CALENDAR_ACCESS_GRANTS.ACCESS_LEVEL',
                     'EDIT', 'Edit', '{}', '{}', 'RETIRED'),
                    ('PLATFORM.CAL_CALENDAR_ACCESS_GRANTS.ACCESS_LEVEL',
                     'LEGACY_REGISTRY_ONLY', 'Legacy', '{}', '{}', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO sys_code_bindings (
                    code_set_key, consumer_service, usage_type,
                    source_reference, enforcement_type, lifecycle_state)
                VALUES (
                    'PLATFORM.CAL_CALENDAR_ACCESS_GRANTS.ACCESS_LEVEL',
                    'dwp-platform-server', 'DATABASE_COLUMN',
                    'cal_calendar_access_grants.access_level', 'CHECK', 'RETIRED')
                """);
    }

    private static void assertExactManifest() {
        Map<String, String> expected = Arrays.stream(EXPECTED_CONTRACTS.strip().split("\\R"))
                .map(line -> line.split("\\t", 3))
                .collect(Collectors.toMap(
                        parts -> parts[0] + "\t" + parts[1],
                        parts -> parts[2]));

        List<ContractSnapshot> snapshots = jdbc.query("""
                SELECT binding.consumer_service,
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
            assertThat(matches.getFirst().codes()).isEqualTo(contract.getValue());
            assertThat(matches.getFirst().runtimeVisibility()).isEqualTo("ADMIN_ONLY");
        }
        assertThat(expected).hasSize(8);
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
