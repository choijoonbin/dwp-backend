package com.dwp.services.notification.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationQualityFactMigrationInvariantTest {

    @Test
    void persistsOnlyImmutableContentFreeDecisionAndCompletionFacts() throws IOException {
        assertThat(migration())
                .contains("CREATE TABLE ntf_notification_quality_facts")
                .contains("CREATE TABLE ntf_notification_quality_completion_facts")
                .contains("CONSTRAINT uq_ntf_quality_receipt UNIQUE (tenant_id, receipt_id)")
                .contains("decision IN ('ADMITTED', 'SUPPRESSED', 'RATE_LIMITED')")
                .contains("attention_scope_kind")
                .contains("action_required BOOLEAN NOT NULL")
                .contains("collapsed BOOLEAN NOT NULL")
                .contains("thread_identity_hash CHAR(64)")
                .contains("source_identity_hash CHAR(64)")
                .contains("notification_identity_hash CHAR(64)")
                .contains("Notification quality facts are append-only")
                .doesNotContain(
                        "safe_title", "safe_preview", "safe_body", "search_text",
                        "actor_ref", "subject_ref", "target_ref", "display_label");
    }

    @Test
    void enforcesTenantIsolationLeastPrivilegeIndexesAndBoundedRetention() throws IOException {
        assertThat(migration())
                .contains("ALTER TABLE ntf_notification_quality_facts FORCE ROW LEVEL SECURITY")
                .contains("ALTER TABLE ntf_notification_quality_completion_facts FORCE ROW LEVEL SECURITY")
                .contains("tenant_id = ntf_current_tenant_id()")
                .contains("GRANT SELECT, INSERT ON ntf_notification_quality_facts TO dwp_notification_worker")
                .contains("GRANT INSERT ON ntf_notification_quality_completion_facts TO dwp_notification_api")
                .contains("dwp_notification_quality_retention")
                .contains("NOBYPASSRLS")
                .contains("ix_ntf_quality_fact_window")
                .contains("ix_ntf_quality_fact_thread")
                .contains("ix_ntf_quality_completion_window")
                .contains("ix_ntf_quality_completion_thread")
                .contains("requested_cutoff > CURRENT_TIMESTAMP - INTERVAL '30 days'")
                .contains("requested_limit > 500")
                .contains("REVOKE ALL ON FUNCTION ntf_purge_notification_quality_facts")
                .doesNotContain(
                        "GRANT SELECT, INSERT ON ntf_notification_quality_completion_facts "
                                + "TO dwp_notification_api");
    }

    private String migration() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V32__record_immutable_notification_quality_facts.sql")) {
            if (stream == null) throw new IOException("V32 migration is missing.");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
