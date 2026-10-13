package com.dwp.services.time.interpretation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TimeInterpretationMigrationContractTest {

    @Test
    void v10PinsImmutableTypedRuleAttemptSegmentLedgerReceiptAndOutboxContracts()
            throws IOException {
        String resource = "db/migration/V10__tim_base_tfr_tim_010.sql";
        String sql;
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing " + resource);
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("BASE-TFR-TIM-010 / W2-20261013-TIM-010-START-R1")
                .contains("CREATE TABLE tme_interpretation_rule_versions")
                .contains("registry_id = 'TIM_RULE_AST_V1' AND registry_version = 1")
                .contains("CREATE TABLE tme_interpretation_runs")
                .contains("worker_scope_digest CHAR(64) NOT NULL")
                .contains("ledger_delta_applied BOOLEAN NOT NULL")
                .contains("uk_tme_interpretation_semantic_ledger")
                .contains("NULLS NOT DISTINCT")
                .contains("WHERE ledger_delta_applied")
                .contains("CREATE TABLE tme_interpretation_attempts")
                .contains("CREATE TABLE tme_interpreted_segments")
                .contains("CREATE TABLE tme_time_ledger_entries")
                .contains("reverses_entry_public_id")
                .contains("uk_tme_time_ledger_single_reversal")
                .contains("command_type = 'INTERPRETATION_RUN'")
                .contains("originating_action = 'TIME_INTERPRET_RUN'")
                .contains("schema_name = 'TimeInterpreted' AND schema_version = 1")
                .contains("'runId','periodStart','periodEnd','inputDigest',")
                .contains("'ruleDigest','outputDigest','attemptNo'")
                .contains("interpretation evidence is append-only")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("GRANT SELECT, INSERT ON TABLE")
                .contains("GRANT UPDATE (published_at, publish_attempts)")
                .doesNotContain("GRANT ALL")
                .doesNotContain("GRANT UPDATE ON TABLE tme_interpretation_runs")
                .doesNotContain("GRANT DELETE");
    }

}
