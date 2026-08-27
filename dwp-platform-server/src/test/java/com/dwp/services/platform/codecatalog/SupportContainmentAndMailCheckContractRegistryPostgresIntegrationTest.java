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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class SupportContainmentAndMailCheckContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V202__reconcile_support_containment_and_mail_check_contracts.sql");

    private static final String BASE_CONTRACTS = """
            PROVIDER.PRV_OPERATORS.DISPLAY_NAME\tdwp-provider-server\tprv_operators.display_name\tSECURITY\tProvider support containment system
            PROVIDER.PRV_OPERATORS.ROLE_CODE\tdwp-provider-server\tprv_operators.role_code\tSECURITY\tPROVIDER_SYSTEM_CONTAINMENT
            PROVIDER.PRV_SUPPORT_ACCESS_REQUESTS.CANCELLATION_ORIGIN\tdwp-provider-server\tprv_support_access_requests.cancellation_origin\tSECURITY\tAUTOMATIC_OPERATOR_CONTAINMENT,AUTOMATIC_SCOPE_RETIREMENT
            PROVIDER.PRV_SUPPORT_SESSIONS.REVOCATION_ORIGIN\tdwp-provider-server\tprv_support_sessions.revocation_origin\tSECURITY\tAUTOMATIC_OPERATOR_CONTAINMENT,AUTOMATIC_SCOPE_RETIREMENT,AUTOMATIC_TENANT_CONTAINMENT
            PLATFORM.USR_SAVED_VIEW_LIFECYCLE_COMMANDS.SCOPE\tdwp-platform-server\tusr_saved_view_lifecycle_commands.scope\tSECURITY\tPERSONAL,TEAM,TENANT
            """;

    private static final String OPTIONAL_MAIL_CONTRACTS = """
            PLATFORM.MAIL_FOLDERS.COLOR_TOKEN\tdwp-platform-server\tmail_folders.color_token\tREFERENCE\tAMBER,BLUE,CORAL,GREEN,NEUTRAL,TEAL,VIOLET
            PLATFORM.MAIL_FOLDERS.PROVIDER_SYNC_STATE\tdwp-platform-server\tmail_folders.provider_sync_state\tSTATE_MACHINE\tERROR,LOCAL_ONLY,PENDING,SYNCED
            PLATFORM.MAIL_THREADS.WORKFLOW_STATE\tdwp-platform-server\tmail_threads.workflow_state\tSTATE_MACHINE\tARCHIVED,DONE,DRAFT,OPEN,SNOOZED,SPAM,TRASHED
            PLATFORM.MAIL_RULES.MATCH_MODE\tdwp-platform-server\tmail_rules.match_mode\tREFERENCE\tALL,ANY
            PLATFORM.MAIL_RULES.SYNCHRONIZATION_STATE\tdwp-platform-server\tmail_rules.synchronization_state\tSTATE_MACHINE\tERROR,LOCAL_ONLY,PENDING,SYNCED
            PLATFORM.MAIL_RULES.LIFECYCLE_STATE\tdwp-platform-server\tmail_rules.lifecycle_state\tSTATE_MACHINE\tACTIVE,ARCHIVED
            PLATFORM.MAIL_RULE_RUNS.TRIGGER_KIND\tdwp-platform-server\tmail_rule_runs.trigger_kind\tPROTOCOL\tBACKFILL,INCOMING,MANUAL
            PLATFORM.MAIL_RULE_RUNS.RUN_STATUS\tdwp-platform-server\tmail_rule_runs.run_status\tSTATE_MACHINE\tFAILED,RUNNING,SUCCEEDED
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
    void upgradeFromV200WithoutOptionalMailSchemaRegistersOnlyExistingContracts()
            throws Exception {
        cleanAndMigrateThroughV200();

        executeForwardMigration();

        assertExactManifest(BASE_CONTRACTS);
        assertLegacyMailWorkflowContractIsUnchanged();
        assertThat(activeCheckBindingCountForOptionalMailTables()).isZero();
        String fingerprint = registryRevisionFingerprint();

        executeForwardMigration();

        assertExactManifest(BASE_CONTRACTS);
        assertThat(registryRevisionFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void cleanFlywayPathAppliesV202SafelyWithoutV201() {
        cleanAndMigrateThroughV202();

        assertThat(jdbc.queryForObject("""
                SELECT MAX(version::INTEGER)
                  FROM flyway_schema_history
                 WHERE success
                """, Integer.class)).isEqualTo(202);
        assertExactManifest(BASE_CONTRACTS);
        assertLegacyMailWorkflowContractIsUnchanged();
        assertThat(activeCheckBindingCountForOptionalMailTables()).isZero();
    }

    @Test
    void optionalMailLifecycleSchemaBeforeV202RegistersAllThirteenContracts()
            throws Exception {
        cleanAndMigrateThroughV200();
        installOptionalMailLifecycleSchema();

        executeForwardMigration();

        String expectedContracts = BASE_CONTRACTS + OPTIONAL_MAIL_CONTRACTS;
        assertExactManifest(expectedContracts);
        String fingerprint = registryRevisionFingerprint();

        executeForwardMigration();

        assertExactManifest(expectedContracts);
        assertThat(registryRevisionFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void upgradeRepairsCanonicalLifecycleDriftAndRetiresRegistryOnlyValues()
            throws Exception {
        cleanAndMigrateThroughV200();
        seedRegistryDrift();

        executeForwardMigration();

        assertExactManifest(BASE_CONTRACTS);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state
                  FROM sys_code_values
                 WHERE code_set_key =
                       'PLATFORM.USR_SAVED_VIEW_LIFECYCLE_COMMANDS.SCOPE'
                   AND code = 'LEGACY_REGISTRY_ONLY'
                """, String.class)).isEqualTo("RETIRED");
        assertThat(jdbc.queryForMap("""
                SELECT configuration_level, validation_source, contract_kind,
                       runtime_visibility, lifecycle_state
                  FROM sys_code_sets
                 WHERE code_set_key =
                       'PLATFORM.USR_SAVED_VIEW_LIFECYCLE_COMMANDS.SCOPE'
                """)).containsAllEntriesOf(Map.of(
                "configuration_level", "SYSTEM",
                "validation_source", "CHECK",
                "contract_kind", "SECURITY",
                "runtime_visibility", "ADMIN_ONLY",
                "lifecycle_state", "ACTIVE"));
    }

    @Test
    void partialOptionalMailLifecycleSchemaFailsClosed() {
        cleanAndMigrateThroughV200();
        jdbc.execute("""
                ALTER TABLE mail_folders
                    ADD COLUMN color_token VARCHAR(24) NOT NULL DEFAULT 'NEUTRAL',
                    ADD CONSTRAINT ck_mail_folder_color
                        CHECK (color_token IN (
                            'NEUTRAL', 'BLUE', 'TEAL', 'GREEN',
                            'AMBER', 'CORAL', 'VIOLET'))
                """);

        assertThatThrownBy(
                SupportContainmentAndMailCheckContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(
                        "optional mail lifecycle columns without mail_rules");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_code_sets
                 WHERE code_set_key =
                       'PROVIDER.PRV_SUPPORT_SESSIONS.REVOCATION_ORIGIN'
                """, Integer.class)).isZero();
    }

    private static void cleanAndMigrateThroughV200() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("200")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void cleanAndMigrateThroughV202() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("202")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void installOptionalMailLifecycleSchema() {
        jdbc.execute("""
                ALTER TABLE mail_folders
                    ADD COLUMN color_token VARCHAR(24) NOT NULL DEFAULT 'NEUTRAL',
                    ADD COLUMN provider_sync_state VARCHAR(24)
                        NOT NULL DEFAULT 'LOCAL_ONLY',
                    ADD CONSTRAINT ck_mail_folder_color
                        CHECK (color_token IN (
                            'NEUTRAL', 'BLUE', 'TEAL', 'GREEN',
                            'AMBER', 'CORAL', 'VIOLET')),
                    ADD CONSTRAINT ck_mail_folder_sync_state
                        CHECK (provider_sync_state IN (
                            'LOCAL_ONLY', 'PENDING', 'SYNCED', 'ERROR'))
                """);
        jdbc.execute("""
                ALTER TABLE mail_threads
                    DROP CONSTRAINT ck_mail_thread_workflow,
                    ADD CONSTRAINT ck_mail_thread_workflow
                        CHECK (workflow_state IN (
                            'OPEN', 'DONE', 'SNOOZED', 'ARCHIVED',
                            'DRAFT', 'TRASHED', 'SPAM'))
                """);
        jdbc.execute("""
                CREATE TABLE mail_rules (
                    rule_id UUID PRIMARY KEY,
                    match_mode VARCHAR(8) NOT NULL,
                    synchronization_state VARCHAR(24) NOT NULL,
                    lifecycle_state VARCHAR(20) NOT NULL,
                    CONSTRAINT ck_mail_rule_match_mode
                        CHECK (match_mode IN ('ALL', 'ANY')),
                    CONSTRAINT ck_mail_rule_sync_state
                        CHECK (synchronization_state IN (
                            'LOCAL_ONLY', 'PENDING', 'SYNCED', 'ERROR')),
                    CONSTRAINT ck_mail_rule_state
                        CHECK (lifecycle_state IN ('ACTIVE', 'ARCHIVED'))
                )
                """);
        jdbc.execute("""
                CREATE TABLE mail_rule_runs (
                    rule_run_id UUID PRIMARY KEY,
                    trigger_kind VARCHAR(24) NOT NULL,
                    run_status VARCHAR(20) NOT NULL,
                    CONSTRAINT ck_mail_rule_run_trigger
                        CHECK (trigger_kind IN (
                            'MANUAL', 'INCOMING', 'BACKFILL')),
                    CONSTRAINT ck_mail_rule_run_status
                        CHECK (run_status IN (
                            'RUNNING', 'SUCCEEDED', 'FAILED'))
                )
                """);
    }

    private static void seedRegistryDrift() {
        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility, lifecycle_state)
                VALUES (
                    'PLATFORM.USR_SAVED_VIEW_LIFECYCLE_COMMANDS.SCOPE',
                    'dwp-platform-server', 'Stale saved-view scope',
                    'Upgrade fixture', 'EXTENSIBLE', 'CHECK',
                    'usr_saved_view_lifecycle_commands.scope',
                    'REFERENCE', 'RUNTIME', 'RETIRED')
                """);
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    behavior_metadata, lifecycle_state)
                VALUES
                    ('PLATFORM.USR_SAVED_VIEW_LIFECYCLE_COMMANDS.SCOPE',
                     'PERSONAL', 'Personal', '{}', '{}', 'RETIRED'),
                    ('PLATFORM.USR_SAVED_VIEW_LIFECYCLE_COMMANDS.SCOPE',
                     'LEGACY_REGISTRY_ONLY', 'Legacy', '{}', '{}', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO sys_code_bindings (
                    code_set_key, consumer_service, usage_type,
                    source_reference, enforcement_type, lifecycle_state)
                VALUES (
                    'PLATFORM.USR_SAVED_VIEW_LIFECYCLE_COMMANDS.SCOPE',
                    'dwp-platform-server', 'DATABASE_COLUMN',
                    'usr_saved_view_lifecycle_commands.scope',
                    'CHECK', 'RETIRED')
                """);
    }

    private static void assertExactManifest(String manifest) {
        Map<String, ExpectedContract> expected = Arrays.stream(
                        manifest.strip().split("\\R"))
                .map(line -> line.split("\\t", 5))
                .collect(Collectors.toMap(
                        parts -> parts[0],
                        parts -> new ExpectedContract(
                                parts[1], parts[2], parts[3], parts[4])));

        List<ContractSnapshot> snapshots = jdbc.query("""
                SELECT code_set.code_set_key,
                       binding.consumer_service,
                       binding.source_reference,
                       code_set.configuration_level,
                       code_set.validation_source,
                       code_set.contract_kind,
                       code_set.runtime_visibility,
                       string_agg(code_value.code, ',' ORDER BY code_value.code)
                           AS codes
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
                          binding.source_reference,
                          code_set.configuration_level,
                          code_set.validation_source,
                          code_set.contract_kind,
                          code_set.runtime_visibility
                """, (row, ignored) -> new ContractSnapshot(
                row.getString("code_set_key"),
                row.getString("consumer_service"),
                row.getString("source_reference"),
                row.getString("configuration_level"),
                row.getString("validation_source"),
                row.getString("contract_kind"),
                row.getString("runtime_visibility"),
                row.getString("codes")));

        expected.forEach((codeSetKey, contract) -> {
            List<ContractSnapshot> matches = snapshots.stream()
                    .filter(snapshot -> snapshot.consumerService()
                            .equals(contract.ownerService()))
                    .filter(snapshot -> snapshot.sourceReference()
                            .equals(contract.sourceReference()))
                    .toList();
            assertThat(matches)
                    .as("one canonical CHECK registration for %s", codeSetKey)
                    .hasSize(1);
            ContractSnapshot snapshot = matches.getFirst();
            assertThat(snapshot.codeSetKey()).isEqualTo(codeSetKey);
            assertThat(snapshot.configurationLevel()).isEqualTo("SYSTEM");
            assertThat(snapshot.validationSource()).isEqualTo("CHECK");
            assertThat(snapshot.contractKind()).isEqualTo(contract.contractKind());
            assertThat(snapshot.runtimeVisibility()).isEqualTo("ADMIN_ONLY");
            assertThat(snapshot.codes()).isEqualTo(contract.codes());
        });
        assertThat(expected).hasSize(
                manifest.contains("PLATFORM.MAIL_FOLDERS.COLOR_TOKEN") ? 13 : 5);
    }

    private static void assertLegacyMailWorkflowContractIsUnchanged() {
        assertThat(jdbc.queryForObject("""
                SELECT string_agg(code, ',' ORDER BY code)
                  FROM sys_code_values
                 WHERE code_set_key = 'PLATFORM.MAIL_THREADS.WORKFLOW_STATE'
                   AND lifecycle_state = 'ACTIVE'
                """, String.class)).isEqualTo("ARCHIVED,DONE,DRAFT,OPEN,SNOOZED");
    }

    private static int activeCheckBindingCountForOptionalMailTables() {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_code_bindings
                 WHERE lifecycle_state = 'ACTIVE'
                   AND enforcement_type = 'CHECK'
                   AND source_reference IN (
                       'mail_folders.color_token',
                       'mail_folders.provider_sync_state',
                       'mail_rules.match_mode',
                       'mail_rules.synchronization_state',
                       'mail_rules.lifecycle_state',
                       'mail_rule_runs.trigger_kind',
                       'mail_rule_runs.run_status')
                """, Integer.class);
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
                    SELECT 'BINDING|' || code_binding_id || '|' || code_set_key
                               || '|' || lifecycle_state || '|' || updated_at::TEXT
                      FROM sys_code_bindings
                )
                SELECT string_agg(row_value, E'\\n' ORDER BY row_value)
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

    private record ExpectedContract(
            String ownerService,
            String sourceReference,
            String contractKind,
            String codes) {
    }

    private record ContractSnapshot(
            String codeSetKey,
            String consumerService,
            String sourceReference,
            String configurationLevel,
            String validationSource,
            String contractKind,
            String runtimeVisibility,
            String codes) {
    }
}
