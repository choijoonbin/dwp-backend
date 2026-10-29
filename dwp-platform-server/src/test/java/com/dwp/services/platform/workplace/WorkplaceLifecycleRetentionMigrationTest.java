package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceLifecycleRetentionMigrationTest {

    @Test
    void claimAndRetentionIndexesMatchRuntimePredicatesAndOrdering() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V344__index_workplace_lifecycle_retention.sql"));

        assertThat(migration)
                .contains("idx_wp_bookings_no_show_claim")
                .contains("no_show_due_at")
                .contains("trg_wp_bookings_no_show_due_at")
                .contains("booking_status = 'RESERVED' AND require_check_in_snapshot = TRUE")
                .contains("idx_wp_bookings_completion_claim")
                .contains("booking_status = 'CHECKED_IN'")
                .contains("require_check_in_snapshot = FALSE")
                .contains("idx_wp_bookings_user_history_bounded")
                .contains("tenant_id, user_id, starts_at, booking_id")
                .contains("idx_wp_bookings_privacy_claim")
                .contains("personal_data_expires_at, booking_id")
                .contains("idx_wp_release_windows_privacy_claim")
                .contains("personal_data_expires_at, release_window_id")
                .contains("idx_wp_experience_work_plan_retention")
                .contains("expires_at, plan_id")
                .contains("idx_wp_facility_request_retention")
                .contains("request_status IN ('RESOLVED', 'CANCELLED')")
                .contains("idx_wp_facility_closure_retention")
                .contains("GREATEST(ends_at, updated_at)")
                .contains("idx_wp_audit_events_retention")
                .contains("occurred_at, audit_event_id");
    }

    @Test
    void lifecycleTenantCursorsAreDurableAndIndependent() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V375__bound_workplace_lifecycle_tenant_scan.sql"));

        assertThat(migration)
                .contains("CREATE TABLE wp_booking_lifecycle_cursors")
                .contains("lifecycle_kind VARCHAR(24) PRIMARY KEY")
                .contains("last_tenant_id BIGINT")
                .contains("completed_cycles BIGINT NOT NULL")
                .contains("VALUES ('NO_SHOW'), ('COMPLETION')");
    }
}
