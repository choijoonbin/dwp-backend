package com.dwp.services.payroll.statutory.pay005;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class Pay005V57MigrationContractTest {

    private static final String RESOURCE =
            "db/migration/V57__pay_base_tfr_pay_005_tax_result.sql";

    @Test
    void v57CreatesExactlyOneTypedFactAndNoReceiptOutboxSeedOrBackfill()
            throws IOException {
        String sql = migrationSql();
        Matcher matcher = Pattern.compile(
                "(?im)^CREATE\\s+TABLE\\s+([a-z0-9_]+)\\s*\\(")
                .matcher(sql);
        java.util.ArrayList<String> created = new java.util.ArrayList<>();
        while (matcher.find()) created.add(matcher.group(1));
        assertThat(created).containsExactly("pay_tax_result_facts");
        assertThat(sql)
                .contains("provider_result_type = 'TAX_RESULT'")
                .contains("provider_source_semantics = "
                        + "'TYPED_COUNTRY_RULE_OUTPUT'")
                .contains("REFERENCES pay_worker_results")
                .contains("REFERENCES pay_country_pack_activation_versions")
                .contains("line.component_code LIKE 'TAX.%'")
                .doesNotContain("CREATE TABLE pay_command_receipts")
                .doesNotContain("CREATE TABLE pay_outbox")
                .doesNotContain("INSERT INTO pay_")
                .doesNotContain("UPDATE pay_")
                .doesNotContain("DELETE FROM pay_");
    }

    @Test
    void v57IsTenantForcedRlsAppendOnlyAndLeastPrivilege() throws IOException {
        String sql = migrationSql();
        assertThat(sql)
                .contains("UNIQUE (tenant_id, tax_result_fact_id)")
                .contains("UNIQUE (tenant_id, worker_result_id)")
                .contains("REVOKE ALL ON TABLE pay_tax_result_facts FROM PUBLIC")
                .contains("ALTER TABLE pay_tax_result_facts "
                        + "ENABLE ROW LEVEL SECURITY")
                .contains("ALTER TABLE pay_tax_result_facts "
                        + "FORCE ROW LEVEL SECURITY")
                .contains("dwp.payroll_tenant_id")
                .contains("BEFORE UPDATE OR DELETE ON pay_tax_result_facts")
                .contains("GRANT SELECT ON TABLE pay_tax_result_facts")
                .contains("GRANT SELECT, INSERT ON TABLE pay_tax_result_facts")
                .contains("rolbypassrls")
                .doesNotContain("GRANT ALL")
                .doesNotContain("GRANT UPDATE")
                .doesNotContain("GRANT DELETE");
    }

    @Test
    void v57GuardsExactSourceActivePackAndBidirectionalSocialLineage()
            throws IOException {
        String sql = migrationSql();
        assertThat(sql)
                .contains("source_amount IS DISTINCT FROM NEW.amount")
                .contains("canonical_ids IS DISTINCT FROM "
                        + "NEW.source_result_line_ids")
                .contains("activation_state IS DISTINCT FROM 'ACTIVE'")
                .contains("activation_policy_version IS DISTINCT FROM "
                        + "NEW.policy_version")
                .contains("Tax and social-insurance statutory lineage differs")
                .contains("pay_social_insurance_tax_lineage_guard")
                .contains("NUMERIC(19,4)");
    }

    static String migrationSql() throws IOException {
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing " + RESOURCE);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
