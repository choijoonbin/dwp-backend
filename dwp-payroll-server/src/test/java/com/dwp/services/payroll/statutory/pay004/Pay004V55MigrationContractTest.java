package com.dwp.services.payroll.statutory.pay004;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class Pay004V55MigrationContractTest {

    private static final String RESOURCE =
            "db/migration/V55__pay_base_tfr_pay_004_retirement.sql";
    private static final List<String> TABLES = List.of(
            "pay_statutory_cases",
            "pay_case_basis_refs",
            "pay_case_evidence",
            "pay_case_results",
            "pay_case_corrections");

    @Test
    void v55CreatesExactlyTheFiveLeasedTablesAndNoDuplicateStores()
            throws IOException {
        String sql = migrationSql();
        Matcher matcher = Pattern.compile(
                "(?im)^CREATE\\s+TABLE\\s+([a-z0-9_]+)\\s*\\(")
                .matcher(sql);
        java.util.ArrayList<String> created = new java.util.ArrayList<>();
        while (matcher.find()) created.add(matcher.group(1));
        assertThat(created).containsExactlyElementsOf(TABLES);
        assertThat(sql)
                .doesNotContain("CREATE TABLE pay_command_receipts")
                .doesNotContain("CREATE TABLE pay_outbox_events")
                .doesNotContain("CREATE TABLE sys_audit_outbox")
                .doesNotContain("INSERT INTO")
                .doesNotContain("UPDATE pay_")
                .doesNotContain("DELETE FROM pay_");
    }

    @Test
    void everyTableIsTenantFirstRlsForcedLeastPrivilegeAndAppendOnly()
            throws IOException {
        String sql = migrationSql();
        for (String table : TABLES) {
            assertThat(sql)
                    .containsIgnoringWhitespaces("PRIMARY KEY ( tenant_id,")
                    .contains("REVOKE ALL ON TABLE %I FROM PUBLIC")
                    .contains("ALTER TABLE %I ENABLE ROW LEVEL SECURITY")
                    .contains("ALTER TABLE %I FORCE ROW LEVEL SECURITY")
                    .contains("USING (tenant_id = NULLIF(current_setting(")
                    .contains("WITH CHECK (tenant_id = NULLIF(current_setting(");
            assertThat(sql).contains(table);
        }
        assertThat(sql)
                .contains("GRANT SELECT, INSERT ON TABLE %I TO %I")
                .contains("rolbypassrls")
                .contains("CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I")
                .doesNotContain("GRANT ALL")
                .doesNotContain("GRANT UPDATE ON TABLE")
                .doesNotContain("GRANT DELETE ON TABLE");
    }

    @Test
    void closedStateDigestMoneyAndConditionalPolicyControlsArePresent()
            throws IOException {
        String sql = migrationSql();
        assertThat(sql)
                .contains("'DRAFT', 'VALIDATED', 'BLOCKED_PACK_REQUIRED', "
                        + "'CALCULATED'")
                .contains("'APPROVED', 'FINALIZED', 'CORRECTED', 'CANCELLED'")
                .contains("'NOT_REQUIRED', 'NOT_INSTALLED', 'DISABLED', 'ACTIVE'")
                .contains("^[0-9a-f]{64}$")
                .contains("NUMERIC(19,4)")
                .contains("policy_digest CHAR(64) NOT NULL")
                .contains("case_inputs_digest CHAR(64) NOT NULL")
                .contains("pay_v55_canonical_digest")
                .contains("pay_v55_case_inputs_digest")
                .contains("pay_v55_pack_policy_key")
                .contains("roundingPolicies")
                .contains("case_row.recorded_at IS DISTINCT FROM "
                        + "NEW.calculated_at")
                .contains("country_pack_state = 'ACTIVE'")
                .contains("country_pack_state = 'NOT_REQUIRED'")
                .contains("non_pack_policy_public_id IS NOT NULL")
                .contains("Blocked country-pack state cannot produce a result")
                .contains("Retirement case transition is not allowed")
                .contains("Correction lineage or maker-checker evidence differs");
    }

    @Test
    void securityDefinerFunctionsLockSearchPathAndPublicExecution()
            throws IOException {
        String sql = migrationSql();
        for (String function : List.of(
                "pay_v55_reject_mutation",
                "pay_v55_guard_case_revision",
                "pay_v55_guard_case_attachment",
                "pay_v55_guard_case_result",
                "pay_v55_guard_correction_closure")) {
            assertThat(sql)
                    .contains("ALTER FUNCTION " + function + "() SECURITY DEFINER")
                    .contains("ALTER FUNCTION " + function
                            + "()\n    SET search_path TO pg_catalog, public, pg_temp")
                    .contains("REVOKE ALL ON FUNCTION " + function
                            + "() FROM PUBLIC");
        }
        assertThat(sql)
                .contains("ALTER FUNCTION pay_v55_canonical_digest(TEXT[]) "
                        + "SECURITY DEFINER")
                .contains("ALTER FUNCTION pay_v55_pack_policy_key(TEXT) "
                        + "SECURITY DEFINER")
                .contains("ALTER FUNCTION pay_v55_case_inputs_digest("
                        + "BIGINT, UUID, BIGINT)\n    SECURITY DEFINER")
                .contains("REVOKE ALL ON FUNCTION "
                        + "pay_v55_canonical_digest(TEXT[]) FROM PUBLIC")
                .contains("REVOKE ALL ON FUNCTION "
                        + "pay_v55_pack_policy_key(TEXT) FROM PUBLIC");
    }

    static String migrationSql() throws IOException {
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing " + RESOURCE);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
