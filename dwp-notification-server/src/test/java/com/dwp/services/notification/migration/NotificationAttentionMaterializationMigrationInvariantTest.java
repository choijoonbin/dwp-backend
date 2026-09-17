package com.dwp.services.notification.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAttentionMaterializationMigrationInvariantTest {

    @Test
    void decisionProvenanceNeverCopiesScopeKeysOrNotificationContent() throws IOException {
        String sql = migration();

        assertThat(sql)
                .contains("ALTER TABLE ntf_delivery_admission_receipts")
                .contains("ALTER TABLE ntf_user_notifications")
                .contains("attention_rule_id")
                .contains("attention_scope_kind")
                .contains("attention_effect")
                .contains("attention_rule_revision")
                .contains("attention_policy_source")
                .doesNotContain("attention_scope_key")
                .doesNotContain("safe_body")
                .doesNotContain("action_payload");
    }

    @Test
    void recipientProjectionCannotPersistAMuteDecision() throws IOException {
        assertThat(migration())
                .contains("attention_effect IS NULL OR attention_effect IN ('FOLLOW', 'PRIORITIZE')");
    }

    private String migration() throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V29__apply_attention_rules_during_materialization.sql")) {
            if (stream == null) throw new IOException("V29 migration is missing.");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
