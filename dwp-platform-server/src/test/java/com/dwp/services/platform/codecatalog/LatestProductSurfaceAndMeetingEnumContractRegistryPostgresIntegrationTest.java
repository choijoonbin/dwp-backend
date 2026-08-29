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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class LatestProductSurfaceAndMeetingEnumContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V210__govern_latest_product_surface_and_meeting_enums.sql");

    private static final String EXPECTED_CODE_SETS = """
            AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND\tACTION,DATA,PAGE
            AUTH.PRODUCT_SURFACE.ACCESS_MODE\tELEVATED,NORMAL,PROVIDER_SUPPORT
            MEETING.INTELLIGENCE.CLIMATE_LABEL\tALIGNED,CONTESTED,INSUFFICIENT_EVIDENCE,MIXED
            MEETING.INTELLIGENCE.CLIMATE_SIGNAL\tBALANCED_TURN_TAKING,CONSTRUCTIVE_DISAGREEMENT,DOMINANT_MONOLOGUE_PATTERN,LOW_TRANSCRIPT_EVIDENCE,UNRESOLVED_DISAGREEMENT
            MEETING.VM_MEETING_CONTENT_ACL.PERMISSION\tMANAGE,REVIEW,VIEW
            MEETING.VM_MEETING_INTELLIGENCE_REPORTS.AUDIENCE\tMEETING_PARTICIPANTS,PRIVATE_REVIEWERS
            MEETING.VM_MEETING_INTELLIGENCE_REPORTS.REPORT_STATE\tAPPROVED,DELETED,DRAFT,PUBLISHED,REJECTED
            MEETING.VM_MEETING_INTELLIGENCE_REVIEWS.DECISION\tAPPROVE,REJECT
            MEETING.VM_MEETING_INTELLIGENCE_RUNS.RUN_STATE\tFAILED,RUNNING,SUCCEEDED
            MEETING.VM_MEETING_MEDIA_OPERATIONS.OPERATION_STATE\tFAILED,RUNNING,SUCCEEDED
            MEETING.VM_MEETING_MEDIA_OPERATIONS.OPERATION_TYPE\tEND,START
            PLATFORM.PRODUCT_SURFACE.ACCESS_CONTRACT_TYPE\tCAPABILITY,POLICY
            PLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS\tALLOWED,DENIED,UNAVAILABLE
            """;

    private static final String EXPECTED_BINDINGS = """
            dwp-meeting-server\tMeetingIntelligenceProvider.ClimateLabel\tMEETING.INTELLIGENCE.CLIMATE_LABEL
            dwp-meeting-server\tMeetingIntelligenceProvider.ClimateSignal\tMEETING.INTELLIGENCE.CLIMATE_SIGNAL
            dwp-meeting-server\tMeetingProductAccessPolicy.ActiveAccessMode\tAUTH.PRODUCT_SURFACE.ACCESS_MODE
            dwp-meeting-server\tMeetingProductAccessPolicy.RouteKind\tAUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND
            dwp-meeting-server\tVideoMeetingIntelligenceModels.Audience\tMEETING.VM_MEETING_INTELLIGENCE_REPORTS.AUDIENCE
            dwp-meeting-server\tVideoMeetingIntelligenceModels.ContentPermission\tMEETING.VM_MEETING_CONTENT_ACL.PERMISSION
            dwp-meeting-server\tVideoMeetingIntelligenceModels.ReportState\tMEETING.VM_MEETING_INTELLIGENCE_REPORTS.REPORT_STATE
            dwp-meeting-server\tVideoMeetingIntelligenceModels.ReviewDecision\tMEETING.VM_MEETING_INTELLIGENCE_REVIEWS.DECISION
            dwp-meeting-server\tVideoMeetingIntelligenceModels.RunState\tMEETING.VM_MEETING_INTELLIGENCE_RUNS.RUN_STATE
            dwp-meeting-server\tVideoMeetingLifecycleModels.OperationState\tMEETING.VM_MEETING_MEDIA_OPERATIONS.OPERATION_STATE
            dwp-meeting-server\tVideoMeetingLifecycleModels.OperationType\tMEETING.VM_MEETING_MEDIA_OPERATIONS.OPERATION_TYPE
            dwp-platform-server\tCalendarProductSurfaceAccessPolicy.Status\tPLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS
            dwp-platform-server\tCalendarProductSurfaceContract.AccessContractType\tPLATFORM.PRODUCT_SURFACE.ACCESS_CONTRACT_TYPE
            dwp-platform-server\tCalendarProductSurfaceContract.RouteKind\tAUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND
            dwp-platform-server\tCommunicationProductSurfacePepFilter.Decision\tPLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS
            dwp-platform-server\tCommunicationProductSurfacePepFilter.RouteKind\tAUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND
            dwp-platform-server\tMailProductSurfaceAccessPolicy.Status\tPLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS
            dwp-platform-server\tMailProductSurfaceContract.AccessContractType\tPLATFORM.PRODUCT_SURFACE.ACCESS_CONTRACT_TYPE
            dwp-platform-server\tMailProductSurfaceContract.RouteKind\tAUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND
            dwp-platform-server\tServicesProductSurfacePepFilter.Decision\tPLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS
            dwp-platform-server\tServicesProductSurfacePepFilter.RouteKind\tAUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND
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
    void freshPlatformMigrationRegistersEveryLatestJavaEnumContract() {
        cleanAndMigrateThrough("210");

        assertExpectedCodeSets();
        assertExpectedTypedBindings();
    }

    @Test
    void upgradeFromV209IsSemanticallyIdempotent() throws Exception {
        cleanAndMigrateThrough("209");

        executeForwardMigration();
        assertExpectedCodeSets();
        assertExpectedTypedBindings();
        String fingerprint = registryRevisionFingerprint();

        executeForwardMigration();

        assertExpectedCodeSets();
        assertExpectedTypedBindings();
        assertThat(registryRevisionFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void conflictingTypedBindingFailsClosed() {
        cleanAndMigrateThrough("209");
        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility)
                VALUES (
                    'MEETING.CONFLICTING.CLIMATE_SIGNAL',
                    'dwp-meeting-server', 'Conflicting test set',
                    'Conflict fixture', 'SYSTEM', 'TYPED_CONTRACT',
                    'TestConflictClimateSignal', 'PROTOCOL', 'ADMIN_ONLY')
                """);
        jdbc.update("""
                INSERT INTO sys_code_bindings (
                    code_set_key, consumer_service, usage_type,
                    source_reference, enforcement_type)
                VALUES (
                    'MEETING.CONFLICTING.CLIMATE_SIGNAL',
                    'dwp-meeting-server', 'API_CONTRACT',
                    'MeetingIntelligenceProvider.ClimateSignal',
                    'TYPED_CONTRACT')
                """);

        assertThatThrownBy(
                LatestProductSurfaceAndMeetingEnumContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .hasMessageContaining("conflicting typed-contract binding");
    }

    @Test
    void reusableCodeSetValueDriftFailsClosed() {
        cleanAndMigrateThrough("209");
        jdbc.update("""
                UPDATE sys_code_values
                   SET lifecycle_state = 'RETIRED'
                 WHERE code_set_key =
                       'AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND'
                   AND code = 'ACTION'
                """);

        assertThatThrownBy(
                LatestProductSurfaceAndMeetingEnumContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .hasMessageContaining("code-set values drifted");
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

    private static void assertExpectedCodeSets() {
        Map<String, String> expected = Arrays.stream(
                        EXPECTED_CODE_SETS.strip().split("\\R"))
                .map(line -> line.split("\\t", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));

        Map<String, String> actual = jdbc.query("""
                SELECT code_set.code_set_key,
                       string_agg(code_value.code, ',' ORDER BY code_value.code)
                           AS codes
                  FROM sys_code_sets code_set
                  JOIN sys_code_values code_value
                    ON code_value.code_set_key = code_set.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE'
                 WHERE code_set.code_set_key = ANY (?::VARCHAR[])
                   AND code_set.lifecycle_state = 'ACTIVE'
                 GROUP BY code_set.code_set_key
                """, preparedStatement -> preparedStatement.setArray(
                1,
                preparedStatement.getConnection().createArrayOf(
                        "VARCHAR", expected.keySet().toArray())),
                resultSet -> {
                    Map<String, String> result = new java.util.HashMap<>();
                    while (resultSet.next()) {
                        result.put(
                                resultSet.getString("code_set_key"),
                                resultSet.getString("codes"));
                    }
                    return result;
                });

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    private static void assertExpectedTypedBindings() {
        List<TypedBinding> expected = Arrays.stream(
                        EXPECTED_BINDINGS.strip().split("\\R"))
                .map(line -> line.split("\\t", 3))
                .map(parts -> new TypedBinding(parts[0], parts[1], parts[2]))
                .toList();

        for (TypedBinding binding : expected) {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM sys_code_bindings
                     WHERE consumer_service = ?
                       AND source_reference = ?
                       AND code_set_key = ?
                       AND usage_type = 'API_CONTRACT'
                       AND enforcement_type = 'TYPED_CONTRACT'
                       AND lifecycle_state = 'ACTIVE'
                    """, Integer.class, binding.consumerService(),
                    binding.sourceReference(), binding.codeSetKey());
            assertThat(count)
                    .as("one active typed binding for %s/%s",
                            binding.consumerService(), binding.sourceReference())
                    .isEqualTo(1);
        }
    }

    private static String registryRevisionFingerprint() {
        return jdbc.queryForObject("""
                WITH registry_rows AS (
                    SELECT 'SET|' || code_set_key || '|' || schema_version || '|'
                               || lifecycle_state || '|' || updated_at::TEXT AS row_value
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

    private record TypedBinding(
            String consumerService,
            String sourceReference,
            String codeSetKey) {
    }
}
