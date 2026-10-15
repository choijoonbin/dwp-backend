package com.dwp.services.people.hris.experience;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Hrm002MigrationContractTest {

    @Test
    void v56IsScalarOnlyTenantIsolatedAndAppendOnly() throws Exception {
        String sql = resource("db/migration/V56__hrm_base_tfr_hrm_002.sql");

        assertThat(sql)
                .contains("ppl_hris_notification_intent_handoffs")
                .contains("ppl_hris_notification_handoff_claims")
                .contains("ppl_hris_notification_receipt_streams")
                .contains("ppl_hris_notification_receipt_inbox")
                .contains("ENABLE ROW LEVEL SECURITY")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("reject_ppl_hris_experience_evidence_mutation")
                .contains("protect_ppl_hris_notification_receipt_stream")
                .contains("protect_ppl_hris_notification_handoff_claim")
                .contains("lease_expires_at")
                .contains("retry_eligible_at")
                .contains("DEAD_LETTERED")
                .contains("'FAILED'")
                .contains("canonical_intent_sha256")
                .doesNotContainIgnoringCase(" JSONB")
                .doesNotContainIgnoringCase(" BYTEA")
                .doesNotContain("canonical_intent JSON")
                .doesNotContain("template_model JSON")
                .doesNotContain("effective_date")
                .doesNotContain("request_reference")
                .doesNotContain("template_target_type")
                .doesNotContain("subject_users")
                .doesNotContain("ppl_command_receipts (");
    }

    @Test
    void runtimePathsRemainDefaultOff() throws Exception {
        String application = resource("application.yml");
        assertThat(application).contains("experience:")
                .contains("enabled: ${DWP_HRIS_EXPERIENCE_ENABLED:false}")
                .contains("relay:")
                .contains("enabled: ${DWP_HRIS_EXPERIENCE_RELAY_ENABLED:false}");
    }

    private String resource(String path) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
