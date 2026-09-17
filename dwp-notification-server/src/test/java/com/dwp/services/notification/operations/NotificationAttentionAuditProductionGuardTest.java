package com.dwp.services.notification.operations;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAttentionAuditProductionGuardTest {

    @Test
    void localDevelopmentDoesNotRequireExternalAuditTransport() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dwp.environment", "local");

        assertThat(NotificationAttentionAuditProductionGuard.violations(environment)).isEmpty();
    }

    @Test
    void productionRequiresEnabledDedicatedRelayAndCanonicalTransport() {
        MockEnvironment environment = productionEnvironment()
                .withProperty("dwp.notification.attention-audit.enabled", "true")
                .withProperty(
                        "dwp.notification.attention-audit.relay-database-role",
                        "dwp_notification_attention_audit_relay")
                .withProperty("dwp.audit.collector-url", "https://audit.example.com/ingest")
                .withProperty("dwp.audit.ingest-token", "a".repeat(32))
                .withProperty("dwp.notification.attention-audit.published-retention", "P30D")
                .withProperty("dwp.notification.attention-audit.maximum-attempts", "20");

        assertThat(NotificationAttentionAuditProductionGuard.violations(environment)).isEmpty();
    }

    @Test
    void productionFailsClosedForDisabledOrLocalTransportAndUnsafeRetention() {
        MockEnvironment environment = productionEnvironment()
                .withProperty("dwp.notification.attention-audit.enabled", "false")
                .withProperty(
                        "dwp.notification.attention-audit.relay-database-role",
                        "dwp_notification_worker")
                .withProperty("dwp.audit.collector-url", "http://localhost:8080/ingest")
                .withProperty("dwp.audit.ingest-token", " short ")
                .withProperty("dwp.notification.attention-audit.published-retention", "PT1H")
                .withProperty("dwp.notification.attention-audit.maximum-attempts", "1");

        assertThat(NotificationAttentionAuditProductionGuard.violations(environment))
                .hasSize(6)
                .anyMatch(value -> value.contains("enabled must be true"))
                .anyMatch(value -> value.contains("dedicated"))
                .anyMatch(value -> value.contains("HTTPS"))
                .anyMatch(value -> value.contains("canonical production secret"))
                .anyMatch(value -> value.contains("retention"))
                .anyMatch(value -> value.contains("maximum attempts"));
    }

    private MockEnvironment productionEnvironment() {
        return new MockEnvironment().withProperty("dwp.environment", "production");
    }
}
