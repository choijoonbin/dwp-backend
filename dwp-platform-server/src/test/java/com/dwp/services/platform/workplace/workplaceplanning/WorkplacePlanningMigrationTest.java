package com.dwp.services.platform.workplace.workplaceplanning;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class WorkplacePlanningMigrationTest {
    @Test
    void migrationEnforcesEvidenceLifecycleIdempotencyAndNoBookingMutation() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V265__govern_workplace_space_planning.sql"));
        String lower = sql.toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("'WORK_PLAN','RESERVATION','CHECK_IN','ACCESS','SENSOR_OCCUPANCY','NO_SHOW'")
                .contains("'READY','DATA_INSUFFICIENT','STALE','PARTIAL','COMPUTE_FAILED'")
                .contains("forecast_points='[]'::jsonb")
                .contains("recommendation_metrics IS NULL")
                .contains("'DRAFT','PREVIEWED','SUBMITTED','APPROVED','PUBLISHED'")
                .contains("evidence_kind IN ('METER','APPROVED_MODEL')")
                .contains("factor_version")
                .contains("region_code")
                .contains("approval_authority_reference")
                .contains("UNIQUE (tenant_id, actor_user_id, idempotency_key)")
                .contains("request_fingerprint CHAR(64)")
                .contains("result_snapshot JSONB NOT NULL")
                .contains("CREATE TABLE wp_space_planning_outbox")
                .contains("CREATE TABLE wp_space_planning_audit_events")
                .contains("Publishing a scenario never moves, creates, cancels or mutates a booking");
        assertThat(lower)
                .doesNotContain("access_token")
                .doesNotContain("refresh_token")
                .doesNotContain("secret_value")
                .doesNotContain("password")
                .doesNotContain("update wp_bookings")
                .doesNotContain("delete from wp_bookings")
                .doesNotContain("insert into wp_bookings");
    }
}
