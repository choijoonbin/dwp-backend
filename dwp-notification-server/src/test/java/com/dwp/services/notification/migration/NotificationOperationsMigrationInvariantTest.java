package com.dwp.services.notification.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationOperationsMigrationInvariantTest {

    @Test
    void makesSuppressionAndRateDecisionsTenantScopedAndIdempotent() throws IOException {
        String migration = resource(
                "db/migration/V14__add_notification_delivery_admission_controls.sql");

        assertThat(migration)
                .contains("CREATE TABLE ntf_delivery_suppressions")
                .contains("expires_at <= starts_at + INTERVAL '31 days'")
                .contains("critical_bypass BOOLEAN NOT NULL")
                .contains("CREATE TABLE ntf_delivery_admission_receipts")
                .contains("uq_ntf_admission_event_recipient")
                .contains("CREATE TABLE ntf_delivery_rate_windows")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("tenant_id = ntf_current_tenant_id()");
    }

    @Test
    void protectsSavedAndHeldDataAndLeasesOutboxEvents() throws IOException {
        String migration = resource(
                "db/migration/V15__add_notification_retention_and_outbox_operations.sql");

        assertThat(migration)
                .contains("CREATE TABLE ntf_notification_retention_holds")
                .contains("released_at TIMESTAMPTZ")
                .contains("lease_owner VARCHAR(160)")
                .contains("dead_at TIMESTAMPTZ")
                .contains("CREATE TABLE ntf_runtime_tenants")
                .contains("SECURITY DEFINER")
                .contains("ntf_current_tenant_id() IS NULL")
                .contains("Notification runtime tenant scope is invalid");
    }

    @Test
    void repairsPendingAuditEventsToTheCentralPolicyDecisionContract() throws IOException {
        String migration = resource(
                "db/migration/V16__align_notification_audit_policy_decisions.sql");

        assertThat(migration)
                .contains("status IN ('PENDING', 'FAILED')")
                .contains("payload ->> 'policyDecision' = 'DENY_BY_POLICY'")
                .contains("jsonb_set(payload, '{policyDecision}', '\"DENY\"'::jsonb)");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing test resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
