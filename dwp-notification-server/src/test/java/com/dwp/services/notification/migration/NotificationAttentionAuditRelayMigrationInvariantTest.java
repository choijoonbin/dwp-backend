package com.dwp.services.notification.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAttentionAuditRelayMigrationInvariantTest {

    @Test
    void provisionsLeaseRetryPoisonAndPublishedRetentionState() throws IOException {
        assertThat(migration())
                .contains("attempt_count INTEGER NOT NULL DEFAULT 0")
                .contains("available_at TIMESTAMPTZ NOT NULL")
                .contains("lease_owner VARCHAR(255)")
                .contains("last_error VARCHAR(1000)")
                .contains("dead_at TIMESTAMPTZ")
                .contains("ix_ntf_attention_audit_due")
                .contains("ix_ntf_attention_audit_dead")
                .contains("ix_ntf_attention_audit_published")
                .contains("ntf_purge_published_attention_audit_outbox");
    }

    @Test
    void isolatesAppendOnlyEvidenceFromRuntimeAndWorkerRoles() throws IOException {
        assertThat(migration())
                .contains("dwp_notification_attention_audit_relay")
                .contains("dwp_notification_attention_audit_retention")
                .contains("NOBYPASSRLS")
                .contains("REVOKE ALL ON TABLE ntf_attention_rule_audit_outbox")
                .contains("REVOKE ALL ON TABLE ntf_attention_rule_audit_outbox FROM dwp_notification_worker")
                .contains("GRANT EXECUTE ON FUNCTION ntf_current_tenant_id()")
                .contains("GRANT UPDATE (")
                .contains("published_at, updated_at")
                .contains("Attention audit evidence is immutable")
                .contains("Attention audit evidence is append-only")
                .doesNotContain("GRANT DELETE ON TABLE ntf_attention_rule_audit_outbox\n    TO dwp_notification_attention_audit_relay");
    }

    private String migration() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V31__relay_attention_rule_audit_outbox.sql")) {
            if (stream == null) throw new IOException("V31 migration is missing.");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
