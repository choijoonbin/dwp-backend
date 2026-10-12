package com.dwp.services.time.clockingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ClockIngestionMigrationContractTest {

    @Test
    void leasedMigrationKeepsFactsOrderedImmutableTenantScopedAndLeastPrivilege()
            throws IOException {
        String resource = "db/migration/V8__tim_create_clock_ingestion.sql";
        String sql;
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing " + resource);
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("BASE-TFR-TIM-006 / G7-LEASE-TIM-TIME-V8-R1")
                .contains("CREATE TABLE tme_clock_events")
                .contains("CREATE TABLE tme_clock_command_receipts")
                .contains("CREATE TABLE tme_clock_outbox_events")
                .contains("online clock source sequence must advance monotonically")
                .contains("NEW.capture_mode <> 'OFFLINE'")
                .contains("OUT_OF_ORDER_OFFLINE_SEQUENCE quarantine")
                .contains("clock facts and receipts are append-only")
                .contains("uk_tme_clock_event_single_void")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("ClockEventRecorded")
                .contains("GRANT SELECT, INSERT ON TABLE")
                .contains("GRANT UPDATE (published_at, publish_attempts)")
                .doesNotContain("GRANT ALL")
                .doesNotContain("GRANT UPDATE ON TABLE tme_clock_events")
                .doesNotContain("GRANT DELETE");
    }

    @Test
    void correctiveVoidReplayLedgerPinsV36BindingAndRemainsAppendOnly()
            throws IOException {
        String resource = "db/migration/V9__tim_bind_clock_void_step_up.sql";
        String sql;
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing " + resource);
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("G7-CONTROL-TIM-STEPUP-V9-R1")
                .contains("CREATE TABLE tme_clock_step_up_replay_ledger")
                .contains("uk_tme_clock_step_up_challenge_nonce")
                .contains("route.hcm.operations.time-clock-event-void.action")
                .contains("STEPUP-MGMT-HIGH-V1")
                .contains("hcm.operations.time.update")
                .contains("target_type = 'CLOCK_EVENT'")
                .contains("/api/time/v1/clock-events/")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("GRANT SELECT, INSERT ON TABLE")
                .doesNotContain("GRANT ALL")
                .doesNotContain("GRANT UPDATE")
                .doesNotContain("GRANT DELETE");
    }
}
