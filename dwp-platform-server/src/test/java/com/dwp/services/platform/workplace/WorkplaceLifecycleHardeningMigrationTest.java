package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceLifecycleHardeningMigrationTest {

    @Test
    void migrationMakesLifecycleInvariantsDatabaseEnforced() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V162__harden_workplace_transaction_privacy_media_lifecycle.sql"));

        assertThat(sql)
                .contains("policy_snapshot_hash")
                .contains("require_check_in_snapshot")
                .contains("booking_retention_days_snapshot")
                .contains("fk_wp_bookings_release_window_resource")
                .contains("FOR KEY SHARE")
                .contains("idempotency_key IS NULL")
                .contains("wp_floor_plan_media_assets")
                .contains("FLOOR_PLAN_UNREFERENCED")
                .contains("ON CONFLICT DO NOTHING");

        String canonicalHashSql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V164__canonicalize_workplace_policy_hash_and_media_reconciliation.sql"));
        assertThat(canonicalHashSql)
                .contains("CREATE EXTENSION IF NOT EXISTS pgcrypto")
                .contains("digest(policy_snapshot::TEXT, 'sha256')")
                .contains("ck_wp_bookings_policy_snapshot_hash_matches")
                .contains("idx_wp_floor_plan_media_reconciliation");
    }
}
