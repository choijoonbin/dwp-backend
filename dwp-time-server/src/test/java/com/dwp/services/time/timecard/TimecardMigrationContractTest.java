package com.dwp.services.time.timecard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TimecardMigrationContractTest {

    @Test
    void v11IsForwardOnlyTenantScopedAppendOnlyAndLeastPrivilege() throws IOException {
        String resource = "db/migration/V11__tim_base_tfr_tim_017.sql";
        String sql;
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing " + resource);
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("ALTER TABLE tme_time_ledger_entries")
                .contains("ALTER COLUMN interpretation_attempt_public_id DROP NOT NULL")
                .contains("source_type IN ('INTERPRETATION', 'MANUAL_CORRECTION', 'REVERSAL')")
                .contains("source_public_id = interpretation_attempt_public_id")
                .contains("NEW.quantity <> -referenced.quantity")
                .contains("CREATE TABLE tme_timecards")
                .contains("CREATE TABLE tme_timecard_versions")
                .contains("CREATE TABLE tme_timecard_lines")
                .contains("CREATE TABLE tme_timecard_decisions")
                .contains("CREATE TABLE tme_timecard_command_receipts")
                .contains("CREATE TABLE tme_timecard_outbox_events")
                .contains("CREATE TABLE tme_time_ledger_revision_counters")
                .contains("ALTER TABLE tme_time_ledger_entries NO FORCE ROW LEVEL SECURITY")
                .contains("ALTER TABLE tme_time_ledger_entries FORCE ROW LEVEL SECURITY")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("originating_population_kind")
                .contains("originating_required_permission")
                .contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("(state = 'SUPERSEDED') = (successor_timecard_public_id IS NOT NULL)")
                .contains("Timecard correction evidence requires a new ledger revision")
                .contains("Timecard submit cannot mutate correction or decision evidence")
                .contains("Timecard decision cannot mutate submitted or correction evidence")
                .contains("Timecard lock, close or supersede cannot mutate evidence")
                .contains("jsonb_typeof(payload->'timecardId') = 'string'")
                .contains("jsonb_typeof(payload->'workerId') = 'string'")
                .contains("jsonb_typeof(payload->'status') = 'string'")
                .contains("jsonb_typeof(payload->'ledgerRevision') = 'number'")
                .contains("jsonb_typeof(payload->'decisionAt') = 'string'")
                .contains("Timecard evidence is append-only")
                .contains("TimecardDecisionRecorded.v1")
                .contains("GRANT UPDATE (published_at, publish_attempts)")
                .doesNotContain("schema_name = 'TimecardDecisionRecorded'")
                .doesNotContain("GRANT ALL")
                .doesNotContain("GRANT DELETE")
                .doesNotContain("GRANT UPDATE ON TABLE tme_timecard_outbox_events");

        assertThat(sql.indexOf(
                "ALTER TABLE tme_time_ledger_entries NO FORCE ROW LEVEL SECURITY"))
                .isLessThan(sql.indexOf("INSERT INTO tme_time_ledger_revision_counters"));
        assertThat(sql.lastIndexOf(
                "ALTER TABLE tme_time_ledger_entries FORCE ROW LEVEL SECURITY"))
                .isGreaterThan(sql.indexOf("VALIDATE CONSTRAINT ck_tme_time_ledger_source_profile"));
    }
}
