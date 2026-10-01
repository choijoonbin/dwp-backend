package com.dwp.services.platform.personalsettings;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalSettingsLifecycleMigrationTest {

    @Test
    void migrationOwnsFreshnessAndImmutablePrivacyIntakeEvidenceWithoutClaimingFulfillment()
            throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V316__complete_personal_settings_freshness_and_privacy_intake_evidence.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE usr_personal_settings_workspace_states")
                .contains("last_change_at TIMESTAMP NOT NULL")
                .contains("last_confirmed_at TIMESTAMP")
                .contains("CREATE TABLE usr_personal_privacy_request_events")
                .contains("CREATE TABLE usr_personal_privacy_request_receipts")
                .contains("request_fingerprint VARCHAR(64) NOT NULL")
                .contains("evidence_state = 'INTAKE_ONLY'")
                .contains("PRIVACY_OWNER_EXECUTION_NOT_CONNECTED")
                .contains("trg_usr_personal_privacy_request_events_immutable")
                .contains("trg_usr_personal_privacy_request_receipts_immutable")
                .doesNotContain("FULFILLED")
                .doesNotContain("DOWNLOAD_READY")
                .doesNotContain("DELETION_COMPLETED");
    }
}
