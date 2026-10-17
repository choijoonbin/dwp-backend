package com.dwp.services.time.close;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TimeCloseMigrationContractTest {

    private static final String RESOURCE =
            "db/migration/V12__tim_base_tfr_tim_007_close_handoff.sql";

    @Test
    void v12IsConsumerOnlyTenantScopedImmutableAndLeastPrivilege() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("BASE-TFR-TIM-007")
                .contains("CREATE TABLE tme_close_periods")
                .contains("CREATE TABLE tme_close_command_receipts")
                .contains("CREATE TABLE tme_close_attempts")
                .contains("CREATE TABLE tme_closed_time_results")
                .contains("CREATE TABLE tme_closed_time_result_lines")
                .contains("CREATE TABLE tme_closed_time_result_sources")
                .contains("CREATE TABLE tme_payroll_handoffs")
                .contains("CREATE TABLE tme_payroll_handoff_outcomes")
                .contains("CREATE TABLE tme_close_workload_replay")
                .contains("CREATE TABLE tme_close_quarantine")
                .contains("CREATE TABLE tme_close_outbox_events")
                .contains("ALTER TABLE %I FORCE ROW LEVEL SECURITY")
                .contains("Time close period must begin in canonical OPEN state")
                .contains("Time close periods cannot be deleted")
                .contains("Time close evidence is append-only")
                .contains("REVOKE ALL ON TABLE tme_close_periods "
                        + "FROM \"${timeRuntimeRole}\";")
                .contains("REVOKE USAGE, SELECT ON SEQUENCE "
                        + "tme_close_periods_close_period_id_seq\n"
                        + "FROM \"${timeRuntimeRole}\";")
                .contains("GRANT SELECT ON TABLE tme_close_periods")
                .contains("GRANT SELECT ON TABLE tme_close_outbox_events\n"
                        + "TO \"${timeProjectionPublisherRole}\";")
                .contains("GRANT UPDATE (published_at, publish_attempts) "
                        + "ON TABLE tme_close_outbox_events")
                .doesNotContain("GRANT ALL")
                .doesNotContain("GRANT DELETE")
                .doesNotContain("GRANT UPDATE ON TABLE tme_close_outbox_events")
                .doesNotContain("CREATE TABLE tim_close_period_definition")
                .doesNotContain("CREATE PROCEDURE", "CREATE MATERIALIZED VIEW");

        String runtimeInsertGrant = between(
                sql,
                "GRANT SELECT, INSERT ON TABLE\n",
                "TO \"${timeRuntimeRole}\";");
        assertThat(runtimeInsertGrant)
                .contains("tme_close_command_receipts", "tme_close_outbox_events")
                .doesNotContain("tme_close_periods");

        String runtimeSequenceGrant = between(
                sql,
                "FOREACH sequence_name IN ARRAY ARRAY[",
                "] LOOP");
        assertThat(runtimeSequenceGrant)
                .contains("tme_close_command_receipts", "tme_close_outbox_events")
                .doesNotContain("tme_close_periods");
    }

    private static String migrationSql() throws IOException {
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing " + RESOURCE);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String between(String value, String start, String end) {
        int from = value.indexOf(start);
        assertThat(from).as("start marker").isGreaterThanOrEqualTo(0);
        int to = value.indexOf(end, from + start.length());
        assertThat(to).as("end marker").isGreaterThan(from);
        return value.substring(from, to);
    }
}
