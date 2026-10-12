package com.dwp.services.people.hris.employeeservice.employeechange;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmployeeChangeApprovalBridgeMigrationContractTest {

    private static final List<String> TENANT_TABLES = List.of(
            "ppl_employee_change_requests",
            "ppl_employee_change_evidence",
            "ppl_command_receipts",
            "ppl_hris_bridge_proof_replays",
            "ppl_employee_change_approval_request_outbox",
            "ppl_employee_change_approval_bindings",
            "ppl_employee_change_approval_callback_ledger");

    @Test
    void v54OwnsTheExactEmployeeChangeAndApprovalBridgeDurabilityBoundary()
            throws IOException {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V54__create_employee_change_request_lifecycle.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("There is no legacy employee-change")
                    .contains("CREATE TABLE ppl_employee_change_requests")
                    .contains("CREATE TABLE ppl_employee_change_evidence")
                    .contains("CREATE TABLE ppl_command_receipts")
                    .contains("CREATE TABLE ppl_hris_bridge_proof_replays")
                    .contains("CREATE TABLE ppl_employee_change_approval_request_outbox")
                    .contains("CREATE TABLE ppl_employee_change_approval_bindings")
                    .contains("CREATE TABLE ppl_employee_change_approval_callback_ledger")
                    .contains("UNIQUE (tenant_id, decision_event_id)")
                    .contains("UNIQUE (tenant_id, command_receipt_id)")
                    .contains("exact_request_body BYTEA NOT NULL")
                    .contains("exact_response_body BYTEA NOT NULL")
                    .contains("encode(pg_catalog.sha256(exact_request_body), 'hex')")
                    .contains("encode(pg_catalog.sha256(exact_response_body), 'hex')")
                    .contains("BEFORE UPDATE OR DELETE ON ppl_employee_change_approval_bindings")
                    .contains("BEFORE UPDATE OR DELETE ON ppl_employee_change_approval_callback_ledger")
                    .contains("BEFORE UPDATE OR DELETE ON ppl_employee_change_approval_request_outbox")
                    .contains("SECURITY DEFINER")
                    .contains("SET search_path = pg_catalog, public, pg_temp")
                    .contains("REVOKE ALL ON FUNCTION public.protect_ppl_employee_change_request() FROM PUBLIC")
                    .contains("REVOKE ALL ON FUNCTION public.ppl_guard_command_receipt_originating_auth() FROM PUBLIC")
                    .contains("REVOKE ALL ON FUNCTION public.reject_ppl_hris_immutable_mutation() FROM PUBLIC")
                    .contains("NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT")
                    .contains("WITH CHECK (tenant_id = NULLIF(current_setting('dwp.tenant_id', true), '')::BIGINT)")
                    .doesNotContain("CREATE TABLE sys_people_outbox_events")
                    .doesNotContain("CREATE TABLE ppl_assignment_change_proposals");

            String callbackLedger = between(
                    sql,
                    "CREATE TABLE ppl_employee_change_approval_callback_ledger",
                    "CREATE FUNCTION public.protect_ppl_employee_change_request");
            assertThat(callbackLedger)
                    .contains("callback_ledger_id UUID PRIMARY KEY")
                    .contains("tenant_id BIGINT NOT NULL")
                    .contains("decision_event_id UUID NOT NULL")
                    .contains("command_digest CHAR(64) NOT NULL")
                    .contains("command_receipt_id UUID NOT NULL")
                    .doesNotContain("approval_request_id")
                    .doesNotContain("employee_change_request_id")
                    .doesNotContain("decision_actor_public_id");

            for (String table : TENANT_TABLES) {
                assertThat(sql)
                        .contains("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY")
                        .contains("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
            }
        }
    }

    private static String between(String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex);
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return value.substring(startIndex, endIndex);
    }
}
