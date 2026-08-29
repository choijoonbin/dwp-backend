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
class RoomBookingEligibilityCodeContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V212__govern_room_booking_eligibility_reason.sql");
    private static final String CODE_SET =
            "PLATFORM.CALENDAR.ROOM_BOOKING_ELIGIBILITY_REASON";
    private static final String SOURCE =
            "CalendarTypes.RoomBookingEligibilityReason";
    private static final List<String> EXPECTED_CODES = List.of(
            "ELIGIBLE",
            "POLICY_BLOCKED",
            "RESOURCE_CONFLICT",
            "RESOURCE_UNAVAILABLE");

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
    void freshMigrationRegistersExactPublicEligibilityContract() {
        cleanAndMigrateThrough(null);

        assertExactContract();
    }

    @Test
    void upgradeFromV211IsSemanticallyIdempotent() throws Exception {
        cleanAndMigrateThrough("211");

        executeForwardMigration();
        assertExactContract();
        String fingerprint = registryFingerprint();

        executeForwardMigration();

        assertExactContract();
        assertThat(registryFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void conflictingSourceBindingFailsClosed() {
        cleanAndMigrateThrough("211");
        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility)
                VALUES (
                    'PLATFORM.CALENDAR.CONFLICTING_BOOKING_ELIGIBILITY',
                    'dwp-platform-server', 'Conflict', 'Conflict fixture',
                    'SYSTEM', 'TYPED_CONTRACT', 'TestBookingEligibility',
                    'SECURITY', 'ADMIN_ONLY')
                """);
        jdbc.update("""
                INSERT INTO sys_code_bindings (
                    code_set_key, consumer_service, usage_type,
                    source_reference, enforcement_type)
                VALUES (
                    'PLATFORM.CALENDAR.CONFLICTING_BOOKING_ELIGIBILITY',
                    'dwp-platform-server', 'API_CONTRACT', ?,
                    'TYPED_CONTRACT')
                """, SOURCE);

        assertThatThrownBy(
                RoomBookingEligibilityCodeContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .hasMessageContaining("binding drifted");
    }

    private static void cleanAndMigrateThrough(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        Flyway flyway = configuration.load();
        flyway.clean();
        flyway.migrate();
    }

    private static void assertExactContract() {
        CodeSetMetadata metadata = jdbc.queryForObject("""
                SELECT owner_service, source_reference, validation_source,
                       contract_kind, configuration_level,
                       runtime_visibility, lifecycle_state
                  FROM sys_code_sets
                 WHERE code_set_key = ?
                """, (row, ignored) -> new CodeSetMetadata(
                row.getString("owner_service"),
                row.getString("source_reference"),
                row.getString("validation_source"),
                row.getString("contract_kind"),
                row.getString("configuration_level"),
                row.getString("runtime_visibility"),
                row.getString("lifecycle_state")), CODE_SET);
        assertThat(metadata).isEqualTo(new CodeSetMetadata(
                "dwp-platform-server", SOURCE, "TYPED_CONTRACT", "SECURITY",
                "SYSTEM", "ADMIN_ONLY", "ACTIVE"));

        assertThat(jdbc.queryForList("""
                SELECT code
                  FROM sys_code_values
                 WHERE code_set_key = ?
                   AND lifecycle_state = 'ACTIVE'
                 ORDER BY code
                """, String.class, CODE_SET)).containsExactlyElementsOf(EXPECTED_CODES);

        Integer bindingCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_code_bindings
                 WHERE code_set_key = ?
                   AND consumer_service = 'dwp-platform-server'
                   AND usage_type = 'API_CONTRACT'
                   AND source_reference = ?
                   AND enforcement_type = 'TYPED_CONTRACT'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, CODE_SET, SOURCE);
        assertThat(bindingCount).isEqualTo(1);
    }

    private static String registryFingerprint() {
        return jdbc.queryForObject("""
                SELECT md5(
                    COALESCE((
                        SELECT string_agg(to_jsonb(code_set)::TEXT, '|'
                                          ORDER BY code_set_key)
                          FROM sys_code_sets code_set), '') || '#' ||
                    COALESCE((
                        SELECT string_agg(to_jsonb(code_value)::TEXT, '|'
                                          ORDER BY code_set_key, code)
                          FROM sys_code_values code_value), '') || '#' ||
                    COALESCE((
                        SELECT string_agg(to_jsonb(binding)::TEXT, '|'
                                          ORDER BY code_binding_id)
                          FROM sys_code_bindings binding), ''))
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

    private record CodeSetMetadata(
            String ownerService,
            String sourceReference,
            String validationSource,
            String contractKind,
            String configurationLevel,
            String runtimeVisibility,
            String lifecycleState) {
    }
}
