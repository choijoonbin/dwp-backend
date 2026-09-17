package com.dwp.services.notification.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAttentionGovernanceMigrationInvariantTest {

    @Test
    void persistsAllTenantGuardrailsAsImmutableRevisions() throws IOException {
        assertThat(migration())
                .contains("CREATE TABLE ntf_attention_governance_revisions")
                .contains("max_active_user_rules")
                .contains("max_vip_rules")
                .contains("max_follow_rules")
                .contains("approved_topic_allowlist JSONB")
                .contains("ntf_attention_topics_are_canonical(approved_topic_allowlist)")
                .contains("^#[a-z0-9][a-z0-9._-]{1,79}$")
                .contains("COUNT(DISTINCT value)")
                .contains("mandatory_policy_precedence BOOLEAN NOT NULL")
                .contains("minimum_analytics_cohort")
                .contains("independent_reviewer_required BOOLEAN NOT NULL")
                .contains("CREATE UNIQUE INDEX uq_ntf_attention_governance_open_draft")
                .contains("CREATE UNIQUE INDEX uq_ntf_attention_governance_published");
    }

    @Test
    void enforcesFourEyesVersionAndTenantIsolationAtTheDatabaseBoundary() throws IOException {
        assertThat(migration())
                .contains("approved_by <> created_by")
                .contains("NEW.version <> OLD.version + 1")
                .contains("Attention governance revision content cannot be mutated")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("tenant_id = ntf_current_tenant_id()")
                .contains("ntf_is_worker()")
                .contains("CREATE POLICY ntf_attention_governance_api_read_scope")
                .contains("FOR SELECT TO dwp_notification_api")
                .contains("AND state = 'PUBLISHED'")
                .contains("GRANT SELECT ON ntf_attention_governance_revisions")
                .contains("REVOKE DELETE ON ntf_attention_governance_revisions");
    }

    private String migration() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V30__govern_tenant_attention_policy.sql")) {
            if (stream == null) throw new IOException("V30 migration is missing.");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
