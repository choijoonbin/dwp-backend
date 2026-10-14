package com.dwp.services.platform.hrisconfiguration.explorer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class WorkExplorerMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V327__create_hris_work_explorer.sql");

    @Test
    void v327OwnsExactlyTheSealedFourTablesAndNoSourcePayload() throws Exception {
        String sql = Files.readString(MIGRATION);
        List<String> tables = matches(
                sql, "(?m)^CREATE TABLE ([a-z0-9_]+) ", 1);
        List<String> references = matches(
                sql, "(?m)\\bREFERENCES\\s+([a-z0-9_]+)", 1);

        assertThat(tables).containsExactly(
                "sys_hris_explorer_preferences",
                "sys_hris_explorer_favorites",
                "sys_hris_explorer_recents",
                "sys_hris_command_receipts");
        assertThat(references).containsExactly("sys_hris_explorer_preferences");
        assertThat(sql)
                .doesNotContain(
                        "request_payload",
                        "response_payload",
                        "source_payload",
                        "case_body",
                        "worker_identifier",
                        "explorer_command_receipts")
                .contains("subject_principal_public_id UUID NOT NULL")
                .contains("CREATE TABLE sys_hris_explorer_favorites (\n"
                        + "    favorite_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),\n"
                        + "    tenant_id BIGINT NOT NULL,\n"
                        + "    principal_public_id UUID NOT NULL")
                .contains("explorer_preference_id UUID PRIMARY KEY")
                .contains("favorite_id UUID PRIMARY KEY")
                .contains("recent_id UUID PRIMARY KEY")
                .contains("command_receipt_id UUID PRIMARY KEY")
                .contains("population_scope_digest CHAR(64) NOT NULL")
                .contains("authority_population_scope_digest CHAR(64) NOT NULL")
                .contains("field_policy_revision BIGINT NOT NULL")
                .contains("authority_field_policy_revision BIGINT NOT NULL")
                .contains("authorization_revision BIGINT NOT NULL")
                .contains("authority_authorization_revision BIGINT NOT NULL")
                .contains("originating_action VARCHAR(128) NOT NULL")
                .contains("purpose_code VARCHAR(64) NOT NULL")
                .contains("length(originating_action) BETWEEN 1 AND 128")
                .contains("length(purpose_code) BETWEEN 3 AND 64");
    }

    @Test
    void v327BoundsFavoritesRecentsAndSharedReceiptReplay() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("UNIQUE (tenant_id, principal_public_id)")
                .contains("updated_by = principal_public_id")
                .contains("tenant_id, principal_public_id, explorer_preference_id, route_key)")
                .contains("tenant_id, principal_public_id, explorer_preference_id, position)")
                .contains("position BETWEEN 1 AND 20")
                .contains("UNIQUE (tenant_id, principal_public_id, route_key)")
                .contains("expires_at = last_visited_at + INTERVAL '30 days'")
                .contains("idempotency_key VARCHAR(128) NOT NULL")
                .contains("length(idempotency_key) BETWEEN 16 AND 128")
                .contains("subject_principal_public_id,\n"
                        + "            originating_action,\n"
                        + "            idempotency_key)")
                .contains("request_digest ~ '^[a-f0-9]{64}$'")
                .contains("authority_population_scope_digest ~ '^[a-f0-9]{64}$'")
                .contains("field_policy_revision BETWEEN 1 AND 9007199254740991")
                .contains("authorization_revision BETWEEN 1 AND 9007199254740991")
                .contains("authority_field_policy_revision > 0")
                .contains("authority_authorization_revision > 0")
                .contains("authority_field_policy_revision & 9007199254740991")
                .contains("authority_authorization_revision & 9007199254740991")
                .contains("result_ref IS NULL OR length(result_ref) BETWEEN 1 AND 500")
                .contains("completed_at IS NULL OR completed_at >= created_at")
                .contains("'RESULT_UNKNOWN'")
                .contains("trg_sys_hris_command_receipt_seal")
                .contains("Platform HRIS command receipt cannot be deleted")
                .contains("authorization and idempotency seal is immutable");
        assertThat(sql).containsSubsequence(
                "NEW.field_policy_revision",
                "NEW.authority_field_policy_revision",
                "NEW.authorization_revision",
                "NEW.authority_authorization_revision");
    }

    @Test
    void allFourTablesEnableAndForceTenantAndPrincipalRls() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(matches(
                sql,
                "(?m)^ALTER TABLE ([a-z0-9_]+) ENABLE ROW LEVEL SECURITY;$",
                1)).containsExactlyInAnyOrderElementsOf(List.of(
                        "sys_hris_explorer_preferences",
                        "sys_hris_explorer_favorites",
                        "sys_hris_explorer_recents",
                        "sys_hris_command_receipts"));
        assertThat(matches(
                sql,
                "(?m)^ALTER TABLE ([a-z0-9_]+) FORCE ROW LEVEL SECURITY;$",
                1)).containsExactlyInAnyOrderElementsOf(List.of(
                        "sys_hris_explorer_preferences",
                        "sys_hris_explorer_favorites",
                        "sys_hris_explorer_recents",
                        "sys_hris_command_receipts"));
        assertThat(matches(sql, "(?m)^CREATE POLICY ([a-z0-9_]+)$", 1))
                .hasSize(4);
        assertThat(sql).containsOnlyOnce(
                "REFERENCES sys_hris_explorer_preferences");
        assertThat(sql).contains("current_setting('dwp.tenant_id', TRUE)");
        assertThat(sql).contains("current_setting('dwp.principal_public_id', TRUE)");
    }

    private static List<String> matches(String input, String expression, int group) {
        return Pattern.compile(expression)
                .matcher(input)
                .results()
                .map(result -> result.group(group))
                .toList();
    }
}
