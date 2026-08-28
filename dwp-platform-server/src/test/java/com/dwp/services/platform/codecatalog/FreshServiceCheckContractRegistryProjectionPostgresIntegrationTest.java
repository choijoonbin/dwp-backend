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
class FreshServiceCheckContractRegistryProjectionPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V209__reconcile_fresh_service_check_contracts.sql");

    private static final String EXPECTED_ACTIVE_CONTRACTS = """
            dwp-auth-server\tauth_product_predicate_policy.owner_service_key\tagent,approval,auth,meeting,messaging,notification,people,platform,space
            dwp-meeting-server\tvm_meeting_content_acl.content_type\tINTELLIGENCE_REPORT
            dwp-meeting-server\tvm_meeting_content_acl.permission\tMANAGE,REVIEW,VIEW
            dwp-meeting-server\tvm_meeting_intelligence_deletions.deletion_reason\tRETENTION_EXPIRED
            dwp-meeting-server\tvm_meeting_intelligence_reports.audience\tMEETING_PARTICIPANTS,PRIVATE_REVIEWERS
            dwp-meeting-server\tvm_meeting_intelligence_reports.report_state\tAPPROVED,DELETED,DRAFT,PUBLISHED,REJECTED
            dwp-meeting-server\tvm_meeting_intelligence_retention_health.health_key\tREPORT_RETENTION
            dwp-meeting-server\tvm_meeting_intelligence_reviews.decision\tAPPROVE,REJECT
            dwp-meeting-server\tvm_meeting_intelligence_runs.analysis_profile\tSTANDARD_RECAP_V1
            dwp-meeting-server\tvm_meeting_intelligence_runs.run_state\tFAILED,RUNNING,SUCCEEDED
            dwp-meeting-server\tvm_meeting_media_operations.operation_state\tFAILED,RUNNING,SUCCEEDED
            dwp-meeting-server\tvm_meeting_media_operations.operation_type\tEND,START
            dwp-notification-server\tntf_notification_intents.decision\tDUPLICATE,MATERIALIZED,QUARANTINED,SUPPRESSED
            dwp-provider-server\tprv_operation_step_attempts.lifecycle_state\tABANDONED,FAILED,RUNNING,SUCCEEDED
            """;

    private static final List<String> RETIRED_AUTH_AGENT_SOURCES = List.of(
            "ai_agent_citations.source_type",
            "ai_agent_runs.answer_state",
            "ai_agent_runs.policy_outcome",
            "ai_agent_runs.risk_tier",
            "ai_agent_runs.run_state",
            "ai_answer_feedback.rating",
            "ai_conversation_messages.role",
            "ai_model_calls.call_state");

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
    void freshPlatformMigrationProjectsTheReconciledContracts() {
        cleanAndMigrateThrough(null);

        assertProjectedActiveContracts();
        assertRetiredAuthAgentContracts();
    }

    @Test
    void upgradeFromV208IsSemanticallyIdempotent() throws Exception {
        cleanAndMigrateThrough("208");

        executeForwardMigration();
        assertProjectedActiveContracts();
        assertRetiredAuthAgentContracts();
        String fingerprint = registryRevisionFingerprint();

        executeForwardMigration();

        assertProjectedActiveContracts();
        assertRetiredAuthAgentContracts();
        assertThat(registryRevisionFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void conflictingActiveBindingFailsClosed() {
        cleanAndMigrateThrough("208");
        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility)
                VALUES (
                    'MEETING.CONFLICTING.CONTENT_TYPE',
                    'dwp-meeting-server', 'Conflicting test set',
                    'Conflict fixture', 'SYSTEM', 'CHECK',
                    'vm_meeting_content_acl.content_type',
                    'REFERENCE', 'ADMIN_ONLY')
                """);
        jdbc.update("""
                INSERT INTO sys_code_bindings (
                    code_set_key, consumer_service, usage_type,
                    source_reference, enforcement_type)
                VALUES (
                    'MEETING.CONFLICTING.CONTENT_TYPE',
                    'dwp-meeting-server', 'DATABASE_COLUMN',
                    'vm_meeting_content_acl.content_type', 'CHECK')
                """);

        assertThatThrownBy(FreshServiceCheckContractRegistryProjectionPostgresIntegrationTest
                ::executeForwardMigration)
                .hasMessageContaining("conflicting active CHECK binding");
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

    private static void assertProjectedActiveContracts() {
        Map<String, String> expected = Arrays.stream(
                        EXPECTED_ACTIVE_CONTRACTS.strip().split("\\R"))
                .map(line -> line.split("\\t", 3))
                .collect(Collectors.toMap(
                        parts -> parts[0] + "\t" + parts[1],
                        parts -> parts[2]));

        List<ContractSnapshot> snapshots = jdbc.query("""
                SELECT binding.consumer_service,
                       binding.source_reference,
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
                 GROUP BY binding.consumer_service, binding.source_reference
                """, (resultSet, rowNumber) -> new ContractSnapshot(
                resultSet.getString("consumer_service"),
                resultSet.getString("source_reference"),
                resultSet.getString("codes")));

        for (Map.Entry<String, String> contract : expected.entrySet()) {
            List<ContractSnapshot> matches = snapshots.stream()
                    .filter(snapshot -> snapshot.contractReference().equals(contract.getKey()))
                    .toList();
            assertThat(matches)
                    .as("one active registration for %s", contract.getKey())
                    .hasSize(1);
            assertThat(matches.getFirst().codes())
                    .as("active values for %s", contract.getKey())
                    .isEqualTo(contract.getValue());
        }
    }

    private static void assertRetiredAuthAgentContracts() {
        List<String> retiredSources = jdbc.queryForList("""
                SELECT code_set.source_reference
                  FROM sys_code_sets code_set
                 WHERE code_set.owner_service = 'dwp-auth-server'
                   AND code_set.source_reference IN (
                       'ai_agent_citations.source_type',
                       'ai_agent_runs.answer_state',
                       'ai_agent_runs.policy_outcome',
                       'ai_agent_runs.risk_tier',
                       'ai_agent_runs.run_state',
                       'ai_answer_feedback.rating',
                       'ai_conversation_messages.role',
                       'ai_model_calls.call_state')
                   AND code_set.lifecycle_state = 'RETIRED'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM sys_code_bindings binding
                        WHERE binding.code_set_key = code_set.code_set_key
                          AND binding.lifecycle_state <> 'RETIRED')
                   AND NOT EXISTS (
                       SELECT 1
                         FROM sys_code_values code_value
                        WHERE code_value.code_set_key = code_set.code_set_key
                          AND code_value.lifecycle_state <> 'RETIRED')
                 ORDER BY code_set.source_reference
                """, String.class);
        assertThat(retiredSources)
                .containsExactlyElementsOf(RETIRED_AUTH_AGENT_SOURCES);
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

    private record ContractSnapshot(
            String consumerService,
            String sourceReference,
            String codes) {

        private String contractReference() {
            return consumerService + "\t" + sourceReference;
        }
    }
}
