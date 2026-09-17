package com.dwp.services.notification.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAttentionMigrationInvariantTest {

    @Test
    void createsExactUserOwnedRulesWithForcedRlsAndBoundedIndexes() throws IOException {
        String migration = migration();

        assertThat(migration)
                .contains("CREATE TABLE ntf_user_attention_rules")
                .contains("'APP_TYPE', 'ACTOR', 'THREAD', 'RESOURCE', 'TOPIC_TOKEN'")
                .contains("'FOLLOW', 'PRIORITIZE', 'MUTE'")
                .contains("UNIQUE (tenant_id, user_id, scope_kind, scope_key_hash)")
                .contains("source IN ('USER', 'TENANT_POLICY', 'SYSTEM_DEFAULT')")
                .contains("managed BOOLEAN NOT NULL")
                .contains("exception_allowed BOOLEAN NOT NULL")
                .contains("version BIGINT NOT NULL")
                .contains("CREATE INDEX ix_ntf_attention_rule_active_owner")
                .contains("CREATE INDEX ix_ntf_attention_rule_expiry")
                .contains("ALTER TABLE ntf_user_attention_rules FORCE ROW LEVEL SECURITY")
                .contains("user_id = ntf_current_user_id()")
                .contains("source = 'USER'")
                .contains("AND NOT managed")
                .contains("AND NOT exception_allowed");
    }

    @Test
    void bindsChannelsAndRecipientContextsToTheirOwners() throws IOException {
        assertThat(migration())
                .contains("CREATE TABLE ntf_user_attention_rule_channels")
                .contains("FOREIGN KEY (tenant_id, user_id, rule_id)")
                .contains("ON DELETE CASCADE")
                .contains("CREATE TABLE ntf_recipient_notification_contexts")
                .contains("FOREIGN KEY (tenant_id, user_id, notification_id)")
                .contains("ALTER TABLE ntf_recipient_notification_contexts FORCE ROW LEVEL SECURITY")
                .contains("GRANT SELECT ON ntf_recipient_notification_contexts TO dwp_notification_api");
    }

    @Test
    void auditOutboxIsStructurallyContentFree() throws IOException {
        String migration = migration();
        String audit = migration.substring(
                migration.indexOf("CREATE TABLE ntf_attention_rule_audit_outbox"),
                migration.indexOf("CREATE TABLE ntf_test_delivery_receipts"));

        assertThat(audit)
                .contains("subject_type", "event_type", "scope_kind", "effect", "subject_version")
                .doesNotContain(
                        "scope_key", "context_key", "display_label", "safe_title",
                        "safe_preview", "safe_body", "payload JSONB");
    }

    @Test
    void diagnosticReceiptsExpireInTwentyFourHoursAndStayOutOfInboxState()
            throws IOException {
        String migration = migration();
        String receipt = migration.substring(
                migration.indexOf("CREATE TABLE ntf_test_delivery_receipts"),
                migration.indexOf("CREATE INDEX ix_ntf_attention_rule_active_owner"));

        assertThat(receipt)
                .contains("requested_channels JSONB")
                .contains("'PENDING', 'COMPLETED', 'PARTIAL', 'FAILED', 'EXPIRED'")
                .contains("stage_results JSONB")
                .contains("expires_at = created_at + INTERVAL '24 hours'")
                .doesNotContain(
                        "ntf_user_notifications", "ntf_notifications",
                        "ntf_delivery_jobs", "ntf_outbox_events");
    }

    private String migration() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V28__add_user_attention_rules_and_diagnostics.sql")) {
            if (stream == null) throw new IOException("V28 migration is missing.");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
