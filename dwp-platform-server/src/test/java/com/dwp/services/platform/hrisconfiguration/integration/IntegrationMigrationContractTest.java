package com.dwp.services.platform.hrisconfiguration.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationMigrationContractTest {

    static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V334__create_hris_integration_control_plane.sql");
    static final Set<String> TABLES = Set.of(
            "sys_connector_definitions",
            "sys_connector_definition_versions",
            "sys_connector_mapping_versions",
            "sys_connector_executions",
            "sys_connector_execution_items",
            "sys_connector_execution_attempts");

    @Test
    void v334CreatesExactlySixTenantFencedTablesAndNoParallelLedger()
            throws Exception {
        String sql = Files.readString(MIGRATION);
        Set<String> actual = Pattern.compile("(?m)^CREATE TABLE ([a-z0-9_]+) ")
                .matcher(sql).results().map(result -> result.group(1))
                .collect(Collectors.toUnmodifiableSet());

        assertThat(actual).isEqualTo(TABLES);
        TABLES.forEach(table -> assertThat(sql)
                .contains("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY;")
                .contains("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY;")
                .contains("REVOKE ALL ON TABLE " + table + " FROM PUBLIC;"));
        assertThat(sql).doesNotContain(
                "CREATE TABLE sys_connector_command_receipts",
                "CREATE TABLE sys_connector_audit",
                "CREATE TABLE sys_connector_events",
                "GRANT ", "INSERT INTO sys_connector_");
    }

    @Test
    void v334ScopesTerminalReceiptRulesToTheSixteenSys008Actions()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(Sys008ProjectionContract.ACTION_KEYS).hasSize(16);
        Sys008ProjectionContract.ACTION_KEYS.forEach(action ->
                assertThat(sql).contains("'" + action + "'"));
        assertThat(sql)
                .contains("SYS008_TERMINAL_COMMAND_RECEIPT_IMMUTABLE")
                .contains("OLD.originating_action NOT IN")
                .doesNotContain("originating_action LIKE 'hris.action.sys.%'")
                .doesNotContain("DROP FUNCTION sys_automation_guard_shared_receipt")
                .doesNotContain("DROP TRIGGER trg_sys_automation_shared_receipt");
    }

    @Test
    void v334ContainsImmutableVersionExecutionFenceAndCompensationGuards()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("SYS008_DEFINITION_VERSION_SUBJECT_IMMUTABLE")
                .contains("SYS008_MAPPING_VERSION_SUBJECT_IMMUTABLE")
                .contains("SYS008_EXECUTION_ACCEPTANCE_SNAPSHOT_IMMUTABLE")
                .contains("SYS008_STALE_ATTEMPT_FENCE_REJECTED")
                .contains("SYS008_FAILED_ONLY_RETRY_SET_REJECTED")
                .contains("SYS008_POST_DISPATCH_COMPENSATION_REQUIRED")
                .contains("SYS008_TERMINAL_EXECUTION_IMMUTABLE")
                .contains("SYS008_TERMINAL_EXECUTION_ITEM_IMMUTABLE")
                .contains("SYS008_TERMINAL_EXECUTION_ATTEMPT_IMMUTABLE");
    }

    @Test
    void forbiddenSecretPayloadAndEmployeeColumnsAreAbsent() throws Exception {
        String sql = Files.readString(MIGRATION).toLowerCase();
        assertThat(sql).doesNotContain(
                "secret_value", "plaintext_secret", "raw_payload",
                "provider_request_payload", "provider_response_payload",
                "mapping_body", "employee_id", "person_id");
    }
}
