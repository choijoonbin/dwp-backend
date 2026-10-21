package com.dwp.services.platform.hrisconfiguration.automation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V333__create_hris_automation_control_plane.sql");
    private static final Set<String> TABLES = Set.of(
            "sys_automation_definitions", "sys_automation_versions",
            "sys_automation_schedules", "sys_automation_runs",
            "sys_automation_run_attempts", "sys_automation_run_items",
            "sys_automation_item_attempts", "sys_automation_run_checkpoints");

    @Test
    void v333CreatesExactlyEightTenantFencedAutomationTables() throws Exception {
        String sql = Files.readString(MIGRATION);
        var matcher = Pattern.compile("(?m)^CREATE TABLE ([a-z0-9_]+) ")
                .matcher(sql);
        Set<String> actual = matcher.results().map(result -> result.group(1))
                .collect(Collectors.toUnmodifiableSet());

        assertThat(actual).isEqualTo(TABLES);
        for (String table : TABLES) {
            assertThat(sql).contains(
                    "ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY;")
                    .contains("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY;")
                    .contains("CREATE POLICY " + table + "_tenant ON " + table);
        }
        assertThat(sql).contains("current_setting('dwp.tenant_id', TRUE)")
                .doesNotContain("CREATE EXTENSION", "DROP TABLE", "DROP TRIGGER");
    }

    @Test
    void v333PersistsTheCompletePinnedRuntimeAndRetryEvidence() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("requested_at TIMESTAMPTZ NOT NULL")
                .contains("requested_by_actor_ref VARCHAR(240) NOT NULL")
                .contains("idempotency_key VARCHAR(128) NOT NULL")
                .contains("correlation_id UUID NOT NULL")
                .contains("expected_item_count BIGINT NOT NULL")
                .contains("scope_id UUID")
                .contains("subject_ref VARCHAR(500) NOT NULL")
                .contains("accepted_at TIMESTAMPTZ NOT NULL")
                .contains("next_attempt_at TIMESTAMPTZ")
                .contains("result_ref VARCHAR(500)")
                .contains("retryable_snapshot BOOLEAN NOT NULL")
                .contains("worker_fence_epoch BIGINT NOT NULL")
                .contains("reconciliation_fence_epoch BIGINT NOT NULL")
                .contains("reconciliation_required_at TIMESTAMPTZ")
                .contains("reconciliation_required_reason VARCHAR(80)")
                .contains("compensation_effect_state VARCHAR(24)")
                .contains("compensation_resolution_mode VARCHAR(24)")
                .contains("FOREIGN KEY (tenant_id, run_id, run_item_id)")
                .contains("FOREIGN KEY (tenant_id, run_id, attempt_number, run_attempt_id)")
                .contains("tenant_id, definition_id, version_id, pinned_version_number)")
                .contains("FOREIGN KEY (tenant_id, definition_id, version_id, version_number)")
                .contains("FOREIGN KEY (tenant_id, definition_id, active_version_number)");
    }

    @Test
    void databaseGuardsLiveExternalEffectsCheckpointsAndImmutableCompletion()
            throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("trg_sys_automation_original_effect_authority")
                .contains("BEFORE INSERT OR UPDATE ON sys_automation_item_attempts")
                .contains("SYS006_ORIGINAL_EFFECT_RESOLUTION_REQUIRED")
                .contains("trg_sys_automation_compensation_authority")
                .contains("BEFORE INSERT OR UPDATE ON sys_automation_run_items")
                .contains("SYS006_COMPENSATION_RESOLUTION_REQUIRED")
                .contains("trg_sys_automation_checkpoint_authority")
                .contains("SYS006_CHECKPOINT_TRUSTED_RECEIPT_REJECTED")
                .contains("statement_timestamp()")
                .contains("SYS006_TERMINAL_RUN_IMMUTABLE")
                .contains("SYS006_CHECKPOINT_IMMUTABLE")
                .contains("SYS006_DEDUPED_ITEM_EVIDENCE_REJECTED");
    }

    @Test
    void sharedReceiptExtensionIsLimitedToTheFourteenSealedSys006Actions()
            throws Exception {
        String sql = Files.readString(MIGRATION);
        Set<String> actions = Set.of(
                AutomationService.CREATE_DEFINITION,
                AutomationService.QUEUE_RUN,
                AutomationService.RETRY_RUN,
                AutomationService.CREATE_VERSION,
                AutomationService.VALIDATE_VERSION,
                AutomationService.SUBMIT_VERSION,
                AutomationService.APPROVE_VERSION,
                AutomationService.ACTIVATE_VERSION,
                AutomationService.CREATE_SCHEDULE,
                AutomationService.SUBMIT_SCHEDULE,
                AutomationService.APPROVE_SCHEDULE,
                AutomationService.ACTIVATE_SCHEDULE,
                AutomationService.SUSPEND_SCHEDULE,
                AutomationService.CANCEL_RUN);

        assertThat(actions).hasSize(14);
        actions.forEach(action -> assertThat(sql).contains("'" + action + "'"));
        assertThat(sql)
                .contains("OLD.originating_action NOT IN")
                .contains("SYS006_TERMINAL_COMMAND_RECEIPT_IMMUTABLE")
                .doesNotContain("originating_action LIKE 'hris.action.sys.%'")
                .doesNotContain("DROP FUNCTION sys_hris_guard_command_receipt_seal")
                .doesNotContain("DROP TRIGGER trg_sys_hris_command_receipt_seal");
    }
}
